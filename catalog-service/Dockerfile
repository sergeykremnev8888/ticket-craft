FROM eclipse-temurin:21-jdk-jammy AS build

ARG MODULE=catalog-service

WORKDIR /workspace

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./

COPY common-dto/pom.xml common-dto/pom.xml
COPY api-gateway/pom.xml api-gateway/pom.xml
COPY catalog-service/pom.xml catalog-service/pom.xml
COPY order-service/pom.xml order-service/pom.xml
COPY payment-service/pom.xml payment-service/pom.xml
COPY notification-service/pom.xml notification-service/pom.xml

COPY common-dto/src/ common-dto/src/
COPY ${MODULE}/src/ ${MODULE}/src/

RUN --mount=type=cache,id=ticketcraft-maven,target=/root/.m2,sharing=locked \
    sed -i 's/\r$//' mvnw \
    && chmod +x mvnw \
    && ./mvnw -B -ntp -pl "${MODULE}" -am package -DskipTests \
    && cp "${MODULE}"/target/"${MODULE}"-*.jar /workspace/application.jar

FROM eclipse-temurin:21-jre-jammy AS runtime

RUN groupadd --gid 10001 app \
    && useradd --uid 10001 --gid app --create-home app

WORKDIR /app

COPY --from=build --chown=app:app /workspace/application.jar application.jar

USER app

EXPOSE 8081

ENTRYPOINT ["java", "-jar", "/app/application.jar"]
