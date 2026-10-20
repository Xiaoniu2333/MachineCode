# MachineCode

MachineCode 是一套 Minecraft 客户端机器码采集 + Velocity 通道桥接 + Paper 后端查询/封禁系统。

- 客户端：Fabric（Loader 0.19.5）、NeoForge 21.11.48、Forge 61.2.4
- 代理：Velocity 4.2（通道桥接，不注册任何指令）
- 后端：Paper 1.21.11
- 语言/字节码：Java 21
- 数据：SQLite

> 说明：`com.Xiaoniu2333.mlky.machinecode` 是本项目的基础包名（主包）。各平台入口类如下：
>
> - Paper：`com.Xiaoniu2333.mlky.machinecode.paper.MachineCodePlugin`
> - Velocity：`com.Xiaoniu2333.mlky.machinecode.velocity.MachineCodeVelocityPlugin`
> - Fabric：`com.Xiaoniu2333.mlky.machinecode.fabric.MachineCodeClient`
> - NeoForge：`com.Xiaoniu2333.mlky.machinecode.neoforge.MachineCodeNeoForge`
> - Forge：`com.Xiaoniu2333.mlky.machinecode.forge.MachineCodeForge`

## 1. 工作流程

```text
Fabric 客户端                        Velocity                        Paper 后端
     |                                  |                                |
     | 1. 启动时采集/校验/哈希机器码      |                                |
     | 2. 玩家正常进服（不阻塞登录）      |                                |
     | 3. 进服后异步重新采集             |                                |
     |---- machinecode:main ----------->| 4. 通道注册、附加代理侧 UUID     |
     |                                  |--- machinecode:main ---------->| 5. 解析、写 SQLite
     |                                  |                                | 6. 查询封禁状态
     |                                  |                                | 7. 已封禁 -> 踢出
     |                                  |                                |    未上报 -> 30 秒后踢出
```

关键点：

- 客户端第一次采集发生在客户端启动阶段；失败会弹系统窗口并崩溃。
- 玩家进入服务器时会在后台线程再次采集。采集完成前玩家已经进入服务器，**不阻塞登录/世界加载**。
- Velocity 只负责 `machinecode:main` 插件的消息通道和转发。它不注册指令、不存储数据。
- Velocity 转发时会把玩家 UUID 写在消息头。后端会校验该 UUID 与 Bukkit 玩家一致，客户端无法用消息体伪造归属。
- 后端收到数据后异步写 SQLite，再判断封禁。封禁判断不阻塞登录线程。

## 2. 目录结构

```text
machinecode/
├── common/       客户端/服务端共享的协议、平台枚举、机器码校验与哈希、硬件采集
├── fabric/       Fabric 客户端 Mod
├── neoforge/     NeoForge 21.11 客户端 Mod
├── forge/        Forge 61.x 客户端 Mod
├── velocity/     Velocity 通道桥接插件
├── paper/        Paper 后端插件、SQLite、指令、封禁、30 秒容错
├── docs/         协议与部署说明
└── dist/         构建产物（构建后生成）
```

## 3. 构建

要求：

- JDK 21（Fabric Loom 1.17.21、Fabric 客户端与 Paper 插件；项目字节码目标为 Java 21）
- JDK 25（仅用于 `velocity` 模块编译；Velocity 4.2 的 API 本身是 Java 25 字节码）
- 首次构建需要访问 Fabric Maven、PaperMC Maven、NeoForged Maven、MinecraftForge Maven、Maven Central
- NeoForge/Forge 首次构建会下载并反编译 Minecraft，可能耗时 10 分钟以上
- `JAVA_HOME` 可以指向 JDK 21 或 25，Gradle 工具链会自动定位已安装的另一版本

```powershell
cd machinecode
$env:JAVA_HOME = "C:\Program Files\Java\jdk-21"
.\gradlew.bat build
```

如果 Forge Maven 暂时不可用，可先跳过 Forge 模块构建服务端/Fabric/NeoForge：

```powershell
.\gradlew.bat build -PskipForge
```

单独构建：

```powershell
.\gradlew.bat :paper:jar
.\gradlew.bat :paper:thinJar
.\gradlew.bat :velocity:jar
.\gradlew.bat :fabric:build
.\gradlew.bat :neoforge:build
.\gradlew.bat :forge:build
```

