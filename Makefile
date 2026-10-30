# =============================================================================
# Halotap - build, test, and run automation for both watches
#
# Requirements:
#   * JDK 17+. Gradle itself comes from the wrapper checked into this repo.
#   * The Android SDK, for every wear-* target.
#   * Xcode and xcodegen (brew install xcodegen), for every watch-* target.
#
# Any variable below can be overridden per invocation, e.g.
#   make wear-avd AVD_NAME=MyWatch
#   make watch-build CONFIG=Release
#
# Run `make help` to list targets.
# =============================================================================

# -----------------------------------------------------------------------------
# Host detection
# -----------------------------------------------------------------------------
UNAME_M := $(shell uname -m)

# Simulator binaries are host-architecture-specific: an Intel Mac cannot run an
# arm64 simulator slice and an Apple Silicon Mac cannot run the x64 one. So the
# Kotlin/Native target used for tests, and the arch handed to xcodebuild, both
# follow the host. This block is the only thing that changes when this repo
# moves to Apple Silicon -- which it will have to, since Kotlin/Native has
# deprecated both the macos_x64 host and the watchosX64 target.
ifneq (,$(filter arm64 aarch64,$(UNAME_M)))
    KN_TEST_TARGET := watchosSimulatorArm64
    WATCH_SIM_ARCH := arm64
else
    KN_TEST_TARGET := watchosX64
    WATCH_SIM_ARCH := x86_64
endif

# -----------------------------------------------------------------------------
# Android / Wear OS
# -----------------------------------------------------------------------------
ANDROID_SDK ?= $(if $(ANDROID_HOME),$(ANDROID_HOME),$(HOME)/Library/Android/sdk)
ADB         := $(ANDROID_SDK)/platform-tools/adb
EMULATOR    := $(ANDROID_SDK)/emulator/emulator
AVDMANAGER  := $(ANDROID_SDK)/cmdline-tools/latest/bin/avdmanager
SDKMANAGER  := $(ANDROID_SDK)/cmdline-tools/latest/bin/sdkmanager

AVD_NAME    ?= Halotap_Wear
WEAR_IMAGE  ?= system-images;android-34;android-wear;x86_64
WEAR_DEVICE ?= wearos_large_round

# applicationId and namespace differ on purpose -- :wearApp shares the
# applicationId with :androidApp so Play can serve both from one listing -- so
# the launch component cannot be shortened to ".MainActivity".
APP_ID        := com.halotap.app
WEAR_ACTIVITY := $(APP_ID)/$(APP_ID).wear.MainActivity

# -----------------------------------------------------------------------------
# Apple Watch
# -----------------------------------------------------------------------------
WATCH_DIR     := watchApp
WATCH_PROJECT := $(WATCH_DIR)/watchApp.xcodeproj
WATCH_TARGET  ?= Halotap
CONFIG        ?= Debug

# xcodebuild is driven by -target, not -scheme, deliberately. A -scheme build
# resolves destinations first, and that fails outright ("Found no destinations")
# whenever no installed simulator runtime matches Xcode's watchOS SDK. Building
# a target needs only the SDK, which is always present.
XCODEBUILD := xcodebuild -project watchApp.xcodeproj -target $(WATCH_TARGET) \
	-configuration $(CONFIG) ONLY_ACTIVE_ARCH=NO CODE_SIGNING_ALLOWED=NO

.DEFAULT_GOAL := help
.PHONY: help test test-shared test-android test-device wear-build wear-install wear-run \
	wear-avd wear-emulator wear-logcat android-build lint \
	watch-project watch-build watch-build-device ios-framework clean sanity

