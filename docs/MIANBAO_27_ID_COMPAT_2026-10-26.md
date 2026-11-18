# 面包军火库 2.7 ID 适配（2026-10-26）

适配对象为交付目录中的 `mianbaos_modernwarfare-2.7.0b1-neoforge.jar`，SHA256：`3f223a4b78861680cedfb021d483025b148711b85a3e6dbafd3523f20a33db17`，与同目录 `成品校验.json` 一致。以两份 ID CSV 和同批源码包核对。说明文档开头的旧校验值不作为本次测试依据。

## 检查范围

扫描 `src/main` 的 Java、JSON 和模组元数据，包括完整命名空间、短 ID、动态拼接的旧火箭巢弹量后缀、注册表查询、方块／实体分类和反射过程入口。对照结果：现有接入功能中，需要增加新版 ID 的部分集中在火箭巢及火箭实体；大量改名的灯具、围栏装甲、烟幕、APS、车长／炮长观瞄等装饰或其他设备，当前火控没有按这些方块 ID 接入。

| 识别用途 | 主要入口 | 2.7 核对结果 |
|---|---|---|
| 反坦克发射器、弹药和弹体 | `MianbaoMissileCompat`、`MianbaoLauncherAmmo` | 六类发射器及相关弹药 ID 保留 |
| 防空发射器、普通／高速弹药和弹体 | `MianbaoAirDefenseCompat`、`OrdinaryAirDefenseLauncher` | 七种发射器及相关弹药 ID 保留 |
| 机载导弹挂架、空挂架、机载雷达 | `MianbaoAircraftCompat` | 已接入挂架、弹体、`rack`、`jetradar` 保留 |
| 13 类炸弹挂架和弹体 | `MianbaoBombCompat`、`MianbaoBombBallistics` | 已接入 ID 保留 |
| 巡航导弹、垂发结构和弹药 | `MianbaoCruiseMissileCompat` | 已接入 ID 保留 |
| 弹道导弹 | `MianbaoBallisticMissileCompat` | `ballistic_missile_launcher`、`ballistic_missile_entity` 保留 |
| 光电吊舱 | `ElectroOpticalPodCompat` | `electroopticalpod`、`_2`、`_3` 保留 |
| 干扰弹发射器 | `MianbaoCountermeasureCompat` | `countermeasurelauncher`、`countermeasurelauncher_2` 保留 |
| 遥控武器站、炮塔吊舱、重机枪 | `MianbaoRemoteWeaponStationCompat`、`MianbaoCannonTurretPodCompat`、`MountedHeavyMachineGunCompat` | 已接入方块与弹体 ID 保留；本模组没有按旧重机枪方块实体注册名查找 |
| 面包机炮、转管炮与炮管 | `MianbaoCannonCompat`、`CannonBallisticsCompat` | 已接入方块 ID 保留 |
| 火箭巢、火箭弹和弹体 | `MianbaoRocketPodCompat`、`MianbaoLauncherAmmo` | 已增加三个新版方块、改名弹体及新增制导弹药支持 |
| 物品提示、绑定高亮、武器切换、远距离渲染、音效和粒子 | `FireControlTooltipHandler`、各设备兼容类、`VirtualRocketVisuals` 等 | 火箭巢提示复用生产识别表；新版弹体加入客户端 Mixin，其余已使用 ID 保留 |

`PodControlledCannonBallistics` 原本同时识别 `rocketpod` 和 `rocket_pod`，可覆盖新版名称，无须替换。旧名仍出现在火箭巢兼容表中，用于读取交付版保留的旧设备。

## 逐项适配

下表 ID 均使用 `mianbaos_modernwarfare:` 命名空间。容量按新版实际库存核对，不能按旧型号名或旧后缀推算。

| 旧设备／实体 | 新 ID | 处理 |
|---|---|---|
| `rocketpod`、`rocketpod_1`…`rocketpod_14` | `medium_rocket_pod_type_1` | 新方块绑定、提示、单槽库存，容量 14 |
| `rocketpodsecond` 及左右变体、各弹量后缀 | `medium_rocket_pod_type_2` | 新方块绑定、提示、单槽库存，容量 36 |
| `mediumrocketpod` | `medium_rocket_pod_type_3` | 新方块绑定、提示、单槽库存，容量 9 |
| `rocketstanshewu` | `rocket_he_tanshe` | 同时识别新旧弹体，接入数学飞行、命中爆炸和客户端模型／尾迹 |
| 新增目视制导火箭 | `rockets_visual_guided` → `rocket_visual_guided_tanshe` | 三个新火箭巢接受该弹药；读取原生发射者，保留 6 格／tick 初速、10 tick 发射延迟及视线转向 |

新火箭巢统一调用原生 `MediumrocketpodgoProcedure`，由原生过程扣弹和播放发射音效。火控只读取库存和接管飞行，不额外扣弹。空槽、错误物品和无库存的新版设备均不回退到旧 `rockets` NBT。旧型号保留原来的 ID 后缀／NBT／容器读取及原生发射入口。

新版普通火箭在构造时已关闭重力：数学飞行和客户端预测单独传递首 tick 重力，避免沿用旧弹的首 tick `0.05` 下落。后续每 tick `0.025` 下落保持原生规则。目视制导火箭使用独立无重力飞行，不套用普通火箭的弹道；其固定落点提示隐藏，服务端每 5 tick 同步制导修正。

瞄准网络协议由 `53` 改为 `54`，传递首 tick 重力。联机时客户端与服务端需同时使用本适配构建。火控不改写用户世界或将旧方块强制换成新方块。

## 验证

单元／回归任务：`testMianbaoLauncherAmmo`、`testMianbaoRocketBallistics`、`testVirtualRockets`、`testOpenControllerPayload`、`testSafetyRegressions`、`assemble` 已通过。

实际加载面包 2.7、Sable 2.0.6、航空学／Simulated 1.3.2、CBC 运行时，在隔离测试服务器中检查三个新版火箭巢的真实库存、两种弹药的原生发射、空弹／错误物品／残留 NBT、扣弹后的绑定有效性，并回归旧火箭巢、近墙爆炸和 192 发弹群。最终结果见本次交付的验证记录。

客户端模型与尾迹分支已编译，目标类与 `tick` 入口按 2.7 源码核对；本次未运行游戏客户端进行画面验收。

复现命令（将 `$mianbaoJar` 设置为本地 2.7 JAR 路径）：

```powershell
.\gradlew.bat --offline testMianbaoLauncherAmmo testMianbaoRocketBallistics testVirtualRockets testOpenControllerPayload testSafetyRegressions assemble "-PmianbaoJar=$mianbaoJar" -Pmod_version=0.9.3-mianbao27
.\gradlew.bat --offline runServer -ProcketIntegration=true -PcbcRuntime=true -PrunRoot=run_mianbao27_verify_clean "-PmianbaoJar=$mianbaoJar" -Pmod_version=0.9.3-mianbao27
```

测试使用专用世界；若更换了测试方块或上一次中途失败，使用新的 `runRoot`，避免旧测试存档中的弹体影响结果。
