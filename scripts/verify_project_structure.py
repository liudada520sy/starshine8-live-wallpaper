#!/usr/bin/env python3
"""
Addy Osmani Anti-Rationalization, ADR-001 Contract & P0/P1/P2 OEM Verification Script
(`verify_project_structure.py`)
"""

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

REQUIRED_FILES = [
    ("ADR-001 Doc", "docs/ADR-001-architecture-decisions.md"),
    ("Gradle Build", "app/build.gradle.kts"),
    ("Gradle Wrapper Unix", "gradlew"),
    ("Gradle Wrapper Win", "gradlew.bat"),
    ("AndroidManifest", "app/src/main/AndroidManifest.xml"),
    ("ProGuard Rules", "app/proguard-rules.pro"),
    ("Wallpaper XML", "app/src/main/res/xml/starshine_wallpaper.xml"),
    ("Slice 1 Vertex Shader", "app/src/main/assets/shaders/starshine_master_scene.vert"),
    ("Slice 1 Fragment Shader", "app/src/main/assets/shaders/starshine_master_scene.frag"),
    ("ADR-001 Contracts", "app/src/main/java/com/galaxy/starshine8/wallpaper/core/contract/StarshineCoreContracts.kt"),
    ("Slice 1 GLES30 Renderer", "app/src/main/java/com/galaxy/starshine8/wallpaper/core/render/StarshineGlSceneRenderer.kt"),
    ("Slice 4 H265 VPU Decoder", "app/src/main/java/com/galaxy/starshine8/wallpaper/core/render/PhaseBHardwareVideoDecoder.kt"),
    ("Slice 1 3-Tier Audio Pulse", "app/src/main/java/com/galaxy/starshine8/wallpaper/core/sensor/CockpitAudioPulseProvider.kt"),
    ("Slice 1 Safety Monitor", "app/src/main/java/com/galaxy/starshine8/wallpaper/host/CockpitSafetyMonitor.kt"),
    ("Slice 1 WallpaperService", "app/src/main/java/com/galaxy/starshine8/wallpaper/host/StarshineWallpaperService.kt"),
    ("Slice 1 ShowcaseActivity", "app/src/main/java/com/galaxy/starshine8/wallpaper/host/ImmersiveShowcaseActivity.kt"),
    ("Slice 2 LAN HTTP & H5 Crop", "app/src/main/java/com/galaxy/starshine8/wallpaper/feature/transfer/LanFastTransferServer.kt"),
    ("Slice 2 OOM-Safe Normalizer", "app/src/main/java/com/galaxy/starshine8/wallpaper/core/pipeline/SafeImageNormalizer.kt"),
    ("Slice 2 Serial INT8 Depth", "app/src/main/java/com/galaxy/starshine8/wallpaper/core/pipeline/SerialDepthInferenceEngine.kt"),
    ("Slice 2 512MB Quota LRU", "app/src/main/java/com/galaxy/starshine8/wallpaper/core/storage/WallpaperQuotaManager.kt"),
    ("Slice 3 Obsidian Theme", "app/src/main/java/com/galaxy/starshine8/wallpaper/ui/theme/StarshineCockpitTheme.kt"),
    ("Slice 3 Workbench Screen", "app/src/main/java/com/galaxy/starshine8/wallpaper/ui/workbench/CockpitWorkbenchScreen.kt"),
    ("Slice 3 StandBy Overlay", "app/src/main/java/com/galaxy/starshine8/wallpaper/ui/showcase/StandByOverlayLayer.kt"),
    ("Slice 4 Phase B Adapter", "app/src/main/java/com/galaxy/starshine8/wallpaper/feature/phaseb/LanExternalComputeAdapter.kt"),
    ("Slice 4 FastAPI AIGC Server", "remote-aigc-server/main.py"),
    ("P2 Unit Test: Contracts", "app/src/test/java/com/galaxy/starshine8/wallpaper/core/contract/StarshineCoreContractsTest.kt"),
    ("P2 Unit Test: Image/LRU", "app/src/test/java/com/galaxy/starshine8/wallpaper/core/pipeline/ImageAndQuotaMathTest.kt"),
    ("P2 Unit Test: Audio/Bark", "app/src/test/java/com/galaxy/starshine8/wallpaper/core/sensor/AudioPulseMathTest.kt"),
]

FORBIDDEN_PATTERNS = [
    r"//\s*TODO",
    r"#\s*TODO",
    r"//\s*FIXME",
    r"省略实现",
    r"NotImplementedError",
]

ADR_CONTRACT_SIGNATURES = [
    "package com.galaxy.starshine8.wallpaper.core.contract",
    "sealed interface DomainResult",
    "sealed interface CockpitWorkbenchUiState",
    "interface DynamicSceneRenderer",
    "fun onSurfaceReady(width: Int, height: Int)",
    "fun bindWallpaperBundle(bundle: LoadedWallpaperBundle)",
    "fun updateStyleConfig(config: WallpaperStyleConfig)",
    "fun updateCockpitState(state: CockpitEnvironmentState)",
    "fun setRenderPaused(paused: Boolean)",
    "fun renderFrame(elapsedTimeSec: Float)",
    "fun release()",
    "data class CockpitEnvironmentState",
    "data class WallpaperStyleConfig",
    "enum class BaseVisualMode",
    "enum class ParticlePreset",
    "interface ExternalComputeAdapter",
    "suspend fun discoverNodes(timeoutMs: Long = 2500L): List<ComputeNodeEndpoint>",
    "suspend fun submitVideoRedrawJob(",
    "suspend fun pollAndDownloadMp4(",
]

