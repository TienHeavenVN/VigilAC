plugins {
    java
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "ac.vigil"
version = "1.0.0"

// Overridden by CI's compatibility matrix: -PpaperApiVersion=1.21.4-R0.1-SNAPSHOT
val paperApiVersion = providers.gradleProperty("paperApiVersion").getOrElse("1.21.8-R0.1-SNAPSHOT")

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:${'$'}paperApiVersion")
    compileOnly("com.github.retrooper:packetevents-spigot:2.11.2")
    implementation("org.xerial:sqlite-jdbc:3.46.1.3")

    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
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
    archiveFileName.set("VigilAC.jar")
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
