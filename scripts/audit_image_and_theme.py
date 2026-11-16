#!/usr/bin/env python3
"""
Garry Tan GStack Phase 1 — Automated Design Token, WCAG 2.1 Contrast & Hardcoded Color Audit
(`scripts/audit_image_and_theme.py`)
"""

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def srgb_channel_to_linear(c: int) -> float:
    v = c / 255.0
    return v / 12.92 if v <= 0.03928 else ((v + 0.055) / 1.055) ** 2.4


def relative_luminance(hex_color: str) -> float:
    h = hex_color.lstrip("#")
    if len(h) == 8:
        h = h[2:]
    r, g, b = int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16)
    return (
        0.2126 * srgb_channel_to_linear(r)
        + 0.7152 * srgb_channel_to_linear(g)
        + 0.0722 * srgb_channel_to_linear(b)
    )


def contrast_ratio(hex1: str, hex2: str) -> float:
    l1 = relative_luminance(hex1)
    l2 = relative_luminance(hex2)
    bright, dark = max(l1, l2), min(l1, l2)
    return (bright + 0.05) / (dark + 0.05)


def main() -> int:
    print("=" * 92)
    print("GARRY TAN GSTACK PHASE 1 — DESIGN TOKEN, WCAG 2.1 CONTRAST & TOKEN ESCAPE AUDIT")
    print("=" * 92)

    pairs = [
        ("Primary Text (#F2DFC4) vs Obsidian Surface (#0B0E14)", "#F2DFC4", "#0B0E14", 4.5),
        ("Vibrant Gold Accent (#D49B54) vs Obsidian Surface (#0B0E14)", "#D49B54", "#0B0E14", 4.5),
        ("CTA Dark Text (#0B0E14) vs Vibrant Gold Button (#D49B54)", "#0B0E14", "#D49B54", 4.5),
    ]

    all_ok = True
    for label, fg, bg, min_ratio in pairs:
        ratio = contrast_ratio(fg, bg)
        status = "PASS" if ratio >= min_ratio else "FAIL"
        if ratio < min_ratio:
            all_ok = False
        print(f"[{status}] {label:<58} | Contrast = {ratio:>5.2f}:1 (Min {min_ratio}:1)")

    # Scan UI screens (excluding ui/theme/StarshineCockpitTheme.kt) for hardcoded Color(0x...) escapes
    ui_dir = ROOT / "app/src/main/java/com/galaxy/starshine8/wallpaper/ui"
    escapes = []
    for kt_file in sorted(ui_dir.rglob("*.kt")):
        if kt_file.name == "StarshineCockpitTheme.kt":
            continue
        for idx, line in enumerate(kt_file.read_text(encoding="utf-8").splitlines(), 1):
            if re.search(r"Color\(0x", line):
                escapes.append(f"{kt_file.relative_to(ROOT)}:{idx}: {line.strip()}")

    if escapes:
        all_ok = False
        for esc in escapes:
            print(f"[FAIL] Design Token Escape: {esc}")
    else:
        print("[PASS] Zero hardcoded Color(0x...) escapes in ui/workbench and ui/showcase.")

    print("=" * 92)
    return 0 if all_ok else 1


if __name__ == "__main__":
    sys.exit(main())
