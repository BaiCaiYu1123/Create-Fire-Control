# 导弹发射与转向检查（2026-10-23）

本次在 AIM-120D 修复的基础上检查其他火控导弹，不改变用户确认正常的陆基巡航和低空巡航弹道。成品版本为 `0.9.1-missile-math-steering-fix`。

## 检查结果与修复

先核对面包原生尾迹程序的开始制导条件，再检查火控各分支最终写入速度的位置，未把所有弹种套成同一种弹道。

| 弹种/设备 | 检查结果和处理 |
| --- | --- |
| 远程、中程、近程空空弹，两种外观变体；MICA-EM；AIM-120D | 原生 `FarMissile1trailProcedure`、`Missile2trailProcedure`、`CloseMissile1trailProcedure` 从年龄 10 开始制导。除已修的 AIM-120D 外，火控原先从年龄 1 介入。统一恢复前 9 tick 出架直行、第 10 tick 捕获，保留各自的速度、过载及转角参数。 |
| AGM 直射，两种变体 | `Agm1trailProcedure` 的转向条件为 `time > 10`，火控原先从年龄 1 介入。改为第 11 tick 起采用原有比例导引，保留直射制导方式。 |
| AGM 攻顶，两种变体 | `Agm2trailProcedure` 的发射段在年龄 10 切换，火控原先提前转向。恢复第 10 tick 开始攻顶控制，保留高弧线、中段和末端俯冲路径。原先仅执行角度限幅，现同时执行已配置的过载上限。 |
| 激光制导弹 | 原生 `Missile3trailProcedure` 无同类等待条件，保持其即时制导。原先只执行角度限幅，现补上已配置过载上限和初期渐进转向约束。 |
| 高速防空弹，火控计算机/显示器 | 原有火控出架等待 10 tick 保留，不改变引信安全时序。两种设备共用随速度变化的转向半径约束，满速仍是原有 13°/tick 上限。 |
| 诱饵转向 | 原先选择诱饵时立即转一次、常规制导 tick 再转一次，且部分分支只检查角度。现在选择目标时不额外转向，常规 tick 统一限幅；初始出架阶段也不能绕过。防空诱饵追踪同时持续保存新的方向。 |
| 反坦克束驾导弹 | 原有第 3 tick 开始制导和 4°/tick 参数保留；用有界球面旋转替代线性混合后归一化，消除小角度超限和正反向时不能开始转弯的问题。此弹不属于当前高速数学模式的接管范围。 |
| 巡航、低空巡航、独立弹道导弹、电视手动制导 | 保留其独立飞行或输入处理代码。本次不改变这些路径。 |

## 共用约束

`MissileSteeringLimits` 在转向前保留原有角度/过载两个上限，低速阶段再约束为原有满速转弯半径。速度增加时可用转角逐渐增加；不会把低速发射解释成可以立即转过几十度。高速时原有过载上限仍适用。

机载弹在最终速度写入前统一限幅，覆盖雷达拦截、比例导引、攻顶、激光和诱饵分支。加速曲线、满速、寿命、引信参数和一次性载具速度继承不改。攻顶、激光等此前漏掉过载限制的分支，现在真正执行既有配置，因此其最终转角可能比旧实现更小。

## 验证

- `testAirDefenseIntercept`：282 个场景通过；逐弹族固定检查原有转角、过载常数，检查各自出架时序、低速/满速/超速约束、保速旋转和反向转弯。
- `testLowAltitudeCruise`、`testMathematicalMissiles`、`testSafetyRegressions` 通过。
- 使用整合包现有 `mianbaos_modernwarfare-2.6.0b6-neoforge-explosion-opt.jar`、Sable 2.0.6，在独立测试服运行真实发射。84 次发射组成 42 对实体/数学轨迹，每次对照 100 tick，共 8400 条记录：13 个机载弹变体的东/北发射、额外移动目标、五种空空弹的静态 Sable 挂架、防空计算机与显示器两个设备、五种空空弹和高速防空弹的诱饵分支。
- 每 tick 检查方向、各自出架阶段、转角和适用的过载上限。诱饵用例在年龄 5 选择目标，断言事件本身不改变速度，也不打断出架阶段。
- 配对发射复用同一份原生散布样本，避免把两次随机散布误判为后端差异。雷达/对地/激光目标快照由测试注入，发射、接管、发动机与制导使用生产实现。同位置的对照弹关闭碰撞，避免相撞；不据此推断所有碰撞场景一致。
- 同次保留原有 22 弹种 × 120 原生 tick 积分、实际巡航弹扣弹/碰撞、集束、时限和模式切换检查。大量实体发射的测试收尾会释放测试船体的强加载票并留出区块卸载时间。
- 最终干净测试世界完整退出并得到 `BUILD SUCCESSFUL`；8400 条轨迹记录的最大配对位置差为 `3.725290298461914e-9` 格，最大速度差为 `3.552713678800501e-15` 格/tick。
- 成品逐类 SHA-256 对照确认：巡航/低空巡航、独立弹道弹、电视手动制导、数学积分/管理、网络包和导弹/粒子渲染类与上一份 AIM-120D 修复包完全相同。共用转向约束类已打包，集成测试类未打包。

测试源码位于 `src/missileIntegration/`，不打入成品。轨迹文件：`run_guided_missile_launch_clean_verify/server/guided-missile-launch-trajectories.csv`，结果文件：`run_guided_missile_launch_clean_verify/server/mathematical-missile-integration-result.txt`。

本次不包含完整整合包客户端的逐帧画面验收，也不能代替所有旋转载具、动态地形、目标机动及全航程测试。数学模式原有的地形、在飞存档和画面验证边界仍见 `SWITCHABLE_MISSILE_MATH_2026-10-22.md`。

## 使用与复现

替换旧火控 JAR 后完整重启游戏/服务端。只保留一个火控版本；指令仍为 `/cfc missile mode math|entity`，切换影响后续发射。

成品：`build/libs/create-fire-control-0.9.1-missile-math-steering-fix.jar`，SHA-256：`7f7c5f44e2c34b73fcd4d1ce01dc0a7eba3ddd6f79c3a62267a3381e70cd5f52`。

```powershell
.\gradlew.bat --offline testAirDefenseIntercept testLowAltitudeCruise testMathematicalMissiles testSafetyRegressions
.\gradlew.bat --offline runServer -PmissileIntegration=true -PcbcRuntime=true -PrunRoot=run_guided_missile_launch_clean_verify '-PmianbaoJar=D:/Game/PCLII/.minecraft/versions/Create航空学/mods/mianbaos_modernwarfare-2.6.0b6-neoforge-explosion-opt.jar'
```
