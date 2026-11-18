# 火炮附属弹道兼容检查

## 反馈与定位

用户补充的 `latest (1).log` 加载的是 **CBC Military Supplement 2.1.4**，其中火控在识别到已组装火炮后反复输出 `NO_AMMUNITION`、`profile=false`。本机游戏安装的是该附属的 **3.0.0** 文件（其 mod 元数据仍使用 2.1.9 版本号），两者的单管/双联装弹丸后端不同。

旧逻辑仅区分 CBC 大炮和已知物品供弹机炮。Military Supplement 的 `MountedDualCannonContraption` 因而落入普通大炮分支，而它的 `DualCannonProjectileBlock` 不是 CBC 的 `ProjectileBlock`。旧版独立的初速/寿命和新版 Shaolib 属性均没有被读取。

## 修改

- 为 Military Supplement 单管/双联装单独识别炮种，检查实际装入的完整炮弹，遵循发射时的首个完整弹丸顺序。
- 旧实体接口读取 `getProjectile`、`getInitVel`、`getLifetime` 以及真实重力、阻力；新版直接调用 `CBCMSDualCannonMunitionRegistry` 的实时属性解析器，读取 Shaolib 初速、重力、阻力与寿命。新版旧 JSON 的初速可能与实际 Shaolib 配置不同，不能混用。
- 寿命包含炮材质、战斗指令和设备修正。炮口包含单管/双联装实际管间距和当前炮管侧，不再使用普通机炮的炮口偏移。
- 先进技术重型机炮支持专用弹箱的实际出弹选择，以及快装炮闩内部已装填弹药；读取副本，不消耗弹药。
- Autogun Revolution 的高速弹会在首个飞行 tick 将完整速度翻倍；提前量现在包含这项实际增速及继承速度的相应变化。
- 炮种识别沿父类检查，避免名字不同的现有机炮子类误入大炮路径。
- 弹道缺失诊断增加炮种、实际装填物和炮体描述，便于确认其他版本接口。

普通炮弹提前量仍由同一 `CannonBallisticsCompat` 数据供光电、地面雷达和激光测距使用。没有更改炮弹初速、伤害、散布、相机捕获规则或网络格式。保持当前工作区版本 0.9.3、协议 52。

## 实际运行检查

新增可选 `-PcannonAddonMods=<mods目录>` 测试配置。测试只读取该目录中的指定附属 JAR，在工作区独立服务端世界加载；用户存档和安装文件没有被改写。

| 检查对象 | 实际检查 |
| --- | --- |
| Military Supplement 3.0.0 单管/双联装 | 实际装弹、Shaolib 实时属性、材质/指令/设备寿命、装填不被读取改变；直接调用真实 Shaolib 运动模型连续推进并比对预测位置 |
| Military Supplement 2.1.4 | 根据日志版本补齐旧实体接口分支；本机没有该版本 JAR，未运行该版本的完整集成测试 |
| 先进技术 0.1.4c 重型机炮 | 专用弹箱与快装炮闩均读取到真实炮弹，非兜底弹道；装填保持不变 |
| 先进技术双管机炮 | 实际已装填普通机炮弹，得到有效交会解 |
| Modern Warfare 中型炮、转管炮 | 实际炮材质、弹药/创造曳光弹匣读取及提前量生成 |
| Autogun Revolution | 普通与高速 APFSDS 读取；实际实体首 tick 运动与两倍初速预测一致 |
| 增强型弹药 1.4.0 | AP Shot Mk1、AP Shell Mk2，在真正装填火药的大炮中读取实际炮弹/装药属性 |

上述实际运行样本均检查移动目标交会，模拟误差小于 0.026 格。另执行 `testCannonLead`、`testLaserRangeCompensation`、`testCannonImpactPrediction`、`testCannonRenderFrame`、`testLockTrackingSmooth`、`testOpticalLockAim`、`shadowJar`、`verifyShadedJar`。

同时检查了附属中的火箭发射架、鱼雷管和先进技术推进火箭：这些有额外推进、介质或制导过程，不等同于本次验证的火炮弹道，未在本次声称完成其提前量兼容。

交付：`build/libs/create-fire-control-0.9.3-cannon-addon-ballistics.jar`。
