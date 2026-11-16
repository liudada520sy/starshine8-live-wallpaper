#!/usr/bin/env python3
"""
Matt Pocock Gate 3 Vertical Slice Scaffolding & Static Boundary Audit (`scaffold_vertical_slices.py`)
"""

import argparse
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

DIRECTORIES = [
    "docs",
    "scripts",
    "app/src/main/assets/shaders",
    "app/src/main/assets/models",
    "app/src/main/res/xml",
    "app/src/main/res/values",
    "app/src/main/java/com/galaxy/starshine8/wallpaper/core/contract",
    "app/src/main/java/com/galaxy/starshine8/wallpaper/core/render",
    "app/src/main/java/com/galaxy/starshine8/wallpaper/core/sensor",
    "app/src/main/java/com/galaxy/starshine8/wallpaper/core/pipeline",
    "app/src/main/java/com/galaxy/starshine8/wallpaper/core/storage",
    "app/src/main/java/com/galaxy/starshine8/wallpaper/host",
    "app/src/main/java/com/galaxy/starshine8/wallpaper/feature/transfer",
    "app/src/main/java/com/galaxy/starshine8/wallpaper/feature/phaseb",
    "app/src/main/java/com/galaxy/starshine8/wallpaper/ui/theme",
    "app/src/main/java/com/galaxy/starshine8/wallpaper/ui/workbench",
    "app/src/main/java/com/galaxy/starshine8/wallpaper/ui/showcase",
    "remote-aigc-server",
]


def check_boundaries() -> int:
    print("=" * 88)
    print("MATT POCOCK GATE 3 — VERTICAL SLICE BOUNDARY ISOLATION AUDIT")
    print("=" * 88)
    kt_root = ROOT / "app/src/main/java/com/galaxy/starshine8/wallpaper"
    violations = []

    for kt_file in sorted(kt_root.rglob("*.kt")):
        rel = kt_file.relative_to(kt_root).as_posix()
        content = kt_file.read_text(encoding="utf-8")
        imports = re.findall(r"^import\s+([\w\.]+)", content, flags=re.MULTILINE)

        if rel.startswith("core/"):
            for imp in imports:
                if any(seg in imp for seg in [".ui.", ".host.", ".feature."]):
                    violations.append(f"[Boundary Violation] {rel} illegally imports upper layer: {imp}")

        if rel.startswith("feature/transfer/"):
            for imp in imports:
                if ".feature.phaseb." in imp:
                    violations.append(f"[Cross-Slice Violation] {rel} illegally imports sibling slice: {imp}")
        if rel.startswith("feature/phaseb/"):
            for imp in imports:
                if ".feature.transfer." in imp:
                    violations.append(f"[Cross-Slice Violation] {rel} illegally imports sibling slice: {imp}")

    if violations:
        for v in violations:
            print(v)
        return 1

    print("[PASS] Zero cross-slice internal import violations detected across all Kotlin modules.")
    print("=" * 88)
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="Scaffold and audit vertical slice boundaries")
    parser.add_argument("--check-boundaries", action="store_true", help="Run static cross-slice import audit")
    args = parser.parse_args()

    for d in DIRECTORIES:
        (ROOT / d).mkdir(parents=True, exist_ok=True)
    print(f"[OK] Scaffolded {len(DIRECTORIES)} vertical slice directories under {ROOT}")

    if args.check_boundaries:
        return check_boundaries()
    return 0


if __name__ == "__main__":
    sys.exit(main())
