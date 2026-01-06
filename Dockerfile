FROM maven:3.9.6-eclipse-temurin-17 AS build

RUN echo '<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0" \
          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" \
          xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.0.0 \
          http://maven.apache.org/xsd/settings-1.0.0.xsd"> \
          <mirrors> \
            <mirror> \
              <id>aliyunmaven</id> \
              <mirrorOf>*</mirrorOf> \
              <name>阿里云公共仓库</name> \
              <url>https://maven.aliyun.com/repository/public</url> \
            </mirror> \
          </mirrors> \
        </settings>' > /root/.m2/settings.xml
        
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