产物：

```text
paper/build/libs/MachineCode-Paper-1.0.0.jar        # 含 SQLite 驱动的完整包
paper/build/libs/MachineCode-Paper-1.0.0-thin.jar   # 不含第三方依赖的轻量包
velocity/build/libs/MachineCode-Velocity-1.0.0.jar
fabric/build/libs/MachineCode-Fabric-1.0.0.jar
neoforge/build/libs/MachineCode-NeoForge-1.0.0.jar
forge/build/libs/MachineCode-Forge-1.0.0.jar
```

Forge/NeoForge 首次构建会下载并反编译 Minecraft/加载器，耗时明显长于 Fabric；之后会复用 Gradle/NFRT 缓存。

`dist/` 中的文件是本次已构建好的副本。

### Velocity 版本说明

- 插件使用 Velocity 4.2.0 API（Build 30）编译。
- Velocity 4.2 运行本身要求 Java 25，因此实际代理进程必须使用 Java 25；插件 class 文件仍是 Java 21，可运行在 Java 25 上。
- 若必须让代理运行在 Java 21 上，请把 Velocity 换成支持 Java 21 的 3.x，并使用对应 API 重新编译；本项目不强制锁定代理小版本。

## 4. 安装

1. 把 `MachineCode-Velocity-1.0.0.jar` 放进 Velocity 的 `plugins/`。
2. 把 Paper 插件放进每个 Paper 子服的 `plugins/`，二选一：
   - `MachineCode-Paper-1.0.0.jar`：自带 SQLite 驱动，约 14 MB，最稳妥；
   - `MachineCode-Paper-1.0.0-thin.jar`：约 60 KB，不含 SQLite/第三方依赖，
     使用 Paper 1.21.11 自带的 `org.xerial:sqlite-jdbc:3.49.1.0`。
     仅适用于 Paper（Spigot 等不自带该驱动的服务端请用完整包）。
3. 按客户端加载器**三选一**放进 `mods/`（不要同时安装多个，它们使用同一个 mod id）：
   - Fabric：`MachineCode-Fabric-1.0.0.jar` + Fabric API 0.141.6+1.21.11
   - NeoForge：`MachineCode-NeoForge-1.0.0.jar`
   - Forge：`MachineCode-Forge-1.0.0.jar`
4. 让 Paper 后端只接受来自 Velocity 的连接（防火墙/绑定回环地址），不要让玩家绕过代理直连后端，否则客户端可以直接发送任意插件消息。
5. 重启代理与后端。

Fabric 依赖：

```json
{
  "fabricloader": ">=0.19.5",
  "minecraft": "~1.21.11",
  "fabric-api": "*",
  "java": ">=21"
}
```

## 5. 配置（Paper）

首次启动生成 `plugins/MachineCode/config.yml`。

```yaml
database:
  file: "machinecode.db"

enforcement:
  enabled: true
  grace-seconds: 30
  timeout-kick-message: |-
    &c未在 %seconds% 秒内接收到你的机器码。
    &7请安装并启用 MachineCode 客户端 Mod 后重新连接。
  banned-kick-message: |-
    &c你已被封禁。
    &7机器码: &f%machine_code%
    &7原因: &f%reason%

query:
  show-ban-line-when-not-banned: false
  player-line: "&e玩家ID: &f%player%"
  ip-line: "&e玩家IP: &f%ip%"
  machine-code-line: "&e机器码: &f%machine_code%"
  platform-line: "&e系统平台: &f%platform%"
  ban-line: "&e封禁信息: &f%status%"
  ban-status-yes: "&c已封禁"
  ban-status-no: "&a正常"
  unknown-value: "&7未获取"

lookup:
  machine-code-line: "&e机器码: &f%machine_code%"
  platform-line: "&e系统平台: &f%platform%"
  players-line: "&e登录过的游戏ID: &f%players%"
  no-players: "&7无记录"
```

