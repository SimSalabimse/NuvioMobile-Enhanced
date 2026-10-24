# Shared iOS Simulator paths for the build-196 smoke harness and verify-nuvio-ios.
# Source this file. Do not execute it.

simulator_repository_root() {
  cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P
}

simulator_derived_data() {
  local root
  root="$(simulator_repository_root)"
  printf '%s\n' "${SMOKE_DERIVED_DATA:-${root}/build/ios-derived-full-simulator}"
}

simulator_app_path() {
  printf '%s\n' "$(simulator_derived_data)/Build/Products/Debug-iphonesimulator/${SMOKE_APP_NAME:-Nuvio Enhanced.app}"
}

simulator_xcode_overrides() {
  # Ad-hoc signature so a hosted runner can install a simulator build without the
  # project development team certificate. Overrides the target's Automatic signing.
  printf '%s\n' \
    CODE_SIGN_STYLE=Manual \
    CODE_SIGN_IDENTITY=- \
    CODE_SIGNING_REQUIRED=NO \
    CODE_SIGNING_ALLOWED=YES \
    DEVELOPMENT_TEAM= \
    ONLY_ACTIVE_ARCH=YES \
    ARCHS=arm64
}

simulator_bundle_id_from_app() {
  local app_path="$1"
  /usr/libexec/PlistBuddy -c 'Print :CFBundleIdentifier' "${app_path}/Info.plist"
}

simulator_app_alive() {
  local udid="$1"
  local bundle_id="$2"
  xcrun simctl spawn "${udid}" launchctl list 2>/dev/null | grep -q "UIKitApplication:${bundle_id}"
}
