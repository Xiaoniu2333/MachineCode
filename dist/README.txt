MachineCode 1.0.0

服务端：
1. MachineCode-Velocity-1.0.0.jar -> Velocity/plugins/
2. Paper 插件 /plugins/（二选一）：
   完整包 MachineCode-Paper-1.0.0.jar（自带 SQLite，约 14MB）
   轻量包 MachineCode-Paper-1.0.0-thin.jar（约 60KB，使用 Paper 自带 SQLite）

客户端（按加载器三选一，切勿同时安装多个）：
3. Fabric 1.21.11:
   MachineCode-Fabric-1.0.0.jar -> mods/
   同时安装 Fabric API 0.141.6+1.21.11
4. NeoForge 1.21.11:
   MachineCode-NeoForge-1.0.0.jar -> mods/
5. Forge 1.21.11:
   MachineCode-Forge-1.0.0.jar -> mods/

运行环境：
- Paper 1.21.11 / Java 21
- Velocity 4.2.0-30 / Java 25（Velocity 4.2 自身要求 Java 25）
- Fabric Loader 0.19.5 / Java 21
- NeoForge 21.11.48 / Java 21
- Forge 1.21.11-61.2.4 / Java 21

首次启动 Paper 后编辑 plugins/MachineCode/config.yml。
enforcement.enabled=false 时，可通过 notify-missing-code 配置每分钟提醒未上报机器码的玩家。
指令只在 Paper：/machinecode 或 /mcode。
可选安装 PlaceholderAPI，变量：%machinecode_machinecode%、
%machinecode_platform%、%machinecode_ip%、%machinecode_received%、
%machinecode_received_cn%；支持追加 _玩家ID 查询指定玩家。

完整说明见 ../README.md，协议见 ../docs/PROTOCOL.md。
SHA256 校验值见 SHA256SUMS.txt。
