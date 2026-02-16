FROM eclipse-temurin:11-jdk AS build
WORKDIR /app
COPY . .
RUN ./gradlew build
FROM eclipse-temurin:11-jdk
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
COPY --from=build /app/config/elastic/elastic-apm-agent-1.54.0.jar /config/elastic/elastic-apm-agent.jar
EXPOSE 8000
CMD ["java", "-jar", "app.jar"]
