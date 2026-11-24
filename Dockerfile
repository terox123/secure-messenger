FROM eclipse-temurin:25-jdk AS build
WORKDIR /app
COPY src/MessengerServer.java src/MessengerServer.java
RUN javac --release 25 -d classes src/MessengerServer.java

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /app/classes ./classes
COPY index.html app.js style.css ./
ENV PORT=10000
EXPOSE 10000
CMD ["java", "-cp", "classes", "MessengerServer"]
