# 有限落点稳定器修复及验收记录

日期：2026-10-03。JDK 21，Minecraft 1.21.1 / NeoForge 21.1.230，
现有 Sable 2.0.5 / 航空学 1.3.2。服务端与 JNI 检查使用原有 Sable 原生 DLL，
没有修改或替换 Sable，没有运行或修改用户车辆存档，没有自动安装火控 JAR。

本记录补充 [上一轮验收](STABILIZER_ACCEPTANCE_2026-10-03.md) 的覆盖遗漏：
固定世界射线通过，不能证明指向方块的有限目标在车体运动中也通过。
主详细表来自最新源码的完整八阶段验收 `build/finite-aim-carrier-release`，已严格 PASS，
包含全部运动首步和停车采样；命令实际过期及模式转换也已通过最新 1720 步检查。
最新完整其他功能服务端回归也已 PASS；较早版本和严格失败仍保留。
最新独立静止后抓取也已 PASS。最终构建和发行包检查已完成；
交付文件及 SHA256 见下节。

## 最终发行包

[修复包](../build/libs/create-fire-control-0.7.8-finite-aim-stabilizer-fix.jar)，
[SHA256 文件](../build/libs/create-fire-control-0.7.8-finite-aim-stabilizer-fix.jar.sha256)。
大小 14,626,878 字节，使用现有 Sable 后端。

```text
191AF77BB942FD7F28E2AB262554853A67E2248AC45705C76937338F911634C6
```

[最终构建日志](../build/finite-aim-final-build.log)：
`build -PcharacterizeCarrierHold=true` 成功，17 秒，67 个任务。
有限目标、来源身份、平台预测尾部、动态目标反解及可变关节框架检查通过；
144 例原生预测对照的最大姿态误差为 0.000024767°，位置误差为 0.000000323303 格。
该参数仅保留并放行旧简化控制器的偏心自由重载对照失败；
旧检查仍报告 `KNOWN FAILURE: production sustained hold accuracy`，
不能写成全部旧重载精度检查通过。上述最新服务端严格验收均未使用该参数。

[发行包检查](../build/finite-aim-package-audit.txt) 确认生产稳定器、控制器和弹道类
与最终编译输出逐字节一致；`FiniteAimTarget.Source`、稳定炮口速度和新框架 mixin 已打包并注册。
测试夹具、检查类和原生 DLL 未进入发行包，JCodec/JAAD 使用重定位包名。
游戏 mods 中的火控包仍为上一版 `D69053AC28C988EFE3024FE9E66755923C03511D808AAFC10A94EB854A7F8147`；
本次未自动替换，旧独立交付文件也保留。

## 根因与修复

旧火控计算有限目标时，在实际炮口位置采样外层车体的速度：
`sampleBodyVelocity(level, BearingStabilizer.aimCarrier(bearing), profile.muzzle())`。
这相当于把稳定炮管当成刚性长杆，附加 `ω车体 × (炮口 − 车体质心)`。
实际稳定器已经让炮管反转，取消了对应的炮管角运动。这项虚构速度进入逆弹道，
使炮管向车体转动的反方向补偿；落点显示使用真实炮管速度，两条计算路径不一致。
车体停转后虚构项消失，偏差随之归正。空射线跳过有限目标逆弹道，因而没有相同的持续偏移。
俯仰也会产生相同的长杆速度错误。

`FiniteMuzzleVelocityChecks` 使用生产逆弹道和 CBC 飞行方程复现此问题：
炮口长杆 8 格、初速 8 格/tick，车体 120°/秒对应虚构横速 0.837758 格/tick，
引入约 6.011021° 的反向偏移；360°/秒约为 18.310067°。
检查同时保留真实平移、重力及线性/二次阻力，证明不能直接把继承速度全部清零。

现在有限目标绑定实际炮口刚体，保存炮口与炮管方向的局部坐标和世界目标。
获取目标时，用受控祖先关节的名义稳定运动学计算炮口速度：

```text
v稳定炮口 = v外层车体(炮口) + Σ qdot关节 × (炮口 − 真实关节轴心)
```

其中关节反转速度由炮管方向的二维切平面最小范数解求出，只抵消保持该世界方向所需的运动。
真实平移、偏置轴心输运与未受控滚转仍保留。目标不可达或输入退化时回退原控制。
获取阶段不把操作者自身瞄准速度反馈进新的瞄准要求，避免获取自激。

