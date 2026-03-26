import org.jetbrains.dokka.gradle.DokkaExtension
import org.jetbrains.dokka.gradle.engine.parameters.VisibilityModifier

plugins {
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.dokka)
    `maven-publish`
    signing
}

allprojects {
    group = "io.github.octavius-framework"
    version = "6.2.1"
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

    plugins.withId("maven-publish") {

        configure<PublishingExtension> {

            publications.withType<MavenPublication>().configureEach {
                val pubName = name

                val javadocTask = project.tasks.register<Jar>("${pubName}JavadocJar") {
                    archiveClassifier.set("javadoc")
                    archiveAppendix.set(pubName)
                    from(tasks.named("dokkaGenerateHtml"))
                }
                artifact(javadocTask)

                pom {
                    name.set("Octavius Database - ${project.name}")
                    description.set("SQL-first data access layer for Kotlin & PostgreSQL. An Anti-ORM with fluent query builders, automatic type mapping (ENUM, COMPOSITE, ARRAY), transaction plans with step dependencies, and polymorphic storage")
                    url.set("https://github.com/Octavius-Framework/octavius-database")

                    licenses {
                        license {
                            name.set("The Apache License, Version 2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        }
                    }
                    developers {
                        developer {
                            id.set("PolskiAnonim")
                            name.set("PolskiAnonim")
                            email.set("115878440+PolskiAnonim@users.noreply.github.com")
                            organization.set("Octavius Framework")
                            organizationUrl.set("https://github.com/Octavius-Framework")
                        }
                    }
                    scm {
                        connection.set("scm:git:git://github.com/Octavius-Framework/octavius-database.git")
                        developerConnection.set("scm:git:ssh://github.com/Octavius-Framework/octavius-database.git")
                        url.set("https://github.com/Octavius-Framework/octavius-database")
                    }
                }
            }

            repositories {
                maven {
                    name = "LocalStaging"
                    url = uri(rootProject.layout.buildDirectory.dir("staging-deploy"))
                }
            }
        }

        project.apply(plugin = "signing")
        configure<SigningExtension> {
            val signingKey = System.getenv("OSSRH_GPG_SECRET_KEY")
            val signingPassword = System.getenv("OSSRH_GPG_SECRET_KEY_PASSWORD")

            if (!signingKey.isNullOrBlank()) {
                useInMemoryPgpKeys(signingKey, signingPassword)
                sign(extensions.getByType<PublishingExtension>().publications)
            }
        }
    }
}