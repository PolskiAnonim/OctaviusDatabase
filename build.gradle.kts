import org.jetbrains.dokka.gradle.DokkaExtension
import org.jetbrains.dokka.gradle.engine.parameters.VisibilityModifier

plugins {
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.dokka)
    `maven-publish`
}

allprojects {
    group = "org.octavius"
    version = "1.0.2"
}

dokka {
    moduleName.set("Octavius Database")

    dokkaPublications.html {
        outputDirectory.set(layout.buildDirectory.dir("dokka/html"))
    }
}

dependencies {
    dokka(project(":api"))
    dokka(project(":core"))
}

subprojects {
    apply(plugin = "org.jetbrains.dokka")

    extensions.configure<DokkaExtension> {
        moduleName.set(name)

        dokkaSourceSets.configureEach {
            documentedVisibilities.set(
                setOf(
                    VisibilityModifier.Public,
                    VisibilityModifier.Protected,
                    VisibilityModifier.Internal
                )
            )
            skipEmptyPackages.set(true)
        }
    }
}

allprojects {
    plugins.withId("maven-publish") {
        configure<PublishingExtension> {
            repositories {
                maven {
                    name = "GitHubPackages"
                    url = uri("https://maven.pkg.github.com/${System.getenv("GITHUB_REPOSITORY")}")
                    credentials {
                        username = System.getenv("GITHUB_ACTOR")
                        password = System.getenv("GITHUB_TOKEN")
                    }
                }
            }
        }
    }
}