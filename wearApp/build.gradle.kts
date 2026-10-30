import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}

dependencies {
    // Only :sharedLogic, deliberately. :sharedUI is Compose Multiplatform, whose Material 3 is
    // built for round-rectangle screens; Wear OS needs androidx.wear.compose instead, so the
    // watch draws its own UI over the shared state machine.
    implementation(project(":sharedLogic"))

    implementation(libs.androidx.activity.compose)
    implementation(libs.play.services.wearable)
    // play-services-wearable drags in androidx.fragment 1.1.0, which predates the
    // ActivityResult APIs this app registers in onCreate. Lint fails the build on it
    // rather than letting it break at runtime.
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.work.runtime)
    implementation(libs.wear.compose.material3)
    implementation(libs.wear.compose.foundation)
    implementation(libs.wear.compose.navigation)

    implementation(libs.wear.toolingPreview)
    // The JUnit variant: a plain Android module has no multiplatform test runner,
    // so kotlin.test needs to be mapped onto JUnit4 explicitly.
    androidTestImplementation(libs.kotlin.testJunit)
    androidTestImplementation(libs.androidx.testRunner)
    androidTestImplementation(libs.androidx.testExt.junit)

    implementation(libs.compose.uiToolingPreview)
    debugImplementation(libs.compose.uiTooling)
}

// Derived from the single `appVersion` entry in gradle/libs.versions.toml. Computed here
// rather than read from the root project, which the configuration cache discourages.
val appVersionName: String = libs.versions.appVersion.get().removePrefix("v")
val appVersionCode: Int = appVersionName.split(".").let { (major, minor, patch) ->
    major.toInt() * 10_000 + minor.toInt() * 100 + patch.toInt()
}

android {
    namespace = "com.halotap.app.wear"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        // Same applicationId as :androidApp on purpose. Play delivers a Wear APK and a phone
        // APK from one listing only when they share it, differing by version code.
        applicationId = "com.halotap.app"
        minSdk = libs.versions.android.wearMinSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        // Both derived from `appVersion` in gradle/libs.versions.toml, which is the only
        // place the version is declared. 0.1.0 -> 100, 0.2.0 -> 200, 1.0.0 -> 10000.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = appVersionCode + 1
        versionName = appVersionName
        // +1 so the Wear APK never collides with the phone APK: Play requires every
        // APK in one release to carry a distinct versionCode, and these two share an
        // applicationId on purpose.
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    // Release signing comes from the environment so that no keystore or password is ever
    // committed. Absent -- which is every local build -- the release type stays unsigned and
    // `assembleRelease` still works; only the publishing pipeline needs real credentials.
    val keystorePath: String? = System.getenv("ANDROID_KEYSTORE_PATH")
    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}
