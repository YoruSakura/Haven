# 终端源码来源

`termlib/` 基于 [GlassOnTin/termlib](https://github.com/GlassOnTin/termlib)
的提交 `acd39c023b747deed6e61cb5ccbe22e428fbbc3f`，随本仓库直接保存。
其许可证及 libvterm 许可证分别保留在 `termlib/LICENSE` 和
`termlib/lib/src/main/cpp/libvterm/LICENSE`。

本地修复位于 `lib/src/main/java/org/connectbot/terminal/KeyboardHandler.kt`，
并有对应的 `KeyboardHandlerTest.kt`：单独按 Ctrl、Shift 等修饰键时保留选区，
Ctrl+Shift+C 通过本地复制回调处理选中文字。之前实际构建和安装的 APK 已使用这些修复。

2026-11-13 重建远端主线时，将原子模块转换为普通源码目录，避免只发布旧的
子模块指针而遗漏本地修复。Gradle 仍通过 `includeBuild("termlib")` 从源码构建，
应用功能、包名、版本和签名不因这一整理而改变。
