plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    `maven-publish`
}

publishing {
    repositories {
        mavenLocal()
    }
}

afterEvaluate {
    publishing.publications.withType<MavenPublication>().configureEach {
        artifactId = "database-$artifactId"
    }
}

kotlin {

    jvm("desktop")
    js(IR) {
        browser()
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                api(libs.kotlinx.serialization.json)
                api(libs.kotlinx.datetime)
                api(libs.kotlinx.coroutines.core)
            }
        }
        val desktopMain by getting {
            dependencies {
                implementation(libs.kotlin.reflect)
            }
        }
        val jsMain by getting
    }
}
