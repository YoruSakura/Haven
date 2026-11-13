# Haven 上一轮扩展功能方案（历史参考）

基线：`v5.94.24` / `fe04c76ec`。整理日期：2026-11-11。

本文件记录上一轮勾选的 14 项扩展功能范围。用户随后明确用途为普通远程客户端，当前建议已收窄，请以 [REBUILD_PLAN.md](REBUILD_PLAN.md) 为准。本文件仅保留先前的依赖核对和实施细节，不能作为当前保留清单。功能编号对应 [REBUILD_MODULES.md](REBUILD_MODULES.md)。

## 1. 范围

### 已选保留项

| 功能组 | 保留编号与能力 |
| --- | --- |
| 连接与终端 | 01 SSH；02 Mosh、Eternal Terminal；多标签、会话恢复、快捷键和 SSH 转发 |
| 文件与内容 | 03 本地、SAF、Root、SFTP/SCP；04 SMB；05 云盘、同步、分享和 DLNA；06 文本编辑 |
| 远程桌面 | 08 VNC、RDP、SPICE、GLKVM / PiKVM，以及远程桌面的输入、剪贴板和投屏 |
| 网络 | 12 扩展隧道、代理、敲门和 SPA；现有协议默认全部保留 |
| AI 与自动化 | 13 AI 聊天；14 Agent / MCP；21 Tasker / MacroDroid |
| 设备与认证 | 16 串口与 USB 转发；18 普通 SSH 密钥；19 FIDO2、安全芯片密钥与 step-ca |

### 删除候选与直接影响

| 编号 | 删除范围 | 仍需保留的相邻能力 |
| --- | --- | --- |
| 07 | FFmpeg 转码、滤镜、图片裁剪/校正工具、媒体生成预览、HLS 转码服务器 | 普通文件打开、原文件 HTTP 串流、云盘 DLNA、AI 图片输入、MCP 已有媒体展示 |
| 09 | PRoot、本地发行版、rootfs 安装与管理、本地 Linux 命令行 | Android 本地文件、SAF、Root 文件后端、SSH、必要的 Shizuku 平台操作 |
| 10 | 本地 Wayland / X11 桌面、GPU broker、Linux 应用安装与单应用窗口 | 四种远程桌面、远程输入与投屏、MCP 的 HTML/PDF/媒体展示 |
| 11 | UML、QEMU、本地系统镜像、USB 磁盘 VM 助手、救援环境 | USB 枚举与传输、FIDO2、串口、向远程主机导出 USB/IP 和 usbredir |
| 15 | 邮件界面、IMAP/SMTP、Proton 邮件桥接、邮件规则与轮询 | 文件读写、云盘、普通通知、MCP 配对/授权/审计 |
| 17 | Reticulum、rnsh、Prns、Mesh 文件后端与转发 | SSH/Mosh/ET、普通端口转发和用户选择的隧道 |
| 20 | 内置 OCR、二维码识别、终端拍照识别/扫描入口 | 终端普通文件上传、AI 图片消息、MCP 截图、坐标点击/拖动/键盘输入 |

本轮按已选大项保留其子功能。远程桌面各协议、VPN 各协议、MCP 的设备访问工具均未额外缩减；进一步裁剪这些子项见第 9 节。

## 2. 模块与代码删除清单

### 预计移除的 9 个现有 Gradle 库

```text
:core:ffmpeg          :feature:imagetools
:core:local           :core:wayland
:core:mail            :feature:mail
:core:reticulum       :core:prns
:core:scan
```

现有 45 个库移除上述 9 个后，留下 **36 个现有库：26 个 core、10 个 feature**。`app`、`integration-tests` 另计；若为公共平台能力新增库，新增数量另计。此统计不能换算为 APK 大小或构建耗时。

### 跨模块清理

应用 Kotlin 包根目录为 `app/src/main/kotlin/sh/haven/app/`。

