# Veil Messenger

Java 25 messenger prototype with browser-side AES-GCM encryption.

## Run

In PowerShell, from this directory:

```powershell
./run.ps1
```

Then open <http://127.0.0.1:8081>.

## Deploy to Render

This folder is the service root. Push it to a Git provider, then in Render choose **New → Blueprint**, connect that repository, and select `render.yaml`. The Blueprint builds the Java 25 Docker image and Render supplies the public HTTPS URL.

Render requires a connected Git provider for repository deployments. It sets `PORT` for the app, which the server reads at startup.

The relay stores messages in memory, so a restart clears chat history. Both people must open the same deployed URL and enter exactly the same room name and passphrase. Share the passphrase through a separate trusted channel. Anyone with the passphrase can read messages. This is a prototype, not an audited messenger or a replacement for Signal.