- `enforcement.enabled`：是否强制收到机器码。
- `enforcement.grace-seconds`：容错时间，默认 30 秒。玩家先正常进服，超时仍未收到报告才踢出。
- `timeout-kick-message`：未上报的踢出提示，支持 `%player%`、`%seconds%`。
- `banned-kick-message`：封禁踢出提示，支持 `%player%`、`%machine_code%`、`%reason%`。
- `notify-missing-code.enabled`：仅在 `enforcement.enabled: false` 时生效。玩家未上报机器码时，
  每隔 `interval-seconds` 秒发送一次 `message`；收到机器码或玩家退出后自动停止。
- `notify-missing-code.interval-seconds`：默认 60 秒。
- `notify-missing-code.message`：提示文本，默认 `&e请使用官方客户端进行游玩`，支持 `&` 颜色代码。
- `query.show-ban-line-when-not-banned`：默认 `false`。第五行只在封禁时显示；设为 `true` 时未封禁也会显示“正常”。
- 修改配置后执行 `/mcode reload`。数据库路径修改需要重启。

对应配置片段：

```yaml
notify-missing-code:
  enabled: true
  interval-seconds: 60
  message: "&e请使用官方客户端进行游玩"
```

## 6. 指令

所有指令只在 Paper 后端注册，Velocity 不注册指令。

### `/machinecode` / `/mcode`

别名完全等价。

| 用法 | 说明 | 权限 |
|---|---|---|
| `/mcode` | 查询自己 | `machinecode.query` |
| `/mcode <玩家名>` | 查询该玩家当前机器码 | `machinecode.query` |
| `/mcode <64位机器码>` | 反查使用过该机器码的所有游戏 ID | `machinecode.lookup` |
| `/mcode ban <机器码>` | 封禁机器码（也支持在线玩家名/数据库玩家名） | `machinecode.ban` |
| `/mcode unban <机器码>` | 解除机器码封禁 | `machinecode.ban` |
| `/mcode reload` | 重新加载 config.yml | `machinecode.reload` |

玩家查询输出（固定前四行，第五行按封禁状态显示）：

```text
玩家ID: Steve
玩家IP: 203.0.113.10
机器码: 5f0c...（64 位小写十六进制）
系统平台: Windows
封禁信息: 已封禁
```

反查输出（固定三行）：

```text
机器码: 5f0c...（64 位小写十六进制）
系统平台: Windows
登录过的游戏ID: Steve, Alex
```

玩家尚未上报时：

```text
玩家ID: Steve
玩家IP: 203.0.113.10
机器码: 未获取
系统平台: 未获取
```

### PlaceholderAPI 变量

PlaceholderAPI 为可选依赖（`softdepend`，开发/实测使用 2.11.6）。安装 PlaceholderAPI 后，
MachineCode 会自动注册 identifier 为 `machinecode` 的 expansion；未安装时插件不会加载 PAPI 类，
其余功能正常。

| 变量 | 返回值 |
|---|---|
| `%machinecode_machinecode%` | 机器码；未获取为空 |
| `%machinecode_code%` | 机器码别名 |
| `%machinecode_platform%` | `Windows` / `Linux` / `macOS` |
| `%machinecode_ip%` | 当前/最后 IP |
| `%machinecode_received%` | `True` / `False` |
| `%machinecode_received_cn%` | `是` / `否` |

默认使用 PlaceholderAPI 传入的当前玩家。也可以直接在变量后追加玩家 ID，查询指定玩家：

```text
%machinecode_machinecode_Steve%
%machinecode_code_Steve%
%machinecode_platform_Steve%
%machinecode_ip_Steve%
%machinecode_received_Steve%
%machinecode_received_cn_Steve%
```

说明：

- 在线玩家优先使用本会话已上报的数据；本会话尚未上报时，`received` 为 `False`。
- 离线玩家从 SQLite 缓存读取最后一次记录；服务端启动时异步预热缓存，PAPI 请求不会查库。
- 未获取的平台/IP/机器码返回空字符串，用 `received` 判断是否有效。
- 已实测：`Player134` 返回机器码、`Windows`、`127.0.0.1`、`True`、`是`。

## 7. SQLite 数据结构

主表 `machinecode_players` 按需求严格为五列，顺序如下：

| 列序 | 列名 | 说明 |
|---|---|---|
| 1 | `player_name` | 玩家 ID |
| 2 | `player_uuid` | 玩家 UUID |
| 3 | `platform` | 系统平台 `WINDOWS` / `LINUX` / `MACOS` |
| 4 | `machine_code` | 64 位 SHA-256 机器码 |
| 5 | `banned` | `true` / `false` |

