# Haven 功能拆解与精简边界

分析基线：`v5.94.24` / `fe04c76ec`，2026-11-10。

这是完整功能目录与依赖拆分参考。当前已确定保留远程客户端核心、Root/Shizuku、FIDO2，以及 WireGuard、OpenVPN、AnyConnect、Tailscale、NetBird；完整保留与删除清单见 [当前裁剪计划](REBUILD_PLAN.md)。上一轮 14 项方案保留在 [历史参考](REBUILD_PLAN_EXTENDED.md)。本文件保留上游功能分析作为历史参考，包含已移除的模块名称；裁剪已执行，当前工程以 REBUILD_PLAN.md 与 settings.gradle.kts 为准。

## 上游基线（裁剪前）

- 33 个 `core` 库模块、12 个 `feature` 库模块，共 45 个；另有应用入口 `app` 和 `integration-tests`。
- `feature` 主要负责界面和流程；`core` 承载协议、状态、存储和平台能力。
- 桌面装配、MCP 服务、Tasker、USB 磁盘、工作区和备份等功能有大量代码直接位于 `app`。
- 模块目录已经分开，但编译依赖和功能边界尚未完全分开。隐藏页面、取消 Gradle 模块、停止原生构建是三项不同的工作。

## 上游按用户功能拆成 21 个取舍项

位置中的 `app/agent`、`app/desktop` 等按应用 Kotlin 包后缀简写，实际根目录为 `app/src/main/kotlin/sh/haven/app/`；`core/local/uml` 指 `core/local` 模块下的 `uml` Kotlin 包。

