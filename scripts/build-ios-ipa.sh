#!/usr/bin/env bash

set -euo pipefail

repository_root="$(cd "$(dirname "$0")/.." && pwd -P)"
version_file="${repository_root}/iosApp/Configuration/Version.xcconfig"
version_default="$(sed -nE 's/^[[:space:]]*MARKETING_VERSION[[:space:]]*=[[:space:]]*([^[:space:]#]+).*$/\1/p' "${version_file}" | head -n 1)"
configuration="${IOS_CONFIGURATION:-Release}"
# fast (default): Release Swift + debug Kotlin framework, arm64 only, dwarf.
# release: full optimized Kotlin framework (use --release or IOS_BUILD_MODE=release).
build_mode="${IOS_BUILD_MODE:-fast}"
if [[ "${1:-}" == "--release" ]]; then
    build_mode="release"
    shift
fi
version="${1:-${version_default:-}}"
case "${configuration}" in
    Debug)
        configuration_slug="debug"
        ;;
    Release)
        configuration_slug="release"
        ;;
    *)
        echo "Unsupported iOS configuration: ${configuration}" >&2
        exit 1
        ;;
esac
derived_data="${IOS_DERIVED_DATA_PATH:-${repository_root}/build/ios-derived-full-${configuration_slug}${build_mode/#release/}}"
output_directory="${IOS_IPA_OUTPUT_DIR:-${repository_root}/build/ios-ipa}"
clang_module_cache="${CLANG_MODULE_CACHE_PATH:-${derived_data}/ModuleCache.noindex}"
swiftpm_module_cache="${SWIFTPM_MODULECACHE_OVERRIDE:-${derived_data}/SwiftPMModuleCache.noindex}"

if [[ ! "${version}" =~ ^[0-9A-Za-z][0-9A-Za-z._-]*$ ]]; then
    echo "Invalid IPA version: ${version}" >&2
    exit 1
fi

cd "${repository_root}"

ipa_status_helper="${IPA_STATUS_HELPER:-${repository_root}/scripts/ipa-live-status.sh}"
if [[ ! -f "${ipa_status_helper}" ]]; then
    echo "ipa-status: helper missing: ${ipa_status_helper}" >&2
    exit 1
fi
# shellcheck source=ipa-live-status.sh
source "${ipa_status_helper}"

# Item 1: Strip symbols optimization support
# Set STRIP_SYMBOLS=true to enable symbol stripping for smaller binary size
strip_symbols_flags=()
if [[ "${STRIP_SYMBOLS:-false}" == "true" ]]; then
    echo "Symbol stripping enabled (Item 1 optimization by Simsalabim)"
    strip_symbols_flags=(
        STRIP_INSTALLED_PRODUCT=YES
        COPY_PHASE_STRIP=YES
        STRIP_STYLE=all
        STRIP_SWIFT_SYMBOLS=YES
        DEPLOYMENT_POSTPROCESSING=YES
    )
else
    echo "Symbol stripping disabled (enable in Settings > Advanced > Performance)"
fi

ipa_build_cleanup() {
    local code=$?
    if [[ -n "${package_root:-}" ]]; then
        rm -rf "${package_root}"
    fi
    ipa_live_status_close "${code}"
}
trap ipa_build_cleanup EXIT
export IPA_STATUS_REPO="${repository_root}"
ipa_live_status_open_build
ipa_live_status stage --name xcodebuild

build_environment=(
    env
    NUVIO_IOS_DISTRIBUTION=full
    CLANG_MODULE_CACHE_PATH="${clang_module_cache}"
    SWIFTPM_MODULECACHE_OVERRIDE="${swiftpm_module_cache}"
)
if [[ -n "${NUVIO_KOTLIN_NATIVE_JVMARGS:-}" ]]; then
    build_environment+=("ORG_GRADLE_PROJECT_kotlin.native.jvmArgs=${NUVIO_KOTLIN_NATIVE_JVMARGS}")
fi
fast_flags=()
caffeinate_cmd=()
if [[ "${build_mode}" == "fast" ]]; then
    build_environment+=(KOTLIN_FRAMEWORK_BUILD_TYPE=debug)
    fast_flags=(ARCHS=arm64 ONLY_ACTIVE_ARCH=YES DEBUG_INFORMATION_FORMAT=dwarf -parallelizeTargets -jobs "$(sysctl -n hw.ncpu)")
