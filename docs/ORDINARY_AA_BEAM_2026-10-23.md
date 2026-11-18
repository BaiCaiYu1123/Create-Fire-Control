# 普通防空导弹的火控驾束制导

火控计算机现可通过既有七种防空发射器装填、识别和发射 `mianbaos_modernwarfare:portableantiairmissile`。普通弹使用原生 `portableantiairmissiletanshe` 实体和模型；高速弹保留原有雷达拦截路径。未修改面包模组源码或 JAR。

后续补充：普通弹也读取计算机的防空近炸范围，最新交付包为 `create-fire-control-0.9.1-ordinary-aa-beam-fuse.jar`。详见 [近炸引信补充与验收](ORDINARY_AA_FUZE_2026-10-23.md)。本文末尾的原包记录保留为首次交付记录。

2026-10-24 更新：普通弹机动参数已提升为四倍，当前包为 `create-fire-control-0.9.3-ordinary-aa-agility4.jar`，包含近炸引信兼容。参见 [四倍机动参数与验收](ORDINARY_AA_AGILITY_2026-10-24.md)；下文 4°/tick 等参数记录首次交付行为。

## 使用与飞行参数

- 将既有防空发射器绑定到火控计算机，手持普通防空弹右键装填，或用漏斗向槽 0 输入。二型左右手动装填上限为 2，其余为 4，与原生高速弹手动装填容量一致；禁止混合覆盖槽中另一弹种。
- 选择“防空导弹”弹种，使用地面导弹发射输入；原有防空发射输入仍可触发。普通弹要求有效的火控瞄准射线，不要求雷达锁定。显示器继续使用高速防空弹。
- 普通弹默认以 2 格/tick 出架，在 200 tick（10 秒）内线性加速到 17.015 格/tick，即按项目换算的 340.3 米/秒、1 马赫；之后保持该上限。载具发射继承发射点速度一次，最终初速仍受 1 马赫上限约束。
- 与现有反坦克导弹共用 `BeamRidingGuidance`：横向修正系数 0.08，横向速度最多为前进速度的 22%，每 tick 最大转角 4°，前两 tick 保留出架方向。制导跟随实测火控射线，不追逐雷达预测拦截点。
- 发射年龄达到 400 tick（20 秒）且尚未命中时直接移除，不产生超时爆炸。计算机被拆除后，已发射导弹继续按既有方向飞行、加速和计时。
- 普通弹与反坦克弹一样使用实体飞行和临时区块加载；全局高速导弹数学模式不会把普通弹转为数学弹。

## 自加载与画面

`OrdinaryAirDefenseGuidance` 为导弹的当前运动区段申请实体 ticking 票，并预取前方有界地形。区块尚未就绪时沿用现有的无同步加载推进入口。所有票在命中、超时或服务器停止时释放，不使用面包原生永久 `forceload` 命令。

面包普通弹的原生 trail 过程仅对火控已接管弹跳过，以免原生加速、目标搜索及 220 tick 超时与新控制器争用。手持发射器的原生行为保留。导弹年龄、初速和精确计算机引用保存在实体持久数据中，重接管时不重置寿命。

远端状态沿用现有导弹同步通道，客户端以脱离区块实体列表的代理绘制原生普通弹模型。超过当前投影远裁剪面时，仅为该次绘制扩展远裁剪面，保持原生模型变换与真实角尺寸，并保留短尾迹。该范围使用独立绘制通道与加载范围内的前景地形射线遮挡，避免将不同投影的深度值与原地形深度缓冲混用；近距离继续使用原深度测试。绘制后恢复投影与雾参数。服务端移除消息会立即清除远端代理。同步距离上限为 20 千米，客户端沿用既有导弹显示距离设置。

同步按根世界坐标选择同世界的所有在线玩家，包含 Sable 子空间内的观察者。显示器待发命令与计算机普通弹之间增加接管互斥，防止驾束弹被雷达路径抢占。

