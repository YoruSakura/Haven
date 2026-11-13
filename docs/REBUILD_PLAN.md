# Haven 远程客户端重做范围与裁剪计划

基线：`v5.94.24` / `fe04c76ec`。更新日期：2026-11-11。

定位：以 SSH 类、远程桌面类、文件传输类为核心的远程客户端。用户已明确保留 Root、Shizuku、FIDO2，以及内置 WireGuard、OpenVPN、AnyConnect、Tailscale、NetBird；其余此前讨论的扩展移除。本文件记录已实施的功能裁剪与兼容边界。功能代码、模块装配、原生构建和后台组件已按此范围裁剪；构建与验证记录见 [REBUILD_VALIDATION.md](REBUILD_VALIDATION.md)。

完整功能目录见 [REBUILD_MODULES.md](REBUILD_MODULES.md)。[REBUILD_PLAN_EXTENDED.md](REBUILD_PLAN_EXTENDED.md) 仅保留上一轮 14 项方案的历史分析，不作为当前范围。

## 1. 保留范围

| 类别 | 保留能力 | 边界 |
| --- | --- | --- |
| SSH 类 | SSH、Mosh、Eternal Terminal；多标签、快捷键、复制粘贴、重连、会话恢复和后台保活 | 保留普通服务器管理流程；SSH 使用默认 JSch 引擎。 |
| 远程桌面 | RDP、VNC；键鼠、剪贴板、缩放、全屏、重连和 SSH 隧道 | 远程桌面范围收窄为这两种协议。 |
| 文件传输 | SFTP/SCP、SMB、Android 本地文件、SAF；上传下载、多选、复制移动、进度与冲突处理 | 保留文件管理和 Android 文档提供者的相应后端。 |
| Root 本机文件访问 | Root 文件页、`RootFileBackend`、`RootFsClient` 与 `su` 文件 helper | 保留现有显式启用、主动进入后才调用 `su` 的行为。Root 是手机本机能力，独立于远程 SSH 的 root/sudo。 |
| Shizuku | 授权与状态、通用 shell/日志、应用安装更新、电池优化白名单、普通网络发现所需能力 | 从 `core/local/WaylandSocketHelper.kt` 迁出通用实现；Wayland socket、guest/VM 端口辅助和 Agent 专用入口随对应业务删除。 |
| 网络连接 | SSH 跳板、`-L/-R/-D` 转发、普通 SOCKS/HTTP 代理 | 连接配置与路由继续复用现有能力。 |
| 内置 VPN / 组网 | WireGuard、OpenVPN、AnyConnect、Tailscale、NetBird | 五种均保留，包括配置、登录/授权、路由、重连、状态与所需原生构建。 |
| 登录与安全 | 密码、普通 SSH 密钥生成/导入/选择、FIDO2 的 USB/NFC 安全密钥、主机密钥校验、凭据加密、应用锁 | 保留登录时的多轮认证和验证码输入；FIDO2 继续使用所需 USB host 与 NFC 底层。 |
| 辅助使用 | 连接分组/收藏、最近连接、基础文本编辑、普通会话/标签恢复、手动加密备份与恢复 | 保留远程客户端的日常使用流程。 |

主导航为 **连接、终端、桌面、密钥、文件、设置** 六项；VPN 管理从连接或设置进入，Root 在文件页显式启用后使用。

## 2. 移除范围

以下均列入本轮移除范围，已不再作为待定选项。

