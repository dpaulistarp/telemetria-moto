#!/usr/bin/env python3
"""Install the verified Android CLI in ANDROID_HOME, without relying on runner images."""
import hashlib
import os
from pathlib import Path
import shutil
import tempfile
import urllib.request
import zipfile


def main():
    sdk = Path(os.environ["ANDROID_HOME"])
    destination = sdk / "cmdline-tools" / "19.0"
    if (destination / "bin" / "sdkmanager").is_file():
        return
    if destination.exists():
        raise RuntimeError("Incomplete Android tools installation; inspect before replacing")
    with tempfile.TemporaryDirectory() as directory:
        archive_path = Path(directory) / "tools.zip"
        url = "https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip"
        with urllib.request.urlopen(url, timeout=90) as source, archive_path.open("wb") as target:
            shutil.copyfileobj(source, target)
        # Published checksum in Google's repository2-1.xml for cmdline-tools;19.0.
        with archive_path.open("rb") as artifact:
            checksum = hashlib.file_digest(artifact, "sha1").hexdigest()
        if checksum != "5fdcc763663eefb86a5b8879697aa6088b041e70":
            raise RuntimeError("Android command-line tools checksum mismatch")
        unpacked = Path(directory) / "unpacked"
        with zipfile.ZipFile(archive_path) as archive:
            archive.extractall(unpacked)
            for entry in archive.infolist():
                mode = entry.external_attr >> 16
                if mode:
                    (unpacked / entry.filename).chmod(mode)
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.move(str(unpacked / "cmdline-tools"), str(destination))


if __name__ == "__main__":
    main()
