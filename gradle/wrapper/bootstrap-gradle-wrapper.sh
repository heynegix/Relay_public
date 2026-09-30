#!/bin/sh

# SPDX-License-Identifier: Apache-2.0

set -eu

die() {
    echo "gradle wrapper bootstrap: $*" >&2
    exit 1
}

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
PROPERTIES_FILE="$SCRIPT_DIR/gradle-wrapper.properties"

[ -f "$PROPERTIES_FILE" ] || die "missing $PROPERTIES_FILE"

read_property() {
    property_name=$1
    sed -n "s/^${property_name}=//p" "$PROPERTIES_FILE" | head -n 1
}

WRAPPER_URL=$(read_property wrapperJarUrl)
WRAPPER_SHA256=$(read_property wrapperJarSha256)

case "$WRAPPER_URL" in
    https://raw.githubusercontent.com/gradle/gradle/*/gradle/wrapper/gradle-wrapper.jar) ;;
    *) die "wrapperJarUrl must use an immutable Gradle GitHub raw URL" ;;
esac

commit_ref=${WRAPPER_URL#https://raw.githubusercontent.com/gradle/gradle/}
commit_ref=${commit_ref%/gradle/wrapper/gradle-wrapper.jar}
printf '%s\n' "$commit_ref" | grep -Eq '^[0-9a-f]{40}$' || die "wrapperJarUrl must pin a 40-character commit"
printf '%s\n' "$WRAPPER_SHA256" | grep -Eq '^[0-9a-f]{64}$' || die "wrapperJarSha256 must be a SHA-256 digest"

sha256_file() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | awk '{print $1}'
    elif command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$1" | awk '{print $1}'
    else
        die "sha256sum or shasum is required"
    fi
}

if [ -n "${GRADLE_USER_HOME:-}" ]; then
    gradle_user_home=$GRADLE_USER_HOME
else
    user_home=$(CDPATH= cd -- "${HOME:-.}" && pwd)
    gradle_user_home="$user_home/.gradle"
fi

jar_dir="$gradle_user_home/wrapper/jars"
jar_path="$jar_dir/gradle-wrapper-$WRAPPER_SHA256.jar"
properties_path="$jar_dir/gradle-wrapper-$WRAPPER_SHA256.properties"

download_wrapper_jar() {
    mkdir -p "$jar_dir"
    temporary_path="$jar_path.download.$$"
    trap 'rm -f "$temporary_path"' EXIT HUP INT TERM

    if command -v curl >/dev/null 2>&1; then
        curl --fail --location --silent --show-error --retry 3 --connect-timeout 10 \
            --max-time 120 --output "$temporary_path" "$WRAPPER_URL"
    elif command -v wget >/dev/null 2>&1; then
        wget --quiet --output-document="$temporary_path" "$WRAPPER_URL"
    else
        die "curl or wget is required to bootstrap Gradle"
    fi

    actual_sha256=$(sha256_file "$temporary_path")
    [ "$actual_sha256" = "$WRAPPER_SHA256" ] || die "wrapper JAR SHA-256 verification failed"
    mv "$temporary_path" "$jar_path"
    trap - EXIT HUP INT TERM
}

if [ -f "$jar_path" ] && [ "$(sha256_file "$jar_path")" != "$WRAPPER_SHA256" ]; then
    rm -f "$jar_path"
fi
[ -f "$jar_path" ] || download_wrapper_jar
cp "$PROPERTIES_FILE" "$properties_path"

if [ -n "${JAVA_HOME:-}" ]; then
    javacmd="$JAVA_HOME/bin/java"
else
    javacmd=$(command -v java || true)
fi
[ -n "$javacmd" ] && [ -x "$javacmd" ] || die "JAVA_HOME is invalid and no java command was found"

default_jvm_opts='-Xmx64m -Xms64m'
set -- "-Dorg.gradle.appname=gradlew" -jar "$jar_path" "$@"
eval "set -- $(
    printf '%s\n' "$default_jvm_opts ${JAVA_OPTS:-} ${GRADLE_OPTS:-}" |
        xargs -n1 |
        sed 's~[^-[:alnum:]+,./:=@_]~\\&~g' |
        tr '\n' ' '
)" '"$@"'

exec "$javacmd" "$@"