主键为 `(player_uuid, machine_code)`，因此：

- 一个玩家换硬件会保留历史，反查仍能找到旧机器码。
- 一个机器码可以被多个游戏 ID 使用，反查能列出全部。
- 查询“当前机器码”时按 SQLite 的 `rowid DESC` 取最近一次上报。

辅助表：

- `machinecode_ips`：保存最近 IP（IP 不属于需求指定的五列，因此单独存放）。
- `machinecode_bans`：保存机器码封禁，使“先封禁、后出现”的机器码也会被拦截。

## 8. 客户端机器码采集

### Windows

优先使用非管理员方式执行：

```powershell
(Get-CimInstance -ClassName Win32_BIOS).SerialNumber
```

即 WMI/CIM 的 `Win32_BIOS.SerialNumber`。之后依次回退：

1. `wmic bios get serialnumber /value`
2. 注册表 `HKLM\HARDWARE\DESCRIPTION\System\BIOS` 的 `SystemSerialNumber`

不会申请管理员权限，也不会调用 `runas`。

### Linux

依次读取：

1. `/sys/class/dmi/id/product_uuid`
2. `/sys/class/dmi/id/board_serial`
3. `/sys/class/dmi/id/product_serial`
4. `/etc/machine-id`
5. `/var/lib/dbus/machine-id`
6. `dmidecode -s system-uuid`（失败不影响，部分系统需要 root）

### macOS

依次读取：

1. `ioreg -rd1 -c IOPlatformExpertDevice` 的 `IOPlatformUUID`
2. `ioreg` 的 `IOPlatformSerialNumber`
3. `system_profiler SPHardwareDataType` 的 `Serial Number (System)`
4. `sysctl -n kern.uuid`

### 校验与哈希

原始值先经过 `MachineCodeValidator`：

- 空值、过短、过长
- `Default string`、`To be filled by O.E.M.`、`Unknown`、`N/A` 等占位值
- 全 0、全相同字符
- 不含字母/数字

校验失败会抛出异常，不会退化成随机值或空机器码。

通过校验后：

```text
machineCode = SHA-256("MachineCode|v1|" + platform + "|" + normalizedSerial)
```

输出 64 位小写十六进制。服务器永远收不到原始 BIOS 序列号/UUID。

### 崩溃行为

采集失败时：

1. 弹出系统窗口，文本为：
   ```text
   请使用管理员模式启动
   如仍然异常，请发送错误报告
   ```
2. 抛出异常，使 Minecraft 生成崩溃报告。

启动时和每次进入服务器时都会重新采集；每次进入服务器采集失败同样走上述崩溃流程。

## 9. 测试

```powershell
.\gradlew.bat :common:test :paper:test
```

覆盖：

- 占位/全零/无效硬件值拒绝
- 哈希稳定性与平台隔离
- 协议编解码、UUID 头、版本校验
- SQLite 五列顺序
- 双向查询
- 先封禁后上报
- 解封
- 换硬件保留历史，以及 A → B → A 后“当前机器码”仍为 A

本次实际验证：

- Windows 实机采集成功（WMI `Win32_BIOS.SerialNumber`，输出 64 位哈希和平台信息，多次启动结果一致）。
- Paper `1.21.11-132` 成功加载 `MachineCode 1.0.0`，SQLite 建表成功，RCON 执行封禁命令后日志记录
  `Banned machine code aaaaaaaa... by Rcon`，封禁记录写入 `machinecode_bans`。
- Velocity `4.2.0-30`（Java 25）成功加载插件并输出
  `MachineCode Velocity bridge registered channel machinecode:main`。
- Fabric `1.21.11` 使用 Yarn `1.21.11+build.6` 完成编译、重映射，产物 class 文件被重映射为 intermediary。
- **完整链路实测**：Fabric 客户端由 `--quickPlayMultiplayer` 自动连接 Velocity，
  Velocity 转发到 Paper；客户端进服约 1 秒后完成第二次采集并发送报告，
  Paper 写入：
  `Player134 | WINDOWS | dd89c24a... | false`，30 秒后未被踢出。
