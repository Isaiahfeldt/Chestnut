plugins {
    kotlin("jvm") version "2.2.10-RC2"
    id("com.gradleup.shadow") version "8.3.0"
    id("xyz.jpenilla.run-paper") version "2.3.1"
}

group = "xyz.bellbot"
version = "0.1.1"

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

// Load optional deployment directory from a .env file at project root.
// If PLUGIN_DEPLOY_DIR is missing or .env doesn't exist, no copy task will be added.
val pluginDeployDir: String? = run {
    val envFile = rootProject.file(".env")
    if (!envFile.exists()) null else runCatching {
        val map = envFile.readLines().mapNotNull { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@mapNotNull null
            val idx = line.indexOf('=')
            if (idx <= 0) return@mapNotNull null
            val key = line.substring(0, idx).trim()
            var value = line.substring(idx + 1).trim()
            if ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'"))) {
                value = value.substring(1, value.length - 1)
            }
            key to value
        }.toMap()
        map["PLUGIN_DEPLOY_DIR"].takeUnless { it.isNullOrBlank() }
    }.getOrNull()
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

    // Conditionally add a copy task that places the built jar into PLUGIN_DEPLOY_DIR
    if (!pluginDeployDir.isNullOrBlank()) {
        register<Copy>("copyPluginToDeployDir") {
            group = "distribution"
            description = "Copies the built plugin jar to the configured PLUGIN_DEPLOY_DIR (.env)"
            dependsOn("shadowJar")
            // Copy the produced jar from build/libs
            from(layout.buildDirectory.dir("libs")) {
                include("*.jar")
            }
            into(file(pluginDeployDir!!))
        }

        named("build") {
            finalizedBy("copyPluginToDeployDir")
        }
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