同一来源、同一目标或未改变的视线继续追踪时，车体运动及有限射线重采样引起的方向变化
直接运输保持方向。它们不反复重置 0.15 秒到位制动，也不受头瞄主动转速限制。
首次新输入、主动更换视线和模式切换仍走原有获取与主动限速。
共有方向机保留各炮管的等权方向来源，各高低机分别保留自己的有限目标。

进入联合物理预测后，每次试算都从该次预测的实际炮口刚体 COM、位置、姿态、线速度、
角速度重新求炮口速度和逆弹道方向。Newton 的响应探针也重新计算目标，
避免把上一帧或未经该电机命令影响的弹道方向当成常量。
动态目标最多 6 次 Newton 修正，静态目标仍最多 3 次；每次修正上限 0.05 rad，
相对原命令的总修正上限仍是 0.15 rad，残差容差仍为 `1e-5 rad`。
逆解拒绝时原电机命令逐位不变。旧 3 次动态求解的独立严格失败保存在
[three-iteration/output.txt](../build/finite-dynamic-refinement-local/three-iteration/output.txt)，
错误为 `nonlinear dynamic target converges: DID_NOT_CONVERGE`；新增迭代没有放宽精度或总修正范围。

停车时还发现独立的休眠门控缺陷：即使测得车体及子体运动很小，当前已准备的电机目标
仍可能偏离真实关节坐标。旧判据只看运动和外部电机，过早退出联合规划，让旧目标重新扰动炮管。
第一步修复同时检查所有已准备电机目标与真实关节坐标的差；超过 `1e-5 rad` 继续预测，
由此得到中间版 `pending-motor` PASS。固定的操作者电机也参与检查，因为它会对保持中的
兄弟炮塔产生反作用。随后独立静止抓取回归揭示此规则对普通固定射线过宽，
最新门控及其独立复测见“普通射线休眠回归”。

## 有限点和空射线的严格物理检查

[最新完整八阶段流程 PASS](../build/finite-aim-carrier-release/server/bearing-integration-result.txt)、
[八阶段结果 PASS](../build/finite-aim-carrier-release/server/bearing-finite-carrier-aim-result.txt)、
[逐步数据](../build/finite-aim-carrier-release/server/bearing-finite-carrier-aim-diagnostics.csv)、
[控制状态数据](../build/finite-aim-carrier-release/server/bearing-finite-carrier-lock-diagnostics.csv)。

生产火控控制器与原生五刚体双炮塔在新建超平坦服务端运行。物理频率 40 Hz，
`dt=0.025s`。静止获取后头瞄速度设为零，车体仍执行 +120/-120/+360°/秒转动或俯仰。
每阶段两个炮塔各记录全部 200 个运动物理步，包含启动首步，没有跳过换向或起步暂态。
有限点误差来自实际炮口继承速度的 CBC 扫掠轨迹与方块目标面交点，空射线检查实际炮管世界方向。
获取完成、持续运动、停止的严格条件均为 `<0.05°`，未使用 characterization 放行。

下表每格为炮塔 0 / 炮塔 1，单位为度。“首步”是相应阶段运动开始的第一个物理步；
运动峰值覆盖全部 200 步，停止峰值覆盖该阶段全部停车采样。

