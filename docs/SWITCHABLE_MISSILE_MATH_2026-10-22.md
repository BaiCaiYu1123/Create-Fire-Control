# 高速导弹数学飞行切换（实验实现，2026-10-22）

## 指令

```mcfunction
/cfc missile mode math
/cfc missile mode entity
/cfc missile mode
```

需要管理员等级 2。自 2026-10-23 起默认 `math`，旧存档首次更新也自动迁移到数学模式；只有更新后显式执行 `entity` 指令才启用实体模式，这个选择会保存到世界。迁移后的指令切换影响后续发射，已发射的每枚导弹继续自己的路线。原有实体、自加载、制导与远程渲染代码保留。详见[默认模式与旧存档迁移](MATH_DEFAULT_MIGRATION_2026-10-23.md)。

这份实现还不能作为“任意场景弹道零偏差、画面逐像素一致”的验收结论。下面区分已验证的部分和仍有限制的部分。

## 速度与弹种核对

以当前 `E:/Codex/mianbao/mianbaos_modernwarfare` 源码与实际构建 `mianbaos_modernwarfare-2.6.0b6-neoforge.jar`、火控现有接管参数为准。`1 Ma = 17.015 格/刻`；`2 Ma = 34.03 格/刻`。

| 原有控制路线 | 实际存在的实体 ID（省略 `mianbaos_modernwarfare:`） | 火控上限与弹道 |
| --- | --- | --- |
| 火控计算机的机载雷达、空空挂架 | `farmissile_1tanshe`, `farmissile_2tanshe` | 2 Ma；远距截获，寿命 400 刻 |
| 同上 | `missile_2tanshe`, `medium_aim_missile_2tanshe`, `mica_em_tanshe`, `aim_120_tanshe` | 2 Ma；中距截获，寿命 300 刻 |
| 同上 | `closemissile_1tanshe`, `closemissile_2tanshe` | 2 Ma；近距截获，寿命 200 刻 |
| 火控计算机的空地目标 | `agm_1tanshe`, `agm_3tanshe` | 1 Ma；比例导引直攻 |
| 同上 | `agm_2tanshe`, `agm_4tanshe` | 1 Ma；爬升、中段高弧、100 格内俯冲 |
| 光电吊舱激光制导 | `missiletanshe` | 1 Ma；按原有激光目标及转弯限制制导 |
| 火控计算机雷达、防空显示器、自动反导 | `antiairmissiletanshe` | 2 Ma；200 刻加速、10 刻安全/出筒段；计算机和显示器各自保留其制导/引信 |
| 显示器、普通发射架或垂发井 | `cruisemissiletanshe`, `cruisemissilelowtanshe`, `nuclearcruisemissiletanshe` | 1 Ma；普通巡航、独立低空跟随、核巡航分别处理 |
| 显示器、专用陆基发射器 | `groundnuclearcruisemissiletanshe`, `groundmissiletanshe`, `groundmissile_2tanshe`, `groundmissile_3tanshe`, `groundmissile_4tanshe` | 2 Ma；陆基核、高爆、集束、巡航、低空巡航分别处理 |

合计 22 种现存实体。兼容表中的 `agmmissile_3`、`agmmissile_4` 是旧版本别名，当前构建未注册这两个实体。光学/电视制导弹的上限为 0.5 Ma，不进入本次切换。

另有独立的原生弹道导弹/DF-26 路线：爬升约 0.65 Ma、巡航 2 Ma、末段 3 Ma。它们含贴架、脱轨和原生公开 API，本次切换没有替换这一独立体系。

## 实现方式

