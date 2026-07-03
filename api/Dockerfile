# ---- build stage ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q -e -B dependency:go-offline
COPY src ./src
RUN mvn -q -e -B clean package -DskipTests

# ---- runtime stage ----
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/aegis-api-*.jar app.jar
# Run as an unprivileged user with a writable home (DJL/ONNX model cache lives in $HOME)
RUN useradd -m -u 1001 aegis && chown -R aegis /app
USER aegis
ENV HOME=/home/aegis
EXPOSE 8080
# Cloud Run sets $PORT; Spring reads server.port, so honor $PORT when present.
ENV JAVA_OPTS=""
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -Dserver.port=${PORT:-8080} -jar /app/app.jar"]