| 位置 | 具体工作 |
| --- | --- |
| `app/reticulum` | 删除 Reticulum 装配、NativeReticulumTransport 与 Prns 接入；清理会话和前台服务注册。 |
| `app/usb` | 删除 `UsbDriveVmManager`、`UsbDriveConnectionPreflight` 及绑定、`UmlRecoveryManager` 等本地磁盘/救援实现；通用 USB 主机能力保留在 `core/usb`。 |
| `app/desktop` | 删除本地发行版/桌面管理、InstalledApps、AppWindowLauncher 与本地应用快捷方式；保留远程 session、tab、输入和投屏。`DesktopViewModel` 和混合界面应按职责拆开。 |
| `app/agent` | 删除邮件、PRoot/UML/QEMU、本地应用、Mesh、FFmpeg/OCR 相关工具和依赖注入；保留其余工具、服务、配对、授权、审计和展示。 |
| `app/agent/mailrules`、`app/mail` | 删除邮件规则引擎、轮询、审批队列执行与附件装配。 |
| `app/transport` | 删除 Local、Guest、Reticulum、Mail 的适配器和注册，保留其他协议。 |
| `app` 启动与导航 | 清理 `ProotIdleReaper`、本地桌面与邮件导航、冷启动初始化、自动恢复、快捷方式和深链接。 |
| `feature/connections` | 清理 LOCAL/GUEST/EMAIL/RETICULUM 表单、状态订阅、连接分支；保留普通发现功能所需的公共平台实现。 |
| `feature/terminal` | 移除本地与 Mesh session、OCR/扫码状态和入口，保留普通上传、FIDO 提示与远程协议。 |
| `feature/sftp` | 移除 Reticulum 文件后端、转码/HLS、ImageTools 界面及 ViewModel 状态；保留编辑器、其他文件后端、DLNA、原文件串流。 |
| `feature/keys`、`feature/settings` | 去掉扫码、本地 Linux、邮件、Mesh、转码相关入口；保留高级认证、隧道、备份和选中的 MCP 功能。 |
| `app/HavenDocumentsProvider.kt` | 保留 Android 文档提供者与云盘接入，移除 PRoot rootfs 的根目录和访问路径。 |
| 工作区、备份、更新 | 从新建与自动启动路径移除不支持的类型；保留旧数据的读取和导出边界。 |

不要整目录删除 `app/desktop` 或 `app/agent`：它们同时包含本轮选中的功能。

## 3. 必须先解耦的接口

以下为建议职责与实施边界，并非已经新增的类。

| 拆分点 | 当前耦合证据 | 计划处理 | 完成条件 |
| --- | --- | --- | --- |
| 通用 Android / Shizuku 平台服务 | `core/local/WaylandSocketHelper.kt` 同时提供 APK 安装、shell 执行、日志/ADB 等能力；HavenApp、Settings、网络发现和 MCP 引用它 | 迁出为 `PlatformShell`、`AppInstaller` 等公共职责，可放入新 `core:platform`；仅 Wayland socket 和 guest 安装部分随本地环境删除 | 启动、发现、安装/诊断的保留调用不再引用 `core.local` |
| 协议与会话装配 | `app/transport/TransportSessionManagerModule.kt` 和 `ForegroundSessionParticipantModule.kt` 显式绑定所有协议 | 复用现有 `TransportSessionManager`、`SessionManagerRegistry`、`ForegroundSessionParticipant`，移除未保留实现；收窄 ViewModel 中的状态聚合和恢复逻辑 | 创建连接、重连、断开与后台计数只覆盖保留协议；无缺失 Hilt 绑定 |
| 文件后端和可选动作 | `feature/sftp/transport/FileBackend.kt` 已有接口；SftpViewModel 同时注入 FFmpeg/HLS 并处理 ImageTools、Mesh | 保留 FileBackend 和上传管线，删除 Mesh 后端；将转码/图片处理动作从文件浏览、串流和编辑流程剥离 | 文件列表、跨后端复制、编辑、附件上传、原文件串流不引用 FFmpeg/ImageTools |
| MCP 远程桌面与本地运行 | `DesktopToolProvider` 构造器依赖 `LocalSessionManager`；同一 provider 处理远程 tab 和本地桌面/VM | 按职责拆出远程桌面工具，继续通过 `DesktopSessionRegistry` 访问远程 frame；删除本地生命周期工具 | MCP 可以截图、坐标操作、输入远程桌面，无本地 Linux 依赖 |
| OCR 与普通桌面操作 | `inspect_desktop_tab` 当前必经 `buildOrGetOcrIndex`；识别器为空会报错 | 按当前选择移除 OCR 工具的注册、schema、缓存和 OCR 增强字段，保留基本截图/交互状态；若决定保留 OCR，仅重新接入专用识别实现 | `tools/list` 不承诺文字定位能力；坐标和键盘工具独立工作 |
| USB 主机操作与本地 guest | `UsbToolProvider` 同时注入 USB/IP、usbredir 和 `UmlRecoveryManager` | 保留主机枚举、授权、传输、USB/IP 和远程 usbredir；去掉 guest attach、磁盘 VM、救援及其注入 | FIDO2、串口和远程 USB 导出均不需要 `core:local` |
| 展示与 Linux 应用窗口 | `PresentationHost` / `PipController` 同时承载媒体、Web/PDF 与本地 VNC 应用窗口 | 保留现有媒体和 Web/PDF 展示及所需 PiP；剥离 Linux 应用窗口队列、启动和缓存 | `present_media` / `present_web` 不经 PRoot，`present_app` 不再公开 |
| 终端附件与识别 | TerminalViewModel 同时持有上传协调器、BarcodeDecoder、TextRecognizer | 保留 `TerminalAttachCoordinator` 的普通文件上传，去掉扫描/识别流程；当前终端与文件界面的直接依赖可后续继续整理 | 上传文件不依赖 `core:scan`；AI 聊天图片输入独立保留 |