| 阶段 | 获取后误差 | 运动首步 | 运动峰值 | 停止峰值 |
| --- | ---: | ---: | ---: | ---: |
| 0 近点 +120°/秒 yaw | 0.000495013 / 0.000267420 | 0.001170848 / 0.001132608 | 0.006711613 / 0.013570384 | 0.004834531 / 0.001315152 |
| 1 远点 -120°/秒 yaw | 0.000629386 / 0.000642816 | 0.001570041 / 0.000011643 | 0.006872854 / 0.014202233 | 0.004118405 / 0.000927418 |
| 2 远点 +360°/秒 yaw | 0.000799674 / 0.001076419 | 0.003188848 / 0.001670291 | 0.019107238 / 0.040937246 | 0.012319180 / 0.004411162 |
| 3 近点 pitch | 0.000957808 / 0.000818351 | 0.000769598 / 0.000368159 | 0.001715168 / 0.001873986 | 0.006223002 / 0.003161170 |
| 4 远点 yaw + pitch | 0.001432971 / 0.000691803 | 0.001134685 / 0.000957050 | 0.006853967 / 0.014652948 | 0.005442749 / 0.002927929 |
| 5 空射线 +360°/秒 yaw | 0.000006830 / 0.000003195 | 0.000009035 / 0.000038637 | 0.000616024 / 0.000578593 | 0.000454175 / 0.000485802 |
| 6 空射线 pitch | 0.000010664 / 0.000013364 | 0.000109270 / 0.000130454 | 0.000116917 / 0.000159699 | 0.001251975 / 0.000982589 |
| 7 重新取得有限点 yaw + pitch | 0.001313727 / 0.000797963 | 0.001561343 / 0.000375083 | 0.006488070 / 0.015122653 | 0.004867958 / 0.012187340 |

总体运动峰值 0.040937246°，启动首步峰值 0.003188848°，停止峰值 0.012319180°。
各阶段车体累计角行程为 10.236498、10.211222、30.631722、0.195777、10.243647、
30.673447、0.194940、10.224458 rad；检查包含多圈持续转动。
首步取 CSV 的 age `200+220×阶段` 的首两行，停车从 age `300+220×阶段` 开始；
同一 tick 有两个物理步，表中首步没有混合第二步。

[最新规划诊断](../build/finite-aim-carrier-release/server/bearing-servo-planning-result.txt)：
`no-held-motor=50, accepted=3218, fixed-motor-accepted=10, resting-carrier=312`。
末尾空 `{}` 是另一组诊断，没有代表额外误差通过。
锁 CSV 在物理步采样时已消费并清空 `servoStep`，其 `model_ready/eligible=false`
不能解释为本步没有实际联合规划，应结合上述累计计数及实测误差判断。

中间版 [pending-motor 八阶段 PASS](../build/finite-aim-pending-motor/server/bearing-finite-carrier-aim-result.txt)
及 [原规划统计](../build/finite-aim-pending-motor/server/bearing-servo-planning-result.txt) 仍保留：
`no-held-motor=50, accepted=3526, fixed-motor-accepted=10, resting-carrier=4`。
此版本尚未缩小普通射线休眠门控及加入模式/超时恢复保护；其 phase 5–7 完整数值
以该历史结果文件为准，不能覆盖主表最新源码结果。阶段 0–4 的数值与最新运行一致。

## 目标转换与来源身份

[休眠门控调整前的有限点转换流程 PASS](../build/finite-aim-transitions/server/bearing-integration-result.txt)，
[转换精度结果](../build/finite-aim-transitions/server/bearing-finite-aim-transitions-result.txt)：
1560 个物理步，零主动速度保持峰值 0.000332489°，炮管偏差峰值 0.005770979°，
未改变视线但移动相机时落点峰值 0.002078031°，主动转速超限 0.000000000°，
解绑期间兄弟炮塔落点峰值 0.001024625°。
这些转换使用真实控制器和 CBC 几何；合法解绑/重绑保留原物理刚体，
没有集成验证同一刚体上实际更换为另一座炮架。
该 PASS 是较早检查版的结果；普通射线休眠门控调整后出现新的模式切换失败，
不能用此旧 PASS 代表最新源码转换精度，详见下面的转换回归。

随后来源身份补充炮架绑定位置和炮体实体 UUID，避免同一物理刚体上的两座不同炮架
继承彼此的已获取状态。对应纯回归新增于 `FiniteAimTargetChecks`；
身份修改及普通射线休眠门控版的
[有限点八阶段流程 PASS](../build/finite-aim-release-final/server/bearing-integration-result.txt)，
[八阶段结果](../build/finite-aim-release-final/server/bearing-finite-carrier-aim-result.txt)
仍严格包含全部运动首步及停车采样，运动峰值 0.040937246°。
此 run 早于后续模式恢复保护修改，不能作为最新完整源码的最终验收。

## 其他功能和多炮塔回归

