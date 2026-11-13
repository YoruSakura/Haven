# Haven 精简客户端构建与验证

日期：2026-11-12。基线：上游 `v5.94.24` / `fe04c76ec87a87e181059e8a0a2f5258e9d38ae6`，当前分支 `sakuradev/rebuild`。

## SSHPilot 导入合并与覆盖

- 最新安装包：[haven-5.94.24-arm64-sshpilot-overwrite.apk](../artifacts/haven-5.94.24-arm64-sshpilot-overwrite.apk)，已通过无线 ADB 覆盖安装到用户的 Xiaomi 25053RP5CC（Android 16）。包名 `sh.haven.app`、版本 `5.94.24`、versionCode `9491` 和签名证书均保持不变，包含此前的导入闪退与终端键盘修复。
- 文件大小：37,458,294 字节；SHA-256：`0e3106cd828390c11f5d14eb4c82b7f4cbaa6699723e32b20569b17dbe6efa95`。
- 设置 → 导入备份的密码弹窗新增“覆盖已有的 SSHPilot 条目”，每次打开默认关闭。关闭时仅新增不存在的条目；开启时更新匹配的服务器、密码、分组和软件密钥。两种模式均保留备份中没有的本地记录，Haven 自有 `.enc` 还原逻辑保持不变。
- 覆盖沿用本地 ID，重映射跳板、分组、密钥及认证链引用，保留本地 VPN、代理和密钥安全开关。设备绑定私钥不被文件替换；备份未包含的保存密码或私钥口令会清除旧值。导入结果列出新增、覆盖或保留数量，详见 [SSHPILOT_BACKUP.md](SSHPILOT_BACKUP.md)。
- 25 项备份测试通过：原有 16 项互通用例、7 项合并/覆盖用例、2 项服务导入用例。覆盖稳定 ID、引用重映射、密码及口令更新/清除、设备密钥保护、无关本地记录保留，以及错误密码在写入前失败。服务测试使用内存仓库替身，不单独验证真实 Room 回滚。
- 真机使用 SSHPilot 原 Python 写入器生成的加密 YAML / SPBK，通过系统文件选择器验证新增、重复合并、错误密码拒绝、覆盖和覆盖后再次合并。逐次检查同一虚构服务器的地址、端口和保存密码长度；覆盖后的字段正确更新，再次关闭开关导入时保持更新值。每次弹窗的开关均默认关闭，27 字符文件密码也能正常导入。测试未建立 SSH 连接，临时服务器和手机测试文件均已删除。
- Release 构建、源文件预检、原生库检查、11 个 R8 入口、最终 APK 内容、v2/v3 签名和 16 KiB zipalign 检查通过。构建日志、包检查和本轮测试结果位于 `artifacts/sshpilot-overwrite/`。

## 终端快捷键工具栏与实体键盘

- 工具栏与键盘修复包：[haven-5.94.24-arm64-keyboard-fix.apk](../artifacts/haven-5.94.24-arm64-keyboard-fix.apk)，已通过无线 ADB 覆盖安装到用户的 Xiaomi 25053RP5CC（Android 16）。包名 `sh.haven.app`、版本 `5.94.24`、versionCode `9491` 和签名证书均保持不变。
- 文件大小：37,454,198 字节；SHA-256：`21fb1a0779728bfc642f0b38b8279ee79baac4cbe88e498fd54e549c5e8fc324`。
- 设置 → 外观与终端新增“显示终端快捷键工具栏”，默认开启。连接可用的实体键盘时自动隐藏两排快捷键；断开后按开关状态恢复。全屏也遵守此设置。检测使用 Android 的 QWERTY / hardKeyboardHidden 配置，并随配置变化更新；折叠后不可用的键盘不阻止触屏工具栏显示。
- 隐藏工具栏时释放其 Ctrl/Alt 锁定、Shift 和布局编辑状态，防止不可见的修饰键影响实体键盘输入。
- 修复 termlib 在识别修饰键前先清除选区的问题；补上本地 Ctrl+Shift+C 复制选区，通过现有智能复制流程写入安卓剪贴板，不向远端发送 Ctrl+C。普通输入、Ctrl+C 中断、选区方向键、Enter 和 Esc 保持原有行为。
- 61 项 termlib 键盘测试和 221 项终端模块回归全部通过；Release 构建、签名 v2/v3、16 KiB zipalign、11 个 R8 入口及 APK 内容检查通过。键盘测试使用 SDK 自带 CMake 构建宿主 JNI，并运行原有 ASan 检查。
- 真机验证：开关跨页面保留且可恢复；已连接的 Xiaomi Keyboard 使工具栏自动隐藏；选中文字后单按 Ctrl/Shift 仍保持选区；Ctrl+Shift+C 复制的内容与测试文字完全一致，隔离 SSH 服务端收到的终端输入为零。使用的临时测试服务不执行任何命令，测试连接已删除，ADB 转发和本地服务已关闭，开关最终保持开启。
- 本轮没有实际拔插实体键盘，断开后的恢复由配置变化逻辑处理。详细测试结果、构建日志和截图在 `artifacts/terminal-keyboard-fix/`。

