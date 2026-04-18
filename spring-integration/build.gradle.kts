plugins {
    alias(libs.plugins.kotlinJvm)
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
            artifactId = "database-spring-integration"
        }
    }
}

dependencies {
    api(projects.core)
    
    implementation(libs.spring.jdbc)

    // Test dependencies
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testImplementation(libs.assertj.core)
    testImplementation(libs.hikari)
    testImplementation(libs.postgres)
}

tasks.withType<Test> {
    useJUnitPlatform()
}
