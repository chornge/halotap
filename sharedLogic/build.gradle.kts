import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
}

kotlin {
    // watchOS only. Compose Multiplatform cannot render there, so the Apple Watch UI is
    // SwiftUI and this framework is the logic it drives. There are no iOS targets: the
    // template's iPhone app was deleted, and the watch needs no companion.
    //   watchosArm64        - Apple Watch Series 4 and later (arm64_32)
    //   watchosDeviceArm64  - newer watch hardware built for arm64
    //   watchosSimulatorArm64 - simulator on Apple Silicon
    //   watchosX64          - simulator on Intel, which is what this project is developed on
    listOf(
        watchosArm64(),
        watchosDeviceArm64(),
        watchosSimulatorArm64(),
        watchosX64()
    ).forEach { appleTarget ->
        appleTarget.binaries.framework {
            baseName = "SharedLogic"
            isStatic = true
        }
    }
    android {
       namespace = "com.halotap.app.sharedLogic"
       compileSdk = libs.versions.android.compileSdk.get().toInt()
       minSdk = libs.versions.android.minSdk.get().toInt()
       compilerOptions {
           jvmTarget = JvmTarget.JVM_11
       }
       androidResources {
           enable = true
       }
       withHostTest {
           isIncludeAndroidResources = true
       }
       // The Keystore and SharedPreferences code cannot be tested on the JVM: there is no
       // real Android Keystore to encrypt against, and that crypto is exactly the part worth
       // proving. These run on a device or emulator.
       withDeviceTestBuilder {
           sourceSetTreeName = "test"
       }.configure {
           instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
       }
    }
    sourceSets {
        commonMain.dependencies {
            // put your Multiplatform dependencies here
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        getByName("androidDeviceTest").dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.androidx.testRunner)
            implementation(libs.androidx.testExt.junit)
        }
    }
}