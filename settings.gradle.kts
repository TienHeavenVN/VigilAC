pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/") {
            name = "papermc"
        }
        // PacketEvents is additionally mirrored here (official Retrooper repository).
        maven("https://repo.codemc.io/repository/maven-public/") {
            name = "codemc"
        }
    }
}
rootProject.name = "VigilAC"
