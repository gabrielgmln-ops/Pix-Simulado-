/*
 * Build do Pix Simulado.
 *
 * Decisões:
 *  - Kotlin + Spring Boot 3 sobre Java 21 (toolchain fixa para builds reprodutíveis).
 *  - Plugin "kotlin-spring" abre as classes anotadas com @Component/@Configuration etc.
 *    (o Spring precisa de classes não-finais para criar proxies).
 *  - Plugin "kotlin-jpa" gera construtores sem argumentos para entidades JPA.
 *  - Jacoco já configurado para que a Etapa 5 apenas ajuste a meta de cobertura.
 */
plugins {
    id("org.springframework.boot") version "3.5.6"
    id("io.spring.dependency-management") version "1.1.7"
    kotlin("jvm") version "2.1.21"
    kotlin("plugin.spring") version "2.1.21"
    kotlin("plugin.jpa") version "2.1.21"
    jacoco
}

group = "br.com.pixsimulado"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

// Testcontainers 1.21.4+ é compatível com o Docker Engine 29 (API mínima 1.44).
// A versão gerenciada pelo Spring Boot (1.21.3) não consegue se conectar a ele.
extra["testcontainers.version"] = "1.21.4"

dependencies {
    // --- Web, validação e observabilidade ---
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // --- Persistência: JPA + PostgreSQL + migrações versionadas com Flyway ---
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    // --- Kotlin: serialização JSON de data classes e reflexão ---
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    // --- Documentação OpenAPI / Swagger UI ---
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.13")

    // --- Gera metadados das @ConfigurationProperties (autocompletar no IDE) ---
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

    // --- Testes: JUnit 5, Testcontainers (PostgreSQL real) ---
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    compilerOptions {
        // Respeita as anotações de nulidade do Spring (JSR-305) de forma estrita.
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

// Entidades JPA precisam ser "open" para o Hibernate gerar proxies de carregamento tardio.
allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}

tasks.withType<Test> {
    useJUnitPlatform()
    // Ao final dos testes, gera automaticamente o relatório de cobertura.
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required = true   // consumido pelo CI na Etapa 5
        html.required = true  // leitura humana: build/reports/jacoco/test/html
    }
}

// Jar "plain" (sem dependências) não é útil aqui; mantemos apenas o bootJar executável.
tasks.jar {
    enabled = false
}
