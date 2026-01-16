plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {

    jvm("desktop") // Dla aplikacji desktopowej
    js(IR) {       // Dla wtyczki
        browser()
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                api(libs.kotlinx.serialization.json) // @DynamicallyMappable opiera się na kotlinx.serialization
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