# -----------------------------------------------------------------------------
# Help
# -----------------------------------------------------------------------------
help:
	@echo "Halotap targets"
	@echo ""
	@echo "  Shared logic"
	@echo "    test                Run the shared tests on this host ($(KN_TEST_TARGET)) and the JVM"
	@echo "    test-shared         Shared tests on the watchOS simulator only"
	@echo "    test-android        Shared tests on the JVM only"
	@echo "    test-device         Keystore, marker and sync tests on a connected watch"
	@echo ""
	@echo "  Wear OS"
	@echo "    wear-avd            Install the Wear system image and create the '$(AVD_NAME)' AVD"
	@echo "    wear-emulator       Boot that AVD (blocks; run it in its own terminal)"
	@echo "    wear-build          Assemble the debug APK"
	@echo "    wear-install        Install it on the running watch or emulator"
	@echo "    wear-run            Install, then launch it"
	@echo "    wear-logcat         Follow this app's logs only"
	@echo ""
	@echo "  Apple Watch"
	@echo "    watch-project       Regenerate the Xcode project from project.yml"
	@echo "    watch-build         Build for the watch simulator ($(WATCH_SIM_ARCH))"
	@echo "    watch-build-device  Build the arm64_32 device slice"
	@echo ""
	@echo "  Other"
	@echo "    android-build       Assemble the phone companion (template placeholder)"
	@echo "    ios-framework       Link the shared framework for iOS"
	@echo "    lint                detekt over every module, then Android lint"
	@echo "    sanity              test, wear-build, watch-build, in that order"
	@echo "    clean               Delete Gradle output and the generated Xcode project"

# -----------------------------------------------------------------------------
# Shared logic
# -----------------------------------------------------------------------------
test: test-shared test-android

test-shared:
	./gradlew :sharedLogic:$(KN_TEST_TARGET)Test

test-android:
	./gradlew :sharedLogic:testAndroidHostTest

# Needs a connected watch or a running emulator (`make wear-avd && make wear-emulator`).
# These cover the Android Keystore encryption and the armed marker, neither of which can be
# tested on the JVM: there is no real Keystore there, and that crypto is the part worth
# proving. Not wired into CI yet -- see CONTRIBUTING.md.
test-device:
	./gradlew :sharedLogic:connectedAndroidDeviceTest :wearApp:connectedDebugAndroidTest

# -----------------------------------------------------------------------------
# Wear OS
# -----------------------------------------------------------------------------
# sdkmanager is a no-op when the image is already present, so this is safe to
# re-run. --force replaces an existing AVD of the same name, and `echo no`
# declines avdmanager's custom-hardware-profile prompt.
wear-avd:
	$(SDKMANAGER) "$(WEAR_IMAGE)"
	echo no | $(AVDMANAGER) create avd --force -n "$(AVD_NAME)" -k "$(WEAR_IMAGE)" -d $(WEAR_DEVICE)

wear-emulator:
	$(EMULATOR) -avd $(AVD_NAME) -no-audio

wear-build:
	./gradlew :wearApp:assembleDebug

wear-install:
	./gradlew :wearApp:installDebug

wear-run: wear-install
	$(ADB) shell am start -n $(WEAR_ACTIVITY)

wear-logcat:
	$(ADB) logcat --pid=$$($(ADB) shell pidof -s $(APP_ID))

android-build:
	./gradlew :androidApp:assembleDebug

lint:
	./gradlew detekt
	./gradlew :wearApp:lintDebug

# -----------------------------------------------------------------------------
# Apple Watch
# -----------------------------------------------------------------------------
# The project is generated, not committed, so every watch-* target regenerates
# it first. Building it also builds the Kotlin framework: a pre-build script in
# the project links whichever slice the destination needs.
watch-project:
	./gradlew generateWatchVersionConfig
	cd $(WATCH_DIR) && xcodegen generate

watch-build: watch-project
	cd $(WATCH_DIR) && $(XCODEBUILD) -sdk watchsimulator ARCHS=$(WATCH_SIM_ARCH) build

watch-build-device: watch-project
	cd $(WATCH_DIR) && $(XCODEBUILD) -sdk watchos ARCHS=arm64_32 CODE_SIGNING_REQUIRED=NO build

ios-framework:
	./gradlew :sharedLogic:linkDebugFrameworkIosArm64

# -----------------------------------------------------------------------------
# Housekeeping
# -----------------------------------------------------------------------------
sanity: test wear-build watch-build

clean:
	./gradlew clean
	rm -rf $(WATCH_PROJECT) $(WATCH_DIR)/build $(WATCH_DIR)/Info.plist
