plugins {
    `java-library`
}

group = "com.aktagon"
version = "0.0.1"

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
}