- **封禁实测**：把该机器码写入封禁表后重新进入，客户端收到：
  ```text
  你已被封禁。
  机器码: dd89c24a...
  原因: 机器码封禁
  ```
- **Paper thin 包实测**：`MachineCode-Paper-1.0.0-thin.jar`（60 KB，无 SQLite 依赖）
  在 Paper `1.21.11-132` 上成功加载，使用服务器自带的 `sqlite-jdbc-3.49.1.0` 建库并启用 30 秒容错。
- **NeoForge 实测**：`21.11.48` 开发客户端成功加载 MachineCode，
  输出 `MachineCode initial fingerprint ready` 和
  `MachineCode NeoForge client initialized (1.0.0)`。
- **Forge 构建实测**：`1.21.11-61.2.4` 模块编译、打包成功。开发客户端启动时
  Minecraft 资源 CDN 返回 HTTP/2 `GOAWAY`，资源未下载完，因此本环境没有完成 Forge 进服联调。

客户端系统弹窗、Linux/macOS 采集路径和“采集失败崩溃”无法在不破坏当前机器的前提下自动触发，
需要在对应平台上按第 8 节做最终人工验证。

## 10. 安全边界与已知限制

- **机器码不是反作弊**。它只能识别硬件标识，无法阻止虚拟机、硬件伪造、修改后的客户端或直接构造插件消息。
- 必须保证后端端口只对代理开放。Bukkit 端相信 Velocity 写入的 UUID；如果玩家能绕过代理直连 Paper，就能伪造报告。
- Windows 的 BIOS 序列号可能被主板厂商写成占位值，此时客户端会按设计崩溃并提示，而不是发送错误机器码。
- 客户端采集是异步的，进入服务器后最多约 24 秒完成（Windows 三条探测各 6 秒上限）；服务端默认 30 秒容错。低配机器如经常超时，可把 `grace-seconds` 调大。
- 机器码与平台绑定。更换操作系统会导致机器码变化。
- 查询中的“玩家IP”是 Paper 后端看到的地址。Velocity 的 `player-info-forwarding-mode` 为 `NONE` 时会显示代理地址；需要真实玩家 IP 时请配置 `modern` 转发并做好 `forwarding.secret` 保护。
- 多 Paper 子服架构下，每个后端各自维护自己的 SQLite。若需要跨服统一查询/封禁，应共享同一个数据库服务（当前版本按单后端/单库设计）。
- 客户端 Mod 为客户端环境专用；没有安装 Mod 的玩家在 `enforcement.enabled: true` 时会被 30 秒后踢出。

## 11. Forge / NeoForge

两个加载器现在都有正式构建，复用 `common` 的采集、校验、哈希和协议：

- **NeoForge 21.11.48**
  - `@Mod(dist = Dist.CLIENT)`，通过 `RegisterPayloadHandlersEvent` 注册
    `playToServer` 的 optional payload；
  - 进服用 `ClientPlayerNetworkEvent.LoggingIn`；
  - 发送用 `ClientPacketDistributor.sendToServer(...)`；
  - 开发客户端已成功加载 Mod 并完成启动采集。
- **Forge 1.21.11-61.2.4**
  - `mods.toml` 标记 `clientSideOnly = true`；
  - 使用 Forge 1.21.11 的 `ChannelBuilder`/`SimpleChannel`，通道为 optional；
  - 进服用 `ClientPlayerNetworkEvent.LoggingIn`，发送用 `CHANNEL.send(payload, connection)`；
  - 编译和打包通过；本环境启动开发客户端时资源 CDN 中断，未完成进服运行验证。

与 Fabric 的差异：

- Fabric 端使用 `ClientPlayNetworking.canSend` 检查通道，未声明时不泄露机器码。
- Forge/NeoForge 通过 Velocity 时，代理不会完成加载器级的 mod-channel 协商，
  因此这两个实现完成采集后直接发送标准 custom payload，不再等待加载器级“通道已声明”状态。
  如果玩家用它连接其他服务器，服务端只会收到一个无法识别的 `machinecode:main` 数据包；
  如不接受该行为，可改为在发送前检查服务端品牌/通道，但会导致部分代理组合无法工作。

## 12. 许可

MIT。见 `LICENSE`。