| 功能 | 删除内容 | 保留的相邻能力 |
| --- | --- | --- |
| AI 聊天 / 模型接入 | `feature/chat`、`core/openai`、`app/chat`，模型配置、聊天入口与活跃数据逻辑 | SSH 和文件编辑照常使用。 |
| Agent / MCP | `core/mcp`，MCP 服务与工具注册、配对、策略、审计、活动界面、Agent 驱动与设备感知入口 | 迁出普通客户端仍使用的认证、窗口、输入和执行帮助代码。 |
| 云盘 / S3 / WebDAV | `core/rclone`，rclone 文件后端、OAuth、云端同步与分享业务 | SFTP/SCP、SMB、本地、SAF、Root 文件后端保留。 |
| SPICE | `core/spice`、`spice-kotlin` 及对应连接、查看器与 Rust 产物 | RDP/VNC 与它们所需的原生库保留。 |
| GLKVM / PiKVM | `core/glkvm` 和硬件 KVM 配置、连接与查看器适配 | 普通远程桌面保留。 |
| 敲门 / SPA | `core/knock`、`core/spa`，连接前钩子、表单与测试入口 | SSH 跳板、代理、端口转发和五种内置网络协议保留。 |
| Cloudflare 专用接入 | Cloudflare Access 登录、网关、配置与路由分支 | 用户明确选择的五种 VPN/组网能力保留。 |
| 独立 TOTP 管理器 | TOTP 密钥管理、导入、扫码、动态码生成界面与活跃逻辑 | 服务器要求验证码时，继续接受用户输入外部验证器生成的验证码。 |
| 本地 Linux 命令行 | PRoot、发行版与 rootfs 管理、本地 shell、`build-proot` 链路 | 远程 SSH 终端、手机 Root 文件访问与通用 Shizuku 保留。 |
| 本地图形桌面与应用 | Wayland/Xwayland、本地桌面环境、Linux 应用安装/启动、本地 GPU 集成 | RDP/VNC 的远程会话、输入、全屏和普通投屏能力保留。 |
| UML / QEMU / USB 磁盘救援 | 本地 VM、内核/镜像下载、guest 设备挂载、磁盘与救援助手 | Root 文件访问不依赖这些本地 guest。 |
| 邮件与邮件规则 | IMAP/SMTP、Proton 桥接、邮件界面、轮询、附件规则与审批队列 | 普通文件操作与应用通知保留。 |
| Reticulum / Prns Mesh | Mesh 终端、文件后端、路由、引擎与原生构建 | SSH/Mosh/ET 与五种内置网络协议保留。 |
| 图像与媒体加工 | 图片裁剪/校正工具、FFmpeg 转码、滤镜、媒体生成预览与 native 构建 | 普通文件打开、Android 图像解码和远程桌面画面保留。 |
| OCR / 扫码 | 文字识别、二维码扫描、OCR 模型、ONNX Runtime 和专用附件入口 | 终端普通文件上传保留。 |
| 媒体与手机文件服务 | DLNA、HLS、局域网文件服务器及相关持续服务 | 文件下载后通过 Android 应用打开。 |
| Tasker / MacroDroid | 外部自动化组件、插件配置、命令回传入口 | 手动 SSH 与普通会话恢复保留。 |
| USB / 蓝牙 / BLE 串口 | 三种串口连接、授权、TCP 桥接与恢复流程 | FIDO2 的 USB/NFC 通道保留。 |
| USB/IP、usbredir、设备转发 | 主机 USB 导出、原始设备控制工具、远程设备转发和 guest 挂载 | `core/usb` 中 FIDO2 所需的主机枚举、授权与传输底层保留。 |
| step-ca 自动签发/续期 | `core/stepca`、配置、worker 与续期深链接 | 普通 SSH 密钥和 FIDO2 登录保留。 |
| 跨协议工作区编排 | 多资源自动启动与复杂工作区编排 | 连接分组、收藏与普通标签恢复保留。 |
| 自动拉取/同步备份 | `BackupAutoPull`、`BackupAutoSync` 和相应快捷方式、调度 | 手动加密导出/导入保留。 |
| 实验性第二套 SSH 引擎 | `sshlib` 实现、依赖与 `HavenSshEngine sshlib` 分支 | 默认 JSch 和受支持的认证路径保留。 |

## 3. Gradle 模块范围

基线有 33 个 `core`、12 个 `feature`，合计 45 个现有库模块；`app` 与 `integration-tests` 另计。

### 移除的 21 个现有库

```text
:core:knock           :core:mcp             :core:spa
:core:stepca          :core:reticulum       :core:prns
:core:btserial        :core:usbserial       :core:bleserial
:core:spice           :core:glkvm           :core:rclone
:core:mail            :core:openai          :core:local
:core:wayland         :core:ffmpeg          :core:scan
:feature:mail         :feature:chat         :feature:imagetools
```

