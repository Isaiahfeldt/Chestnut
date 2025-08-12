plugins {
    kotlin("jvm") version "2.2.10-RC2"
    id("com.gradleup.shadow") version "8.3.0"
    id("xyz.jpenilla.run-paper") version "2.3.1"
}

group = "xyz.bellbot"
version = "0.1.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/") {
        name = "papermc-repo"
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.8-R0.1-SNAPSHOT")
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
}

tasks {
    // Produce only the shaded jar, with no "-all" suffix
    shadowJar {
        archiveClassifier.set("")   // <- removes "-all"
        // Optional hardening:
        // minimize()
        // relocate("kotlin", "xyz.bellbot.chestnut.libs.kotlin")
    }

    // Don’t build the plain/thin jar
    named<Jar>("jar") { enabled = false }

    // Ensure `build` runs the shaded jar
    build { dependsOn(shadowJar) }

    processResources {
        val props = mapOf("version" to version)
        inputs.properties(props)
        filteringCharset = "UTF-8"
        filesMatching("plugin.yml") { expand(props) }
        exclude("paper-plugin.yml")
    }

    runServer {
        minecraftVersion("1.21") // run-paper will pick shadowJar automatically if present
    }
}

val targetJavaVersion = 21
kotlin {
    jvmToolchain(targetJavaVersion)
}

tasks.build {
    dependsOn("shadowJar")
}

tasks.processResources {
    val props = mapOf("version" to version)
    inputs.properties(props)
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") {
        expand(props)
    }
    // Ensure we ship as a Bukkit-style plugin (no paper-plugin.yml)
    exclude("paper-plugin.yml")
}
