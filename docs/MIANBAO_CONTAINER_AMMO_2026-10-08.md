# 面包军火库容器弹药适配核对

核对来源：`E:/Codex/mianbao/mianbaos_modernwarfare/src/main/java/net/mcreator/myfirstmod` 当前 NeoForge 移植源码，参照 `.cfr-2.6-20260925` 中的原版 2.6 发射过程。此处的“迁移”指原版 NBT 弹药计数与当前移植版容器弹药的差异，不意味着物品注册 ID 改名。

## 数量与范围

按设备用途合并同类/高度/左右/涂装/开关状态，共发现 **11 类**导弹及相邻火箭/鱼雷设备使用容器槽 0 发射弹药。其中 **5 类、17 个方块 ID** 已接入火控。仅计算导弹发射器是 **3 类、14 个 ID**；另有 **2 类、3 个 ID** 火箭巢。2026-10-23 补充修复二型防空左右发射器，纠正此前将其列为 NBT 例外的结论。

| 当前改用槽 0 的设备类别 | 火控现有支持数量 | 弹药物品 ID 后缀 | 原生发射入口 |
| --- | ---: | --- | --- |
| 陆基导弹发射器 | 1 | `groundmissile_1item`、`groundmissile_2item`、`groundmissile_3item`、`groundmissile_4item`、`groundnuclearcruisemissileitem`；移植版额外接受 `cruisemissilelowitem` | `GroundMissileLauncherSupport` / `GroundmissilelaunchergoProcedure` |
| 反坦克导弹发射器一至四型 | 6 | `antitankmissile_1`、`antitankmissile_2`；四型只发射 `_1` | `AntitankMissilelaunchergoProcedure`、`AntitankmissilelaunchererxingWORKProcedure`、`AntitankmissilelaunchersanxingworkProcedure`、`Antitankmissilelauncher_sixing_fireProcedure` |
| 防空导弹一型/二型/三型 | 7 | `portableantiairmissile_2`；火控计算机额外支持 `portableantiairmissile` | 高速弹：`AntiairmissilelaunchergoProcedure`；普通弹：`OrdinaryAirDefenseLauncher` |
| 中型火箭巢 | 1 | `rockets` | `MediumrocketpodgoProcedure` |
| 重型火箭巢两型 | 2 | `farrocketsexplosion`、`farrocketsfire`、`farrocketlaserguided` | `HeavyrocketpodgoProcedure` |
| 混合导弹发射器 | 0 | `portableantiairmissile_2`、`antitankmissile_1`、`antitankmissile_2` | `Mixed_missile_launcher_fireProcedure` |
| 便携反导系统（方块设备） | 0 | `portableantimissilemissile` | `PortableantimissilesystemgoProcedure` |
| 激光导弹发射器 | 0 | `laser_guided_missile`、`laserguidedmissilesecond` | `MissilelaunchergoProcedure` |
| 混合 AGM 发射架 | 0 | `antitankmissile_1`、`antitankmissile_2` | `AgmmissilemixedrackgoProcedure` |
| 远程火箭发射器 | 0 | `farrocketsexplosion`、`farrocketsfire`、`farrocketscluster`、`farrocketlaserguided`、`farrocketswp`、`portableantimissilemissile` | `FarrocketslaunchergoProcedure` |
| 鱼雷发射器 | 0 | `highexplosivetorpedoitem`、`highexplosiveguidedtorpedoitem`、`highexplosivevisualguidedtorpedoitem` | `Torpedo_launcher_fireProcedure` |

所有 ID 的命名空间为 `mianbaos_modernwarfare`。原本未接入火控的 6 类不在本次扩大接入范围；导弹实体受支持，不等同于所有能发射该实体的设备都已接入。

## 当前适配的 17 个方块 ID 后缀