- 在原有设备完成接管后取消导弹世界实体注册。保留一个不注册、不 tick 的原生对象，作为旧制导、雷达、干扰弹和碰撞 API 的数据适配器。数学记录不申请沿途区块票据，也没有原生实体跟踪同步。
- 每刻先用旧速度移动，再施加原生箭矢的浮点阻力、重力、发动机步骤。之后继续执行原来的控制设备制导函数，避免用一条统一抛物线替代不同弹种。
- 保留原生朝向初始化、旋转插值、1200 刻/4096 格/世界高度及边界限制。巡航弹继续保留原有刷新飞行原点的例外。陆基高爆、集束的原生 620 刻结束条件也单独保留。
- 集束弹在原生条件下抛撒 24 枚子弹药，使用原生 `shoot` 的速度、散布与伤害参数；子弹药进入已有小炸弹数学模拟。若其共享容量耗尽，回退到原生子弹药。
- 碰撞使用已加载地形或异步读取已保存方块调色板，再扫掠实体和 Sable 结构；真实碰撞回调继续经过 NeoForge 投射物事件和原有导弹引爆入口。爆炸破坏仍可能需要加载落点，飞行本身不加载整条路径，也不预加载原有 25×25 落点显示区。
- 雷达和防空拦截的查询补入数学导弹，RWR/诱饵仍使用原设备持有的制导状态。
- 客户端在区块实体系统之外调用原生实体渲染器。模型、贴图、尺寸、旋转读取原有渲染器。
- 使用安装版本的原生粒子提供器，保留烟、焰、尾光、点火烟团的材质、尺寸、动画、寿命与物理行为。提前构造下一刻的整段粒子；未到各采样点时间的粒子既不 tick，也不提交渲染。时间到后交回原生粒子行为。动画时钟单调推进，暂停时停止，不随世界时间同步倒退。
- 同一世界的数学导弹批量同步；协议升级为 40，客户端与服务端需要同时更新。

## 已完成验证

- `testMathematicalMissiles`：批量网络完整往返、超量包拒绝、原生烟迹密度、下一刻各采样点的显示顺序、阻力/重力执行顺序。
- 现有 `testAirDefenseIntercept`（273 场景）、`testLowAltitudeCruise`、`testSafetyRegressions` 通过。
- 独立实际服务端装载面包 2.6.0b6 与 Sable 2.0.6：22 种弹、每种 120 个真实原生 tick，对比自由飞行位移、朝向及适用的发动机输出；数学对象未注册为世界实体，远处路径区块保持未加载。低空弹的原生地形推力被火控制导覆盖，因此不把该临时输出作为最终轨迹比较值。
- 同一测试验证实际巡航弹扣弹、取消实体注册后仍算发射成功、近墙原生引爆回调、24 枚集束子弹药、陆基一型 620 刻结束，以及切回实体模式后原生实体仍能正常加入世界。
- 独立实际客户端：22 个原生渲染器和原生粒子提供器完成加载、渲染、移除；普通速度与 2 Ma 场景中，未来粒子年龄保持为零，已到时间的粒子开始渲染/tick。截图位于 `run_mathematical_missile_client_verify/client/missile-math-qa/`。

## 验证边界

- 上述原生 tick 对照验证了自由飞行积分及设备共享参数，不能替代每个控制设备、载具运动、所有目标机动、动态碰撞和整段航程的对照。
- 尚未做旧路线与新路线的逐帧像素差分；原版粒子随机尺寸、旋转和散布本身也不是逐次相同的。旧路线已有的近远距离补偿粒子叠加，同样尚未完成逐项画面对照。不能据此承诺视觉零偏差。
- 未生成地形没有可读取的真实方块数据。数学模式不生成路径区块；未返回的保存数据会延迟碰撞判定，低空探测会暂时得到“未知”。这与把地形全部加载出来的路线可能产生不同结果。
- 数学在飞记录目前保存在内存；模式设置持久化，在飞导弹尚未实现退出世界/重启后的恢复。需要跨重启保持在飞弹药时使用实体模式。
- 仍复用原生烟粒子的碰撞和动画，以避免改变其外观，因此没有承诺大型烟幕场景的客户端 FPS 提升；服务端移除沿途区块工作的收益也不能用数学单测耗时替代实际 TPS 基准。

## 复现

```powershell
.\gradlew.bat --offline testMathematicalMissiles testAirDefenseIntercept testLowAltitudeCruise testSafetyRegressions
.\gradlew.bat --offline runServer -PmissileIntegration=true -PcbcRuntime=true -PrunRoot=run_mathematical_missile_verify -PmianbaoJar=E:/Codex/mianbao/mianbaos_modernwarfare/build/libs/mianbaos_modernwarfare-2.6.0b6-neoforge.jar
.\gradlew.bat --offline runClient -PmissileClientChecks=true -PcbcRuntime=true -PrunRoot=run_mathematical_missile_client_verify -PmianbaoJar=E:/Codex/mianbao/mianbaos_modernwarfare/build/libs/mianbaos_modernwarfare-2.6.0b6-neoforge.jar
```

这些集成检查使用独立测试目录，测试源码不进入成品 JAR。
