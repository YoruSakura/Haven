# SSHPilot CN 与 Haven 互导服务器

Haven 的「设置 → 加密备份 → 导入备份」现在自动识别：

- Haven 自有的 `.enc` 加密备份，沿用原还原逻辑。
- SSHPilot CN「特殊设置」导出的加密 YAML。
- SSHPilot 的 `.spbk`，包括加密文件和未加密的历史文件。

SSHPilot 文件可导入 SSH 服务器、分组、保存的登录密码、私钥及私钥口令。
在「导入备份」的密码弹窗中，用「覆盖已有的 SSHPilot 条目」开关选择本次模式：

- **关闭（默认，合并）**：仅新增不存在的条目，匹配的服务器、密钥和密码保持 Haven 现有内容。
- **开启（覆盖）**：新增不存在的条目，并用备份更新匹配的服务器、密码、分组和软件密钥。备份中缺失的密码或私钥口令也会清除匹配记录原有的保存值。

两种模式都保留备份中没有的本地记录。覆盖沿用已有记录的 ID，重映射跳板、分组、
密钥和认证链的引用；保留匹配服务器的 VPN、代理等手机专用设置，以及已有密钥的
生物识别和自动认证开关。设备绑定私钥不会被可迁移文件替换。导入凭据使用记录自身
的登录信息，防止本地共用登录配置遮盖备份密码。

服务器优先按稳定 ID 匹配，否则要求名称、地址、端口及用户名全部相同；分组按 ID
或名称匹配，密钥按类型及指纹匹配。未匹配上的条目作为新增处理。
结果分别列出新增、覆盖或保留的数量及兼容性说明。每次打开导入弹窗时开关默认关闭，
不沿用上一次的覆盖选择。此开关只对 SSHPilot 文件生效，Haven 自有 `.enc` 沿用原还原逻辑。

## 密码与 PAM

PAM 是桌面端导出前验证当前用户身份、解锁 GNOME Keyring 的机制，不是备份文件
格式的一部分。Android 直接以文件密码执行 scrypt 和 AES-256-GCM 解密，不调用 PAM。

SSHPilot CN 特殊备份的密码是**导出当时输入的桌面登录密码**。之后即使改过系统
登录密码，旧备份仍需原来的密码。它不是手机锁屏密码。SSHPilot `.spbk` 则使用当时
设置的独立备份密码；只有未加密的历史 `.spbk` 可以留空。

## Haven → SSHPilot CN

1. 在 Haven 的「设置 → 加密备份」选择「导出 SSH 服务器供 SSHPilot 使用」。
2. 设置文件密码，保存 `haven-sshpilot-servers.yaml`。
3. 将文件传到电脑，在 SSHPilot CN「特殊设置 → 还原完整服务器备份」中打开。
4. 输入上一步的文件密码，保持默认的「合并」模式。

此方向复用 SSHPilot CN 原有的 YAML 读取、预览和还原流程，无需更换或修改桌面包。
Haven 原有的全量备份入口继续使用 `.enc`，用于手机自身的完整备份。

导出的私钥使用虚拟源目录 `/home/haven-portable/.ssh/haven/`，桌面现有的路径迁移
逻辑会同时将 `IdentityFile`、密钥文件和钥匙环中的私钥口令重定位到当前用户家目录。
文件密码可自行设置；从 Haven 导出不需要桌面登录密码。

## 字段与边界

