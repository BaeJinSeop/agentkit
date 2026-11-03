plugins {
    kotlin("jvm") version "2.3.21"
    `java-library`
}

group = "dev.jinseop"
version = "0.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
    withSourcesJar()
}

repositories {
    mavenCentral()
}

dependencies {
    api("com.fasterxml.jackson.core:jackson-databind:2.18.2")
    implementation("org.slf4j:slf4j-api:2.0.16")

    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.16")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}