[最新完整其他功能回归 PASS](../build/finite-aim-regression-release/server/bearing-integration-result.txt)，
覆盖当前模式转换/命令过期恢复保护源码的既有生产流程：双轴限位、装配与偏移、反作用、
主动到位制动、真实手杖让控/释放、解绑、重连和解散。
[最新混合多炮塔 PASS](../build/finite-aim-regression-release/server/bearing-mixed-turret-result.txt)：
车体 120°/秒，炮塔 0 按操作者 30°/秒主动瞄准并实际转动 39.367361°；
保持世界方向的兄弟炮塔峰值偏差 0.000546898°。
[最新真实手杖抓车体 PASS](../build/finite-aim-regression-release/server/bearing-hold-accuracy-result.txt)，
两个炮塔峰值分别 0.000400° / 0.000142°。

[最新持续转动回归 PASS](../build/finite-aim-regression-release/server/bearing-turn-accuracy-result.txt)：

| 车体转动及瞄准状态 | 炮塔 0 峰值 | 炮塔 1 峰值 |
| --- | ---: | ---: |
| +120°/秒，头瞄速度为零 | 0.000270° | 0.000268° |
| -120°/秒，仅稳定器 | 0.000268° | 0.000234° |
| +360°/秒，头瞄速度为零 | 0.000696° | 0.000725° |
| +30°/秒，仅稳定器 | 0.000240° | 0.000208° |

此既有持续转动流程采用稳定阶段定义；主表有限点流程另外覆盖全部启动首步和停车采样。
[最新回归规划统计](../build/finite-aim-regression-release/server/bearing-servo-planning-result.txt)
仍保留正常回退：`accepted=2109, fixed-motor-accepted=115, DID_NOT_CONVERGE=21,
SATURATED_RESPONSE=1, unmodelled-connection=230, ineligible=56, resting-carrier=156,
no-held-motor=13`。解绑/重连及额外连接阶段可回退；严格结果检查实际最终误差，
没有把回退次数本身算成精度通过。

较早回归结果继续保留如下，不能替代以上最新运行：
含来源身份修改、尚未缩小普通射线休眠门控的检查版已经编译并加载，
[完整既有功能服务端回归 PASS](../build/finite-aim-regression-final/server/bearing-integration-result.txt)。
流程沿用双轴限位、装配与偏移、反作用、主动到位制动、手杖让控/释放、解绑、重连和解散检查。
[混合多炮塔结果 PASS](../build/finite-aim-regression-final/server/bearing-mixed-turret-result.txt)：
车体 120°/秒，炮塔 0 按操作者 30°/秒主动瞄准并实际转动 39.367246°；
保持世界方向的兄弟炮塔峰值偏差 0.000383300°。
[真实手杖抓车体 PASS](../build/finite-aim-regression-final/server/bearing-hold-accuracy-result.txt)，
两个炮塔峰值分别 0.000102° / 0.000459°。

[持续转动回归 PASS](../build/finite-aim-regression-final/server/bearing-turn-accuracy-result.txt)：
固定世界方向在 +120°/秒的双炮塔峰值为 0.000428° / 0.000264°，
-120°/秒为 0.000228° / 0.000295°，+360°/秒为 0.000631° / 0.000301°，
+30°/秒为 0.000228° / 0.000234°。
这些旧流程中的持续转动值使用其稳定阶段定义，不能取代上表包含启动首步的有限点全步检查。
这次较早完整回归先于后续普通射线休眠门控修改；最新完整结果已另行归档于本节开头。

## 普通射线休眠回归

[第一次独立静止复测 FAIL](../build/finite-aim-isolated-final/server/bearing-integration-result.txt)：
`isolated fixture has settled angular momentum before grabbing`。
其静止前提在真实抓取前失败，因而没有生成抓取精度结果，不能说抓取后的精度已经失败或通过。
[原日志](../build/finite-aim-isolated-final.log) 保留五体状态，例 yaw1 角速度仍约
`(0.000786219, -0.000689097, -0.002301107) rad/s`，没有强行清速度让前提通过。

原因是普通固定世界射线的静载荷偏置也被无条件待执行电机检查持续反解，
让原生无法自然休眠。第一版缩小后的门控保留有限瞄准组件的全部待执行电机检查；普通固定射线仅在
`MotorTarget.hasCarrierPrediction(1e-3)`、真实操作者运动或机械命令速度非零时检查。
其中载体预测检查同时考虑前次载体速度和过滤后的速度变化，真实车体刚停时仍可保持
未消失的预测补偿；稳定静载荷进入原休眠路径。所有轴的实际子体角速度检查仍保留，
原有停车精度 `<0.05°` 未放宽。该改变没有删掉上表中间结果或旧 FAIL。

