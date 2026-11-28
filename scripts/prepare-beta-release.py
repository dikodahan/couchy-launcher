#!/usr/bin/env python3
"""Set the next Couchy beta version after fetching origin branches and tags."""
import re
import subprocess
from pathlib import Path

GRADLE = Path("app/build.gradle.kts")
VERSION = re.compile(r'^(\s*versionName = ")([^"]+)(")$', re.MULTILINE)
CODE = re.compile(r"^(\s*versionCode = )(\d+)$", re.MULTILINE)
RELEASE_TAG = re.compile(r"v\d+\.\d+\.\d+(?:-beta\.[1-9]\d*)?")
STABLE = re.compile(r"(\d+)\.(\d+)\.(\d+)")


def git(*args):
    return subprocess.check_output(("git", *args), text=True).strip()


def version_code(source):
    return int(CODE.search(source)[2])


def main():
    if git("branch", "--show-current") != "Development":
        raise SystemExit("Run this on the Development branch.")
    stable_source = git("show", f"origin/main:{GRADLE}")
    stable = STABLE.fullmatch(VERSION.search(stable_source)[2])
    if stable is None:
        raise SystemExit("origin/main must contain a stable version.")
    major, minor, patch = map(int, stable.groups())
    beta_base = f"{major}.{minor}.{patch + 1}-beta."
    tags = [tag for tag in git("tag", "--list").splitlines() if RELEASE_TAG.fullmatch(tag)]
    beta_number = max((int(tag.split(".")[-1]) for tag in tags if tag.startswith("v" + beta_base)), default=0) + 1
    refs = ["origin/main", "origin/Development", *tags]
    code = max(version_code(git("show", f"{ref}:{GRADLE}")) for ref in refs) + 1
    name = f"{beta_base}{beta_number}"
    source = GRADLE.read_text()
    source = VERSION.sub(lambda match: f"{match[1]}{name}{match[3]}", source, count=1)
    source = CODE.sub(lambda match: f"{match[1]}{code}", source, count=1)
    GRADLE.write_text(source)
    print(f"{name} (versionCode {code})")


if __name__ == "__main__":
    main()
