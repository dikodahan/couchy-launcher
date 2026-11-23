#!/usr/bin/env python3
"""Create Couchy's path catalog from the pinned Iconify Material Symbols tarball."""
import argparse
import hashlib
import json
import re
import tarfile
from pathlib import Path
from xml.etree import ElementTree

SOURCE_SHA256 = "d001c3c1a39dcaeb99066327d1357e7e03bd1ea5ec393c1d4f525a0a32eacb05"
FEATURED = (
    "movie", "live-tv", "smart-display", "play-circle", "music-note", "headphones",
    "sports-esports", "photo-camera", "video-library", "radio", "podcasts", "school",
    "travel-explore", "favorite", "rocket-launch", "nightlight", "sunny", "home",
    "public", "terminal", "folder", "settings", "cloud-download", "security",
)

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("tarball", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    if hashlib.sha256(args.tarball.read_bytes()).hexdigest() != SOURCE_SHA256:
        parser.error("Expected the @iconify-json/material-symbols 1.2.95 archive")
    with tarfile.open(args.tarball) as archive:
        data = json.load(archive.extractfile("package/icons.json"))

    # Keep standard and outline artwork so search results stay varied.
    # Rounded/sharp versions repeat the same concepts with different corners.
    icons = {name: icon for name, icon in data["icons"].items()
             if not name.endswith(("-rounded", "-sharp"))}
    aliases = {name: [] for name in icons}
    for name, alias in data["aliases"].items():
        if set(alias) == {"parent"} and alias["parent"] in icons and not name.endswith(("-rounded", "-sharp")):
            aliases[alias["parent"]].append(name)

    assert set(FEATURED) <= icons.keys(), sorted(set(FEATURED) - icons.keys())
    order = FEATURED + tuple(sorted(icons.keys() - set(FEATURED)))
    with args.output.open("w", encoding="utf-8", newline="\n") as output:
        for name in order:
            icon = icons[name]
            svg = ElementTree.fromstring("<svg>" + icon["body"] + "</svg>")
            assert len(svg) == 1 and svg[0].tag == "path" and set(svg[0].attrib) == {"d", "fill"}
            assert svg[0].attrib["fill"] == "currentColor"
            path = svg[0].attrib["d"]
            assert re.fullmatch(r"[MmLlHhVvCcSsQqTtAaZz0-9eE,.+\- ]+", path)
            width = int(icon.get("width", data["width"]))
            height = int(icon.get("height", data["height"]))
            assert -1 < icon.get("left", 0) == icon.get("top", 0) == 0
            assert not any(icon.get(key) for key in ("hFlip", "vFlip", "rotate"))
            print(name, " ".join(sorted(aliases[name])), width, height, path, sep="\t", file=output)
    print(f"Prepared {len(order)} icons")

if __name__ == "__main__":
    main()