[调整门控后的独立复测 PASS](../build/finite-aim-isolated-sleep/server/bearing-integration-result.txt)，
[历史状态及精度](../build/finite-aim-isolated-sleep/server/bearing-isolated-staff-accuracy-result.txt) 保留，
此版本先于模式恢复保护修改。
[含全部最新修复的独立抓取流程 PASS](../build/finite-aim-isolated-release/server/bearing-integration-result.txt)，
[最新真实状态及精度](../build/finite-aim-isolated-release/server/bearing-isolated-staff-accuracy-result.txt)：
抓取前五刚体实际线速度和角速度均精确为零，未强制清速度满足前提。
两个炮塔的世界方向峰值如下，数值与较早 `isolated-sleep` 独立复测一致：

| 阶段 | 炮塔 0 | 炮塔 1 |
| --- | ---: | ---: |
| 抓取前 | 0.000010316° | 0.000008452° |
| 真实手杖抓车体期间 | 0.000156066° | 0.000218833° |
| 释放后 | 0.000209410° | 0.000163095° |

释放后保留实际速度、角速度并继续自由下落，没有强制设置结构姿态；
结束高度仍离地，不能把结果写成撞地精度通过。此最新独立复测已覆盖当前源码，
最新完整其他功能回归也已通过，见前节。

## 有限点与空射线转换回归

缩小普通射线休眠门控后，
[转换最终检查 FAIL](../build/finite-aim-transitions-final/server/bearing-integration-result.txt)，
[严格错误](../build/finite-aim-transitions-final/server/bearing-finite-aim-transitions-result.txt)：
age 480 从有限点切到零主动速度空射线的第一物理步，炮塔 1 实际炮管相对切换基准
偏移 0.288967872°，保持方向的 yaw/pitch 变化仅 0.000000155° / 0.000330865°。
此时 `accepted` 停在 799，`resting-carrier` 第一次增至 1；
不是操作者保持方向主动移动约 0.289°，而是切换时实际炮管失去本步联合补偿。
[转换逐步数据](../build/finite-aim-transitions-final/server/bearing-finite-aim-transitions-diagnostics.csv)
和 [锁状态](../build/finite-aim-transitions-final/server/bearing-finite-carrier-lock-diagnostics.csv)
保留该失败。

有限逆弹道与普通世界射线使用不同的修正历史。`resetFiniteModeHistory()` 在有限目标
模式进入或退出时清理 `motorResidual`、`motorTarget`、`aimVelocity`，并设置 0.5 秒
`finiteModeRecoveryRemaining`。最新实现也在 `refreshFiniteDirection` 发现有限瞄准
命令不属于当前游戏 tick，或世界稳定已关闭时调用同一 helper，再清除过期有限目标和补偿标记。
清理只处理控制历史，不改变真实刚体位置、姿态、线速度、角速度或当前保持方向。
恢复窗口内继续检查待执行目标，避免普通射线刚接管或命令刚过期时立即休眠；
窗口按实际物理步递减，之后回到普通射线休眠判据。
这项恢复保护维持当前保持方向，不绕过头瞄新输入的主动转速限制。
本次检验的是历史清理与恢复窗口的组合，未把两者拆开宣称各自独立充分。

[加入模式恢复后的转换流程 PASS](../build/finite-aim-transitions-recovery/server/bearing-integration-result.txt)，
[1560 步严格转换结果](../build/finite-aim-transitions-recovery/server/bearing-finite-aim-transitions-result.txt)：
零主动速度保持方向峰值 0.000439739°，实际炮管峰值 0.006961639°；
未改变视线但移动相机时落点峰值 0.002078031°，主动转速超限 0.000000000°，
解绑期间兄弟炮塔落点峰值 0.001016797°。
同一刚体上的实际炮架替换仍没有集成覆盖。
此 1560 步检查覆盖模式转换，尚未覆盖实际命令超时；该覆盖由下面的新检查补齐。

