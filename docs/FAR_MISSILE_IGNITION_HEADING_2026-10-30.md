# 远距空空弹点火朝向与引信保险（2026-10-30）

两种远距空空弹保留面包军火库的无动力下落阶段。点火交接后，火控以点火瞬间的弹体世界朝向作为初始飞行方向，保留当时速度大小，再继续原来的加速、出架直行阶段和平滑制导。

## 原因与修复

新版面包在点火时把发动机推力加入下落惯性，`RackFireControlBridge` 随后把这个合速度写入火控的 `worldVelocity` 和 `flightDirection`。静止水平释放的实际样本在点火后为 `(2.39246, -0.44001, 0)` 格/刻，火控会沿偏下的方向继续加速。

`FarMissileIgnitionHeading` 从原生 `MianbaoFarRackRelease` 数据中的四元数、世界角速度和实际点火下落年龄还原弹体前向。`MianbaoAircraftGuidanceHandler.beforeTick` 在点火后的第一次有动力移动之前校正实体速度、火控速度、初始速度大小和箭矢姿态插值。保险与制导不把这个校正算作一次目标转弯。一次性标记随弹体保存，后续制导、读档和数学交接不会再次拉回初始方向。

不强制水平飞行：倾斜、滚转和释放自转后的实际点火朝向仍然有效。没有延迟点火数据的旧版导弹保持原路径。

## 引信保险

火控计算机的保险设置改为从点火开始计时。下落阶段保持未解除保险，即使设置为 0 秒，也要等到点火后才允许引爆。

`MissileFuseSafety` 统一提供时间判定，接入三处：原生弹体碰撞、数学飞行碰撞、火控的地形接触与近炸引信。原生下落数据的 `LastStep` 是完成下落并点火的世界游戏刻，后续不再更新；用它计算已点火时长，可以跨读档及数学/实体交接保留时间，避免实体 `tickCount` 重置导致重复计时。

例如保险设为 0.5 秒，即点火后 10 个游戏刻解除；此前的下落时间不计入。没有无动力下落阶段的导弹继续沿用原发射年龄。中英文设置名称同步标明点火语义。

## 验证

使用面包 `mianbaos_modernwarfare-2.6.9-rack-release-raycast-fix.jar`、Sable 2.0.6、航空学/Simulated 1.3.2、Create 6.0.10、CBC 与 NeoForge 21.1.230，启动隔离测试世界。

- 12 次原生挂架发射：两种远距空空弹 × 实体/数学路线 × 水平低空、倾斜带惯性、释放自转三种状态。测试注入载机惯性及姿态数据、雷达观测；实际挂架发射、原生下落、原生可选火控桥接、制导及数学交接均使用生产实现。
- 检查第一步有动力位移和前 9 刻出架直行的朝向；水平低空样本在这段飞行中不再继续下降；点火速度大小保持一致。
- 0.5 秒保险在下落和点火时尚未解除，点火后第 10 刻准确解除。调用真实原生碰撞入口验证保险期间不爆炸，达到边界才引爆；数学弹体实际穿过测试障碍的保险期检查及到期碰撞引爆通过。
- 重置恢复弹体的 `tickCount`，验证持久化的点火时间仍然有效；校正标记经过生产恢复入口后不重复生效。
- 已交付面包包的真实下落为 15 刻；另检查 10/15 刻姿态数据兼容、固定点火姿态、缺失或非法四元数及旧版无下落数据的保险行为。
- 最终回归：`testAirDefenseIntercept`、`testMathematicalMissiles`、`testSafetyRegressions`、`testLowAltitudeCruise`、`testMianbaoRocketBallistics`、`assemble`。

测试结果与逐刻轨迹位于 `run_far_ignition_heading_verify/server/far-ignition-heading-*`，日志位于 `build/far-ignition-integration.log` 和 `build/far-ignition-final-checks.log`。本次未进行客户端画面或玩家手动驾驶验收。

集成测试复现：

```powershell
.\gradlew.bat --offline --no-configuration-cache -I tools/far-ignition-heading-checks.init.gradle runServer '-PmianbaoJar=E:\Codex\mianbao\交付-20261030-挂架射线卡死修复\mianbaos_modernwarfare-2.6.9-rack-release-raycast-fix.jar' -PcbcRuntime=true -PrunRoot=run_far_ignition_heading_verify
```

测试源码只在此 init 脚本中启用，不进入发行包。
