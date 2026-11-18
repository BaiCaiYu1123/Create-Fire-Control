# 稳定器修订验收记录

日期：2026-10-03。使用 JDK 21、Minecraft 1.21.1 / NeoForge 21.1.230、
Sable 2.0.5 / 航空学 1.3.2。服务端及 JNI 检查均加载现有 Sable 的原生 DLL。
没有安装定制物理后端；没有启动或修改用户车辆存档。

## 交付文件

- [新版 JAR](../build/libs/create-fire-control-0.7.8-articulated-stabilizer-fix.jar)，
  14,607,715 字节，是正式 shaded 发行构建的逐字节副本。
- [SHA256 校验文件](../build/libs/create-fire-control-0.7.8-articulated-stabilizer-fix.jar.sha256)：
  `D69053AC28C988EFE3024FE9E66755923C03511D808AAFC10A94EB854A7F8147`。
- [打包检查 PASS](../build/stabilizer-delivery-jar-audit.txt)：新预测器、求解器、
  陀螺及排序记录类均已打包，两项 servo mixin 已注册；所有测试 sourceSet 的
  Java 类及测试 DLL 均未进入发行包，2314 个 ZIP 条目。

以上是上一轮固定世界射线的验收，未覆盖生产有限命中点的动态逆弹道。
本轮已复现该遗漏：有限点在车体转动时受到虚构长杆速度和获取门控影响，
新的有限点服务端流程仍在修复，下面的旧 PASS 不能代表本轮问题已经解决。

本轮检查游戏中的 `mods/create-fire-control-0.7.8.jar` 已与上述交付文件一致，SHA256：
`D69053AC28C988EFE3024FE9E66755923C03511D808AAFC10A94EB854A7F8147`。
安装时退出游戏，备份并用本次新版替换旧火控 JAR，避免两个版本同时加载。
沿用现有 Sable 2.0.5、航空学 1.3.2 及原有其他依赖。

## 最终生产服务端

最终源码在独立超平坦测试世界运行生产控制器、真实物理轴承和真实手杖。
完整验收未使用 `characterizeCarrierHold`；`< 0.05°` 断言保持不变。
结果文件：[完整流程](../build/stabilizer-verified-final/server/bearing-integration-result.txt)、
[持续转动](../build/stabilizer-verified-final/server/bearing-turn-accuracy-result.txt)、
[抓车体](../build/stabilizer-verified-final/server/bearing-hold-accuracy-result.txt)、
[混合多炮塔](../build/stabilizer-verified-final/server/bearing-mixed-turret-result.txt)。

| 场景 | 炮管 1 峰值偏差 | 炮管 2 峰值偏差 | 结果 |
| --- | ---: | ---: | --- |
| 车体 +120°/秒，头瞄转速为零 | 0.000243° | 0.000311° | PASS |
| 车体 -120°/秒，仅稳定器 | 0.000263° | 0.000281° | PASS |
| 车体 +360°/秒，头瞄转速为零 | 0.000708° | 0.000839° | PASS |
| 车体 +30°/秒，仅稳定器 | 0.000256° | 0.000245° | PASS |
| 真实手杖抓车体，含起始子步 | 0.000364° | 0.000196° | PASS |
| 抓炮塔释放后 | 0.000037° | 0.000029° | PASS |

持续转动结果取每次换速一秒后的稳定阶段，逐物理步检查；启动及换向的
全步精度另由原生持续转动和共享方向机检查验证。生产三轴摇动最大偏差
0.001180°，外部姿态约束跳变最大暂态 0.004265°，有限主动瞄准越过目标
0.000545°。这些数值代表测试结构及工况，不能外推到任意车辆。

混合多炮塔流程包含首步：车体持续 +120°/秒，炮塔 1 按操作者 30°/秒
主动瞄准，炮塔 2 保持世界方向。炮塔 1 实际转动 39.367310° 并满足到位断言，
炮塔 2 峰值偏差 0.000557601°。主动瞄准没有使兄弟炮塔退回旧补偿。

完整流程 PASS 还覆盖 CBC 命中→空射线→命中、左右高低机、双轴限位和限位
迁移、轴承偏移、真实反作用、主动到位制动、手杖让控/释放、解绑、重连和解散。
空射线检查验证炮口直线标记与实际炮管方向；尚未以用户客户端画面检查手感。

规划诊断保留正常回退次数：`accepted=2104`、`fixed-motor-accepted=110`、
`DID_NOT_CONVERGE=21`、`unmodelled-connection=230`、`ineligible=57`、
`resting-carrier=161`、`no-held-motor=13`。额外连接用于解绑/重连等阶段；
验收仍检查最终实际炮管误差，不把回退本身计作精度通过。

## 静止后独立抓车体

最终源码另在新建超平坦世界复测。先确认全部五体速度及角速度为零，再用
真实手杖抓取并释放车体，释放后保留动量，继续自由下落。结果
[PASS](../build/stabilizer-final-isolated/server/bearing-isolated-staff-accuracy-result.txt)。

| 阶段 | 炮管 1 峰值偏差 | 炮管 2 峰值偏差 |
| --- | ---: | ---: |
| 抓取前 | 0.000009195° | 0.000012519° |
| 抓取期间 | 0.000126997° | 0.000165368° |
| 释放后 | 0.000188580° | 0.000188373° |

这个测试在离地自由下落中结束，没有验证地面撞击精度。静止及抓取后的真实
姿态、速度和角速度均写入结果；没有清速度或强制设置真实结构姿态。

## 原生验证和回归构建

正式构建 `build -PcharacterizeCarrierHold=true` 成功，日志为
`build/stabilizer-final-build.log`。严格原生任务已加入 `check`，均不受
characterization 放行影响，本轮正式构建重新执行并通过：

