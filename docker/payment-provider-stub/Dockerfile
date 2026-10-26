FROM eclipse-temurin:21-jre-jammy

WORKDIR /app
COPY payment-provider-stub.jar application.jar

EXPOSE 8090

ENTRYPOINT ["java", "-jar", "/app/application.jar"]
