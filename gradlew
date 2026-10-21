#!/usr/bin/env bash
# Source-only bootstrap; no absent Gradle wrapper JAR.
set -euo pipefail
cd "$(dirname "$0")"
version=8.13
base="${GRADLE_USER_HOME:-$HOME/.gradle}/localllm-bootstrap";directory="$base/gradle-$version"
if [[ ! -x "$directory/bin/gradle" ]];then
    mkdir -p "$base";temporary=$(mktemp -d "$base/download.XXXXXXXX");trap 'rm -rf "$temporary"' EXIT
    curl --fail --location --retry 3 "https://services.gradle.org/distributions/gradle-$version-bin.zip" -o "$temporary/gradle.zip"
    curl --fail --location --retry 3 "https://services.gradle.org/distributions/gradle-$version-bin.zip.sha256" -o "$temporary/sha256"
    python3 - "$temporary/gradle.zip" "$temporary/sha256" <<'PY'
from pathlib import Path
import hashlib,sys
h=hashlib.sha256()
with open(sys.argv[1],'rb') as stream:
    for chunk in iter(lambda:stream.read(1024*1024),b''):h.update(chunk)
if h.hexdigest()!=Path(sys.argv[2]).read_text().strip():raise SystemExit('Gradle checksum mismatch')
PY
    unzip -q "$temporary/gradle.zip" -d "$temporary/unpacked"
    mv "$temporary/unpacked/gradle-$version" "$directory"
fi
exec "$directory/bin/gradle" "$@"
