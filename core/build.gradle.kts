plugins {
    kotlin("jvm")
    alias(libs.plugins.kotlinSerialization)
}

dependencies {
    api(projects.database.api)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.datetime)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlin.reflect)
    implementation(libs.logback.classic)
    implementation(libs.kotlin.logging)

    // Database-specific dependencies
    implementation(dbLibs.postgres)
    implementation(dbLibs.hikari)
    implementation(dbLibs.spring.jdbc)
    implementation(dbLibs.classgraph)

    implementation(dbLibs.flyway.postgres)


    // Test dependencies - precyzyjnie tylko to co potrzebne
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter.api)  // @Test, @BeforeAll etc.
    testRuntimeOnly(libs.junit.jupiter.engine)  // silnik do uruchamiania testów
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