# syntax=docker/dockerfile:1
# Builds any backend service: docker build --build-arg SERVICE=api-service .
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY . .
ARG SERVICE
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -q -pl "${SERVICE}" -am package -DskipTests \
 && cp "${SERVICE}"/target/"${SERVICE}"-*.jar /app.jar

FROM eclipse-temurin:21-jre
RUN groupadd --system app && useradd --system --gid app app
WORKDIR /app
COPY --from=build /app.jar app.jar
USER app
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
