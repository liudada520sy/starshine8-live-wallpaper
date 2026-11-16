# 2025款吉利银河星耀8 高美感多风格动态壁纸系统 (`v1.3.0-starshine8-oem`)

面向 **2025款吉利银河星耀8（Flyme Auto 智能座舱 / 龍鹰一号 7nm SoC / 15.4英寸 `2560×1600` 16:10 超清中控横屏）** 打造的车规级多风格动态壁纸工程。

## v1.3.0 工程化与全链路汇总

- **P0/P1 车规级加固**：修复 `Intent.ACTION_CONFIGURATION_CHANGED` 常量、接入 `RECORD_AUDIO` 运行时动态权限请求、补全 Phase B OES `uVideoTexMatrix` 变换矩阵、实现双宿主 `ACTION_WALLPAPER_BUNDLE_UPDATED` 实时热同步、消除 UI 硬编码色值逃逸、升级 30 套 `LazyRow` 缩略图画廊与 5-State 交互闭环、优化 `ReentrantLock + Condition` 解码暂停及 `ProGuard` 序列化规则。
- **P2 工程化闭环**：
  - `app/src/test/`：新增 `StarshineCoreContractsTest`、`ImageAndQuotaMathTest`、`AudioPulseMathTest` 三套 JUnit 单元测试（覆盖 16:10 裁剪、512MB/30 套 LRU 标星淘汰、Bark 频段映射及 `DomainResult` 序列化）。
  - `remote-aigc-server/main.py`：升级至 `2560×1600@60fps` 无缝循环 H.265 管线，支持 `prompt_preset` 差异化调色与 24 小时过期任务自动清理。
  - 工程根路径补全 `gradlew` / `gradlew.bat` 跨平台启动器（若需离线 `gradle-wrapper.jar`，可在本地执行 `gradle wrapper --gradle-version 8.7` 一键生成）。

## 快速校验与运行指南

```bash
# 1. 零占位符、ADR-001 契约、P0/P1 加固与 P2 工程化验收
python3 scripts/verify_project_structure.py

# 2. Matt Pocock Gate 3 垂直切片架构边界隔离审计
python3 scripts/scaffold_vertical_slices.py --check-boundaries

# 3. Garry Tan GStack Phase 1 Design Token 与 WCAG 2.1 对比度审计
python3 scripts/audit_image_and_theme.py

# 4. 运行 JVM 单元测试与安装 Debug APK（需配置 Android SDK）
./gradlew testDebugUnitTest installDebug
```