[含实际命令超时的最新转换流程 PASS](../build/finite-aim-transitions-verified/server/bearing-integration-result.txt)，
[1720 步严格结果](../build/finite-aim-transitions-verified/server/bearing-finite-aim-transitions-result.txt)：
零主动速度保持方向峰值 0.000439739°，实际炮管峰值 0.006961639°；
未改变视线但移动相机时落点峰值 0.002078031°，主动转速超限 0.000000000°，
解绑期间兄弟炮塔落点峰值 0.001007495°，实际输入过期后炮管偏移峰值 0.006923214°。
[逐步转换数据](../build/finite-aim-transitions-verified/server/bearing-finite-aim-transitions-diagnostics.csv)
及 [锁状态](../build/finite-aim-transitions-verified/server/bearing-finite-carrier-lock-diagnostics.csv)
保留首步和后续状态。

超时检查先确认四轴均已启用 `finiteCompensationActive`，且有限目标列表非空，
再停止发送有限瞄准命令。每个物理步确认四轴 `ownsJoint`，
第一次目标列表清空时确认恢复窗口大于零；到阶段结束四轴都必须确实经历过该分支，
且有限目标已清空、有限补偿标记已关闭。炮管从过期首步到全部后续采样均严格 `<0.05°`。
因此这项 PASS 覆盖生产 helper 的真实超时路径，不是原本就无有限目标的空操作。
检查没有直接改目标列表、补偿标记或刚体姿态来满足前提。

最新源码的 `build/finite-aim-carrier-release` 全八阶段已严格 PASS，见主详细表；
`build/finite-aim-regression-release` 完整其他功能回归也已 PASS，
`build/finite-aim-isolated-release` 独立静止后抓取也已 PASS；最终构建和发行包检查见开头记录。
上述最新转换、全八阶段、完整其他功能和独立抓取 PASS 不能替代尚未完成的发行构建/打包检查，
所有历史 FAIL 及较早版本 PASS 保留。

## CBC 输入及实际分析边界

测试安装实际 CBC 钢制自动炮炮架、钢炮闩和三节炮管，炮闩输入 AP 自动炮弹。
没有注入弹道缓存或手写 profile。但生产分析在此夹具中实际选择了
`createbigcannons:fallback_autocannon_round`，不是完整解析出的 AP profile。
[最新装配日志](../build/finite-aim-carrier-release.log) 明确记录：初速 7.5 格/tick、
重力 -0.025、阻力 0.01、线性阻力、寿命 60 tick、弹质量 2.0、后坐参数 3.0。
检查测量上述生产分析结果的实际炮口帧和轨迹；测试炮体只提供几何和弹药分析，
没有实际发射炮弹或验证真实后坐。不能把该结果写成所有 CBC AP 弹药解析与射击都已经验证。

## 独立支持框架奇点与夹具修正

前几次服务端运行还出现原生车体支持失稳，不能全部归因于有限目标逆解。
[独立 JNI 探针](../build/finite-world-frame-local/FreeSupportSingularityAudit.java)
只有单个刚体及世界自由支持，没有炮塔、稳定器或火控，仍复现相同支持框架问题：

- 原固定 free support 在第 60 步、累计输入 yaw 180° 时角速度升至 13.689571774 rad/s，触发有界断言。
- 每步跟随当前 yaw 重定支持框架后，400 步、累计输入 yaw 1200° 全程有界，角速度峰值 2.097155835 rad/s。

[探针日志](../build/finite-world-frame-local/free-support-singularity.log) 保留两种结果。
这是该人工世界支持的独立原生边界，不能当成真实车辆模型的一般规律。
最终有限点夹具使用 generic 自由关节，在受控铰链之后创建世界支持，
每物理步把世界支持框架重定到当前 yaw；世界线性目标、锚点、刚度、阻尼及 yaw 相对的
pitch/roll 目标不变。这是测试支持关节的坐标表达调整，没有瞬移实际刚体或清零其全部速度。
测试仍通过原生反作用求解，车体 yaw 输入由夹具主动施加。

生产适配则记录 generic 支持的两侧框架及后续框架更新，预测器使用对应的真实轴向、
线性目标基底和子框架。这项适配不同于测试夹具的 yaw rebase；生产代码没有自动重定
用户结构的支持框架。[原生预测对照 PASS](../build/finite-world-frame-local/bearing-articulated-prediction-result.txt)
覆盖 144 例，最大姿态误差 0.000024767°，最大位置误差 0.000000323303 格。

