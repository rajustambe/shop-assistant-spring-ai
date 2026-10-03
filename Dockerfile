# ---- Stage 1: build the jar ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
# cache dependencies first (faster rebuilds)
COPY pom.xml .
RUN mvn -q -e -B dependency:go-offline
COPY src ./src
# skip tests in the image build; CI runs them separately (tests + eval gate)
RUN mvn -q -B clean package -DskipTests

# ---- Stage 2: slim runtime ----
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/shop-assistant-0.0.1-SNAPSHOT.jar app.jar
# App reads all config from env vars (see DEPLOY.md): GROQ_API_KEY, PORT, SHOP_MCP_TOKEN, etc.
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
