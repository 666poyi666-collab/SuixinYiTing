"""Mirror the publishable subset of the workspace into the GitHub repository.

The working directory also holds the decompiled master APK, reverse-engineering
scratch space and multi-hundred-megabyte tooling, none of which belongs in the
public repository. This script copies only the sources, resources, scripts and
documents the project actually owns, so publishing is repeatable instead of a
manual copy each release.

Usage:
    python scripts/sync_publish.py [publish-dir]
"""

from __future__ import annotations

import shutil
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_TARGET = REPO_ROOT / "publish" / "SuixinYiTing"

# (source, destination) pairs relative to the workspace root.
FILES = (
    "README.md",
    "DEVELOPMENT_LOG.md",
    "BUGFIX_LOG.md",
)

# (directory, glob) pairs mirrored recursively.
TREES = (
    ("docs", "**/*.md"),
    ("docs", "**/*.png"),
    ("analysis", "**/*.md"),
    ("analysis", "**/*.csv"),
    ("network-bridge/src", "**/*.java"),
    ("res-overlay", "**/*.xml"),
    ("scripts", "*.py"),
    ("scripts", "*.ps1"),
)

# Never mirrored: build caches, and the machine-local file that holds the
# signing keystore password. The publish repo git-ignores the latter too, but it
# must not even land on disk there.
SKIP_NAMES = {"__pycache__", "local-build-env.ps1"}


def mirror(target: Path) -> tuple[int, int]:
    copied = 0
    removed = 0

    wanted: set[Path] = set()
    for name in FILES:
        source = REPO_ROOT / name
        if not source.exists():
            continue
        wanted.add(Path(name))
        destination = target / name
        destination.parent.mkdir(parents=True, exist_ok=True)
        if not destination.exists() or destination.read_bytes() != source.read_bytes():
            shutil.copy2(source, destination)
            copied += 1

    for directory, pattern in TREES:
        base = REPO_ROOT / directory
        if not base.exists():
            continue
        for source in sorted(base.glob(pattern)):
            if not source.is_file():
                continue
            if any(part in SKIP_NAMES for part in source.relative_to(REPO_ROOT).parts):
                continue
            relative = source.relative_to(REPO_ROOT)
            wanted.add(relative)
            destination = target / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            if (
                not destination.exists()
                or destination.read_bytes() != source.read_bytes()
            ):
                shutil.copy2(source, destination)
                copied += 1

    # Drop files that the workspace no longer publishes, but never touch paths
    # the repository owns on its own (release notes, git metadata).
    owned_roots = {Path(directory).parts[0] for directory, _ in TREES}
    for existing in sorted(target.rglob("*")):
        if not existing.is_file():
            continue
        relative = existing.relative_to(target)
        if relative.parts[0] == ".git":
            continue
        if relative.parts[0] not in owned_roots:
            continue
        if relative not in wanted:
            existing.unlink()
            removed += 1

    return copied, removed


def main() -> None:
    target = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else DEFAULT_TARGET
    if not (target / ".git").exists():
        raise SystemExit(f"not a git checkout: {target}")
    copied, removed = mirror(target)
    print(f"synced {target}")
    print(f"copied: {copied}")
    print(f"removed: {removed}")


if __name__ == "__main__":
    main()