## 保留的严格失败

修复过程没有删除失败或放宽阈值：

| 记录 | 严格结果及限制 |
| --- | --- |
| `build/finite-aim-baseline` | FAIL：实际弹道目标不可达；不是完整八阶段基线精度报告 |
| `build/finite-aim-candidate` | FAIL：获取阶段刚体不再有界 |
| `build/finite-aim-gated` | FAIL：age 536，phase 1，pitch2 角速度 46.222151476 rad/s |
| `build/finite-aim-kinematic` | FAIL：age 1105，phase 4，车体角速度 25.296318950 rad/s |
| `build/finite-aim-continuation` | FAIL：age 1106，phase 4，车体角速度 22.365783753 rad/s |
| `build/finite-aim-support-after` | FAIL：age 1106，phase 4，车体角速度 22.379049378 rad/s；仅改顺序未解决框架奇点 |
| `build/finite-aim-rebased` | 全八阶段运动峰值低于 0.05°，但停车最高 3.141260408°，故总结果仍 FAIL |
| `build/finite-aim-isolated-final` | FAIL：真实抓取前尚未满足静止角动量前提，暴露普通固定射线不能自然休眠；没有实际抓取精度结果 |
| `build/finite-aim-transitions-final` | FAIL：有限点切到零速空射线首步，炮塔 1 实际炮管偏移 0.288967872°；旧转换 PASS 不覆盖该门控版本 |

`rebased` 是休眠修复前的直接对照：
`accepted=1845, fixed-motor-accepted=2, resting-carrier=1685`。
例如 phase 0 在 age 309 停车峰值 3.141260408° 时，`accepted` 停在 221，
`resting-carrier` 从 248 增至 249，部分电机仍有约 0.029060 rad 目标差。
其他有限点停车峰值在首次休眠退出时同样放大；停止首步通常很小，随后约 0.2–0.6 秒错误回退。
该对照的 `finite_compensation_active=true` 且操作者速度为零，
证明仅打开有限补偿标记不能修复未考虑待执行目标的静止判据。

## 复现与当前范围

在工作区使用 JDK 21，依次运行，不并发启动 Gradle。新 `runRoot` 下准备
`server/eula.txt` 和 `server/server.properties`：超平坦、种子 173025、禁用结构，
使用空闲端口。既有结果目录已含测试世界，重新复现请另取目录。

```powershell
$env:JAVA_HOME='C:/Users/hooya/.gradle/jdks/eclipse_adoptium-21-amd64-windows.2'
.\gradlew.bat runServer -PbearingIntegration=true -PcbcRuntime=true -PfiniteCarrierAim=true -PfiniteRebaseSupport=true -PrunRoot=build/finite-aim-reproduce '-PmianbaoJar=F:/PCLII/.minecraft/versions/Create航空学/mods/mianbaos_modernwarfare-2.6.0-neoforge.jar' --console=plain
.\gradlew.bat runServer -PbearingIntegration=true -PcbcRuntime=true -PfiniteAimTransitions=true -PfiniteRebaseSupport=true -PrunRoot=build/finite-aim-transitions-reproduce '-PmianbaoJar=F:/PCLII/.minecraft/versions/Create航空学/mods/mianbaos_modernwarfare-2.6.0-neoforge.jar' --console=plain
```

纯检查包括名义稳定速度、预测 COM 与炮口帧、动态目标逐探针刷新、额外迭代和安全回退。
[refinement-math 构建记录](../build/finite-aim-refinement-math.log) 中相关检查 PASS。
JNI 支持与预测探针使用 `build/finite-world-frame-local/javac.args` 中保存的 classpath。

上述服务端结果验证指定测试结构和工况内 `<0.05°`，不是任意车辆、碰撞、载荷或外力下
数学零误差的承诺。现有 8 轴/17 刚体上限、有限扭矩、可达方向、俯仰限位和未知连接回退保留。
极近目标、炮口后方目标、穿越炮口的目标，以及用户车辆客户端画面尚未验证。
本轮发行包、最终构建和打包交付记录见开头“最终发行包”。
