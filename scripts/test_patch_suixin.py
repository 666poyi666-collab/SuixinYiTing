"""Regression tests for the APK patch pipeline.

The patch script rewrites the decompiled master in place across smali, brand
strings, watch UI resources, animations and icons. Because it runs on every
build, the two failure modes that matter are: an anchor drifting so a hook
silently stops applying, and a step double-applying when run twice. These tests
run the patch against the live working tree and assert idempotency plus the
end-state invariants the build depends on.

Run: python scripts/test_patch_suixin.py
"""

from __future__ import annotations

import subprocess
import sys
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
TREE = REPO / "work" / "suixin-apktool"
PATCH = REPO / "scripts" / "patch_suixin.py"


def run_patch() -> subprocess.CompletedProcess:
    return subprocess.run(
        [sys.executable, "-X", "utf8", str(PATCH), str(TREE)],
        capture_output=True, text=True, encoding="utf-8",
    )


def snapshot() -> dict[str, str]:
    """Hash-relevant slice of the tree the patch touches."""
    files = [
        TREE / "AndroidManifest.xml",
        TREE / "apktool.yml",
        TREE / "res" / "values" / "styles.xml",
        TREE / "res" / "values" / "ids.xml",
        TREE / "res" / "layout" / "activity_setting_about.xml",
        TREE / "res" / "layout" / "fragment_activity_main_play.xml",
    ]
    files += sorted((TREE / "res" / "drawable-anydpi-v24").glob("ic_default_*.xml"))
    return {f.relative_to(TREE).as_posix(): f.read_text(encoding="utf-8")
            for f in files if f.exists()}


@unittest.skipUnless(TREE.exists(), "decompiled tree not present")
class PatchPipelineTest(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        first = run_patch()
        assert first.returncode == 0, f"first patch failed:\n{first.stderr}"
        cls.after_first = snapshot()
        second = run_patch()
        assert second.returncode == 0, f"second patch failed:\n{second.stderr}"
        cls.after_second = snapshot()

    def test_patch_is_idempotent(self):
        # Re-running on an already-patched tree must change nothing.
        self.assertEqual(self.after_first, self.after_second,
                         "patch is not idempotent")

    def test_version_bumped(self):
        yml = (TREE / "apktool.yml").read_text(encoding="utf-8")
        self.assertIn("versionCode: 10502", yml)
        self.assertIn("versionName: 1.5.2", yml)

    def test_no_author_traces(self):
        for rel, text in self.after_first.items():
            self.assertNotIn("2023066011", text, f"ICP number left in {rel}")
            self.assertNotIn("Developed by 随心一听社区", text)
            self.assertNotIn("Sky233", text)

    def test_transition_animation_wired(self):
        styles = self.after_first["res/values/styles.xml"]
        self.assertIn("SuixinActivityAnimation", styles)
        self.assertIn("android:windowAnimationStyle", styles)
        for anim in ("suixin_activity_open_enter", "suixin_activity_close_exit",
                     "suixin_window_enter", "suixin_window_exit"):
            self.assertTrue((TREE / "res" / "anim" / f"{anim}.xml").exists(),
                            f"missing anim {anim}")

    def test_disc_icons_have_gradient_glyph_icons_do_not(self):
        # A disc icon is one the refresh gradiented; a glyph icon (e.g. the
        # ic_default_menu_* bars) keeps a flat #6186fc glyph and no gradient.
        # The real disc is a circle ("M0,80C0,..."); the transparent square
        # backgrounds ("M0,0L160,...") are not discs, so classify by outcome.
        gradiented = glyph = 0
        for rel, text in self.after_first.items():
            name = Path(rel).name
            if not name.startswith("ic_default_") or name.endswith("_foreground.xml"):
                continue
            if "aapt:attr" in text:
                gradiented += 1
                self.assertNotIn('android:fillColor="#6186fc"', text,
                                 f"{name} kept a flat disc alongside the gradient")
            elif name.startswith("ic_default_menu_") and "#6186fc" in text:
                glyph += 1
        self.assertGreaterEqual(gradiented, 15, "expected the disc icon set gradiented")
        self.assertGreaterEqual(glyph, 3, "expected glyph-only menu icons untouched")

    def test_watch_ui_ids_and_cover_present(self):
        ids = self.after_first["res/values/ids.xml"]
        for wid in ("suixin_lyric_scroll", "suixin_network_cover", "suixin_quality"):
            self.assertIn(f'name="{wid}"', ids)


if __name__ == "__main__":
    unittest.main(verbosity=2)
