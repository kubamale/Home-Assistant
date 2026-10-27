# Builds the web app and the backend into one image: the backend serves the web app's files.

FROM node:24-alpine AS web
WORKDIR /web
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

FROM maven:3-eclipse-temurin-25 AS build
WORKDIR /app
COPY backend/pom.xml ./
RUN mvn -q -B dependency:go-offline
COPY backend/src ./src
COPY --from=web /web/dist ./src/main/resources/static
RUN mvn -q -B -DskipTests package && cp target/homedisplay-*.jar /app/app.jar

FROM eclipse-temurin:25-jre
RUN useradd --system --uid 10001 --create-home app && mkdir /data && chown app /data
WORKDIR /app
COPY --from=build /app/app.jar ./app.jar
ENV APP_DATA_DIR=/data
VOLUME /data
USER app
EXPOSE 8080
# Small heap settings suit a 1-2 GB VPS.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=60", "-XX:+UseSerialGC", "--enable-native-access=ALL-UNNAMED", "-jar", "/app/app.jar"]
