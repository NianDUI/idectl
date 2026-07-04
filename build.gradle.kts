plugins {
    id("java")
    // Match the platform's bundled Kotlin (2026.1.4 ships Kotlin 2.3.x metadata).
    id("org.jetbrains.kotlin.jvm") version "2.3.21"
    id("org.jetbrains.intellij.platform") version "2.17.0"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // Since 2025.3 (253) Community/Ultimate ship as one unified distribution → intellijIdea(...).
        // useInstaller = false → resolve the SDK zip from the intellij-repository
        // (com/jetbrains/intellij/idea/ideaIC) instead of the OS installer (.dmg).
        intellijIdea(providers.gradleProperty("platformVersion").get()) {
            useInstaller = false
        }
        // ProjectTaskManager / execution / debugger / build APIs live in the bundled Java plugin.
        bundledPlugin("com.intellij.java")
        // JUnitConfiguration (run_test creates JUnit configs on the fly). Always bundled in IDEA.
        bundledPlugin("JUnit")
    }

    // --- Provided by the IntelliJ Platform at runtime → compile-only, never shipped ---
    // Gson: bundled in the platform. kotlinx-coroutines: we MUST reuse the platform's
    // patched copy (D13 — the shipped zip must contain no kotlinx-coroutines jar).
    compileOnly("com.google.code.gson:gson:2.10.1")
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    compileOnly("org.slf4j:slf4j-api:2.0.9")

    // --- Embedded MCP transport (L1) ---
    // The platform 2026 already bundles Ktor 3.x (intellij.libraries.ktor.server.cio.jar etc.)
    // on the compile+runtime classpath. We compile against those exact classes and bundle NONE:
    // a second Ktor copy in the plugin classloader splits the io.ktor.* class graph across
    // classloaders → ClassCastException at runtime. No maven Ktor dependency is declared.
}

intellijPlatform {
    pluginConfiguration {
        version = providers.gradleProperty("pluginVersion")
        ideaVersion {
            // Target 2026.x only (2026.1 == build 261). No upper bound so 2026.2+ still loads.
            sinceBuild = "261"
            untilBuild = provider { null }
        }
    }
}

kotlin {
    // 2026 platform is JVM 21 bytecode — match it to avoid inline-target warnings.
    jvmToolchain(21)
}

tasks {
    runIde {
        jvmArgs("-Xmx1500m")
        // Optional smoke-test hook: ./gradlew runIde -PbridgeToken=... forces a known token.
        providers.gradleProperty("bridgeToken").orNull?.let {
            jvmArgs("-Didea.bridge.token=$it")
            // Smoke runs are unattended: auto-trust so no modal blocks project import/indexing.
            jvmArgs("-Didea.trust.all.projects=true")
        }
        // Optional: open a project on launch (first CLI arg to the IDE) for unattended validation.
        providers.gradleProperty("openProject").orNull?.let { args(it) }
    }
    // Optional settings-search index; flaky headless and not needed for functionality.
    buildSearchableOptions {
        enabled = false
    }
}

// D13 guard: fail the build if any kotlinx-coroutines jar sneaks into the shipped zip.
val verifyNoCoroutinesInZip by tasks.registering {
    dependsOn("buildPlugin")
    doLast {
        val zip = layout.buildDirectory.dir("distributions").get().asFile
            .listFiles { f -> f.extension == "zip" }?.maxByOrNull { it.lastModified() }
            ?: error("no plugin zip produced")
        val offenders = zipTree(zip).matching { include("**/kotlinx-coroutines*.jar") }.files
        require(offenders.isEmpty()) {
            "D13 violation: kotlinx-coroutines jar(s) present in $zip: ${offenders.map { it.name }}"
        }
        logger.lifecycle("D13 OK: no kotlinx-coroutines jar in ${zip.name}")
    }
}

// One-click: build the plugin and install/replace it in your everyday IntelliJ IDEA.
//   ./gradlew deployToIde                      → default IntelliJ IDEA 2026.1 config dir
//   ./gradlew deployToIde -PideConfigDir=/path → any other IDE config dir
// After it finishes, RESTART IntelliJ IDEA (a running IDE only loads the new plugin on restart).
val deployToIde by tasks.registering {
    dependsOn("buildPlugin", verifyNoCoroutinesInZip)
    doLast {
        val ideConfigDir = providers.gradleProperty("ideConfigDir").orNull
            ?: "${System.getProperty("user.home")}/Library/Application Support/JetBrains/IntelliJIdea2026.1"
        val pluginsDir = file("$ideConfigDir/plugins")
        require(pluginsDir.parentFile.isDirectory) {
            "IDE config dir not found: $ideConfigDir (pass -PideConfigDir=/path/to/config)"
        }
        val zip = layout.buildDirectory.dir("distributions").get().asFile
            .listFiles { f -> f.extension == "zip" }?.maxByOrNull { it.lastModified() }
            ?: error("no plugin zip produced")
        val target = file("$pluginsDir/idectl")
        delete(target) // replace any previous install
        copy {
            from(zipTree(zip)) // zip root is `idectl/...`
            into(pluginsDir)
        }
        logger.lifecycle("Deployed ${zip.name} → $target")
        logger.lifecycle(">>> RESTART IntelliJ IDEA to load the new version. <<<")
    }
}