FIDO2、普通密钥与 USB 都在保留范围内，本轮可沿用 `core:data → core:fido → core:usb` 的认证依赖。Tasker 与 MCP 同时保留，`HeadlessSshExec` 可以继续共用；迁出公共 SSH 执行服务属于后续可选整理，不是本次删除的前置条件。

## 4. MCP 工具的具体取舍

| 类型 | 处理 |
| --- | --- |
| 远程操作 | 保留 `list_desktop_sessions`、`capture_desktop_tab`、`tap_desktop_tab`、`move_desktop_mouse`、`drag_desktop_tab`、`type_desktop_text`、`scroll_desktop_tab`、`send_desktop_clipboard`。 |
| OCR | 默认移除 `inspect_desktop_tab`、`tap_desktop_text` 的 OCR 语义；保留必要的基本会话状态与用户交互坐标。若保留同名检查工具，应明确改写 schema 和能力声明，不能留下必调识别器的实现。 |
| 本地运行 | 删除发行版安装、PRoot 执行/文件/日志、桌面安装启停、system VM 镜像管理、guest 应用与 `present_app` 等工具。 |
| USB | 保留 `list_usb_devices`、`request_usb_permission`、`usb_control_transfer`、`usb_bulk_transfer`、USB/IP 和 usbredir 导出启停；删除 `usb_attach_to_guest`、`detach_from_guest`、`open_usb_drive` 等本地 guest/磁盘工具，收窄 `list_usb_exports`。 |
| 邮件、Mesh、媒体 | 删除邮件与规则 provider、Mesh 专用参数和转发分支、FFmpeg 转码/预览/HLS 工具；云盘、普通文件分享/串流与媒体展示保留。 |
| 通用 Agent | 保留配对、授权、审计、策略、终端/文件/AI/隧道/密钥/串口工具，及当前选中的设备和诊断能力；同步更新工具分组、`tools/list`、文档及数量描述。 |

`list_desktop_windows`、`capture_desktop`、`read_desktop_log` 等与本地桌面混用的工具，需要逐个检查 handler：只留下能由保留的远程 session 提供的行为，不用空对象或成功响应掩盖已删除能力。

MCP 中的 `profileId="local"` 仍可表示 Android 本地文件，应保留其文件读写、分享和展示语义；删除的是 Linux guest 路径与生命周期，不能按字符串 `local` 批量删除普通文件操作。

## 5. 构建与原生依赖