| 编号 | 功能项 | 包含能力 | 主要位置 | 裁剪或拆分边界 |
| --- | --- | --- | --- | --- |
| 01 | SSH 终端与连接 | SSH、多标签、会话恢复、连接配置、快捷键 | `feature/terminal`、`feature/connections`、`core/ssh`、`core/terminal-haven`、`core/toolbar`、`termlib` | 终端渲染可以复用；连接管理和终端 ViewModel 需要按协议收窄。SSH 自身的端口转发和 ProxyJump 可留在这一项。 |
| 02 | Mosh / Eternal Terminal | 漫游连接、持久会话 | `core/mosh`、`core/et`、`mosh-kotlin`、`et-kotlin` | 两种协议可以继续分别取舍；要同步移除连接编辑和会话恢复中的协议分支。 |
| 03 | 基础文件管理 | 本地、SFTP/SCP、复制移动、上传下载、Root 后端 | `feature/sftp`、`core/ssh`、`app/HavenDocumentsProvider.kt` | 当前文件界面还包含 SMB、云盘、媒体和编辑功能。Root 后端可再单独裁剪。 |
| 04 | SMB 文件共享 | Windows / Samba 文件访问 | `core/smb`、`feature/sftp` 内 SMB 后端 | 清理文件后端、连接类型、发现入口和会话装配后，可整块移除。 |
| 05 | 云盘与目录同步 | rclone 云存储、OAuth、同步、云端分享、DLNA | `core/rclone`、`rclone-android`、`feature/sftp` | Go 运行时还供隧道和 Proton 邮件使用；删云盘后，未必能删掉整个 Go 工程。 |
| 06 | 文本编辑器 | 语法高亮、查找替换、文件保存 | `feature/editor` | 主要入口来自文件管理，边界较集中，可独立保留或删除。 |
| 07 | 图像与媒体处理 | 图片工具、FFmpeg 转码、滤镜、预览、HLS | `feature/imagetools`、`core/ffmpeg`、`build-ffmpeg` | 图片工具目前直接依赖 FFmpeg；如仅保留图片裁剪，需要拆出不依赖 FFmpeg 的部分。DLNA 随云盘项处理。 |
| 08 | 远程桌面 | VNC、RDP、SPICE、GLKVM / PiKVM | `feature/vnc`、`feature/rdp`、`core/vnc`、`core/rdp`、`core/spice`、`core/glkvm`、`app/desktop` | 四种协议可继续分别取舍；统一查看器、投屏和原生库打包位于多个位置。RDP / SPICE 涉及 Rust 构建。 |
| 09 | 本地 Linux 命令行 | PRoot、多发行版、包管理、rootfs 管理 | `core/local`、`build-proot` | 同一个模块还负责桌面、UML、QEMU 和 USB 辅助；保留命令行时应先把这些实现拆开。 |
| 10 | 本地图形桌面与应用 | Wayland、Xwayland、Xfce / Openbox / Sway、单应用窗口、GPU | `core/wayland`、`wayland-android`、`core/local`、`app/desktop`、部分 `app/agent` 展示代码 | 与 PRoot、远程桌面查看器、画中画和 GPU 构建相连。删除时要保留仍在使用的公共输入和窗口接口。 |
| 11 | UML / QEMU 与 USB 磁盘助手 | Linux 内核环境、模拟系统、USB Linux 文件系统读取、救援 | `core/local/uml`、`core/local` 内 QEMU 代码、`app/usb`、UML / QEMU 获取脚本 | 可与 PRoot 用户态环境分开；包含镜像下载、原生资源、USB 暴露和连接预处理。 |
| 12 | 扩展网络与隧道 | WireGuard、Tailscale、NetBird、OpenVPN、AnyConnect、Cloudflare、敲门、SPA | `core/tunnel`、`feature/tunnel`、`core/knock`、`core/spa`、Go bridges | 应进一步拆成 SSH 路由接口和可选隧道实现；单独去掉 VPN 类协议后，普通 SSH 转发仍可保留。 |
| 13 | AI 聊天 | 模型服务连接、图片消息、模型选择、聊天历史 | `feature/chat`、`core/openai`、`app/chat` | 独立于 MCP；还需清理连接管理、模型配置、聊天表和 MCP 中的聊天工具注册。 |
| 14 | Agent / MCP | 配对、授权、审计、终端与桌面控制、设备访问 | `app/agent`、`core/mcp`、`core/data/agent` | `core/mcp` 主要是协议辅助；实际服务和工具集中在 `app/agent`。其中窗口展示、SSH 执行等帮助代码也被其他功能使用，需先搬出。 |
| 15 | 邮件与邮件规则 | IMAP/SMTP、Proton 读取、附件、规则、审批队列 | `feature/mail`、`core/mail`、`app/mail`、`app/agent/mailrules` | 邮件收发和规则自动化可继续分开。Proton 桥接依赖 Go；附件复用文件后端。 |
| 16 | 串口与 USB 转发 | USB / 蓝牙 / BLE 串口、TCP 桥接、USB/IP、设备暴露 | `core/btserial`、`core/bleserial`、`core/usbserial`、`core/usb`、部分 `app/agent` | 串口、USB 转发应分别取舍；`core/usb` 也被 FIDO2 使用，保留硬件安全密钥时不能整体去掉。 |
| 17 | Reticulum Mesh | 网状网络终端、文件访问、转发、Prns 引擎 | `core/reticulum`、`core/prns`、`reticulum-kt`、`rnsh-kt`、`prns`、`app/reticulum` | 协议、后端和引擎注册一起裁剪；不使用离线 Mesh 时可以作为整组候选。 |
| 18 | 基本 SSH 密钥管理 | 密钥生成、导入、选择、授权部署 | `feature/keys`、`core/security`、`core/data`、`core/ssh` | 可保留普通密钥功能，同时裁剪 FIDO2、扫码导入和证书自动化；现有 Keys 模块会把这些扩展一并引入。 |
| 19 | 高级认证与证书 | FIDO2 / NFC / USB、安全芯片密钥、step-ca 证书续期 | `core/fido`、`core/stepca`、`core/security`、`feature/keys` | 并非统一独立模块。`core/data` 和 `core/ssh` 直接依赖 FIDO，安全芯片支持也在安全与 SSH 层中；要拆成认证接口与实现。 |
| 20 | OCR / 扫码 / 拍照附件 | ONNX OCR、二维码、终端图像附件 | `core/scan`、终端附件、密钥扫码、Agent 桌面检查 | OCR 和二维码共用模块，但用途不同；可保留轻量二维码识别，去掉 OCR 模型和 ONNX Runtime。 |
| 21 | Tasker / MacroDroid | 外部自动化执行 SSH 命令、输出回传 | `app/tasker` | 可以独立于 MCP 保留；目前引用 `app/agent/HeadlessSshExec.kt`，需要先抽取公共 SSH 命令执行服务。 |

## 需要保留并收窄的公共底座

| 部分 | 当前模块或位置 | 精简时的处理 |
| --- | --- | --- |
| 应用壳、导航、主题 | `app`、`core/ui`、`feature/settings` | 由最终功能清单生成入口；当前 Settings 仍直接依赖本地 Linux 和隧道界面。 |
| 数据和配置 | `core/data` | 连接、密钥、聊天、邮件规则、PRoot、MCP 审计等共用 Room 数据库。删除业务前先定旧数据兼容策略，不直接删除表或降数据库版本。 |
| 凭据与应用安全 | `core/security`、`core/redact` | 保留凭据加密、主机校验、必要的应用锁和日志脱敏；按功能收窄高级认证。 |
| 会话与后台生命周期 | `core/ssh`、`core/data`、`app/transport` | 现有抽象可复用，但实现不止 SSH。仅注册保留的协议，统一处理重连、前台服务和断开。 |
| 备份与工作区 | `app/backup`、`app/workspace`、Settings | 基础导入导出可保留；跨后端自动同步、跨协议工作区应随保留范围收窄。 |

## 六处优先解耦点