### 保留的 24 个现有库

```text
:core:ui              :core:terminal-haven  :core:toolbar
:core:ssh             :core:security        :core:data
:core:redact          :core:tunnel          :core:mosh
:core:et              :core:vnc             :core:rdp
:core:smb             :core:fido            :core:usb
:feature:connections  :feature:terminal     :feature:sftp
:feature:keys         :feature:tunnel       :feature:settings
:feature:editor       :feature:vnc          :feature:rdp
```

通用 Shizuku 服务已迁入新 `:core:platform`，供保留的客户端流程使用。实际工程现有 **25 个库：16 个 core、9 个 feature**，另有 `app` 和 `integration-tests`。

保留模块指向上述移除库的生产依赖已解除，应用内的 Hilt、导航、Manifest、后台服务和原生构建入口已同步收窄。

## 4. 必须保留与拆开的共享实现

| 边界 | 实施处理 |
| --- | --- |
| Shizuku 与本地 Linux | 将 `WaylandSocketHelper` 的 binder/授权、shell、日志、安装、电池设置等通用职责迁入 `core:platform`。更新 HavenApp、Settings、Connections 与普通网络发现的调用；删除 Wayland socket、VM/vsock 和 guest 专属方法。保留用户授权与正常安装回退。 |
| Root 与 PRoot | Root 文件后端位于 `feature/sftp/root` 和 `transport/RootFileBackend.kt`，继续保留。PRoot、rootfs 与本地 Linux 管理按独立业务删除，不能按名称中的 root 批量处理。 |
| FIDO2 与 USB | 保留 `core:data → core:fido → core:usb` 所需认证依赖，以及 USB/NFC 权限交互。裁掉串口、USB/IP、usbredir 和 guest 功能前核对共享传输与 native 消费者。 |
| 云盘与五种 VPN 共享 Go | 删除云盘业务库 `core:rclone`，但当前 `rclone-android` included build 同时产出 VPN 使用的 `sh.haven:rclone-transport` 绑定与 `libgojni.so`，必须保留并收窄。保留 wgbridge、tsbridge、nbbridge、ovpnbridge、ocbridge；去掉 rclone/rcbridge、mailbridge 的绑定入口与仅由它们引入的依赖，按实际 import 图验证。 |
| Go 构建输出 | 调整 full/terminal 两条 gomobile bind 命令和绑定提取、JNI 库打包及任务关系；即使当前目录和 artifact 仍带 rclone 名称，也不能删除五种网络协议使用的共享运行时。OpenVPN3、OpenConnect 及各自所需的 C/C++ 构建继续保留。 |
| 远程桌面与 Agent/本地桌面 | 保留 RDP/VNC 的 session、tab、输入、剪贴板、全屏和需要的窗口/PiP 接口；迁出 `app/agent` 中仍被这些流程使用的公共帮助代码。删除 SPICE/KVM、本地桌面与 Agent 专用展示，避免整删混合目录。 |
| 普通代理与网络路由 | 保留 `HavenProxy`、`ProxyJump`、SSH 转发、SOCKS/HTTP 代理及五种隧道的配置与路由；删除敲门、SPA、Cloudflare 分支后逐条验证连接链路。 |
| 文件管理与编辑器 | 保留本地/SAF/Root/SFTP/SCP/SMB 后端和普通附件上传、编辑保存；清理 rclone、Reticulum、媒体工具与相关动作注册。Android DocumentsProvider 继续提供保留的普通文件后端。 |
| 设置与应用装配 | 清理连接类型、Hilt、导航、Manifest、worker、深链接、快捷方式、自动恢复与设置入口；保留 Root/Shizuku、FIDO2、五种网络协议和普通远程会话的对应流程。 |

## 5. 构建链与权限