elif [[ "${build_mode}" != "release" ]]; then
    echo "Unsupported IOS_BUILD_MODE: ${build_mode}" >&2
    exit 1
fi
command -v caffeinate >/dev/null && caffeinate_cmd=(caffeinate -dims)
${caffeinate_cmd[@]+"${caffeinate_cmd[@]}"} "${build_environment[@]}" \
    xcodebuild \
    -project iosApp/iosApp.xcodeproj \
    -scheme iosApp \
    -configuration "${configuration}" \
    -sdk iphoneos \
    -destination 'generic/platform=iOS' \
    -derivedDataPath "${derived_data}" \
    CODE_SIGNING_ALLOWED=NO \
    CODE_SIGNING_REQUIRED=NO \
    CODE_SIGN_IDENTITY= \
    ${NUVIO_IOS_SKIP_DSYM:+DEBUG_INFORMATION_FORMAT=dwarf} \
    ${fast_flags[@]+"${fast_flags[@]}"} \
    ${strip_symbols_flags[@]+"${strip_symbols_flags[@]}"} \
    build

ipa_live_status stage --name checks
products_directory="${derived_data}/Build/Products/${configuration}-iphoneos"
app_paths=()
while IFS= read -r candidate; do
    app_paths+=("${candidate}")
done < <(/usr/bin/find "${products_directory}" -maxdepth 1 -type d -name '*.app' -print 2>/dev/null | sort)
if (( ${#app_paths[@]} != 1 )); then
    echo "Expected exactly one .app in ${products_directory}, found ${#app_paths[@]}." >&2
    exit 1
fi
app_path="${app_paths[0]}"
app_bundle_name="$(basename "${app_path}")"

built_version="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleShortVersionString' "${app_path}/Info.plist")"
if [[ "${built_version}" != "${version}" ]]; then
    echo "Built iOS version ${built_version} does not match ${version}." >&2
    exit 1
fi

executable="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleExecutable' "${app_path}/Info.plist")"
architectures="$(xcrun lipo -archs "${app_path}/${executable}")"
if [[ " ${architectures} " != *" arm64 "* ]]; then
    echo "Built iOS application does not contain arm64." >&2
    exit 1
fi
if ! launch_screen_plist="$(plutil -extract UILaunchScreen xml1 -o - "${app_path}/Info.plist" 2>/dev/null)"; then
    echo "Built iOS application does not contain UILaunchScreen." >&2
    exit 1
fi
if [[ "${launch_screen_plist}" == *"<key>UILaunchScreen</key>"* ]]; then
    echo "Built iOS application contains a nested UILaunchScreen." >&2
    exit 1
fi
if [[ -d "${app_path}/_CodeSignature" ]]; then
    echo "Built iOS application is unexpectedly signed." >&2
    exit 1
fi

widget_path="${app_path}/PlugIns/DownloadsWidgetExtension.appex"
if [[ ! -d "${widget_path}" ]]; then
    echo "Built iOS application does not contain the downloads widget." >&2
    exit 1
fi
widget_executable="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleExecutable' "${widget_path}/Info.plist")"
widget_architectures="$(xcrun lipo -archs "${widget_path}/${widget_executable}")"
if [[ " ${widget_architectures} " != *" arm64 "* ]]; then
    echo "Built downloads widget does not contain arm64." >&2
    exit 1
fi

ipa_live_status stage --name zip
mkdir -p "${output_directory}"
output_directory="$(cd "${output_directory}" && pwd -P)"
package_root="$(mktemp -d "${TMPDIR:-/tmp}/nuvio-ios-ipa.XXXXXX")"
trap ipa_build_cleanup EXIT
mkdir -p "${package_root}/Payload"
ditto "${app_path}" "${package_root}/Payload/${app_bundle_name}"

ipa_path="${output_directory}/nuvio-${version}-enhanced-${configuration_slug}.ipa"
temporary_ipa="${package_root}/nuvio-${version}-enhanced-${configuration_slug}.ipa"
(
    cd "${package_root}"
    /usr/bin/zip -qry "${temporary_ipa}" Payload
)
unzip -tq "${temporary_ipa}"
mv "${temporary_ipa}" "${ipa_path}"
ipa_live_status_record_ipa "${ipa_path}" "${version}"

echo "Created ${ipa_path}"
