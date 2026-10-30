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
    // :sharedLogic only. This app is the setup surface for it: it writes the settings the
    // watch reads. :sharedUI went with the template's sample screen -- it was still being
    // compiled into the APK with nothing calling into it.
    implementation(project(":sharedLogic"))

    implementation(libs.androidx.activity.compose)
    implementation(libs.play.services.wearable)
    // play-services-wearable drags in androidx.fragment 1.1.0, which predates the
    // ActivityResult APIs this app registers in onCreate. Lint fails the build on it
    // rather than letting it break at runtime.
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.lifecycle.runtimeCompose)

    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)

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
    namespace = "com.halotap.app"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.halotap.app"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        // Both derived from `appVersion` in gradle/libs.versions.toml, which is the only
        // place the version is declared. 0.1.0 -> 100, 0.2.0 -> 200, 1.0.0 -> 10000.
        versionCode = appVersionCode
        versionName = appVersionName
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