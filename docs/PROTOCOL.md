# MachineCode 协议

## 通道

```text
machinecode:main
```

Velocity 在启动时通过 `ChannelRegistrar` 注册该通道；Paper 通过
`Messenger.registerIncomingPluginChannel` 接收。Velocity 不注册指令。

## 客户端 -> Velocity

单字节协议版本开始，随后是 Java `DataOutputStream.writeUTF` 字段：

| 偏移 | 类型 | 字段 | 说明 |
|---|---|---|---|
| 0 | u8 | version | 当前为 `1` |
| 1 | UTF | machineCode | 64 位小写十六进制 SHA-256 |
| ... | UTF | platform | `WINDOWS` / `LINUX` / `MACOS` |
| ... | UTF | osName | 如 `Windows 11` |
| ... | UTF | osVersion | 如 `10.0` |
| ... | UTF | architecture | 如 `amd64` |
| ... | UTF | modVersion | 如 `1.0.0` |

整体不超过 4096 字节。字段超过 128 字符、含 NUL、机器码格式错误、平台未知、
版本不匹配都会被拒绝。

Fabric 的 payload codec **不使用** `writeByteArray`，因为那会在 payload 内部再写一个
VarInt 长度，导致 Velocity 看到的第一个字节不是协议版本。当前实现使用
`buffer.writeBytes(data)` 原样写入。

## Velocity -> Paper

Velocity 在原始 payload 前加 16 字节 UUID（大端，两个 `long`）：

```text
0..7    player UUID most significant bits
8..15   player UUID least significant bits
16..    unchanged client payload
```

Paper 校验：

1. 长度在 17..4112 字节之间；
2. 头部 UUID 与 Bukkit 提供的发送者 UUID 一致；
3. 内层协议、机器码和平台有效。

由于 UUID 由代理写入，客户端无法通过消息体把报告归到其他玩家名下。但能直连
Paper 后端的攻击者仍可绕过代理，所以后端必须只对 Velocity 开放。

## 30 秒容错时序

```text
t=0     PlayerJoinEvent，安排 30 秒任务，不阻塞
t=0..   Fabric 后台线程重新采集；采集完成后客户端发送
t=n     后端收到；标记已接收、取消任务；写 SQLite；检查封禁
t=30    若仍未收到 -> 按 config 中 timeout-kick-message 踢出
```

如果报告在 t=30 前到达但数据库操作稍有延迟，`received` 在解析时已经置位，
超时任务不会误踢。
