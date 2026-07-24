from __future__ import annotations

import json
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


ANDROID = "{http://schemas.android.com/apk/res/android}"


def attr(node: ET.Element, name: str):
    return node.get(ANDROID + name)


def inventory(name: str, decoded: Path, jadx: Path) -> dict:
    root = ET.parse(decoded / "AndroidManifest.xml").getroot()
    app = root.find("application")
    assert app is not None
    components = {}
    for kind in ("activity", "activity-alias", "service", "receiver", "provider"):
        rows = []
        for node in app.findall(kind):
            rows.append(
                {
                    "name": attr(node, "name"),
                    "exported": attr(node, "exported"),
                    "process": attr(node, "process"),
                    "permission": attr(node, "permission"),
                    "theme": attr(node, "theme"),
                    "targetActivity": attr(node, "targetActivity"),
                }
            )
        components[kind] = rows

    layouts = sorted(
        str(p.relative_to(decoded / "res"))
        for p in (decoded / "res").glob("layout*/*.xml")
    )
    java_files = list((jadx / "sources").rglob("*.java"))
    return {
        "app": name,
        "package": root.get("package"),
        "versionCode": attr(root, "versionCode"),
        "versionName": attr(root, "versionName"),
        "application": attr(app, "name"),
        "components": components,
        "counts": {
            "activities": len(components["activity"]),
            "activityAliases": len(components["activity-alias"]),
            "services": len(components["service"]),
            "receivers": len(components["receiver"]),
            "providers": len(components["provider"]),
            "layouts": len(layouts),
            "javaFiles": len(java_files),
        },
        "layouts": layouts,
    }


def main() -> None:
    workspace = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
    output = workspace / "artifacts" / "metadata" / "apk_inventory.json"
    payload = {
        "zero": inventory(
            "ZeroMusic",
            workspace / "reverse" / "zero-apktool",
            workspace / "reverse" / "zero-jadx",
        ),
        "netease": inventory(
            "NeteaseWatch",
            workspace / "reverse" / "netease-apktool",
            workspace / "reverse" / "netease-jadx",
        ),
    }
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    for key, value in payload.items():
        print(key, json.dumps(value["counts"], ensure_ascii=False))
    print(output)


if __name__ == "__main__":
    main()
