# Build with the Maven wrapper, run on a slim JRE as a non-root user.
FROM eclipse-temurin:17-jdk AS build
WORKDIR /app
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN ./mvnw -q -B dependency:go-offline
COPY src src
RUN ./mvnw -q -B -DskipTests package && cp target/payments-service-*.jar app.jar

FROM eclipse-temurin:17-jre
RUN useradd --system --uid 10001 payments
WORKDIR /app
COPY --from=build /app/app.jar app.jar
USER payments
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
