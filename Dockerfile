# --- Build stage ---
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app

# Copy only what's needed to resolve dependencies first, so this layer is cached
# across builds unless pom.xml itself changes (source-code-only changes skip it).
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw dependency:go-offline -B

COPY src ./src
RUN ./mvnw clean package -DskipTests -B

# --- Runtime stage ---
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

# Run as a non-root user rather than the container default root.
RUN addgroup --system spring && adduser --system --ingroup spring spring
USER spring:spring

COPY --from=build /app/target/car-booking-service-*.jar app.jar

EXPOSE 8081

ENTRYPOINT ["java", "-jar", "app.jar"]
