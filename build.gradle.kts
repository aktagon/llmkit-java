import com.vanniktech.maven.publish.SonatypeHost

plugins {
    `java-library`
    // Publishes to the Maven Central Portal (central.sonatype.com), which is
    // not a plain Maven repo URL. This plugin builds the required
    // jar/-sources/-javadoc, generates the POM, GPG-signs every artifact, and
    // uploads the bundle. Build-time only — it never ships in the artifact, so
    // the runtime dependency set stays {Gson} (ADR-068 JAVA-002).
    id("com.vanniktech.maven.publish") version "0.30.0"
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

mavenPublishing {
    // automaticRelease = false: the upload lands in the Portal as a staged
    // deployment for a human to review + release, not an irreversible publish.
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL, automaticRelease = false)
    signAllPublications()
    coordinates("com.aktagon", "llmkit", version.toString())
    pom {
        name.set("llmkit")
        // Public package metadata — describe what it does, not how it is built
        // (no ontology/codegen framing).
        description.set("A unified, typed LLM client for Java: one API across many model providers.")
        url.set("https://github.com/aktagon/llmkit-java")
        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/licenses/MIT")
                distribution.set("repo")
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
            url.set("https://github.com/aktagon/llmkit-java")
            connection.set("scm:git:https://github.com/aktagon/llmkit-java.git")
            developerConnection.set("scm:git:ssh://git@github.com/aktagon/llmkit-java.git")
        }
    }
}
