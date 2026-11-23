#!/usr/bin/env python3
"""Query OSV for the exact Maven versions from dependencyInventory (standard library only)."""
import datetime
import json
import pathlib
import sys
import urllib.request


def request(url, body=None):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(url, data=data, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=60) as response:
        return json.load(response)


def main():
    source = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else "build/reports/dependencies/inventory.json")
    packages = json.loads(source.read_text())
    results = []
    advisories = {}
    for start in range(0, len(packages), 100):
        batch = packages[start:start + 100]
        response = request("https://api.osv.dev/v1/querybatch", {
            "queries": [{"package": {"name": row["name"], "ecosystem": "Maven"},
                         "version": row["version"]} for row in batch]
        })
        for row, result in zip(batch, response["results"]):
            ids = []
            for vulnerability in result.get("vulns", []):
                identifier = vulnerability["id"]
                if identifier not in advisories:
                    advisories[identifier] = request("https://api.osv.dev/v1/vulns/" + identifier)
                if not advisories[identifier].get("withdrawn"):
                    ids.append(identifier)
            results.append({**row, "advisories": ids})
    report = {"checked_at": datetime.datetime.now(datetime.timezone.utc).isoformat(),
              "source": "https://osv.dev/", "coverage": "Resolved Maven versions; native code needs separate assessment",
              "packages": results, "advisories": advisories}
    target = source.with_name("osv-audit.json")
    target.write_text(json.dumps(report, indent=2) + "\n")
    flagged = [row for row in results if row["advisories"]]
    print(f"Checked {len(results)} resolved modules; {len(flagged)} have active OSV advisories. Report: {target}")
    for row in flagged:
        print(row["name"], row["version"], ", ".join(row["advisories"]))
    return bool(flagged)


if __name__ == "__main__":
    sys.exit(main())
