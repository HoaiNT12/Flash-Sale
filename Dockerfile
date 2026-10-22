FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /workspace
ARG MODULE
COPY pom.xml ./
COPY platform-common/pom.xml platform-common/pom.xml
COPY auth-service/pom.xml auth-service/pom.xml
COPY inventory-service/pom.xml inventory-service/pom.xml
COPY payment-service/pom.xml payment-service/pom.xml
COPY order-service/pom.xml order-service/pom.xml
COPY platform-common platform-common
COPY auth-service auth-service
COPY inventory-service inventory-service
COPY payment-service payment-service
COPY order-service order-service
RUN mvn -B -pl "${MODULE}" -am package -DskipTests && cp "${MODULE}/target/${MODULE}-1.0.0-SNAPSHOT.jar" /workspace/application.jar

FROM eclipse-temurin:21-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 flashsale && useradd --uid 10001 --gid 10001 --create-home flashsale \
    && mkdir /app /keys && chown flashsale:flashsale /app /keys
USER 10001:10001
WORKDIR /app
COPY --from=build --chown=10001:10001 /workspace/application.jar application.jar
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "-jar", "/app/application.jar"]
