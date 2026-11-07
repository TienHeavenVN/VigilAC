plugins {
    java
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "ac.vigil"
version = "1.0.0"

// Overridden by CI's compatibility matrix: -PpaperApiVersion=1.21.4-R0.1-SNAPSHOT
val paperApiVersion = providers.gradleProperty("paperApiVersion").getOrElse("1.21.8-R0.1-SNAPSHOT")
// 1.21.x targets compile with --release 21; 26.x targets with --release 25.
val javaRelease = providers.gradleProperty("javaRelease").getOrElse("21").toInt()
val artifactName = providers.gradleProperty("artifactName").getOrElse("VigilAC")

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(javaRelease))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(javaRelease)
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:${paperApiVersion}")
    compileOnly("com.github.retrooper:packetevents-spigot:2.14.0")
    implementation("org.xerial:sqlite-jdbc:3.46.1.3")

    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
    // Test-only: REAL ONNX Runtime for the end-to-end adapter contract test.
    // Never shipped, never required at plugin runtime (the adapter probes reflectively).
    testImplementation("com.microsoft.onnxruntime:onnxruntime:1.31.1")
    testImplementation("org.yaml:snakeyaml:2.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.processResources {
    val projectVersion = project.version.toString()
    inputs.property("version", projectVersion)
    filesMatching("plugin.yml") {
        filter { line -> line.replace("\${version}", projectVersion) }
    }
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
    }
}

tasks.shadowJar {
    archiveClassifier.set("")
    archiveFileName.set("$artifactName.jar")
    // sqlite-jdbc is intentionally not relocated: it is the only bundled dependency
    // and other plugins bundle it as org.sqlite as well; the driver name is looked
    // up via DriverManager which does not survive relocation.
}

tasks.named("build") {
    dependsOn(tasks.shadowJar)
}

tasks.jar {
    archiveClassifier.set("thin")
}
