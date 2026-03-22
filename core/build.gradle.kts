plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
    `maven-publish`
}

java {
    withSourcesJar()
}

publishing {
    repositories {
        mavenLocal()
    }
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "database-core"
        }
    }
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

val generateVersionKotlin = tasks.register("generateVersionKotlin") {
    val outputDir = layout.buildDirectory.dir("generated/kotlin")

    outputs.dir(outputDir)

    inputs.property("version", project.version)

    doLast {
        val packagePath = "org/octavius/database/config"
        val fileDir = outputDir.get().dir(packagePath).asFile
        fileDir.mkdirs()

        val outputFile = fileDir.resolve("AppInfo.kt")
        outputFile.writeText("""
            package org.octavius.database.config

            object AppInfo {
                const val VERSION = "${project.version}"
            }
        """.trimIndent())
    }
}

kotlin.sourceSets.main {
    kotlin.srcDir(generateVersionKotlin)
}
