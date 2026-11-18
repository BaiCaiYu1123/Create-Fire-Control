# 焊接车体的有限落点稳定补偿

后续游戏反馈仍出现越俯角恢复延迟、摆动和有限目标取得后的短暂偏移。
本记录的验收没有覆盖小俯角限位与近远块面切换，后续修复见
[限位及目标取得修复](STABILIZER_LIMIT_ACQUISITION_2026-10-03.md)。

日期：2026-10-03。本轮针对“视角射线命中方块时，车体转动、俯仰、侧向前进或倒车会让落点准星偏移”。

## 实际证据

只读检查游戏目录 `F:/PCLII/.minecraft/versions/Create航空学`。已安装的
`create-fire-control-0.7.8.jar` SHA256 为
`CDA4935780FCB3CD89E3B23E25A5EFF0F8E1E5F9DB5C771D2F86A3D0D911CC9A`，
确实是上一轮的发行包。

日志没有稳定补偿和落点误差诊断，不能由日志单独断定控制误差来自哪里。
最新日志中的 Shaolib 缓存版本告警也不能作为稳定器偏移的直接证据。
只读解析“新的世界”保存数据，发现当前车体连接了五个 Synaxis FIXED 焊接成员。
读取脚本和结果保存在 `build/inspect_game_state.py`、`build/game-state-inspection.json`。
未改写存档。

旧预测器只能处理受控铰链和世界驱动。遇到这些额外的固定连接时，它会退出整套物理预测。
在保持五条原生固定连接有效的双炮塔夹具中，旧实现被此检查拒绝 **3546 次**，
真实地面碰撞落点最大误差 **3.439380371°**。

[旧实现结果](../build/welded-forced-baseline/server/bearing-finite-carrier-aim-result.txt)
和 [退出原因](../build/welded-forced-baseline/server/bearing-servo-planning-result.txt)
保留。此对照使用上一版主类输出和重新编译的焊接夹具，未重编主实现。
早期 `welded-carrier-baseline` 的 PASS 无效：测试中的焊接成员曾被卸载，后来加入强制加载票据
和每刻检查五条原生 handle 有效性的断言，才得到上述可靠的 FAIL。

## 发行实现

1. 在物理预测中纳入真实固定焊接成员、质心、惯量、双端局部锚点和关节坐标系。
   固定焊接使用原生的三条角向锁定行和三条线性锁定行。
   保留固定成员的真实重量和运动，不修改实际焊接参数。
2. 对齐现有 Sable Rapier 的电机关节与固定关节求解顺序，包括未满 SIMD 桶和完整 SIMD 组。
   只按创建顺序排列这些约束仍会出现可测误差，因此采用了原生分组顺序。
   曾设置过零增益电机的关节仍保留原生 motor bit，不将其误当成无电机的 SIMD 固定关节。
3. 保持最多八根受控轴；额外固定焊接不计入受控轴数。预测组件最多包含32个物理体和64条铰链／固定连接。
   未建模的旋转焊接、额外限位和未知连接继续使用原有安全回退。
4. 原生速度只要是有限值就使用，包括真实零速度。停车或睡眠时不再把旧位姿差算出的速度带回弹道。
5. 每200游戏刻输出限频诊断。服务端记录预测接受／退出原因、每轴状态与命令年龄；
   客户端记录有限落点与视线的角度差、瞄具和炮口所属物理体、继承速度。

方向机、高低机继续各有独立状态和独立电机反馈。头瞄限速与物理补偿的区分沿用上一轮实现。
各炮塔仍按自身物理体身份关联炮口、弹药和落点。
父子承重铰链与世界到子体的驱动未改写，不恢复直接向车体施加反向驱动力矩。
未写姿态、清速度、替换 Sable DLL，未用输入目标代替真实弹道碰撞标记。
本轮未更改网络协议17。

## 验证

最终结果见下表；各服务端精度夹具保持 `<0.05°` 验收线，运动启动和急停均逐物理步取样。

