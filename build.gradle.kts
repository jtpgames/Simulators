import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.7.10"
    kotlin("plugin.serialization") version "1.7.10"

    id("com.google.cloud.tools.jib") version "3.3.1"
    id("io.ktor.plugin") version "2.2.3"
}

group = "me.juritomak"
version = "1.0"

repositories {
    mavenCentral()
}

val ktor_version = "2.2.2"

dependencies {
    implementation("org.jpmml", "pmml-evaluator-metro", "1.6.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.4.1")
    implementation("io.ktor:ktor-server-core:$ktor_version")
    implementation("io.ktor:ktor-server-netty:$ktor_version")
    implementation("io.ktor:ktor-server-call-logging:$ktor_version")
    implementation("ch.qos.logback:logback-classic:1.4.5")

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

java.sourceCompatibility = JavaVersion.VERSION_11
java.targetCompatibility = JavaVersion.VERSION_11

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "11"
}

application {
    mainClass.set("MainKt")
}

ktor {
    docker {
        localImageName.set("teastore-simulator")
        imageTag.set("0.0.1-preview")
        jreVersion.set(io.ktor.plugin.features.JreVersion.JRE_11)
        portMappings.set(listOf(
            io.ktor.plugin.features.DockerPortMapping(
                8080,
                1337,
                io.ktor.plugin.features.DockerPortMappingProtocol.TCP
            )
        ))
    }
}