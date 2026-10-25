FROM eclipse-temurin:17-jdk AS build

RUN apt-get update \
 && apt-get install -y --no-install-recommends g++ python3 firejail \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /build

# Dependency layer, cached independently of source changes.
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN ./mvnw -B dependency:go-offline

COPY src ./src
RUN ./mvnw -B clean package -DskipTests


FROM eclipse-temurin:17-jdk AS runtime

# firejail is required by the default (firejail) execution strategy.
# g++ and javac (from the JDK base image) are required to compile user C++ and
# Java code inside the sandbox. The JRE-only image lacked both.
RUN apt-get update \
 && apt-get install -y --no-install-recommends firejail bash python3 g++ \
 && rm -rf /var/lib/apt/lists/*

# Unprivileged runtime user. Code execution must never run as root.
RUN groupadd --system --gid 1001 collabcode \
 && useradd --system --uid 1001 --gid collabcode --create-home collabcode

WORKDIR /app
COPY --from=build /build/target/editor-backend-*.jar /app/app.jar

# CA bundle for managed Postgres providers. Mount the real bundle and point
# spring.datasource.hikari.ssl-ca at it; this placeholder keeps local runs working.
COPY src/main/resources/ca.pem /app/ca.pem

USER collabcode

ENV PORT=8080
EXPOSE 8080

ENTRYPOINT ["java","-jar","app.jar"]