| 内容 | 处理 |
| --- | --- |
| SSH 别名、显示名称、目标地址、端口、用户 | 双向转换，使用稳定标识避免重复导入 |
| 密码、普通私钥、私钥口令 | 双向转换；密钥在 Haven 中仍通过原仓库存储加密保护 |
| 只有加密私钥、没有其口令 | 有公钥信息时仍可导入，连接时再询问私钥口令 |
| 共用登录配置的密码 | 按显式绑定或分组继承关系取出，再保存到对应服务器；备份缺少该密码时提示 |
| 分组 | 双向转换；Haven 没有分组树，嵌套组用 `父组 / 子组` 表示，多组成员取首组 |
| ProxyJump | 导入已有别名及 `user@host:port`、IPv6、多级跳板；导出为 SSH 别名引用 |
| Include / Host 通配规则 | 只读取备份内嵌的配置文本，保留首个匹配值的语义；不读取手机本地文件 |
| Keepalive、算法选项、ForwardAgent、RemoteCommand、PTY | 转换受支持的常用项 |
| Match、ProxyCommand、本地命令、端口转发、平台专用选项 | 不执行；未迁移项在结果中提示，连接前需检查 |
| known_hosts | 不自动信任桌面主机记录，首次连接时在 Haven 中核对主机指纹 |
| FIDO2 OpenSSH 文件 | 可从 SSHPilot 导入未加密的句柄文件，仍需同一把实体安全密钥；Haven 侧句柄暂不反向导出 |
| Android 硬件绑定私钥 | 不可导出；结果提示，不产生虚假的可迁移私钥 |
| RDP/VNC/SMB、其他桌面协议、VPN、桌面/手机设置 | 不进入此次 SSH 互导；继续用各自原有的完整备份 |

## 文件加密与输入限制

特殊 YAML 与 SSHPilot CN `special_backup.py` 保持一致：scrypt `N=32768, r=8,
p=1`，16 字节随机盐，32 字节密钥，AES-256-GCM，12 字节随机 nonce，128 位 tag。
除 `payload` 以外的外层元数据按 Python 的 `ensure_ascii=False, sort_keys=True,
separators=(",", ":")` JSON 序列化后作为 AAD。Haven 写出 JSON 表示的 YAML，
SSHPilot 的现有 PyYAML 读取器可直接读取。

`.spbk` 按 `SPBK1\n + 原始 JSON 头 + \n + 密文 ZIP` 读取，AAD 使用原始头字节，
不能重排键再认证。只从 ZIP 读取 `manifest.json`，不解压到文件系统。

文件和展开后的载荷均限制为 64 MiB；YAML 禁用自定义对象和所有别名，限制嵌套深度，
拒绝重复键。SPBK 在执行 scrypt 前限制参数，Android 内存上限为 64 MiB；默认参数
占约 32 MiB。错误密码或篡改必须在任何数据库写入之前失败。解析后使用 Room
事务完成合并；数据库版本、包名、版本号及原 `.enc` 格式没有改动。

## 验证

正式包必须运行 `scripts/check-r8-kept-classes.sh` 和
`scripts/check-client-apk.py`。SnakeYAML 2.6 的 `TypeDescription` 会在静态日志器
初始化中调用 `Class.getPackage().getName()`；移入无包名空间后，Android 返回 null，
首次读取 YAML 就会闪退，JVM 单元测试无法覆盖这个 R8 与 Android 的组合。
SSH 模块的 consumer rules 保留其包名，并同样保护 `PropertySubstitute`；最终
DEX 检查确认 `TypeDescription` 类以原名称存在。

2026-11-11 已在无线 ADB 连接的 Android 16 设备上，用正式签名包通过系统文件
选择器验证明文 SPBK、加密 YAML、错误密码提示及加密 SPBK。测试载荷为空列表，
不添加服务器、分组、密钥或凭据；手机临时测试文件在验证后删除。

2026-11-12 在同一设备上验证合并/覆盖开关：首次新增一条虚构服务器，重复合并保留
原地址、端口和密码，开启覆盖后更新同一条记录，再次合并保留更新值。每次打开弹窗
默认关闭覆盖；错误密码拒绝导入且已有字段不变；27 字符文件密码导入成功。
测试未建立 SSH 连接，虚构服务器和手机测试文件均已删除。

专用测试位于 `core/ssh/src/test/kotlin/sh/haven/core/ssh/backup/`。测试数据全部是
虚构域名、密码及一次性密钥，由实际 SSHPilot Python 写入器生成，不包含用户数据。

```sh
/tmp/haven-gradle.sh :core:ssh:testDebugUnitTest --tests 'sh.haven.core.ssh.backup.*'
/home/sakura/localproject/SSHPilot/artifacts/.test-venv/bin/python \
  scripts/sshpilot-interop-fixtures.py /home/sakura/localproject/SSHPilot verify
```

Python 验证运行 SSHPilot 原有解密器、SSH 配置合并、连接仓库分组还原、凭据还原和
私钥写入器；目标全部在临时目录，钥匙环用内存替身，不触碰真实服务器或用户配置。
