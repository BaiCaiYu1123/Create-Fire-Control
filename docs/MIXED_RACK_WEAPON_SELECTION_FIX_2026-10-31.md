# 混装挂架武器切换修复（2026-10-31）

修正上一版四联挂架适配中只能识别原生下一发的问题。现在火控识别四个弹位上的全部弹种，切换武器、直接选弹、按键配置和 HUD 弹数均读取完整库存。选择一种空空弹后，火控从该类型的最高已装弹位释放一枚，其他弹位保持原样。

例如从弹位 1 到 4 装入远距、中距、近距和 AIM-120 后，可以先选中距发射弹位 2，再选远距发射弹位 1，无需先消耗弹位 4 的 AIM-120。同类弹仍按高弹位到低弹位释放。原生红石发射继续使用面包自己的顺序。

## 原因

上一版 `weaponAt` 只读取 `nextSlot()`，弹数只统计从最高弹位开始的连续同类弹。火控的上下切换跳过弹数为零的类型，直接选弹和按键配置又只接收 `boundAircraftWeapons()` 中的类型，因而同架其他弹种被隐藏。上一轮测试直接设置计算机的选弹字段，没有覆盖实际切换输入，未能发现这一问题。

本轮新增回归先在旧实现上复现：四种弹药已实际装入挂架，但识别结果缺少非下一发弹位的类型。

## 修复

- `MianbaoAircraftRackCompat.weaponsAt` 扫描四个独立物品，返回全部已装武器类型；`ammoCount` 统计该类型的全部弹数，同类弹之间隔着其他弹种也不漏算。
- 火控计算机的可选武器集合、上下切换、直接选弹和按键配置数据使用完整库存；发射选择有对应弹药的挂架。
- 若选中原生最高弹位，继续调用原生 `fire()`。若选择其他弹位，调用面包公开的逐弹位发射后端 `QuadAirToAirRackSupport.launch`，成功后只清空该弹位并调用原生同步。没有临时调换库存顺序，也不发射其他类型来清路。
- 保留频道、方块实体持久数据、其他物品及组件。原生发射被取消时不消费弹药；反射异常和缺弹不会回退到其他弹种。
- 发射关联仍记录实际计算机、挂架和弹位；载机挂点速度改从所选弹位的局部发射点采样，避免误用原生下一发的挂点。原生实体、几何校准、惯性及数学/实体路线继续使用原实现。
- 近距弹仍要求红外锁定，其他空空弹要求对应雷达目标。点火朝向和点火起算的保险修复保留。

旧单联挂架、武器枚举序号和网络协议不变；本轮没有修改面包项目或其 JAR。

## 验证

使用 `mianbaos_modernwarfare-2.6.9-quad-air-rack.jar`、Create 6.0.10、Sable 2.0.6、航空学/Simulated 1.3.2、CBC 和 NeoForge 21.1.230 的隔离服务器。

本轮测试通过实际 `WirelessPulseReceiver` 输入执行上切、下切、直接选弹及发射；调用方不直接设置 `selectedAircraftWeapon`。同时检查发送给按键配置界面的武器动作列表。测试中的雷达观测和红外目标锁定使用夹具，发射、库存、制导接管和数学交接使用生产实现。

108 次实际挂架释放全部通过。集成场景包括：混装四种类型并先发射非最高弹位的中距、红外近距及远距弹；同类弹分隔装填；八种空空弹各四发；部分装填、库存空洞、存档读写、空架、失效绑定；原生发射取消时库存不变；其他弹位的自定义名称组件、频道数据和客户端更新数据保留；两台火控绑定同架时归属正确；两条飞行路线、远距弹 10 刻下落和点火保险。

结果位于 `run_mixed_air_rack_selection_verify/server/mixed-air-rack-result.txt` 和 `mixed-air-rack-details.txt`。修复前/后日志分别为 `build/mixed-rack-selection-before.log` 和 `build/mixed-rack-selection-fixed.log`。

最终回归 `testFireControlWeaponInputs`、`testInfraredMissiles`、`testMathematicalMissiles`、`testAirDefenseIntercept`（282 个场景）、`testSafetyRegressions` 和 `assemble` 均通过。同一新版面包包上的旧远距挂架 12 次真实发射也通过，覆盖点火朝向、两条飞行路线、碰撞保险和存档恢复；日志为 `build/mixed-rack-selection-final-checks.log`。

本轮验证服务端输入及数据同步内容，未进行客户端实际键盘操作/画面验收，未验证运动载机上的新挂点几何。

复现：

```powershell
.\gradlew.bat --offline --no-configuration-cache -I tools/mixed-air-rack-checks.init.gradle runServer '-PmianbaoJar=E:\Codex\mianbao\交付-20261030-四联空空导弹复合挂架\mianbaos_modernwarfare-2.6.9-quad-air-rack.jar' -PcbcRuntime=true -PrunRoot=run_mixed_air_rack_selection_verify
```
