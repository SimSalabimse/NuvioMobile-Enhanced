#!/usr/bin/env bash
# Checks the iOS Swift export allowlist without a Mac.
#
# Runs the Kotlin/Native ObjC export (same compiler version as the project) over composeApp with
# composeApp/build/generated/swift-export/entry-points.txt, and fails when:
#   - the framework link would assert (for example `bridgePropertyType` "Assertion failed"),
#   - a protocol Swift implements requires equals/hashCode/toString or loses a method,
#   - a Kotlin type Swift names loses a member.
# Library types are not resolved on this path, so a Mac build is still the final proof.
#
# Usage: ./scripts/check-ios-objc-export.sh
# Needs network the first time (downloads kotlin-native-compiler-embeddable, about 85 MB).
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repository_root"

kotlin_version="$(sed -n 's/^kotlin = "\(.*\)"/\1/p' gradle/libs.versions.toml)"
work_dir="${NUVIO_OBJC_EXPORT_CHECK_DIR:-$repository_root/build/objc-export-check}"
maven="https://repo1.maven.org/maven2/org/jetbrains/kotlin"
compiler_jar="$work_dir/kotlin-native-compiler-embeddable-$kotlin_version.jar"
stdlib_jar="$work_dir/kotlin-stdlib-$kotlin_version.jar"
mkdir -p "$work_dir"

if [[ ! -f "$compiler_jar" ]]; then
    curl -sfL -o "$compiler_jar.part" "$maven/kotlin-native-compiler-embeddable/$kotlin_version/kotlin-native-compiler-embeddable-$kotlin_version.jar"
    mv "$compiler_jar.part" "$compiler_jar"
fi
if [[ ! -f "$stdlib_jar" ]]; then
    curl -sfL -o "$stdlib_jar.part" "$maven/kotlin-stdlib/$kotlin_version/kotlin-stdlib-$kotlin_version.jar"
    mv "$stdlib_jar.part" "$stdlib_jar"
fi

./gradlew :composeApp:generateSwiftExportEntryPoints -q

classes_dir="$work_dir/classes"
source_file="scripts/objc-export-check/ObjCExportCheck.kt"
if [[ ! -f "$classes_dir/ObjCExportCheckKt.class" || "$source_file" -nt "$classes_dir/ObjCExportCheckKt.class" ]]; then
    rm -rf "$classes_dir"
    java -cp "$compiler_jar" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
        -no-stdlib -no-reflect -Xsuppress-version-warnings \
        -cp "$compiler_jar" -Xfriend-paths="$compiler_jar" \
        -d "$classes_dir" "$source_file"
fi

app="composeApp"
java -ea -Xmx8g -cp "$classes_dir:$compiler_jar" ObjCExportCheckKt \
    "$app/build/generated/swift-export/entry-points.txt" \
    "$work_dir/header" \
    iosApp \
    "$stdlib_jar" \
    "$app/src/commonMain/kotlin" \
    "$app/build/generated/runtime-config/kotlin" \
    "$app/src/iosMain/kotlin" \
    "$app/src/iosFull/kotlin" \
    "$app/src/fullCommonMain/kotlin"