- **Go 继续保留。** 云盘和邮件退出产品后，五种 VPN/组网仍需要共享 Go 运行时与绑定，不能删除整个 Go 工程。
- **Rust 继续保留。** RDP 仍依赖 `rdp-kotlin` 与 Rust；仅清理 SPICE、Prns 的绑定、产物与专用任务。
- **移除本地与媒体构建。** 清理 PRoot、Wayland、Wayvnc、UML、QEMU、FFmpeg、OCR、Reticulum/rnsh/Prns 的构建/下载/打包入口和失效缓存引用；保留终端、Mosh、ET、RDP/VNC、VPN、FIDO2 所需产物。
- **保留 Shizuku 与 Root。** 保留 Shizuku provider、查询、依赖、授权和保留用途的调用，以及 Root 文件 helper；去掉 Wayland/guest/Agent 专属入口。Root/Shizuku 均保持可选，普通远程连接照常工作。
- **保留基础与认证权限。** 网络、后台会话通知、唤醒锁、前台服务、本地文件/SAF、NFC、生物识别、USB host 按最终消费者保留。
- **移除已无消费者的设备权限。** AI/MCP、OCR、蓝牙串口移除后清理相机、定位、蓝牙、传感器、通知读取及专用前台服务；以最终合并 Manifest 和实际调用为准。后台连接通知与普通应用安装更新继续保留。

功能变体已收敛为 `full`，包含裁剪后的远程客户端。保留 arm64、x64、armv7 ABI 构建配置，本次交付 arm64 Release。原包名 `sh.haven.app`、版本名 `5.94.24`、base versionCode `949` 及 ABI 偏移均未修改。

## 6. 实施顺序与数据兼容

1. **先迁出公共实现。** 建立 `core:platform`，迁移 Shizuku；分离远程桌面与 Agent/本地环境的公共窗口、输入与认证接口。Root/FIDO2 按保留路径继续工作。
2. **收窄装配与流程。** 按第 1、2 节清理入口、连接类型、会话/文件后端注册、Hilt、后台调度、恢复与设置。保留五种网络协议的完整配置和连接流程。
3. **移除模块与原生链。** 清理 21 个库和相应 included builds、资源、native 任务；精简共享 Go 绑定，保留 RDP、VPN、USB/FIDO2 所需构建。
4. **处理旧配置与数据。** 保留数据库 version 88 的兼容读取和迁移链。删除业务的旧记录不自动启动、不重试、不触发 worker，仍可由兼容导出流程读取；首轮不通过删表、降版本或清空数据实现精简。`sshlib` 旧配置迁移到受支持引擎并验证。
5. **编译并回归保留功能。** 依赖图和 task graph 不得再触发被删构建；对保留的协议和权限做针对性验证，发布包再实际测量大小与构建耗时。

## 7. 验收范围

- SSH/Mosh/ET 的密码、普通密钥、多轮认证、FIDO2 USB/NFC、跳板、代理、转发、恢复与切网重连。
- RDP/VNC 的连接、键鼠、剪贴板、全屏、重连、后台行为与 SSH 隧道。
- SFTP/SCP/SMB、本地/SAF/Root 的上传下载、跨后端复制、冲突处理、编辑保存与文档提供者访问；Root 开关关闭后不产生 `su` 调用。
- WireGuard、OpenVPN、AnyConnect、Tailscale、NetBird 各自的导入/配置、登录/授权、连接、路由、断开、重连和原生库打包。
- Shizuku 的未安装、未启动、未授权、已授权与服务重启情况，以及日志、安装更新、电池设置和保留的网络发现路径。
- 凭据加密、主机密钥变更提示、应用锁、手动加密备份与旧配置导入；被删连接类型不会被误当作 SSH 连接。
- AI/MCP、云盘、SPICE/KVM、敲门/SPA、TOTP 管理器、本地环境等已删功能无菜单、监听端口、调度、自动恢复或专用权限残留。

本轮已完成代码裁剪、单元回归、原生编译与 APK 内容检查；最终构建、签名和校验值记录在 [REBUILD_VALIDATION.md](REBUILD_VALIDATION.md)。本机没有连接 Android 设备，尚未做真机协议连接与交互回归。
