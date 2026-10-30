#!/bin/bash
#
# Builds the one sharedLogic slice this Xcode build actually needs and stages it at a fixed
# path. Staging matters: FRAMEWORK_SEARCH_PATHS is static in the project, but which Kotlin
# target is correct changes per destination, so the path stays put and the contents move.
#
# Only one architecture is produced per build, which is what a simulator or device run wants.
# A universal archive for the App Store should use an XCFramework instead.
set -euo pipefail

case "${SDK_NAME}" in
  watchsimulator*)
    case "${ARCHS}" in
      *arm64*) KT_TARGET=watchosSimulatorArm64 ;;   # Apple Silicon host
      *)       KT_TARGET=watchosX64 ;;              # Intel host
    esac
    ;;
  watchos*)
    case "${ARCHS}" in
      *arm64_32*) KT_TARGET=watchosArm64 ;;         # Series 4 and later
      *)          KT_TARGET=watchosDeviceArm64 ;;
    esac
    ;;
  *)
    echo "error: build-kotlin-framework.sh does not handle SDK_NAME='${SDK_NAME}'" >&2
    exit 1
    ;;
esac

if [ "${CONFIGURATION}" = "Release" ]; then KT_BUILD=release; else KT_BUILD=debug; fi

capitalize() { printf '%s%s' "$(echo "${1:0:1}" | tr '[:lower:]' '[:upper:]')" "${1:1}"; }
TASK=":sharedLogic:link$(capitalize "${KT_BUILD}")Framework$(capitalize "${KT_TARGET}")"

cd "${SRCROOT}/.."
echo "note: building ${TASK}"
./gradlew "${TASK}"

FRAMEWORK="sharedLogic/build/bin/${KT_TARGET}/${KT_BUILD}Framework/SharedLogic.framework"
if [ ! -d "${FRAMEWORK}" ]; then
  echo "error: expected framework at ${FRAMEWORK} but it is missing" >&2
  exit 1
fi

STAGE="${SRCROOT}/build/kotlin-framework"
mkdir -p "${STAGE}"
rm -rf "${STAGE}/SharedLogic.framework"
cp -R "${FRAMEWORK}" "${STAGE}/"
echo "note: staged ${KT_TARGET} ${KT_BUILD} framework at ${STAGE}"