| 检查 | 结果 |
| --- | --- |
| [原生焊接与混合驱动](../build/test-runs/native-articulated-prediction/bearing-articulated-prediction-result.txt) | 188个原生案例通过，包含 Fixed／Generic、静态／动态父体、40／120Hz、多条固定焊接、世界驱动及零行无碰撞连接 |
| [主车体双炮塔＋五条有效焊接的平移](../build/welded-native-order-fix/server/bearing-finite-carrier-aim-result.txt) | 中间实现 PASS；地面碰撞最大0.003784094°；此运行之后移除了未经验证的跨焊接成员组件合并方案，最终版本另做回归 |
| [最终持续旋转、俯仰及急停](../build/welded-rotation-final/server/bearing-finite-carrier-aim-result.txt) | PASS；地面碰撞最大0.011436019°；真实火控 BE 经 Sable actor tick，运动阶段头瞄速度保持30°/s |
| [最终随车瞄具、平移叠加旋转与俯仰](../build/welded-combined-final/server/bearing-finite-carrier-aim-result.txt) | PASS；3200个运动样本及全部取得／急停样本；真实地面碰撞和渲染炮口重算落点峰值均为0.012492119°，未出现额外连接拒绝 |
| [最终服务端功能回归](../build/welded-function-loaded/server/bearing-integration-result.txt) | PASS；左右高低机、到位制动、限位、解绑／重连／解散、手杖抓炮塔及车体、双炮塔混合操作、有限／空处标记 |
| [最终构建](../build/welded-carrier-release-build.log)与[包校验](../build/welded-carrier-package-verification.txt) | 构建通过，100个相关生产类与最新编译输出逐字节一致；Mixin已注册，测试类与原生DLL未混入；旧简化重载精度失败按下文明确保留 |

旋转夹具使用固定世界目标；随车射线夹具才把瞄具原点附着于真实炮塔，并逐物理步重求块面交点。
前者报告的旧文字“sight rides primary turret”不适用于该运行，已更正夹具报告源码。
这些检查使用真实 CBC 中口径 APFSDS 分析、炮口几何、继承速度和世界地形碰撞查询，
并非实际发射炮弹的实测弹着记录。

## 过程失败与范围

跨固定焊接成员安装第二炮塔的扩展夹具仍失败，**不列入通过范围**。
试验先后出现失去地面落点或异常角速度：
`welded-member-turret`、`welded-member-turret-near`、`welded-member-turret-height`、
`welded-member-turret-staged`、`welded-member-turret-settled`。
恢复分组件控制的 `welded-member-separate-control` 同样在取得目标阶段出现异常角速度，
该对照没有证明异常由跨组件预测引入，也没有证明此场景已解决。
跨成员组件合并、将任意原生固定连接加入碰撞隔离图的试验改动均已从发行实现移除。
现有 Synaxis 碰撞隔离方式及多炮塔分组行为保持原实现。

`welded-rotation-regression`、`welded-member-turret-contact-fix` 曾在夹具初始化期间触发
Sable 原生 `no entry found for key` 中止。后续夹具将嵌套组装分到父体注册后的下一刻，
并为车体及焊接成员加加载票据。没有将这些中止写成 PASS。

广泛功能回归的 `welded-function-final` 在首刻子体尚未注册时就发送了操作命令，出现空引用；
后续先等待注册；`welded-function-final-verified` 证实异步加载不保证在第二刻完成。
最终为测试源区块加载并等待真实注册（最多40世界刻），注册后才开始原验收时钟。
`welded-function-registered` 的过宽注册断言又误拦截了有意解散炮塔的阶段，已限制到启动检查。
原有解绑／解散断言和误差要求没有放宽，以上失败记录保留。

最终功能回归中，零头瞄速度下真实手杖抓车体的两炮塔保持峰值为
0.000573°／0.000569°；持续旋转的峰值均小于0.000678°。
早期取得阶段峰值0.056954°按原功能容差通过，不能称整个功能流程均小于0.05°。

最终完整构建继续使用 `-PcharacterizeCarrierHold=true`。
该参数只允许旧简化自由偏心重载对照保留已知精度失败，不能据此声称全部旧精度对照通过。
本轮有限目标服务端验收不使用此参数；广泛功能回归中的取得阶段沿用原功能容差。

未自动安装发行包，也没有在用户实际客户端、坦克存档或16倍瞄具中完成实测验收。
测试说明这些已覆盖场景的精度，不保证现有 Sable 下任意外部关节、地形切换和网络条件的数学零误差。
相机射线仍在新输入包到达前沿已选块面传播，跨遮挡或块面轮廓的目标变化由新射线包更新。

## 发行包

[create-fire-control-0.7.8-welded-carrier-stabilizer-fix.jar](../build/libs/create-fire-control-0.7.8-welded-carrier-stabilizer-fix.jar)，14,656,254字节。

SHA256：`A20419511B1078A3D04E791F710DDA8D20D4ABA238140E58FBC2C2308BFCB766`。

[校验文件](../build/libs/create-fire-control-0.7.8-welded-carrier-stabilizer-fix.jar.sha256)。
用此包替换旧火控 JAR，mods 中只保留一个火控模组，然后重启游戏。
多人环境客户端和服务端需使用同一包；Sable 保持原版本。
此包已构建和验证，尚未写入用户 mods。
