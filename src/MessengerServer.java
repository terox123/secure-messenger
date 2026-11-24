
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/**
 * Tiny, dependency-free Java 25 relay.
 * Message bodies are opaque ciphertext.
 */
public final class MessengerServer {

    private static final int MAX_MESSAGES = 500;

    private static final List<Map<String, String>> MESSAGES =
            new ArrayList<Map<String, String>>();

    private static final Object LOCK = new Object();

    private static String esc(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    private static Map<String, String> form(String body)
            throws UnsupportedEncodingException {

        Map<String, String> out = new HashMap<String, String>();

        for (String part : body.split("&")) {
            String[] kv = part.split("=", 2);

            out.put(
                    URLDecoder.decode(kv[0], "UTF-8"),
                    kv.length > 1
                            ? URLDecoder.decode(kv[1], "UTF-8")
                            : ""
            );
        }

        return out;
    }

    private static void send(
            HttpExchange x,
            int status,
            String type,
            String body
    ) throws IOException {

        byte[] b = body.getBytes(StandardCharsets.UTF_8);

        x.getResponseHeaders().set(
                "Content-Type",
                type + "; charset=utf-8"
        );

        x.getResponseHeaders().set("Cache-Control", "no-store");
        x.getResponseHeaders().set("X-Content-Type-Options", "nosniff");

        x.getResponseHeaders().set(
                "Content-Security-Policy",
                "default-src 'self'; style-src 'self' 'unsafe-inline'; " +
                        "script-src 'self' 'unsafe-inline'; connect-src 'self'; " +
                        "img-src 'self' data:"
        );

        x.getResponseHeaders().set("X-Frame-Options", "DENY");

        x.sendResponseHeaders(status, b.length);
        x.getResponseBody().write(b);
        x.close();
    }

    public static void main(String[] args) throws Exception {

        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", args.length > 0 ? args[0] : "8080"));

        final File root = new File(".").getCanonicalFile();

        HttpServer server = HttpServer.create(
                new InetSocketAddress("0.0.0.0", port),
                64
        );

        server.setExecutor(
                Executors.newFixedThreadPool(
                        Math.max(4, Runtime.getRuntime().availableProcessors())
                )
        );

        server.createContext("/api/messages", x -> {

            try {

                if ("GET".equals(x.getRequestMethod())) {

                    String selectedRoom = "";
                    String query = x.getRequestURI().getRawQuery();
                    if (query != null) {
                        for (String pair : query.split("&")) {
                            String[] kv = pair.split("=", 2);
                            if ("room".equals(URLDecoder.decode(kv[0], "UTF-8"))) {
                                selectedRoom = kv.length > 1 ? URLDecoder.decode(kv[1], "UTF-8") : "";
                            }
                        }
                    }

                    StringBuilder out = new StringBuilder("[");

                    synchronized (LOCK) {

                        for (int i = 0; i < MESSAGES.size(); i++) {

                            Map<String, String> m = MESSAGES.get(i);
                            if (!selectedRoom.equals(m.get("room"))) continue;
                            if (out.length() > 1) out.append(',');

                            out.append("{\"id\":\"")
                                    .append(esc(m.get("id")))
                                    .append("\",\"room\":\"")
                                    .append(esc(m.get("room")))
                                    .append("\",\"sender\":\"")
                                    .append(esc(m.get("sender")))
                                    .append("\",\"senderId\":\"")
                                    .append(esc(m.get("senderId")))
                                    .append("\",\"iv\":\"")
                                    .append(esc(m.get("iv")))
                                    .append("\",\"ciphertext\":\"")
                                    .append(esc(m.get("ciphertext")))
                                    .append("\",\"time\":")
                                    .append(m.get("time"))
                                    .append('}');
                        }
                    }

                    out.append(']');

                    send(x, 200, "application/json", out.toString());

                } else if ("POST".equals(x.getRequestMethod())) {

                    ByteArrayOutputStream buf = new ByteArrayOutputStream();

                    byte[] chunk = new byte[4096];

                    int n;
                    int total = 0;

                    while ((n = x.getRequestBody().read(chunk)) != -1) {

                        total += n;

                        if (total > 12000) {
                            send(x, 413, "text/plain", "Too large");
                            return;
                        }

                        buf.write(chunk, 0, n);
                    }

                    Map<String, String> f = form(
                            new String(buf.toByteArray(), StandardCharsets.UTF_8)
                    );

                    if (f.get("room") == null
                            || f.get("sender") == null
                            || f.get("senderId") == null
                            || f.get("senderId").length() > 80
                            || f.get("iv") == null
                            || f.get("ciphertext") == null
                            || f.get("ciphertext").length() > 10000) {

                        send(x, 400, "text/plain", "Invalid message");
                        return;
                    }

                    Map<String, String> m = new HashMap<String, String>();

                    m.put("id", UUID.randomUUID().toString());
                    m.put("room", f.get("room"));
                    m.put("sender", f.get("sender"));
                    m.put("senderId", f.get("senderId"));
                    m.put("iv", f.get("iv"));
                    m.put("ciphertext", f.get("ciphertext"));

                    m.put(
                            "time",
                            String.valueOf(System.currentTimeMillis())
                    );

                    synchronized (LOCK) {

                        MESSAGES.add(m);

                        while (MESSAGES.size() > MAX_MESSAGES) {
                            MESSAGES.remove(0);
                        }
                    }

                    send(x, 201, "application/json", "{\"ok\":true}");

                } else {

                    x.getResponseHeaders().set("Allow", "GET, POST");

                    send(x, 405, "text/plain", "Method not allowed");
                }

            } catch (Exception e) {

                try {
                    send(x, 400, "text/plain", "Bad request");
                } catch (Exception ignored) {
                }
            }
        });

        server.createContext("/", x -> {

            try {

                String path = x.getRequestURI().getPath();

                if (path.equals("/")) {
                    path = "/index.html";
                }

                File f = new File(root, path.substring(1))
                        .getCanonicalFile();

                if (!f.toPath().startsWith(root.toPath()) || !f.isFile()) {

                    send(x, 404, "text/plain", "Not found");
                    return;
                }

                String type = f.getName().endsWith(".css")
                        ? "text/css"
                        : f.getName().endsWith(".js")
                        ? "application/javascript"
                        : "text/html";

                byte[] b = new byte[(int) f.length()];

                try (FileInputStream in = new FileInputStream(f)) {

                    int offset = 0;
                    int n;

                    while (offset < b.length
                            && (n = in.read(b, offset, b.length - offset)) > 0) {

                        offset += n;
                    }
                }

                send(
                        x,
                        200,
                        type,
                        new String(b, StandardCharsets.UTF_8)
                );

            } catch (Exception e) {

                send(x, 500, "text/plain", "Server error");
            }
        });

        server.start();

        System.out.println(
                "Messenger listening on http://127.0.0.1:" + port
        );
    }
}
