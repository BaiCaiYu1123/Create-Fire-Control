# 独立轴稳定器、驱动力矩与地面落点修复

日期：2026-10-03。本轮最终源码的服务端、原生检查与构建已完成，发行包已校验。
环境为 Minecraft 1.21.1 / NeoForge 21.1.230 / Sable 2.0.5 / 航空学 1.3.2，使用原有 Sable DLL。
没有自动安装模组、修改用户存档、瞬移结构或清除刚体速度。

## 实现

每根轴承的 `NativeBearingJoint` 独立管理父子铰链和世界到子体的驱动电机。
前者保留连接、承重、外力和俯仰止挡；后者只驱动对应旋转轴，没有父体电机反向力矩。
偏心连接仍会传递实际连接载荷，这不同于电机向父体施加等大反向旋转力矩。
铰链保留零力电机位，防止 Rapier 将无电机关节重排到 SIMD 桶；该配置不施加驱动冲量。
每步世界电机框架固定，基础目标不重复预测父体转动。

方向机与高低机分别保存保持方向、请求、校准、限位、速度和残差历史。
`solveIndependent` 每个反馈环只试探和修正自己的驱动电机；其他电机目标在该次试算中固定。
最多六轮反馈，残差容差 `1e-5 rad`、总修正边界 0.15 rad；未放宽服务端 `<0.05°` 验收线。
连接结构的物理载荷仍统一预测，因为它们实际上通过铰链相连。

方向机局部保持基准校准后不随炮管俯仰重写。方向环的 `AzimuthObservation` 只读取实际炮管横向误差，
不读取或覆盖高低机的控制目标，也不让一个轴驱动另一个轴的电机。
共有方向机沿用各炮管等权观测；每根高低机使用自己的俯仰限位，保留多炮塔分组。
转速限制只作用于主动头瞄指令；车体补偿不使用该速度预算。

## 已定位问题

使用实际中口径 APFSDS：`create_fire_control:apfsds_medium_shell`，短炮管实际速度12格/tick，
重力 -0.03、线性阻力0.01。没有用自动炮 fallback 冒充中口径检查。
炮口几何已和 CBC-MW 实际 `fireShot` 字节码对照，未盲改炮口偏移。

急停俯仰时曾出现方向机参考线误差几乎为零，而实际炮管横向误差约0.08°。
同一步预测与实际姿态差约0.0002°，说明当时主要误差在反馈观测，而非物理预测精度。
改为观测实际炮管横向误差后，该步反解残差约0.000001°。
另外修复了头瞄俯仰预裁剪误用火控全局限位的问题，现在读取对应高低机的设置。

## 过程证据（均保留）

- `build/independent-axis-carrier`：世界电机子锚点不在子 plot 内导致创建失败；已改为真实子轴心。
- `build/independent-axis-carrier-valid`：原生关节求解顺序尚未修复时的严格失败。
- `build/independent-axis-native-offset.log`：无电机铰链的原生 SIMD 重排对照失败。
- `build/independent-axis-native-ordered.log`：164例原生对照通过，最大姿态差0.000024767°，位置差0.000000323303格。
  中心轴纯子体驱动时父体角速度低于 `1e-7 rad/s`，同时验证子体确实转动。
- `build/independent-medium-ordered`：真实中口径急停偏差最大约0.146°，FAIL。
- `build/independent-medium-ground`：无玩家的测试世界卸载远处地面后无法计算碰撞标记，FAIL；
  后续只在独立测试世界保持目标附近区块加载，没有改生产碰撞逻辑。
- `build/independent-medium-ground-loaded`：真实地面碰撞和参考线反馈的严格失败。
- `build/independent-medium-frame-held`：固定方向机校准基准后，仍保留实际炮管横向偏差的失败证据。
- `build/independent-medium-head-trajectory`：中间版双炮塔八阶段 PASS，包含首步、每个运动子步与全部急停采样；
  地面实际碰撞落点最大0.013706520°，运动/停车最大0.030751644°。这不是最终版全部功能验收。
- `build/independent-axes-regression`：普通保持缺少实际炮管横向观测，手杖抓车体峰值0.084910°，FAIL。

`build/independent-axes-native-final.log` 的子体驱动检查还发现一个旧测试假设：它要求任何保持电机都必须改目标。
世界电机在父体绕同一世界轴转动时可以保持原目标；该假设仅对两体电机成立。
修正这一测试前提后，实际射线、连接精度和兄弟电机不被修改的断言继续严格执行。
全部过程失败保留，均不计作通过。

## 最终源码验收

三组八阶段测试均采样运动首步、每个运动物理步与全部停车阶段，每组包含3200个运动样本。
包含近/远有限目标、正反120°/秒、360°/秒、车体俯仰、转动加俯仰、空射线、重新命中。
所有阶段保持 `<0.05°` 阈值，未使用 characterization 参数。
方向机局部保持基准在炮管俯仰和模式切换中的最大变化均为0，未被高低机反馈重写。

