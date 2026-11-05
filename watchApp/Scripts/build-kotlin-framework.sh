#!/bin/bash
#
# Builds the one sharedLogic slice this Xcode build actually needs and stages it at a fixed
# path. Staging matters: FRAMEWORK_SEARCH_PATHS is static in the project, but which Kotlin
# target is correct changes per destination, so the path stays put and the contents move.
#
# The two SDKs are served differently, on purpose.
#
# A **device** build targets arm64 and arm64_32 at once -- Series 9 and later alongside
# everything from Series 4 -- so a single-architecture framework can never link, and an
# archive for the App Store could never be produced. It gets the fat slice the Kotlin
# plugin assembles for its XCFramework, which carries both.
#
# A **simulator** build needs exactly one architecture, and the Makefile pins it to the
# host's. Linking four slices to use one would make the inner loop several times slower for
# nothing, so that path still builds the single framework it needs.
set -euo pipefail

if [ "${CONFIGURATION}" = "Release" ]; then KT_BUILD=release; else KT_BUILD=debug; fi
capitalize() { printf '%s%s' "$(echo "${1:0:1}" | tr '[:lower:]' '[:upper:]')" "${1:1}"; }

case "${SDK_NAME}" in
  watchsimulator*)
    case "${ARCHS}" in
      *arm64*) KT_TARGET=watchosSimulatorArm64 ;;   # Apple Silicon host
      *)       KT_TARGET=watchosX64 ;;              # Intel host
    esac
    TASK=":sharedLogic:link$(capitalize "${KT_BUILD}")Framework$(capitalize "${KT_TARGET}")"
    FRAMEWORK="sharedLogic/build/bin/${KT_TARGET}/${KT_BUILD}Framework/SharedLogic.framework"
    SLICE="${KT_TARGET}"
    ;;
  watchos*)
    TASK=":sharedLogic:assemble$(capitalize "${KT_BUILD}")WatchosFatFrameworkForSharedLogicXCFramework"
    FRAMEWORK="sharedLogic/build/SharedLogicXCFrameworkTemp/fatframework/${KT_BUILD}/watchos/SharedLogic.framework"
    SLICE="watchos fat, arm64_32 and arm64"
    ;;
  *)
    echo "error: build-kotlin-framework.sh does not handle SDK_NAME='${SDK_NAME}'" >&2
    exit 1
    ;;
esac

cd "${SRCROOT}/.."
echo "note: building ${TASK}"
./gradlew "${TASK}"

if [ ! -d "${FRAMEWORK}" ]; then
  echo "error: expected framework at ${FRAMEWORK} but it is missing" >&2
  exit 1
fi

STAGE="${SRCROOT}/build/kotlin-framework"
mkdir -p "${STAGE}"
rm -rf "${STAGE}/SharedLogic.framework"
cp -R "${FRAMEWORK}" "${STAGE}/"
echo "note: staged ${SLICE} ${KT_BUILD} framework at ${STAGE}"