| 构建链 | 移除或修改 | 保留 |
| --- | --- | --- |
| Gradle | 从根 include、app/feature 生产依赖和测试依赖移除上述 9 个库；清理 Hilt、资源和 Manifest 引用 | 其余 36 个现有库与必要的集成测试工程 |
| 本地 Linux / Wayland | 删除 `core:local:preBuild` 的 PRoot、Wayvnc、guest USB helper、QEMU loader、UML 构建/获取任务；删除 app 的 `fetchUmlRootfs`；清理 `build-proot`、`wayland-android` 及对应任务 | 迁出的公共 Shizuku 能力；`core:usb` 的主机侧实现及其必要原生产物 |
| FFmpeg | 删除 `core/ffmpeg`、`build-ffmpeg` 以及相关脚本、库、缓存/打包资源引用 | Android 原文件打开和无需 FFmpeg 的 HTTP/DLNA 路径 |
| Reticulum / Prns | 移除 `includeBuild("reticulum-kt")`、`includeBuild("rnsh-kt")`、Prns JVM binding included build 与 app 直接依赖；清理 prns native 任务 | Mosh / ET 的 included builds |
| Go | 从 full、terminal 两条 `gomobile bind` 命令中去掉 `mailbridge`，重建 bindings 与 `.so`；删除仅由邮件桥接引入的包/依赖 | rclone、wgbridge、tsbridge、nbbridge、ovpnbridge、ocbridge；Cloudflare Access 和相关登录 UI |
| Go 依赖图 | 根据实际 import 图执行依赖整理，检查生成绑定与 `.so` 是同一批产物；rclone 后端仍需要的依赖继续保留 | 云盘和隧道需要的共享密码学、网络与运行时依赖；不能按 Proton 等名称批量删除依赖 |
| Rust | 删除 Prns 的构建链与绑定 | `rdp-kotlin`、`spice-kotlin` 和 cargo-ndk / Android targets；保留 Rust 工具链 |
| OCR | 删除 `fetchRapidOcrModels`、ONNX 模型资产、ONNX Runtime 与相关打包设置；去除扫码则同时去掉相应 ZXing 消费路径 | AI 服务端视觉输入、远程桌面截图与 Android 图像解码 |
| 子模块与工作流 | 更新 `.gitmodules`、CI 的路径缓存、native 产物清单、许可证与第三方说明；删除候选为 PRoot、Wayland、Reticulum、rnsh、Prns 子模块 | termlib、mosh-kotlin、et-kotlin、spice-kotlin；RDP 和 rclone 工程 |

**构建变体：**当前 `terminal` 变体会排除 RDP/SPICE 原生库，不适合直接承载这次保留范围。先用现有 `full` 变体承载裁剪后的模块，保证远程桌面可用，再决定是否将功能变体合并成一个产品。ABI 选择单独决定。

完成检查应覆盖依赖解析和实际 task graph：删除的 included build、下载器、CMake/Cargo/Go 任务不得仍从保留构建触发；保留的远程桌面、Go 隧道和 USB 所需库不得被通配排除。

## 6. 权限、组件与默认入口

| 权限或组件 | 本计划处理 |
| --- | --- |
| 网络、通知、唤醒锁、会话前台服务 | 随远程会话、MCP 和隧道保留，核对最终合并 Manifest。 |
| NFC、生物识别、USB host | 随高级认证和 USB 功能保留；USB 继续按设备请求系统授权。 |
| 蓝牙 / BLE | 保留对应权限；兼容旧 Android 的 BLE 位置权限边界不能随 OCR 一起清除。 |
| 相机 | 仍可能被 AI 拍照输入和 MCP 摄像头工具使用。删除 OCR/扫码后，仅移除识别入口，按保留调用点决定权限。 |
| 定位、定位前台服务 | 由保留的 MCP GPS/位置能力和旧版 BLE 需求决定；若后续删 GPS，再删除 GpsLogService 等组件。 |
| 文件访问、SAF、DocumentsProvider | 保留文件管理与云盘入口，去掉 PRoot 根目录；广泛文件访问能力是否进一步收窄列为可选子项。 |
| APK 安装、Shizuku provider、FileProvider | 更新与 MCP 安装/诊断仍会使用，迁出公共服务后保留必要组件；修改遗留的 Wayland 注释和入口。 |
| Tasker、Cloudflare、step-ca / WorkManager | 随保留功能保留对应 receiver、activity、深链接与 worker。 |
| 本地 Linux / 邮件 / Mesh / 媒体动作 | 从新建连接、桌面管理、设置、导航、快捷方式、分享菜单和自动启动中移除；组件/权限以最终活跃消费者为依据裁剪。 |

MCP 的授权、审计、撤销和后台审批机制继续覆盖保留工具。权限精简与功能删除一起核对，不单按模块名字删权限。