OEM_ENHANCEMENT_CHECKS = [
    (
        "P0 #1 SDK Constant Fix",
        "app/src/main/java/com/galaxy/starshine8/wallpaper/host/CockpitSafetyMonitor.kt",
        ["Intent.ACTION_CONFIGURATION_CHANGED", "fun restartAudioPulse()"],
    ),
    (
        "P0 #2 Runtime RECORD_AUDIO",
        "app/src/main/java/com/galaxy/starshine8/wallpaper/host/ImmersiveShowcaseActivity.kt",
        ["ActivityResultContracts.RequestPermission", "Manifest.permission.RECORD_AUDIO", "restartAudioPulse()"],
    ),
    (
        "P0 #3 OES uVideoTexMatrix",
        "app/src/main/assets/shaders/starshine_master_scene.frag",
        ["uniform mat4 uVideoTexMatrix;", "(uVideoTexMatrix * vec4(sampleUv, 0.0, 1.0)).xy"],
    ),
    (
        "P1 #1 Dual-Host Live Sync",
        "app/src/main/java/com/galaxy/starshine8/wallpaper/host/StarshineWallpaperService.kt",
        ["ACTION_WALLPAPER_BUNDLE_UPDATED", "notifyConfigurationChanged", "override fun onSurfaceDestroyed"],
    ),
    (
        "P1 #2 Condition Pause & OES Matrix",
        "app/src/main/java/com/galaxy/starshine8/wallpaper/core/render/PhaseBHardwareVideoDecoder.kt",
        ["ReentrantLock", "pauseCondition.await()", "getTransformMatrix(outTransformMatrix16)"],
    ),
    (
        "P1 #3 30-Item LazyRow & 5-State CTAs",
        "app/src/main/java/com/galaxy/starshine8/wallpaper/ui/workbench/CockpitWorkbenchScreen.kt",
        ["LazyRow", "onRetryRecoverableError", "onOpenQrFromEmpty", "MaterialTheme.colorScheme.onPrimary"],
    ),
    (
        "P1 #4 ProGuard Serialization Rules",
        "app/proguard-rules.pro",
        ["$$serializer", "WallpaperQuotaManager", "LanExternalComputeAdapter"],
    ),
    (
        "P2 #1 Phase B 60fps & Cleanup",
        "remote-aigc-server/main.py",
        ["fps=60", "PRESET_GRADING_MAP", "cleanup_expired_jobs_loop"],
    ),
    (
        "P2 #2 JUnit Test Dependencies",
        "app/build.gradle.kts",
        ["junit:junit:4.13.2", "kotlinx-coroutines-test", "1.3.0-starshine8-oem"],
    ),
]


def main() -> int:
    print("=" * 96)
    print("STARSHINE 8 LIVE WALLPAPER — P0/P1/P2 OEM-GRADE VERIFICATION REPORT (v1.3.0)")
    print("=" * 96)

    all_passed = True
    total_lines = 0

    for label, rel_path in REQUIRED_FILES:
        full_path = ROOT / rel_path
        if not full_path.exists():
            print(f"[FAIL] Missing required file: {rel_path} ({label})")
            all_passed = False
            continue

        text = full_path.read_text(encoding="utf-8")
        lines = len(text.splitlines())
        total_lines += lines

        violations = [pat for pat in FORBIDDEN_PATTERNS if re.search(pat, text)]
        if violations:
            print(f"[FAIL] {rel_path}: Found forbidden placeholder pattern(s): {violations}")
            all_passed = False
        else:
            print(f"[PASS] {label:<28} | {rel_path:<75} | {lines:>4} lines")

    contract_file = ROOT / "app/src/main/java/com/galaxy/starshine8/wallpaper/core/contract/StarshineCoreContracts.kt"
    contract_text = contract_file.read_text(encoding="utf-8")
    missing_sigs = [sig for sig in ADR_CONTRACT_SIGNATURES if sig not in contract_text]
    if missing_sigs:
        print(f"[FAIL] Missing ADR-001 contract signatures: {missing_sigs}")
        all_passed = False
    else:
        print("[PASS] ADR-001 Section 5 & Matt Pocock Gate 2 Contract Signatures: 100% matched.")

    print("-" * 96)
    for check_name, rel_path, tokens in OEM_ENHANCEMENT_CHECKS:
        file_text = (ROOT / rel_path).read_text(encoding="utf-8")
        missing_tokens = [t for t in tokens if t not in file_text]
        if missing_tokens:
            print(f"[FAIL] {check_name}: Missing {missing_tokens} in {rel_path}")
            all_passed = False
        else:
            print(f"[PASS] {check_name:<36} | Verified in {rel_path}")

    print("-" * 96)
    print(f"Total Verified Files: {len(REQUIRED_FILES)} | Total Production & Test Lines: {total_lines}")
    print(f"Final Verdict: {'PASS' if all_passed else 'FAIL'}")
    print("=" * 96)
    return 0 if all_passed else 1


if __name__ == "__main__":
    sys.exit(main())
