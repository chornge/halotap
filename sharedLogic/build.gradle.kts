import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

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
    // Collected into an XCFramework as well as published individually.
    //
    // A device build targets arm64 *and* arm64_32 at once -- Series 9 and later against
    // everything from Series 4 -- and a single-architecture framework cannot satisfy both, so
    // an archive could never link. The XCFramework carries one fat slice per platform
    // variant, which is the only shape that works for a real submission.
    //
    // The individual frameworks stay, because they are what the fast path uses: a simulator
    // build needs one slice and linking four would make the inner loop several times slower
    // for nothing. watchApp/Scripts/build-kotlin-framework.sh picks between them.
    val xcframework = XCFramework("SharedLogic")
    listOf(
        watchosArm64(),
        watchosDeviceArm64(),
        watchosSimulatorArm64(),
        watchosX64()
    ).forEach { appleTarget ->
        appleTarget.binaries.framework {
            baseName = "SharedLogic"
            isStatic = true
            xcframework.add(this)
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