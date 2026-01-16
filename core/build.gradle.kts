plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
}

dependencies {
    api(projects.api)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.datetime)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlin.reflect)
    implementation(libs.logback.classic)
    implementation(libs.kotlin.logging)

    // Database-specific dependencies
    implementation(libs.postgres)
    implementation(libs.hikari)
    implementation(libs.spring.jdbc)
    implementation(libs.classgraph)
    implementation(libs.flyway.postgres)

    // Test dependencies
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.junit.jupiter.params)
    testImplementation(libs.mockk)
    testImplementation(libs.assertj.core)
}

tasks.withType<Test> {
    useJUnitPlatform()

    testLogging {
        events("passed", "skipped", "failed")
    }
}
