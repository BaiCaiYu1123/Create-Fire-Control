# APFSDS 在 Sable 结构上的斜射抖动与分段尾迹修复

2026-11-17 另修复了精确飞行切回停弹同步时的相对坐标基准错误，实机复现与
验证见 [停弹位置同步记录](APFSDS_STOP_SYNC_2026-11-17.md)。

交付包：`build/libs/create-fire-control-0.8.1-apfsds-sable-motion-fix.jar`。

## 原因与修复

真实 Sable 炮台测试表明，服务端出膛后的世界坐标弹道正常，问题出在出生朝向及客户端运动同步。

普通 `ClientboundSetEntityMotionPacket` 会把速度每个分量分别截断到 ±3.9 格/tick，
从而改变高速斜射炮弹的速度和方向。客户端沿错误方向推进并生成尾迹，再被位置包纠正，
形成横向抽动和倾斜的分段尾迹。CBC 的完整出生数据也会被随后发送的普通速度包覆盖。

第一版仅把 RPL 标签从错误的 `tags/entity_types` 迁移到 Minecraft 1.21.1 使用的
`tags/entity_type`。进一步检查发现，当前依赖的 RPL 2.1.2 NeoForge JAR 虽然包含
`ServerEntityMixin`，却未注册其 Mixin 配置；真实服务器日志也确认该 Mixin 未加载，
因此仅修正标签仍没有发出精确运动包。

本轮修复包含：

- `ApfsdsServerEntityMixin` 明确通过 RPL 已注册的网络通道发送完整世界位置和速度，
  在 Sable 临时转换追踪坐标之前采样。按包类型过滤自由飞行 APFSDS 的普通移动、
  传送和截断速度包，避免竞争同步，也不依赖容易随 Minecraft 版本变化的调用序号。
- 出生配对数据在实体创建和 CBC 完整出生数据之后追加精确运动包，过滤普通配对速度包。
  APFSDS 同时忽略客户端 `lerpMotion` 的截断速度；完整出生数据及 RPL 包直接设置完整速度。
- Sable 出膛交接转换了速度，但 CBC 的独立朝向可能仍为空或陈旧。自由飞行 APFSDS
  的 `getOrientation()` 使用最终世界速度，令弹体渲染和出生朝向与飞行方向一致。
  落地时仍使用 CBC 存储的接触朝向。
- 保留正确的 `entity_type/precise_motion.json` 标签和 `replace: false`。

其他实体、落地炮弹、弹速倍率、重力、阻力、穿透和跳弹沿用原有实现。
已有尾迹代码使用每 tick 起止位置，本轮未另行改写尾迹或渲染器。

## 验证

隔离专用服务器加载实际 CBC、CBC-MW、RPL、Sable 与 Rapier 原生运行时，通过
`SimAssemblyHelper` 创建真实 Sable 结构，并调用真实 `MountedMediumcannonContraption.fireShot`：

- 方位角 0–330°（间隔 30°）、俯仰角 -45/-15/0/15/45°、横滚角 0/25°，
  分别覆盖静止及带线速度、角速度的结构，共 240 发。出膛后继续改变结构位置与旋转。
- 每发运行 3 次实际 CBC 飞行 tick 和已注入 Mixin 的 `ServerEntity.sendChanges`。
  720 个精确运动包均保留完整世界坐标及速度；异常出生朝向、竞争普通包、截断出生速度均为 0。
  位置与速度相对 CBC 自由飞行公式的最大误差均小于 1e-6。
- 使用真实 `sendPairingData` 检查出生包顺序，验证 `NeoForgePacket.STREAM_CODEC` 往返；
  同时确认雪球仍发送原版运动包、落地 APFSDS 保留接触朝向与既有同步路径。
- 未修复基线 120 发：异常出生朝向 120、竞争普通运动包 600、截断出生速度 120，
  服务端飞行误差为 0。
- `testApfsdsMotionSync` 使用 Minecraft `TagLoader` 加载资源、合并 CBC 标签，
  并覆盖 1,296 组真实 RPL 包编解码。普通包复现最大 35.58° 方向误差和
  20.88 格/tick 速度损失。
- `testCannonImpactPrediction`、`shadowJar`、`verifyShadedJar` 通过。
  对最终 JAR 再执行标签和网络包测试，检查生产类及 Mixin 注册存在、旧标签目录及测试类不存在。

真实 Sable 测试使用以下命令，测试 sourceSet 不进入交付 JAR：

```powershell
.\gradlew.bat runServer -PapfsdsIntegration=true -PcbcRuntime=true '-PrunRoot=build/apfsds-sable-fixed' '-PmianbaoJar=E:/Codex/mianbao/mianbaos_modernwarfare/build/libs/mianbaos_modernwarfare-2.6.3-neoforge.jar' --offline --console=plain
```

服务器在隔离测试目录运行，仅监听 `127.0.0.1:25586`，测试结束后自动退出。
日志：`build/apfsds-sable-baseline.log`、`build/apfsds-sable-fixed.log`、
`build/apfsds-sable-motion-validation.log`、`build/apfsds-sable-motion-package-validation.log`。
详细结果：`build/apfsds-sable-fixed/server/apfsds-sable-details.txt`。

本次未启动图形客户端做游戏内视觉验收。安装时替换旧模组包并重启游戏；联机服务端也需更新。