## 7. 数据、备份与应用身份

当前 `HavenDatabase` 为 **version 88**，连接、密钥、聊天、隧道、邮件规则、PRoot 日志和 MCP 审计共用数据库。推荐按以下顺序处理：

1. **首轮沿用数据库结构。** 移除后台执行与业务入口，暂留历史表/字段及迁移链，避免把功能裁剪与数据库破坏性迁移绑在一起。保留的 SSH、云盘、隧道、AI 聊天、密钥和 MCP 数据可正常读取。
2. **旧配置保留为不支持的类型。** LOCAL、GUEST、EMAIL、RETICULUM 等记录不自动连接、不自动恢复、不触发 worker；可以导出或由用户主动删除。读取旧值时返回明确状态，避免映射成 SSH 或重复重试。所有本地 VM 类型还需按源码中的实际判定分支逐一清理。
3. **工作区按项恢复。** 只启动支持的项目，报告跳过的历史项目；备份导入同样处理，不静默丢弃连接、密钥或未知字段。
4. **移除后台活性。** 清理 Mail Rules 轮询、PRoot reaper、VM 恢复、Mesh 自动连接、模型下载及其持久调度；保留 step-ca 续期和需要的备份同步任务。
5. **后续再做版本化清理。** 如果决定删除旧表，新增递增的 Room migration 和相应 schema，验证已有数据库及备份升级；不降版本、不使用 destructive fallback 清空数据。
6. **保留凭据保护。** 软件密钥、硬件密钥引用、已配对 MCP 客户端与加密聊天需要各自的读写与恢复验证。安全芯片私钥仍不可迁移，不能在换包名时承诺自动继承。

应用身份需要单独决定：当前主线 `applicationId` 为 `sh.haven.app`；旧开发分支有独立应用身份。重做版是沿用某个既有身份与签名、作为新应用安装，还是提供旧数据导入，会影响 provider authority、深链接、Keystore、更新和备份兼容。默认不通过更换身份或清空数据库来完成裁剪。

## 8. 实施阶段与验收

| 阶段 | 主要交付 | 验收条件 |
| --- | --- | --- |
| A 范围固化 | 保留/删除能力表，远程协议和 Agent 工具清单，应用身份与数据策略 | 下节必要子项得到决定；当前 14 个保留大项有明确行为边界。 |
| B 迁出共享能力 | 公共 Shizuku 服务、远程桌面工具与 USB 主机工具独立于本地环境 | 保留调用不再需要本地 guest；MCP 普通截图/输入与 USB/IP/FIDO 可独立运行。 |
| C 裁剪 UI 与业务 | 删除邮件/本地环境/Mesh/媒体/OCR 流程，收窄连接状态与文件动作 | 删除能力不出现在新建连接、自动恢复和 `tools/list`；保留 AI 图片、文件上传和媒体展示。 |
| D 裁剪构建链 | 移除 9 个库、相关 included builds/native 任务，重建 Go 绑定 | 依赖图和 task graph 不含删除链；保留 Rust/Go/USB 产物完整；应用构建成功。 |
| E 兼容与回归 | 旧数据库、备份导入、工作区跳过机制与保留路径验证 | 无历史配置导致的崩溃、后台重试或数据丢失；已选功能通过下列验证。 |
| F 产品收口 | 导航与设置、权限请求、文案、许可证、CI、打包说明 | 安装包和产品界面准确反映实际能力；再实测包体、首次构建与增量构建时间。 |

验证重点：

- SSH/Mosh/ET：密码、普通密钥、FIDO/安全芯片认证；新标签、恢复、切网重连与后台保活。
- 文件：SFTP、SMB、云盘 OAuth、上传下载、跨后端复制/移动、目录同步、文本保存、SAF/Root 与 DocumentsProvider。
- 媒体边界：原文件 HTTP/DLNA 可用，已移除转码/HLS/图片工具入口；AI 图像和 MCP 展示正常。
- 远程桌面：逐个验证所保留协议的连接、输入、剪贴板、重连、SSH 隧道和投屏。
- 网络：所保留 VPN/代理/敲门/SPA 的路由和重连；Go bindings 与 native 库配套。
- Agent：终端/文件/聊天/桌面/USB 的保留工具、工具发现、配对、授权、审计与撤销；无 OCR 工具的残留承诺。
- 设备：USB/BLE/蓝牙串口、USB/IP/远程 usbredir、FIDO USB/NFC；删除本地磁盘工具后不存在 guest 初始化。
- Tasker、step-ca、备份：无界面执行与返回值、续期任务、旧数据与密钥恢复。

