# 当前客户端功能

此分支已从上游完整工作区产品裁剪为普通远程客户端。上游功能分析保留在 [REBUILD_MODULES.md](REBUILD_MODULES.md)，当前裁剪边界以 [REBUILD_PLAN.md](REBUILD_PLAN.md) 为准。

- **终端：** SSH、Mosh、Eternal Terminal，多标签、重连、后台会话与常用会话管理器。
- **终端输入：** 可手动隐藏快捷键工具栏，连接实体键盘时自动隐藏；选区支持 Ctrl+Shift+C 本地复制。
- **远程桌面：** RDP、VNC，键鼠、剪贴板、缩放、全屏与 SSH 隧道。
- **文件：** SFTP/SCP、SMB、本地、SAF、可选 Root，上传下载、多选、复制移动、冲突处理与文本编辑。
- **网络：** SSH 跳板、端口转发、SOCKS/HTTP 代理，以及 WireGuard、OpenVPN、AnyConnect、Tailscale、NetBird。
- **认证与安全：** 密码、普通 SSH 密钥、USB/NFC FIDO2、交互式认证、主机密钥验证、应用锁与凭据加密。
- **Android：** 可选 Shizuku、显式启用的 Root 文件访问、后台通知和电池设置、手动加密备份与恢复。
- **备份互通：** 导入 SSHPilot 加密 YAML / SPBK，支持合并或覆盖匹配记录；也可导出 SSH 服务器供 SSHPilot 使用。

AI/MCP、云盘、SPICE/KVM、敲门/SPA、独立 TOTP、本地 Linux/VM、邮件、Reticulum、媒体处理与设备转发均已退出当前客户端。

编译、单元测试和打包检查结果见 [REBUILD_VALIDATION.md](REBUILD_VALIDATION.md)。未连接 Android 设备时，构建与单元测试不能替代各协议的真机连接回归。
