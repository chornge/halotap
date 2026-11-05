plugins {
    // this is necessary to avoid the plugins to be loaded multiple times
    // in each subproject's classloader
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidMultiplatformLibrary) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.detekt)
}

// Static analysis across every module, including the Kotlin that Android lint never sees
// (commonMain, watchosMain). Android lint catches manifest and API-level problems; detekt
// catches the Kotlin itself. They do not overlap, so both run in CI.
subprojects {
    apply(plugin = "io.gitlab.arturbosch.detekt")

    detekt {
        parallel = true
        buildUponDefaultConfig = true
        config.setFrom(rootProject.files("config/detekt.yml"))
        baseline = rootProject.file("config/detekt-baseline.xml")
        // Every source set, not just the JVM ones: the watchOS actuals and the shared logic
        // are exactly where an unreviewed rule violation would otherwise hide.
        source.setFrom(files("src"))
    }
}
// `appVersion` in gradle/libs.versions.toml is the one place the app's version is declared.
// Gradle reads it directly for the Android modules; Xcode cannot read a TOML, so this task
// writes it into an xcconfig that watchApp/project.yml includes. Run it before xcodegen --
// `make watch-project` does, and so does CI.
val generateWatchVersionConfig by tasks.registering {
    description = "Write watchApp/Configuration/Version.xcconfig from appVersion"
    group = "build"

    // Stripped: Apple requires a bare number here. See the comment on appVersion.
    val appVersion = libs.versions.appVersion.get().removePrefix("v")
    // Same arithmetic as the Android versionCode, so the two stores never disagree about
    // which build is newer: 0.1.0 -> 1000, 0.2.1 -> 2010, 1.0.0 -> 100000. Patches are
    // spaced ten apart so the Wear +1 cannot land on the next patch's phone code. Leading
    // zeroes are
    // avoided deliberately; Apple wants a plain increasing integer.
    val buildNumber = appVersion.split(".").let { (major, minor, patch) ->
        major.toInt() * 100_000 + minor.toInt() * 1_000 + patch.toInt() * 10
    }
    val output = layout.projectDirectory.file("watchApp/Configuration/Version.xcconfig")

    inputs.property("appVersion", appVersion)
    outputs.file(output)

    doLast {
        val file = output.asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            // Generated from `appVersion` in gradle/libs.versions.toml. Do not edit.
            // Regenerate with: ./gradlew generateWatchVersionConfig
            MARKETING_VERSION = $appVersion
            CURRENT_PROJECT_VERSION = $buildNumber
            """.trimIndent() + "\n",
        )
    }
}