现有构建变体下，可先以 `./gradlew :app:assembleArm64FullDebug` 验证主构建，再按保留模块运行相关单元/集成测试并做真机回归。最终任务名随构建变体决定更新。本次计划阶段尚未执行这些验证，也不估算未经测量的包体收益。

## 9. 实施前待决定的子项

| 子项 | 本计划采用的默认 | 选择会影响什么 |
| --- | --- | --- |
| **应用身份与数据（必须确定）** | 保留现有数据，迁移兼容；不自动更换应用身份或签名 | 是兼容升级、延续旧 fork 身份，还是新应用安装/导入；Keystore 和更新链路。 |
| **OCR / 二维码（有交叉影响）** | 按 20 未选，去掉内置 OCR 与扫码；MCP 通过截图和坐标操作 | 若需要 Agent 的按文字定位，需保留 OCR 子集；若仅保留二维码，可另拆轻量识别实现。 |
| **发布 ABI** | 先用 arm64 验证，发布范围尚未决定 | 仅 arm64，还是继续现有 x64 / armv7 构建入口；各原生协议仍需分别确认产物支持。 |
| 远程桌面协议 | VNC、RDP、SPICE、GLKVM 全保留 | 可继续删某种协议及其专用原生构建；这轮没有自动缩减。 |
| 扩展隧道协议 | 已有 WireGuard、Tailscale、NetBird、OpenVPN、AnyConnect、Cloudflare、代理与敲门/SPA 全保留 | 继续精简会影响 Go bridges、OpenVPN/OpenConnect 构建和登录流程。 |
| MCP 的设备工具 | 保留 14 范围内仍适用的相机、GPS、通知读取、安装/诊断等能力及授权 | 若只需要服务器管理，可另裁设备工具，并减少相应权限、服务和共享平台实现。 |
| Root、DLNA 与原文件串流 | 随 03 / 05 保留 | 如继续去掉这些子功能，可简化文件权限、媒体服务和对应菜单。 |
| 产品构建变体 | 先沿用 full 承载新范围，保留远程桌面 native 库 | 后续是否合并 full / terminal、重命名产品及收窄维护面。 |

只有应用身份/升级路径需要在实际打包和迁移前明确；其余项目可以按上述默认生成后续实施工作包。任何调整应同步修订保留清单、MCP 能力和构建依赖。

## 10. 本次核对依据

- 根工程与产物：`settings.gradle.kts`、`app/build.gradle.kts`、`core/local/build.gradle.kts`、`core/scan/build.gradle.kts`。
- 保留模块对删除候选的直接生产依赖：Connections → Local/Mail/Reticulum；Terminal → Local/Reticulum/Scan；Files → FFmpeg/Reticulum/ImageTools；Keys → Scan；Settings → Local。
- 公共平台：`core/local/src/main/kotlin/sh/haven/core/local/WaylandSocketHelper.kt` 及 HavenApp、Settings、NetworkDiscovery、MCP 的调用。
- MCP 混合工具：`app/src/main/kotlin/sh/haven/app/agent/DesktopToolProvider.kt`、`UsbToolProvider.kt`、`McpTools.kt`。
- 文件后端与 DLNA：`feature/sftp/src/main/kotlin/sh/haven/feature/sftp/transport/FileBackend.kt`、`SftpViewModel.kt`、`SftpStreamServer.kt`。
- Go 绑定：`rclone-android/tools/build-android.sh`、`rclone-android/build.gradle.kts`、`rclone-android/go/go.mod`。
- 数据与权限：`core/data/src/main/kotlin/sh/haven/core/data/db/HavenDatabase.kt`、ConnectionProfile、`app/src/main/AndroidManifest.xml` 及 BLE Manifest。

静态依赖统计只覆盖 `core` / `feature` 中生产 `project(...)` 边。app 内装配、外部库、native 任务和真机行为另行验收。
