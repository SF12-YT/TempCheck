#!/usr/bin/env sh
# Lightweight Gradle bootstrapper for this source project. It downloads the pinned
# Gradle distribution on first use; Android SDK installation is still required.
set -eu
GRADLE_VERSION="8.9"
GRADLE_USER_HOME="${GRADLE_USER_HOME:-$HOME/.gradle}"
DIST_ROOT="$GRADLE_USER_HOME/templearn-bootstrap"
GRADLE_BIN="$DIST_ROOT/gradle-$GRADLE_VERSION/bin/gradle"

if [ -x "$GRADLE_BIN" ]; then
  exec "$GRADLE_BIN" "$@"
fi
if command -v gradle >/dev/null 2>&1; then
  SYSTEM_GRADLE_VERSION="$(gradle --version 2>/dev/null | sed -n 's/^Gradle \([^ ]*\)$/\1/p' | head -n 1 || true)"
  if [ "$SYSTEM_GRADLE_VERSION" = "$GRADLE_VERSION" ]; then
    exec gradle "$@"
  fi
fi

mkdir -p "$DIST_ROOT"
ARCHIVE="$DIST_ROOT/gradle-$GRADLE_VERSION-bin.zip"
URL="https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip"
echo "Gradle $GRADLE_VERSION is not installed; downloading it from services.gradle.org..."
if command -v curl >/dev/null 2>&1; then
  curl -fL --retry 2 "$URL" -o "$ARCHIVE"
elif command -v wget >/dev/null 2>&1; then
  wget -O "$ARCHIVE" "$URL"
else
  echo "Please install Gradle $GRADLE_VERSION or install curl/wget and retry." >&2
  exit 1
fi
if command -v unzip >/dev/null 2>&1; then
  unzip -q -o "$ARCHIVE" -d "$DIST_ROOT"
else
  echo "Please install unzip and retry." >&2
  exit 1
fi
exec "$GRADLE_BIN" "$@"
