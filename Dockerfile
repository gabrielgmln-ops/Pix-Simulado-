# ============================================================================
# Build multi-estágio: o estágio final contém apenas o JRE e o jar.
# ============================================================================

# ---- Estágio 1: compilação ----
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app
# Copia primeiro os arquivos de build para aproveitar o cache de camadas do Docker:
# dependências só são baixadas de novo quando o build.gradle.kts muda.
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
RUN ./gradlew dependencies --no-daemon > /dev/null
COPY src ./src
RUN ./gradlew bootJar --no-daemon -x test

# ---- Estágio 2: execução ----
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
# Usuário sem privilégios: o processo não roda como root dentro do contêiner.
RUN addgroup -S pix && adduser -S pix -G pix
COPY --from=build /app/build/libs/pix-simulado-*.jar app.jar
USER pix
EXPOSE 8080
# MaxRAMPercentage: a JVM respeita o limite de memória do contêiner.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
