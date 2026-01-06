FROM maven:3.8.6-eclipse-temurin-17 AS build
WORKDIR /app

COPY pom.xml .

COPY src ./src

RUN mvn clean package -DskipTests

FROM eclipse-temurin:17-jre

WORKDIR /app

COPY --from=build /app/target/*.jar app.jar

EXPOSE 18088

ENV TELEGRAM_BOT_TOKEN=""
ENV TELEGRAM_BOT_USERNAME=""
ENV TELEGRAM_BOT_ADMINS=""
ENV TELEGRAPH_ACCESS_TOKEN=""
ENV APP_CHARS_PER_PAGE=3000

ENTRYPOINT ["java", "-jar", "app.jar"]
