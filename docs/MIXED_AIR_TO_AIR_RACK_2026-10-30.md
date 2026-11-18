# 四联混装空空挂架适配（2026-10-30）

2026-10-31 更新：[混装挂架武器切换修复](MIXED_RACK_WEAPON_SELECTION_FIX_2026-10-31.md) 已取代本文“只识别下一发/连续同类弹”的限制，现可选择全部已装弹种并释放对应弹位。本文保留为上一版实现记录。

支持面包新增的 `mianbaos_modernwarfare:quad_air_to_air_rack`。新挂架可绑定为机载导弹挂架，支持近距一/二型、中距一/二型、远距一/二型、MICA-EM 和 AIM-120 八种物品。

## 选弹和发射

保留原生装填 1→4、发射 4→1 的顺序。火控读取最高已装弹位的实际物品，只有当前下一发的武器类型可选、可发射；数量显示该挂架上连续可发射的同类弹数。中距一/二型同属中距类型，远距一/二型同属远距类型；MICA-EM 和 AIM-120 独立选择。

例如按下表装填后，发射顺序为 AIM-120、MICA-EM、近距、远距。每次弹种改变后，需要选择对应武器；继续使用已耗尽的类型会提示耗尽。

| 装填弹位 | 弹种 | 发射顺序 |
| --- | --- | --- |
| 1 | 远距 | 4 |
| 2 | 近距 | 3 |
| 3 | MICA-EM | 2 |
| 4 | AIM-120 | 1 |

每次发射只调用一次原生 `fire()`，由面包生成弹体并扣除当前弹位。火控校验其余弹位与物品组件保持不变，再刷新下一发类型和可发射数量。空架、无效物品、类型不匹配及失效绑定均拒绝发射。多架挂架继续按原火控轮换游标选择当前类型的可发射挂架。

近距弹继续要求红外锁定；其他空空弹继续采用对应雷达制导。远距弹保留原生下落、点火瞬间朝向修正和点火起算的引信保险，支持数学/实体两种飞行路线。

## 实现

新增 `MianbaoAircraftRackCompat`，通过反射读取原生四个弹位和 `nextSlot()`，不增加面包编译类型依赖。火控计算机的选弹、弹数、发射以及弹体关联统一使用这个入口，旧挂架回退原兼容逻辑。保留面包包内现有的可选火控 Mixin 和弹位几何校准。

发射期间保存一次性的计算机、挂架、弹位与实际弹体 ID。`ControllerTracker` 优先按这条记录接管弹体，避免两台火控或附近同型挂架的距离匹配覆盖实际发射归属；发射结束或异常时清理上下文。混装挂架用独立库存维持部分装填/空架绑定，拆除后重新放置需要重新绑定。

未修改面包项目及其 JAR，未变更火控网络协议或武器枚举序号。

## 验证

使用交付版 `mianbaos_modernwarfare-2.6.9-quad-air-rack.jar`，SHA-256：`bff1922705a4ccf9380cb575656fd2ffce289e934f13de9e19e21d456cfcda2a`。运行环境为 NeoForge 21.1.230、Create 6.0.10、Sable 2.0.6、航空学/Simulated 1.3.2 与 CBC。

- 92 次真实四联挂架释放：八种弹药、实体/数学路线、四发同型、两组四发混装、一至三发部分装填。
- 通过实际火控计算机发射方法执行，验证 4→1 顺序、逐发消费、连续同类弹数、可选类型刷新和其他弹位/自定义物品组件保留。
- 验证选错类型、缺少红外锁定、空架、非法顶部物品、空洞弹位、保存恢复及拆除重放后的旧绑定拒绝行为。
- 两台真实火控同时绑定同一挂架、各持独立雷达目标，验证每枚弹体记录的控制器与雷达目标均来自实际调用者；红外弹使用真实 Sable 物理目标观测。
- 新挂架实际远距弹下落 10 刻后点火，初始方向保持水平；引信保险在点火后第 10 刻解除，数学交接遵守发射时选择的路线。
- 使用同一新版面包包再次执行上一轮 12 次原生远距挂架集成测试，检查倾斜/自转点火朝向、原生及数学碰撞保险、恢复和旧挂架。
- `testAirDefenseIntercept`、`testInfraredMissiles`、`testMathematicalMissiles`、`testSafetyRegressions`、`testMianbaoLauncherAmmo` 和 `assemble` 均通过。

测试通过仅表示上述服务端场景；本轮未进行客户端画面或手动驾驶验收，未测试运动载机上的新挂架几何。

结果：`run_mixed_air_rack_verify/server/mixed-air-rack-result.txt`、`mixed-air-rack-details.txt`。日志：`build/mixed-air-rack-integration.log`。旧远距回归日志：`build/quad-rack-far-ignition-regression.log`。

复现（测试源不进入成品）：

```powershell
.\gradlew.bat --offline --no-configuration-cache -I tools/mixed-air-rack-checks.init.gradle runServer '-PmianbaoJar=E:\Codex\mianbao\交付-20261030-四联空空导弹复合挂架\mianbaos_modernwarfare-2.6.9-quad-air-rack.jar' -PcbcRuntime=true -PrunRoot=run_mixed_air_rack_verify
```
