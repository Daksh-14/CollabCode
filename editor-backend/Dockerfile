FROM eclipse-temurin:17-jdk

RUN apt-get update \
 && apt-get install -y g++ python3 firejail \
 && rm -rf /var/lib/apt/lists/*

COPY target/editor-backend-0.0.1-SNAPSHOT.jar /app/app.jar
WORKDIR /app

ENV PORT=8080
EXPOSE 8080

ENTRYPOINT ["java","-jar","app.jar"]