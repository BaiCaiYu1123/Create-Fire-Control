# 限位和有限目标取得修复

本轮反馈：超过最大俯角后卡住、方向机左右摆动、近远方块切换抽动，以及侧向行驶时偏移、取得新落点后先偏下再归正。

## 证据

只读检查用户游戏日志。mods 中火控 JAR 的 SHA256 是
`A20419511B1078A3D04E791F710DDA8D20D4ABA238140E58FBC2C2308BFCB766`，确为上轮发行包。
日志的异常阶段包含大量 `pitch-stop`、`ineligible`，有限落点误差曾超过 8°。
ModernUI 字体、配方和缓存告警没有显示与控制异常的因果关系。

新增原生双炮塔夹具复现：最大俯角 10° 时，高低机内部保持目标在一次有限目标刷新后变成约 −55.625°。
实际物理限位仍是 −10°。失败记录：`build/limit-arrival-baseline.log` 及该目录的轴状态 CSV。
内部目标和物理边界不一致，是越限恢复延迟的直接实现错误。

## 修改

- 每次有限弹道刷新、自动参考运输和有限目标操作输入推进，均在高低机父体坐标系限制本轴内部方向，防止越限积累。
- 自动运输只绕本轴旋转，方向机不再通过完整三维 `rotationTo` 被高低方向变化带动。
- 物理预测新增高低机真实单向限位行，包含原生的四元数梯度、单向冲量范围、约束行顺序和锁定行投影。
  旧构造器保留；默认不限位的其他调用不变。
- 物理模型可用性和某根轴能否参与反馈分开。限位轴仍参与载荷预测，其余合格轴继续补偿。
  停在边界的高低机保持自己的原生限位与电机命令，不要求方向机一起退出。
- 在有限目标取得阶段也预测下一物理步的枪口、瞄具交点和实际继承速度；保留本轴未完成的操作角差。
  不再等到取得完成及额外 0.15 秒制动期结束后才开启有限目标的物理补偿。
- 有物理瞄具块面信息时，新的深度或面切换重新按头瞄速度取得；同一块面的车体运动持续自动补偿。
  没有该元数据的旧光学输入保留原有持续运输行为。
- 日志增加各轴操作速度和剩余操作角差，便于区分取得中的误差与取得后的误差。

未改 Sable 后端、未直接写物理姿态或清速度、未将显示准星改画到目标上。
每轴仍控制自己的 child-only 电机，未恢复旋转驱动反作用直接回传车体。
新的内部限位处理、限位行预测和取得期预测限定于有限目标模式。
空处世界方向保持保留原有内部射线、预测资格和目标边界检查，避免把持续保持的世界射线改写为当前可达方向。

## 本轮测试

新增 `finiteLimitTransitions` 服务端场景使用真实控制器 actor tick、两个真实炮塔/CBC 枪口、五个有效固定焊接，
设置 10°/20° 俯仰边界与 40°/28° 每秒头瞄速度。
逐物理步检查越限返回、边界期间方向保持、转到侧面后的前进/倒车、近远落点取得完成的第一个物理步。
返回可达范围时，两高低机必须在第一个物理步开始响应。

- `build/limit-arrival-clear-ground/server/bearing-finite-limit-result.txt`：2840 样本 PASS。
  运动/停车落点峰值 0.003858652°，取得完成落点峰值 0.000678574°，限位期间方向误差峰值 0.001951199°。
- `build/limit-arrival-user-offsets/server/bearing-finite-limit-result.txt`：2840 样本 PASS。
  加入 YawOffset=(0,−0.5,0)、PitchOffset=(0,0,−0.5)、ScopeOffset=(1,1,0)。
  运动/停车落点峰值 0.002132814°，取得完成落点峰值 0.000870028°。
- `build/test-runs/native-articulated-prediction/bearing-articulated-prediction-result.txt`：208 原生对照 PASS。
  新增 20 个限位案例，覆盖两种物理步长、边界内/外状态及不同驱动方向。
  全部案例最大姿态差 0.000024767°，位置差 0.000000725839 格。