- 陆基：`groundmissilelauncherhead`。
- 反坦克：`antitankemissilelauncher`、`antitankemissilelauncherh_2`、`antitankmissilelaunchererxing_right`、`antitankmissilelaunchererxing_left`、`antitankmissilelaunchersanxing`、`antitankmissilelaunchersixing`。
- 防空：`antiairmissilelauncherblock`、`antiairmissilelauncherleft`、`antiairmissilelauncherright`、`antiairmissilelauncherthird_h_1`、`antiairmissilelauncherthird_h_2`、`antiairmissilelauncherthird_h_1sand`、`antiairmissilelauncherthird_h_2sand`。
- 火箭巢：`mediumrocketpod`、`heavy_rocketpod`、`heavy_rocketpod_2`。

## 存储与发射规则

- 反坦克、防空一/二/三型、中/重型火箭巢：以真实槽 0 的有效物品及堆叠数为准，空槽或无效物品返回 0，不复活残留 NBT 弹药。反坦克三/四型仍有 9 个槽，但原生过程只发射槽 0；其它槽不计入可发射弹药。
- 防空二型左右的当前方块已使用通用装填和发射过程；火控与显示器共用的兼容层也统一调用 `AntiairmissilelaunchergoProcedure`。只有无可用容器槽的旧后端才保留 `missileammo` 读取及旧二型发射过程；方法缓存按过程类名区分，避免容器与旧后端串用。
- 原生过程负责消耗上述弹药，火控只观察发射前后堆叠数量变化，不重复扣弹。这同时修复显示、发射可用性和发射成功判定。
- 陆基：优先识别槽 0，`cruisemissilelowitem` 映射为陆基低空导弹。火控现有的自定义巡航装填和旧 NBT 弹药路径继续保留；成功生成导弹后扣除槽 0 一发并清理旧弹药标记。槽中有无效物品时，不回退到旧标记。原有六向发射几何继续使用。
- 重型火箭的当前三个弹种原生初速均为 9 格/tick，修正火控弹道预测读取；无容器的旧后端保留原有 NBT 初速规则。
- 无可用容器槽的后端保留旧数值 NBT 读取。这不是把任意旧版 `Container` 中残留 NBT 自动迁移成物品；当前原生发射过程不消费那些残留标签。

## 核对后无需改动的支持设备

- 普通/二型火箭巢：继续通过带弹量后缀的方块变体记录装弹。
- 单枚飞机导弹挂架：原生发射依据已装弹挂架方块 ID，发射后换为空 `rack`，未迁移为槽 0 发射。
- 弹道导弹：现有 API 与 `loaded` 方块属性判断，未发现本次容器迁移影响。
- 两种干扰弹发射器：原生过程仍消耗 `ammo` NBT，不改为容器计数。
- 手持导弹的玩家物品栏不纳入方块发射器迁移统计。

## 验证

`testMianbaoSiloAmmo` 覆盖陆基真实物品识别、低空弹映射、旧命名/数值弹药、无效槽、成功消耗及旧标签清理。

`testMianbaoLauncherAmmo` 覆盖六种反坦克、七种防空、中型/两种重型火箭巢所用的实际生产读取器，包括堆叠计数、模拟原生扣一发、耗尽、不支持的弹药、9 槽仅发射槽 0、防空容器与无容器旧后端的发射入口选择和重型初速。

测试使用独立启动的 Minecraft 注册表及模拟方块容器，不是游戏内发射测试。

2026-10-23 二型左右的容器修复及真实服务端发射验证另见 `AA_CONTAINER_FIX_2026-10-23.md`。

同日新增的普通防空弹装填、驾束制导、自加载与视距外渲染见 `ORDINARY_AA_BEAM_2026-10-23.md`；显示器继续只接管高速防空弹。

专项测试均通过。完整 `build` 在独立的 `testNativeBearing` 中失败，单独重跑亦复现：`NativeBearingChecks.java:42` 的 `sustained carrier rotation holds every tested load before stopping` 断言失败。该测试检查轴承载荷与载体旋转，本次未改动相关代码，也未修改断言或跳过它来宣称完整构建成功。最终交付另行运行 `assemble verifyShadedJar testMianbaoSiloAmmo testMianbaoLauncherAmmo` 验证。
