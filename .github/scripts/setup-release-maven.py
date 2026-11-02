#!/usr/bin/env python3
"""Install the pinned Maven release toolchain on a GitHub-hosted runner."""
import hashlib
import io
import os
from pathlib import Path
import tempfile
import time
from urllib.request import urlopen
from zipfile import ZipFile

VERSION = "3.8.6"
SHA512 = (
    "f92dbd90060c5fd422349f844ea904a0918c9c9392f3277543ce2bfb0aab941950"
    "bb4174d9b6e2ea84cd48d2940111b83ffcc2e3acf5a5b2004277105fd22be9"
)
URL = f"https://archive.apache.org/dist/maven/maven-3/{VERSION}/binaries/apache-maven-{VERSION}-bin.zip"

for attempt in range(3):
    try:
        with urlopen(URL, timeout=120) as response:
            archive = response.read()
        break
    except OSError:
        if attempt == 2:
            raise
        time.sleep(2 ** attempt)
if hashlib.sha512(archive).hexdigest() != SHA512:
    raise SystemExit("Apache Maven archive checksum mismatch")

install_dir = Path(tempfile.mkdtemp(prefix="coffee-gb-maven-", dir=os.environ["RUNNER_TEMP"]))
with ZipFile(io.BytesIO(archive)) as bundle:
    bundle.extractall(install_dir)
bin_dir = install_dir / f"apache-maven-{VERSION}" / "bin"
if os.name != "nt":
    (bin_dir / "mvn").chmod(0o755)
with open(os.environ["GITHUB_PATH"], "a", encoding="utf-8") as path_file:
    print(bin_dir, file=path_file)
print(f"Installed verified Apache Maven {VERSION} at {bin_dir}")
