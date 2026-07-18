plugins {
    `java-library`
    `maven-publish`
    signing
}

group = "com.aktagon"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    // The single permitted dependency (ADR-068 JAVA-002): Java has no stdlib
    // JSON; Gson's JsonObject is the insertion-ordered dynamic-map waist the
    // shared request pipeline requires. One artifact, zero transitive deps.
    api("com.google.code.gson:gson:2.13.1")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    // Maven Central requires -sources and -javadoc artifacts beside the jar.
    withSourcesJar()
    withJavadocJar()
}

tasks.withType<JavaCompile> {
    // Java 17 floor (ADR-068 JAVA-003) regardless of the JDK running Gradle.
    options.release.set(17)
    options.encoding = "UTF-8"
}

tasks.test {
    useJUnitPlatform()
    // Deterministic dummy SigV4 credentials for the bedrock-chat wire driver
    // (the signature is time-dependent and not asserted; only the body is).
    environment("AWS_REGION", "us-east-1")
    environment("AWS_SECRET_ACCESS_KEY", "test-secret")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            pom {
                name.set("llmkit")
                // Public package metadata — describe what it does, not how it
                // is built (no ontology/codegen framing).
                description.set("A unified, typed LLM client for Java: one API across many model providers.")
                url.set("https://github.com/aktagon/llmkit-java")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
                developers {
                    developer {
                        id.set("aktagon")
                        name.set("Aktagon")
                        email.set("christian@aktagon.com")
                    }
                }
                scm {
                    connection.set("scm:git:https://github.com/aktagon/llmkit-java.git")
                    developerConnection.set("scm:git:ssh://git@github.com/aktagon/llmkit-java.git")
                    url.set("https://github.com/aktagon/llmkit-java")
                }
            }
        }
    }
    // Maven Central now ingests through the Central Portal
    // (central.sonatype.com), which is NOT a plain Maven repository URL, so no
    // `repositories { maven { url = ... } }` block is wired here. Before the
    // first `./gradlew publish`, add the owner's chosen Central Portal
    // publisher (e.g. the com.vanniktech.maven.publish or jreleaser plugin, or
    // a portal bundle upload). Requires: the DNS-verified `com.aktagon`
    // namespace, a Central Portal token, and a published GPG key.
}

signing {
    // Only require a signature for publish tasks, so `build`/`test` and the
    // cross-SDK wire drivers run with no GPG key present.
    setRequired({ gradle.taskGraph.allTasks.any { it.name.startsWith("publish") } })
    useGpgCmd()
    sign(publishing.publications["maven"])
}