- `testNativeArticulatedPrediction`：136 例，姿态最大误差 0.000024767°，
  位置最大误差 0.000000323303 格，包含仅刚度及仅阻尼的外部电机。
- `testNativeGyroForecast`：2538 例，角速度最大误差
  `9.0646375e-6 rad/s`，严格阈值 `2e-5 rad/s`。
- `testNativeArticulatedSubset`：16 例，保持轴峰值 0.000004005°；
  固定兄弟命令不变且兄弟确有真实运动。
- `testNativeArticulatedTurns`：40/120 Hz 共八个转动阶段，包含每次起步和换向，
  严格 `< 0.05°`；40 Hz +360°/秒稳定阶段峰值 0.000617317° / 0.000179040°。
- `testNativeArticulatedSharedYaw`：真实车体→共用方向机→两门独立高低机，
  40/120 Hz 共八阶段，每步含起步/换向；40 Hz +360°/秒峰值
  0.013342431° / 0.013333666°。两炮独立俯仰相差约 17°，有限扭矩反作用有效。
- `testNativeStaffForecast`：手杖世界电机的离散响应和严格保持精度。

正式结果位于 `build/test-runs/native-*`。其他已配置回归涵盖炮口弹道/落点、
头瞄分组、瞄准收敛、轴承装配、Synaxis 焊接图、加载器、遥控武器站转速、
导弹与告警、热成像、注册迁移及安全回归。旧原生基础夹具通过 34180 项功能
检查，但自由偏心重载精度仍 FAIL，详见下文，不能写作全部精度测试通过。

原生关节图排序另外验证了显式 swap-remove、自动删刚体的出边/入边顺序、
handle GC、未知内部关节释放快照及 dispose：
[反射检查 PASS](../build/servo-order-audit/mirror-reflection-output.txt)。
交错四个真实铰链及无求解行链接，再自动删除 dummy 刚体：
[真实 DLL 八阶段 PASS](../build/servo-order-audit/body-dense/native-output.txt)，
40 Hz +360°/秒峰值 0.000632410° / 0.000049386°。
故意使用创建时间排序的相同负对照严格 FAIL（0.055256594° 起步误差），
保留于 `build/servo-order-audit/body-creation`。

## 复现

在工作区根目录使用 PowerShell 和 JDK 21。两次服务器运行分别使用新建的
测试目录，`server.properties` 设置 `level-type=minecraft\:flat`、
`generate-structures=false`、`level-seed=173025`，并按本机空闲端口配置。
依次运行，避免多个 Gradle 实例同时占用原生测试目录。

```powershell
$env:JAVA_HOME='C:/Users/hooya/.gradle/jdks/eclipse_adoptium-21-amd64-windows.2'
.\gradlew.bat runServer -PbearingIntegration=true -PcbcRuntime=true -PrunRoot=build/stabilizer-verified-final '-PmianbaoJar=F:/PCLII/.minecraft/versions/Create航空学/mods/mianbaos_modernwarfare-2.6.0-neoforge.jar' --console=plain
.\gradlew.bat runServer -PbearingIntegration=true -PcbcRuntime=true -PisolatedCarrierStaff=true -PrunRoot=build/stabilizer-final-isolated '-PmianbaoJar=F:/PCLII/.minecraft/versions/Create航空学/mods/mianbaos_modernwarfare-2.6.0-neoforge.jar' --console=plain
.\gradlew.bat build -PcharacterizeCarrierHold=true --console=plain
```

上面的目录已保存本轮结果。独立重新复现时另取新 `runRoot`，先创建对应的
`server/server.properties` 和 `eula.txt`，避免重用已经装配过的测试世界。

关节图反射复现：`javac @build/servo-order-audit/mirror-javac.args`，随后用
该参数文件中保存的依赖 classpath 加上 `build/servo-order-audit/mirror-classes`
执行 `com.hooya.stabilizedturret.content.bearing.DenseMirrorReflectionChecks`。
JNI 自动删除探针源码为 `build/servo-order-audit/BodyRemoveOrderProbe.java`，
在 `body-dense` 目录用 `-DbodyRemoveMirror=true` 运行对应类。
这些辅助材料属于诊断，不打包进发行 JAR。

## 保留失败和实际边界

旧 `testNativeBearing` 未接入联合规划，自由偏心重载 +120/-120/+360°/秒
峰值仍为 0.671527° / 0.366354° / 166.623909°，严格 FAIL。
默认 `build/check` 因此会失败。characterization 参数只允许旧对照完成其他
功能检查，不能表示重载精度通过。对应旧精度结果没有删掉或放宽：
`build/articulated-legacy-strict.log`、
`build/test-runs/native-bearing/bearing-turn-baseline-accuracy-result.txt`。

`testNativeArticulatedTurns -PsupportBeforeHinges=true` 保留世界支撑先于铰链的
原生边界，40 Hz 长阶段仍有横向失稳。普通地形释放下落失败记录保留于
`build/articulated-strict-isolated`。两者均不计作通过。

联合预测尚不模拟接触、原生 stop rows 及其他模组的任意外力；8 轴/17 刚体
上限、有限扭矩、可达方向和俯仰限位仍有效。未知连接、快照失败或逆解失败
保留原控制。任一 Sable 绳索在维度中创建不可观测内部关节后，该维度回退
原控制，直到物理场景 dispose/reload；删除绳索本身不会恢复联合预测。
没有修改绳索、其他模组连接或后端。

用户原车辆、实际后坐、客户端画面及任意碰撞环境尚未验证；这些服务端
数值不能作为所有载荷下数学零误差的承诺。设计见
[实现记录](STABILIZER_REBUILD.md)。
