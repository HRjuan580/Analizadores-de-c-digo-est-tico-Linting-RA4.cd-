plugins {
    kotlin("jvm") version "2.0.20" // Plugin Kotlin
    id("io.gitlab.arturbosch.detekt") version "1.23.1" // Plugin Detekt
}

group = "org.example"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(21)
}

// Configuración de Detekt (fuera del bloque plugins)
detekt {
    buildUponDefaultConfig = true // Usa la configuración por defecto
    config = files("config/detekt/detekt.yml") // Si tienes un archivo de configuración personalizado
}
