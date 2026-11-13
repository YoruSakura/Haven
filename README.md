# Haven 远程客户端

基于上游 Haven `v5.94.24`（`fe04c76ec`）裁剪，面向日常远程连接、远程桌面与文件传输。包名仍为 `sh.haven.app`，版本名仍为 `5.94.24`。

| 功能 | 当前范围 |
| --- | --- |
| 终端 | SSH、Mosh、Eternal Terminal，多标签、快捷键、后台会话和重连 |
| 桌面 | RDP、VNC，键鼠、剪贴板、全屏和 SSH 隧道 |
| 文件 | SFTP/SCP、SMB、本地、SAF、可选 Root，上传下载、复制移动和文本编辑 |
| 网络 | SSH 跳板、端口转发、SOCKS/HTTP、WireGuard、OpenVPN、AnyConnect、Tailscale、NetBird |
| 认证与平台 | 普通密钥、USB/NFC FIDO2、主机密钥校验、生物识别、Root/Shizuku、手动加密备份 |

已删除 AI 聊天/MCP、云盘/S3/WebDAV、SPICE/KVM、敲门/SPA、独立 TOTP 管理器、本地 Linux/虚拟机、邮件、Mesh、媒体/OCR/扫码、设备转发、Tasker、工作区与自动备份等扩展。完整边界见 [裁剪记录](docs/REBUILD_PLAN.md)，实际构建与验证见 [验证记录](docs/REBUILD_VALIDATION.md)。

主导航为连接、终端、桌面、密钥、文件、设置。Root 文件访问需要主动启用；普通远程连接不需要 Root 或 Shizuku。

## 下载

当前发行版为 [Haven 5.94.24 精简客户端](https://github.com/YoruSakura/Haven/releases/tag/v5.94.24)，提供已签名的 arm64 APK、完整源码包及 SHA-256 校验文件。

- [下载 Android arm64 安装包](https://github.com/YoruSakura/Haven/releases/download/v5.94.24/haven-5.94.24-arm64-client.apk)
- SSHPilot 加密 YAML / SPBK 导入支持合并或覆盖匹配记录，开关每次默认关闭；也支持导出供 SSHPilot 使用，详见 [备份互通说明](docs/SSHPILOT_BACKUP.md)。
- 终端快捷键工具栏可手动隐藏，连接可用的实体键盘时自动隐藏；支持保持选区并用 Ctrl+Shift+C 复制。

包名 `sh.haven.app`、版本名 `5.94.24`、arm64 versionCode `9491` 及本项目定制签名保持不变。此包可覆盖此前的本地精简客户端。

## 构建

需要 Java 21、Android SDK 37 / NDK `29.0.14206865` / CMake `3.31.6`、Go `1.27.3`、固定版本的 gomobile/gobind，以及 Rust `1.94.1` / cargo-ndk `3.5.4`。具体安装命令见 [.github/actions/setup-toolchain/action.yml](.github/actions/setup-toolchain/action.yml)。

```bash
git submodule update --init --recursive
export ANDROID_HOME=/path/to/android-sdk
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/29.0.14206865"
./gradlew testDebugUnitTest :app:testArm64FullDebugUnitTest -PtargetAbi=arm64 --max-workers=2
./gradlew :app:assembleArm64FullRelease -PtargetAbi=arm64 --max-workers=2
./scripts/check-native-libs.sh arm64
./scripts/check-r8-kept-classes.sh
python3 scripts/check-client-apk.py app/build/outputs/apk/arm64Full/release/*.apk --abi arm64-v8a
```

`full` 是精简客户端的功能变体；ABI 可选 `arm64`、`x64`、`armv7`。没有配置发行密钥时，Release 构建生成未签名 APK，安装前需要签名。定制包使用自己的密钥，不能覆盖不同签名的上游或 F-Droid 安装。

`termlib/` 随主仓库保存实际使用的终端源码和本地修复，来源见 [终端源码说明](docs/TERMINAL_SOURCES.md)。`mosh-kotlin` 和 `et-kotlin` 继续固定为子模块。发行版的 `haven-5.94.24-client-source.tar.gz` 已包含这两个子模块的源码，解压后可直接从设置 Android 环境变量开始构建；从 Git 克隆时仍需初始化子模块。

`rclone-android` 目前仅作为五种 VPN 的共享 Go/JNI 构建宿主，名称为历史遗留；云盘业务与邮件绑定已删除。RDP、VPN 和终端原生库均从源码构建。

数据库继续保持 version 88 及已有迁移链，兼容读取旧备份。被裁剪的连接类型不会自动连接或被当作 SSH 跳板，旧扩展的后台调度和 MCP 自动反向转发规则会停用。

## 来源与许可证

本项目派生自 [GlassHaven/Haven](https://github.com/GlassHaven/Haven)，沿用 [AGPL-3.0](LICENSE)。上游介绍与致谢保留在 [UPSTREAM_README.md](docs/UPSTREAM_README.md)，字体说明见 [NOTICE-Fonts.md](NOTICE-Fonts.md)。上游的完整功能文档描述裁剪前的产品，不代表本分支提供这些功能。
