#!/usr/bin/env bash
set -euo pipefail
repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$repo_dir/scripts/cloud-env.sh"
if [[ ! -x /workspace/toolchains/jdk-21.0.13+9/bin/javac ]]; then
    python3 - <<'PYTHON'
import hashlib, pathlib, tarfile, tempfile, urllib.request
base = pathlib.Path('/workspace/toolchains')
base.mkdir(parents=True, exist_ok=True)
url = 'https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.13%2B9/OpenJDK21U-jdk_x64_linux_hotspot_21.0.13_9.tar.gz'
with tempfile.TemporaryDirectory() as directory:
    path = pathlib.Path(directory) / 'jdk.tar.gz'
    with urllib.request.urlopen(url, timeout=90) as src, path.open('wb') as dst:
        while chunk := src.read(1024 * 1024): dst.write(chunk)
    expected = 'd7f5d5cf865a2902b70ff549b97586a7d3f234e016f041ab447ea740c7d66d1c'
    if hashlib.file_digest(path.open('rb'), 'sha256').hexdigest() != expected:
        raise RuntimeError('Temurin JDK checksum mismatch')
    with tarfile.open(path) as archive: archive.extractall(base, filter='data')
PYTHON
fi
source "$repo_dir/scripts/cloud-env.sh"
"$JAVA_HOME/bin/javac" -version
if [[ ! -x "$ANDROID_HOME/cmdline-tools/19.0/bin/sdkmanager" ]]; then
    python3 - <<'PYTHON'
import hashlib, os, pathlib, tempfile, urllib.request, zipfile
sdk = pathlib.Path(os.environ['ANDROID_HOME'])
with tempfile.TemporaryDirectory() as directory:
    path = pathlib.Path(directory) / 'tools.zip'
    url = 'https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip'
    with urllib.request.urlopen(url, timeout=90) as src, path.open('wb') as dst:
        while chunk := src.read(1024 * 1024): dst.write(chunk)
    # Checksum published in Google's repository2-1.xml, package cmdline-tools;19.0.
    digest = hashlib.file_digest(path.open('rb'), 'sha1').hexdigest()
    if digest != '5fdcc763663eefb86a5b8879697aa6088b041e70':
        raise RuntimeError('Android command-line tools checksum mismatch')
    with zipfile.ZipFile(path) as archive:
        target = pathlib.Path(directory) / 'unpacked'
        archive.extractall(target)
        for info in archive.infolist():
            mode = info.external_attr >> 16
            if mode: (target / info.filename).chmod(mode)
    destination = sdk / 'cmdline-tools' / '19.0'
    destination.parent.mkdir(parents=True, exist_ok=True)
    if destination.exists(): raise RuntimeError('Existing incomplete SDK tools; inspect before replacing')
    import shutil
    shutil.move(str(target / 'cmdline-tools'), str(destination))
PYTHON
fi
# Use the platform proxy and its trusted system certificate store.
mapfile -t sdk_proxy_args < <(python3 - <<'PYTHON'
import os
from urllib.parse import urlsplit
proxy = urlsplit(os.environ.get('HTTPS_PROXY', ''))
if proxy.hostname:
    print('--proxy=http')
    print('--proxy_host=' + proxy.hostname)
    print('--proxy_port=' + str(proxy.port or 80))
PYTHON
)
sdk_java_opts="${JAVA_OPTS:-}"
if [[ -f /etc/ssl/certs/java/cacerts && "$sdk_java_opts" != *javax.net.ssl.trustStore* ]]; then
    sdk_java_opts="$sdk_java_opts -Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts"
fi
# Accept the Android SDK license for the packages needed to build this project.
set +o pipefail
yes 2>/dev/null | JAVA_OPTS="$sdk_java_opts" "$ANDROID_HOME/cmdline-tools/19.0/bin/sdkmanager" "${sdk_proxy_args[@]}" --sdk_root="$ANDROID_HOME" \
    'platforms;android-36' 'build-tools;35.0.0' 'platform-tools'
sdk_status=${PIPESTATUS[1]}
set -o pipefail
if [[ "$sdk_status" -ne 0 ]]; then exit "$sdk_status"; fi
cd "$repo_dir"
./gradlew assembleDebug testDebugUnitTest :core:telemetry:test \
    :app:assembleDebugAndroidTest :core:database:assembleDebugAndroidTest :core:tracking:assembleDebugAndroidTest lintDebug --console=plain
