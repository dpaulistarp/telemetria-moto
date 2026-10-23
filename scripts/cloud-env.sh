#!/usr/bin/env bash
# Source this file only in the Codex cloud workspace. Never change HOME.
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-/workspace/.cache/gradle}"
export ANDROID_USER_HOME="${ANDROID_USER_HOME:-/workspace/.cache/android}"
export ANDROID_HOME="${ANDROID_HOME:-/workspace/toolchains/android-sdk}"
if [[ -x /workspace/toolchains/jdk-21.0.13+9/bin/javac ]]; then
    export JAVA_HOME="${JAVA_HOME:-/workspace/toolchains/jdk-21.0.13+9}"
    export PATH="$JAVA_HOME/bin:$PATH"
fi
mkdir -p "$GRADLE_USER_HOME" "$ANDROID_USER_HOME"
# Java/Gradle do not automatically consume HTTPS_PROXY. Preserve other user properties.
python3 - <<'PYTHON'
import os
from pathlib import Path
from urllib.parse import urlsplit
proxy = urlsplit(os.environ.get('HTTPS_PROXY', ''))
if proxy.hostname:
    path = Path(os.environ['GRADLE_USER_HOME']) / 'gradle.properties'
    properties = path.read_text().splitlines() if path.exists() else []
    updates = {}
    if Path('/etc/ssl/certs/java/cacerts').is_file():
        updates['systemProp.javax.net.ssl.trustStore'] = '/etc/ssl/certs/java/cacerts'
    for scheme in ('http', 'https'):
        updates[f'systemProp.{scheme}.proxyHost'] = proxy.hostname
        updates[f'systemProp.{scheme}.proxyPort'] = str(proxy.port or 80)
    bypass = ['localhost', '127.*']
    bypass += [('*' + v if v.startswith('.') else v) for v in os.environ.get('NO_PROXY', '').split(',') if v]
    updates['systemProp.http.nonProxyHosts'] = '|'.join(bypass)
    retained = [line for line in properties if line.split('=', 1)[0] not in updates]
    path.write_text('\n'.join(retained + [f'{k}={v}' for k, v in updates.items()]) + '\n')
PYTHON
