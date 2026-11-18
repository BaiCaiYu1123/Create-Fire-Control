# AIM-120D 发射转向修正（2026-10-23）

适用设备：火控计算机绑定机载雷达和 AIM-120D 挂架；实体和数学飞行共用同一修正。

## 原因与行为

AIM-120D 的初速度取自挂架，并继承一次载具速度。但火控制导原先从年龄 1 开始介入，允许最多 56°/tick；因此视场内的偏轴目标可能在第一次制导时直接被对准。这个问题存在于共用制导中，旧实体版本也使用这组参数。

- 前 9 个移动 tick 保持出架方向，第 10 tick 开始制导，对应原生 `Missile2trailProcedure` 的 `time >= 10` 条件。
- 初期额外保留最大速度下的转弯半径约束，转角随速度逐渐增加。原来的角速度上限、过载上限、Mach 2 速度、五秒加速及载具速度继承保留；到达最大速度时仍使用原来的机动范围。
- 出架方向直接使用发射关联已经确认的挂架，避免多挂架情况下再次选择方向。
- 每枚 AIM-120D 增加出架和首次制导日志，可核对 `route`、`axis`、`launchVelocity`、`turnDegrees` 和 `limitDegrees`。
- 按用户更正，陆基巡航、低空巡航及其爬升/掠地控制保持原样；此前试作的陆基发射阶段调整已撤回。其他弹种的速度、转角与过载参数未调整。

## 验证

- `testAirDefenseIntercept`：274 个场景通过，新增 AIM-120D 出架、渐进转向及原有过载/满速转角常数断言。
- `testLowAltitudeCruise`、`testMathematicalMissiles`、`testSafetyRegressions` 通过。
- 使用整合包现有 `mianbaos_modernwarfare-2.6.0b6-neoforge-explosion-opt.jar` 与 Sable 2.0.6 启动独立测试服。
- 10 次真实挂架发射：东/北/西朝向、静止/匀速雷达目标、静态 Sable 装配挂架，各含实体与数学模式。实际火控计算机绑定真实机载雷达；测试仅注入雷达目标快照，不替换发射、接管或制导实现。同位置的轨迹对照弹关闭碰撞，避免彼此相撞；碰撞仍由另一个实际巡航弹发射用例检查。
- 100 tick 内逐 tick 比对两种模式的位置、速度，检查发射方向、初期直行、转角和过载上限。该场景第 10 tick 速度为 5.653 格/tick，首次转角约 8.69845°，两种模式一致。
- 同次保留 22 弹种 × 120 原生 tick 的数学积分对照、实际巡航弹扣弹和碰撞、集束与时限测试。
- 成品逐类 SHA-256 对照确认 `CruiseMissileGuidance`、其 `State`、`LowAltitudeCruiseFlight`、`MianbaoCruiseMissileCompat` 与上一份数学实验包完全相同，巡航弹改动没有带入本次成品。

轨迹记录：`run_aim120_launch_verify/server/aim120-launch-trajectories.csv`。测试结果：`run_aim120_launch_verify/server/mathematical-missile-integration-result.txt`。

这次没有做整合包客户端画面验收；上述结果验证了这些服务端场景中的轨迹与转向，不代表所有机动载具、地形和粒子画面都已完成一致性验收。数学模式的其他已知边界仍见 `SWITCHABLE_MISSILE_MATH_2026-10-22.md`。

## 使用

替换旧的火控 JAR 后完整重启游戏/服务端；同一个 `mods` 目录中只保留一个火控版本。

```mcfunction
/cfc missile mode math
/cfc missile mode entity
/cfc missile mode
```

模式只影响之后发射的导弹。提供的 `debug-1.log.gz` 当时仍记录加载 `create-fire-control-0.9.1.jar`，并出现 `cfc missile mode math` 无法解析的提示；不能把那个日志当作数学模式已启用的证据。新包可通过版本后缀和 AIM-120D 出架日志确认加载情况。

复现：

```powershell
.\gradlew.bat --offline testAirDefenseIntercept testLowAltitudeCruise testMathematicalMissiles testSafetyRegressions
.\gradlew.bat --offline runServer -PmissileIntegration=true -PcbcRuntime=true -PrunRoot=run_aim120_launch_verify '-PmianbaoJar=D:/Game/PCLII/.minecraft/versions/Create航空学/mods/mianbaos_modernwarfare-2.6.0b6-neoforge-explosion-opt.jar'
```