网络协议为 43，客户端与服务端需要使用相同的新火控包并完整重启。

## 已验证场景

- `testOrdinaryAirDefense`：10 秒动力曲线、1 马赫上限、20 秒寿命常量，以及提取后驾束算法与原反坦克算法的一致性。已加入标准 `check`。
- `testMianbaoLauncherAmmo`：七种防空发射器同时覆盖普通和高速弹，包含耗尽、错误物品及残留旧 NBT。
- `testAirDefenseIntercept` 与 `testSafetyRegressions`：现有拦截及安全回归通过。
- 原有高速防空弹与机载弹回归：132 次真实发射，实体与数学飞行轨迹对照通过；22 种原生弹体各 120 tick 的逐 tick 对照通过。结果见 `run_ordinary_aa_legacy_verify/server/mathematical-missile-integration-result.txt`。
- 独立测试服：七种真实发射器的漏斗/右键装填、真实无线弹种选择与发射、无雷达发射、单发扣弹、空瞄准拒发、显示器待发命令互斥、400 tick 飞行、200 tick 达到 340.3 米/秒、存储与重接管、失去计算机后的续飞、无玩家时飞行超过 4 千米、超时静默移除和加载票清理。七枚导弹各记录 399 条存活样本，共 2793 条；最大速度为 17.015 格/tick。
- 独立客户端：通过服务端数据包创建和移除远端代理；在未加载地形的 96 米普通视角和 512 米开镜视角验证原生模型可见像素，并保存截图；8192 米处验证代理与数据包同步不依赖地形区块。该客户端场景使用合成可视状态，飞行参数由上述真实服务端场景验证。

测试服结果与轨迹：`run_ordinary_aa_verify/server/ordinary-aa-result.txt`、`ordinary-aa-trajectories.csv`。客户端结果：`run_ordinary_aa_verify/client/ordinary-aa-client-result.txt`。画面：`run_ordinary_aa_verify/client/ordinary-aa-qa/ordinary-aa-96m.png` 和 `ordinary-aa-512m.png`。

## 复现命令

```powershell
.\gradlew.bat --offline -I tools/ordinary-aa-checks.init.gradle testOrdinaryAirDefense testMianbaoLauncherAmmo testAirDefenseIntercept testSafetyRegressions runServer -PcbcRuntime=true -PrunRoot=run_ordinary_aa_verify '-Pmod_version=0.9.1-ordinary-aa-beam' '-PmianbaoJar=D:/Game/PCLII/.minecraft/versions/Create航空学/mods/mianbaos_modernwarfare-2.6.0b6-neoforge-explosion-opt.jar'
.\gradlew.bat --offline -I tools/ordinary-aa-checks.init.gradle assemble runClient -PordinaryAaClientChecks=true -PcbcRuntime=true -PrunRoot=run_ordinary_aa_verify '-Pmod_version=0.9.1-ordinary-aa-beam' '-PmianbaoJar=D:/Game/PCLII/.minecraft/versions/Create航空学/mods/mianbaos_modernwarfare-2.6.0b6-neoforge-explosion-opt.jar'
```

所有编译输出位于专用 `build-ordinary-aa-verify/`；测试类不打入成品。

测试环境为 Minecraft 1.21.1、NeoForge 21.1.230、面包 2.6.0b6 和 Sable 2.0.6。上述客户端验收未加载 Iris、Sodium 或 Voxy，不代表已完成所有整合包组合验证。

## 交付包

`build/libs/create-fire-control-0.9.1-ordinary-aa-beam.jar`（23,893,637 字节），附同名 `.jar.sha256`。

SHA-256：`4698889acbebc14a0e110ab92b50c454c6023f6bc49034d539d9015abfb97679`。

成品内本次相关的 32 个类（含内部类）与验收编译输出一致，未包含普通弹集成测试类。普通弹制导、发射与火控计算机的核心类仍匹配完整服务端通过时保存的散列。最终客户端结果为 PASS，已检查 512 米截图中的原生弹体。