## SSHPilot 导入闪退修复

- 导入闪退修复包：[haven-5.94.24-arm64-sshpilot-fix.apk](../artifacts/haven-5.94.24-arm64-sshpilot-fix.apk)，已通过无线 ADB 覆盖安装到用户连接的 Xiaomi 25053RP5CC（Android 16 / API 36）。此修复也包含在后续安装包中。
- 包名 `sh.haven.app`、版本名 `5.94.24`、versionCode `9491` 和签名证书均保持不变。
- 文件大小：37,450,102 字节；SHA-256：`99601609e215839ad351174e537d826e29f4b481fb6664a6cdc26b3ad00b0d65`。
- 崩溃根因：R8 将 SnakeYAML 的 `TypeDescription` 移入无包名空间，Android 的 `Class.getPackage()` 返回 null，静态日志器初始化访问 `getName()` 抛出 `ExceptionInInitializerError`。旧 mapping ID 为 `058b1b699c2b4012a48c794f002112974ae91628afb764385578dc62d8b468b7`，与手机崩溃栈完全一致。
- 修复：在 SSH 模块 consumer rules 中保留 `TypeDescription` 以及同样调用 `getPackage()` 的 `PropertySubstitute` 的类名；其余解析器仍可收缩和优化。未使用的 `PropertySubstitute` 允许正常移除。
- 防回归：R8 mapping 检查及最终 APK DEX 定义检查增加 `TypeDescription`；新检查能拒绝旧的闪退包，并通过修复包。
- 真机通过系统文件选择器验证：历史明文 SPBK、加密 YAML、YAML 错误密码提示、加密 SPBK。旧包用同一空 SPBK 稳定复现，修复后全部正常，应用进程保持运行。测试文件只包含空列表，没有服务器、密钥或真实凭据。
- 16 项互通测试检查通过（Gradle 复用既有测试缓存）；Release 构建、签名 v2/v3、16 KiB zipalign、11 个 R8 入口及 APK 内容检查通过。
- 本轮详细日志、真机结果及包信息在 `artifacts/sshpilot-crash-fix/`。下面保留原互通包和初次精简包的历史记录。

## SSHPilot 备份互通更新

- 最新安装包：[haven-5.94.24-arm64-sshpilot.apk](../artifacts/haven-5.94.24-arm64-sshpilot.apk)。
- 包名 `sh.haven.app`、版本名 `5.94.24`、versionCode `9491` 及原定制包签名保持不变，可覆盖上一包。
- 大小：37,450,102 字节；SHA-256：`39f5765944221a3f7b55249e36a48ab88c94c4f51cd2bff85dcb7df4c874d642`。
- 新增 SSHPilot CN 加密 YAML / SPBK 导入与兼容 YAML 导出；服务器按合并处理，不覆盖已存在条目。使用方式及范围见 [SSHPILOT_BACKUP.md](SSHPILOT_BACKUP.md)。
- SSH、数据及应用回归共 1,077 项通过，其中新增 16 项互通测试。最终 Release 构建、APK 内容检查、原生入口、R8 入口、v2/v3 签名及 16 KiB zipalign 检查通过。
- 已用 SSHPilot 的真实 Python 写入器产生测试文件，再由 Kotlin 解密；Kotlin 反向导出经原 Python 解密器、SSH 配置合并、连接仓库分组还原、凭据及密钥还原验证通过。仅使用临时目录与虚构凭据。
- 未改动 SSHPilot 的现有工作区或安装包。当前没有连接的 Android 设备，尚未对新增导入界面及真实服务器连接做手机回归。
- 新一轮构建与测试日志以 `artifacts/sshpilot-` 为前缀，原精简包及其验证记录保留如下。

## 交付 APK