早期零偏移夹具的宽地面覆盖固定车体成员上表面，产生真实接触，预测/实际姿态突然分离至 0.79°，落点误差约 0.53°。
保留失败记录 `build/limit-arrival-repeat.log`，没有提高落点验收阈值。
夹具将目标地面保留在 x=63..113，避开成员 x=48..60 后通过。
此项只修正夹具地形；没有声称现有预测器已经包含地形接触求解。

`build/limit-combined-regression/server/bearing-finite-carrier-aim-result.txt`：组合运动 PASS。
3200 个运动样本，真实地面碰撞/渲染枪口碰撞落点峰值 0.012444921°；包含双炮塔、五个固定焊接、持续旋转、
前后平移、俯仰、急停、有限/空处切换。独立方向机局部参考变化为 0。

`build/limit-input-regression/server/bearing-finite-aim-transitions-result.txt`：1720 物理步 PASS。
零头瞄速度下实体保持峰值 0.001207459°，头瞄超速量 0°，解绑期间另一炮塔落点峰值 0.000587791°，
输入超时后的炮管保持峰值 0.001047627°。合法解绑/重绑保留原物理体；不覆盖物理体身份替换。

完整手杖回归曾暴露新规则改变空处方向保持的问题。用已发布控制器类逐字节对照，
`build/published-staff-comparison/server/bearing-integration-result.txt` 为 PASS。
最终修复将内部参考限制限定于有限目标，保留空处原行为。
测试夹具同时修正嵌套组装时序和无玩家时的子结构加载；未修改正式模组加载逻辑。
诊断中的 `child=null` 指当前预测组件没有该体，不能单凭它认定该体被卸载。

`build/limit-function-final/server/bearing-integration-result.txt`：完整功能回归 PASS。
包括取得制动、俯仰限位编辑、红石/原生约束恢复、心跳超时、解散、碰撞落点与自身车体排除，
抓炮塔让位及释放恢复、抓车体的双炮塔保持、仅稳定器持续旋转，以及主动炮塔与保持炮塔混合控制。
真实手杖抓车体时峰值 0.000598°/0.000580°；360°/秒持续旋转时 0.000577°/0.000267°。
功能流程中的早期取得阶段沿用其原有功能容差，没有把全部阶段宣称为严格精度验收。

`build/limit-arrival-final/server/bearing-finite-limit-result.txt`：最终源码、用户偏移、2840 样本 PASS。
真实地面碰撞落点峰值 0.002970731°，目标平面落点峰值 0.002186157°，
取得完成的第一物理步及后续落点峰值 0.000782120°，限位期间方向峰值 0.001369032°。
两高低机在恢复可达请求的第一物理步开始返回。该场景所有落点验收均保持 `<0.05°`。

`build/limit-acquisition-release-build.log`：`build shadowJar -PcharacterizeCarrierHold=true` 构建成功。
原有简化自由偏心重载精度对照仍输出 `KNOWN FAILURE`；characterization 参数仅允许这个已知精度失败保留，
不能由完整构建成功推断所有旧精度对照均通过。本轮新服务端有限落点验收及最终功能回归未使用该参数。

`build/limit-acquisition-package-verification.txt`：100 个生产控制/稳定/准星类与最终 Gradle 输出逐字节一致，
必需 Mixin 注册，测试类和原生 DLL 未入包，codec 正确重定位。

## 交付

[create-fire-control-0.7.8-limit-acquisition-stabilizer-fix.jar](../build/libs/create-fire-control-0.7.8-limit-acquisition-stabilizer-fix.jar)，14,658,265 字节。

SHA256：`91AE8621ABC666341A75B9220DBE173B7986D11886D8CF47BA4C7E405A69AE11`。

[校验文件](../build/libs/create-fire-control-0.7.8-limit-acquisition-stabilizer-fix.jar.sha256)。
替换旧火控 JAR，mods 中只保留一个火控模组，重启游戏。多人环境客户端/服务端使用同一包。
Sable 保持原版。未自动写入用户 mods，未改写用户存档。

尚未在用户实际客户端和坦克存档完成验收。服务端夹具的通过不等同于实际游戏的所有网络、碰撞与瞄具渲染条件均已通过。