| 最终流程 | 运动标记最大偏差 | 停车标记最大偏差 | 结果 |
|---|---:|---:|---|
| [中口径 APFSDS 地面目标](../build/independent-axes-medium-ground-release/server/bearing-finite-carrier-aim-result.txt) | 0.015335031° | 0.010055474° | PASS |
| [中口径 APFSDS 方块正面](../build/independent-axes-medium-wall-release/server/bearing-finite-carrier-aim-result.txt) | 0.015646618° | 0.009811553° | PASS |
| [自动炮有限/空目标](../build/independent-axes-autocannon-release/server/bearing-finite-carrier-aim-result.txt) | 0.025585662° | 0.015769677° | PASS |

地面流程使用生产 `CannonBallisticsCompat.predictImpact` 检查真实碰撞落点，最大相机视线偏差0.013401007°。
运动/停车列同时取实际碰撞落点和目标平面误差的较大者；空射线测量实际炮管方向。
没有把头瞄输入目标直接作为落点标记。

[完整服务端功能回归](../build/independent-axes-observation-regression/server/bearing-integration-result.txt) PASS：
包含左右高低机、俯仰限位迁移、主动瞄准到位制动、解绑/重连/解散、实际手杖抓炮塔与抓车体、
释放恢复、关闭头瞄保持、多炮塔混合主动转动/保持、CBC命中和空射线标记。
抓车体且头瞄速度为零时双炮塔峰值0.000585° / 0.000565°。
该广泛功能回归中的早期取得阶段使用原有功能性容差，不能称所有阶段均为0.05°精度验收；
三组上表流程及手杖保持的精度断言独立严格执行。

[独立静止后抓取/释放](../build/independent-axes-isolated-release/server/bearing-isolated-staff-accuracy-result.txt) PASS：
抓取前自然休眠，抓取中峰值0.000536779° / 0.000463027°，释放后0.000584505° / 0.000129436°。
释放时保留整车动量，随后自由下落，没有清除速度或补写姿态。

[模式及命令过期检查](../build/independent-axes-transitions-release/server/bearing-finite-aim-transitions-result.txt) PASS，1720个物理步：
零转速保持炮管偏差0.004492098°，相同视线移动相机的标记偏差0.000767524°，主动限速超出量0，
解绑时兄弟标记偏差0.000763033°，命令实际过期后的炮管偏差0.004337308°。
合法解绑/重绑保持原物理体，因此不覆盖换成另一个物理体的来源身份场景。

[32例原生部分轴检查](../build/independent-axes-native-subset-validated.log) PASS，新增世界子体驱动及单变量反馈路径：
一个电机主动转动时，另一个保持电机的射线峰值0.000004005°，实际/预测姿态差0.000008763°。
未被选择的电机命令逐位保持不变，主动电机确实转动。

[共享方向机检查](../build/independent-axes-shared-yaw-final.log) PASS，新增生产的子体驱动和独立反馈路径：
40/120Hz、四种正反转速，各采样完整4秒；一根方向机承载两根不同俯仰的高低机。
包含启动首步的新路径射线最大0.000123780°，连接误差最大0.000070323格；两根炮管保持约17°的独立俯仰差。
连接载荷有限，方向机没有把两个高低机合并成相同目标。

## 最终构建与发行包

[最终构建日志](../build/independent-axes-final-build.log)：`build -PcharacterizeCarrierHold=true` 成功，19秒，67个任务。
该参数只放行保留的旧简化自由偏心重载对照；其旧精度失败仍不是通过。
本轮服务端严格检查均未使用该参数。其他功能、注册迁移、网络、弹道、原生预测及新共享轴检查通过。
[独立轴承蓝图设置检查](../build/independent-axes-blueprint-check.log) 另行PASS，验证保存/放置时轴承引用重映射，
并无损保留轴承速度与隐藏设置；这是编码回归，未在客户端执行实际蓝图放置。

[发行包](../build/libs/create-fire-control-0.7.8-independent-axes-stabilizer-fix.jar)，大小14,628,211字节。
[SHA256](../build/libs/create-fire-control-0.7.8-independent-axes-stabilizer-fix.jar.sha256)：

```text
B4F28F930B61A18ECC41BAF41E35C422374C772DA1973CC2932629D6627945DE
```

[包检查](../build/independent-axes-package-audit.txt) 确认84个稳定器、控制器、标记和相关mixin类
与最终Gradle编译输出逐字节一致；必要mixin已注册，测试类和原生DLL未打包，JCodec/JAAD正确重定位。
检查脚本为 [verify_stabilizer_package.ps1](../tools/verify_stabilizer_package.ps1)。
游戏安装目录仍是上一轮包，SHA256为 `191AF77BB942FD7F28E2AB262554853A67E2248AC45705C76937338F911634C6`。
本轮没有自动替换它。

## 范围

保留现有Sable、多炮塔、主动限速和俯仰止挡；仅验证上述具体环境与夹具。
未在用户车辆存档中运行，也没有直接验收客户端HUD渲染。
接触、额外转轴焊接/绳索、未知物理连接、超出8轴/17刚体预测规模或电机能力的载荷会保留原伺服回退。
偏心铰链仍能传递连接载荷；取消的是驱动电机对父体的直接反向旋转力矩。
这是用户选择保持现有Sable后的已验证修复，不能宣称任意结构与外力下数学意义的零误差。