- 文件：[haven-5.94.24-arm64-client.apk](../artifacts/haven-5.94.24-arm64-client.apk)
- 包名：`sh.haven.app`，保持原包名。
- 版本名：`5.94.24`，未修改；base versionCode `949`，arm64 原有 ABI 偏移后为 `9491`。
- ABI：`arm64-v8a`；最低 Android 8.0 / API 26，targetSdk 35。
- 构建类型：Release，启用 R8 与资源收缩。
- 大小：37,396,854 字节，约 35.66 MiB。
- SHA-256：`d4a3b5ac99d50ff97e9e78c00a102e047d9a7860d2b81ecf7981598c72a36644`。
- 签名：RSA 3072 位，本地持久密钥；APK Signature Scheme v2/v3 验证通过，zipalign 检查通过。
- 证书 SHA-256：`5ce20490ec53a7e09433c14785d9b0a60b1add93e9e955c8497e4eddc6eda71f`。

定制包使用本地签名，不能覆盖使用上游或 F-Droid 签名的现有安装。切换前应通过手动加密备份保留配置。后续定制包更新需继续使用本机 `/home/sakura/.local/share/haven-build/signing/` 中的同一密钥；私钥及密码未放入仓库或交付目录。

## 实际检查结果

1. 完整库单元测试和应用 arm64 Debug 单元测试通过；最新文件上传与连接边界回归也通过。当前合计 **2,008 项测试，0 失败、0 错误、0 跳过**。
2. `:app:assembleArm64FullRelease` 成功。最后一次构建与文件模块测试日志位于 `artifacts/release-build.log`。
3. 五种 VPN 的 JNI 类确实定义在最终 APK 的 DEX 中，非仅有引用或外部 jar：WireGuard、Tailscale、NetBird、OpenVPN、AnyConnect。
4. RDP JNI、五种 VPN JNI、OpenVPN3 静态归档的 ABI 与入口符号检查通过。最终 APK 仅含 arm64 原生库。
5. R8 检查的 10 个反射/JNI 入口均保留原名，包括 Root 文件 worker、RDP bitmap bridge 与五种 VPN 绑定。
6. 最终包未检出 SPICE、PRoot、Wayland、FFmpeg、OCR/ONNX、Reticulum、USB 重定向或 VM 专用 native/asset 载荷；已删 MCP/Tasker/调试等组件和相机、定位、蓝牙、麦克风权限未出现在合并 Manifest 中。
7. `git diff --check`、源文件预检、无新增受跟踪二进制检查通过；CI/构建工作流 YAML 能解析，外部 action 均固定到提交 SHA。未实际运行远程 GitHub Actions。

完整机器可读结果位于 `artifacts/apk-inspection.json`、`artifacts/tests.json` 和 `artifacts/signature.txt`，校验值位于 `artifacts/SHA256SUMS`。

## 回归覆盖

| 模块 | 单元测试数 |
| --- | ---: |
| `core/usb` | 6 |
| `core/rdp` | 33 |
| `core/security` | 89 |
| `core/ssh` | 509 |
| `core/data` | 468 |
| `core/toolbar` | 15 |
| `core/mosh` | 3 |
| `core/tunnel` | 130 |
| `core/fido` | 61 |
| `core/redact` | 10 |
| `core/ui` | 25 |
| `core/vnc` | 31 |
| `feature/connections` | 109 |
| `feature/keys` | 25 |
| `feature/rdp` | 56 |
| `feature/sftp` | 95 |
| `feature/tunnel` | 20 |
| `feature/terminal` | 221 |
| `feature/vnc` | 18 |
| `app` | 84 |

保留了 SSH/FIDO2 认证、多轮交互、跳板、端口转发、会话恢复、Root 开关隔离、文件复制/传输、RDP/VNC 输入等原有用例。删除了仅针对已裁剪业务的测试，并补了旧连接类型禁止作跳板、附件取消传播、上传文件名和文件夹路径边界用例。数据库 version 88、迁移链和旧备份兼容数据继续保留。

## 运行验证边界

本机 `adb devices` 没有连接设备，没有安装 APK 或执行真机端到端回归；也未配置远程测试服务器。各类 SSH/RDP/VNC/SMB 服务器互通、USB/NFC FIDO2 实物交互、五种 VPN 的真实登录与路由，以及 Root/Shizuku 授权仍需在目标设备上验证。单元测试、成功打包与 JNI 检查不等同于这些运行验证。

本次仅交付 arm64 APK；x64/armv7 的构建配置保留，但未在本机执行构建。