1. **终端 → 连接管理和文件管理。** `feature/terminal` 为复用 FIDO 弹窗引用 `feature/connections`，为附件上传引用 `feature/sftp`。后两者又引入大量非终端能力。应把认证弹窗、文件选择和上传接口抽到公共层，协议通过会话接口接入。
2. **文件浏览器 → 全部文件后端和媒体工具。** `feature/sftp` 同时绑定 SFTP、本地、SMB、rclone、Reticulum、FFmpeg 和编辑器。保留文件界面时，先把后端注册和可选动作改为按构建注入。
3. **PRoot → Wayland / UML / QEMU。** `core/local` 直接依赖 `core/wayland`，其 `preBuild` 又绑定 PRoot、Wayvnc、USB 辅助、QEMU 和 UML 任务。应拆为 PRoot 命令行、本地图形、内核/模拟环境和 USB 辅助四部分。
4. **云盘 → 隧道和邮件共享 Go 产物。** `core/tunnel` 直接使用 `rclone-transport` 的绑定；Go 构建一次绑定 rclone、WireGuard、Tailscale、Proton、NetBird、OpenVPN 和 AnyConnect。应先按所选能力精简绑定和 native 任务，再移除不需要的依赖。
5. **MCP → app 内公共帮助代码。** 不应直接整删 `app/agent`：Tasker 的无界面 SSH 执行、应用窗口、画中画等也引用这里的实现。先搬出公共服务，再删除工具提供者和 MCP 服务。
6. **公共数据和认证。** `core/data` 直接依赖 `core/fido`，`core/ssh` 也直接依赖 FIDO 实现；`feature/keys` 引入 step-ca 和 OCR。普通密钥管理应通过认证接口工作，扩展实现按需注册。

## 现有 terminal 构建为什么还不够

`app/build.gradle.kts` 已有 `full` 和 `terminal` 两种功能变体。`terminal` 通过 `BuildConfig.DESKTOP=false` 隐藏入口，并从最终打包中排除一部分桌面、媒体和 UML 原生库；代码明确说明这些模块仍保留在编译类路径上。

因此它可以作为包体精简的参考，不能直接当作已经完成业务解耦的终端版。需要同时检查 `settings.gradle.kts` 的 included builds、模块生产依赖、`preBuild` 任务、Go/Rust/CMake 产物、Manifest、Hilt 装配与入口。

## 一个供讨论的最小候选

如果定位为手机上的远程服务器管理工具，可以先选择 **01 SSH 终端、03 基础文件、18 基本密钥**，再按需要加入 **02 Mosh/ET** 或 **08 中的某一种远程桌面**。应用壳、配置、安全和基础备份随之保留。

以这三个选项及应用公共底座为起点，当前沿生产 `project(...)` 依赖仍会到达 **35 / 45 个库模块**。这反映现有交叉依赖；不代表 35 个库都是精简版必须保留的，也不包含 `app` 的额外装配和原生构建。

这是讨论用默认项，不代表已确定删除其他能力。若定位为本地 Linux 工具，则以 01、09、18 为基础，03 和 10 按需加入；其裁剪顺序会不同。

## 建议实施顺序

1. 确认功能编号，进一步选择远程桌面协议、网络协议、普通/硬件密钥等子项。
2. 收窄导航、连接类型、默认权限和新连接表单，使界面与保留范围一致。
3. 优先抽取六处公共接口，清理 Hilt 注册、会话装配、工具注册和后台任务。
4. 移除不需要的 Gradle 库、included builds、原生构建任务与打包资源。
5. 处理配置、备份和数据库的导入或升级兼容。
6. 对保留路径做针对性验证：建立连接、认证、终端输入和重连、文件传输、后台恢复、备份恢复；增加所选扩展的验证。

## 可复核的代码入口

- 功能构建变体与全部应用依赖：`app/build.gradle.kts`
- 根工程和 included builds：`settings.gradle.kts`
- 三个核心界面的依赖：`feature/terminal/build.gradle.kts`、`feature/connections/build.gradle.kts`、`feature/sftp/build.gradle.kts`
- Linux 和网络构建任务：`core/local/build.gradle.kts`、`core/tunnel/build.gradle.kts`
- Go 共享产物：`core/rclone/build.gradle.kts`、`core/mail/build.gradle.kts`、`rclone-android/tools/build-android.sh`
- 共用数据表：`core/data/src/main/kotlin/sh/haven/core/data/db/HavenDatabase.kt`
- Tasker 与 Agent 的交叉引用：`app/src/main/kotlin/sh/haven/app/tasker/TaskerFireReceiver.kt`

本文通过静态读取构建文件与源码整理。未执行功能裁剪、编译或真机行为验证。库依赖统计仅包含 `core` / `feature` 的生产 `project(...)` 依赖；不包含 `app` 内装配、外部依赖、included builds 和原生任务，也不能用于估算 APK 大小。
