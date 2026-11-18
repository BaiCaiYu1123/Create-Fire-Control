# 上级结构限速、越限切换和创造手杖保持

## 修改与原因

用户游戏目录的现有火控包 SHA256 为 `91AE8621ABC666341A75B9220DBE173B7986D11886D8CF47BA4C7E405A69AE11`，与上一轮交付一致。
只读检查的日志包含越限时约 170–178° 的待取得角差，以及大量 `pitch-stop` 和拒绝求解记录。
日志本身不能证明每一种现象的全部原因，本轮用真实 Sable 服务端物理夹具继续复现。

- 头瞄操作角差在每个轴承的直接父结构坐标中计算：方向机相对车体，高低机相对炮塔。稳定补偿不消耗头瞄速度预算。
  调低速度设置时立即裁掉原有更高操作速度，避免旧速度短暂绕过新上限。
- 有限弹道方向超过机械俯仰包络时退出不可达的有限逆解跟踪，按头瞄速度取得最近的边界方向。
  视线自身越限时优先选视线所在一端；视线可达但所需弹道越限时取发射方向对应端点。
  方位用头瞄射线或方向机轴心到目标的方向，避免枪口位于目标前方时无限反复追转。
- 方块、空处、边界模式切换清理各自旧的操作和制动历史；近竖直方向保留可靠方位，避免放大水平投影噪声。
- 手杖让位仅沿被抓结构的受控祖先路径传播。抓方向机炮塔时，下游高低机捕获真实世界炮管射线，暂停有限落点追踪和操作推进。
  直接抓炮管仍保留原有祖先让位路径。
- 抓炮塔期间以临时原生角约束保持车体姿态，平移仍参与物理联动；炮塔姿态保持与手杖目标及本轴驱动协同。
  约束模型包含真实角度锁、限位与原生约束行顺序。释放、失效和停服清理临时约束。
- 已在限位附近的高低机仍保留物理电机、限位和载荷模型，不再让其不可达残差取消方向机的补偿。
  方向机、高低机各自只修正本轴电机；多炮塔的目标和操作历史保持独立。
- 无限远 HUD 投影统一使用整数 GUI 中心，再对投影偏移取整，修正奇数 GUI 尺寸下的一像素中心差。
  仍绘制真实炮管射线，没有把显示准星吸附到视线目标上。

保持原版 Sable，不写物理姿态、不清速度、不恢复驱动电机直接回传车体的旋转反作用。

## 诊断中保留的失败

`build/edge-current-step` 显示，第二次抓取时高低机本轴误差很小，但限位附近的逆解拒绝使方向机补偿一并丢失，世界炮管方向峰值约 0.445°。
逐物理步清理诊断预测记录后，这些失败步没有新的预测；先前的“预测与实际偏差”混入了上一物理步的旧结果。
不能据旧记录宣称发现了原生模型错误或地形接触。

新夹具原先在抓取后把 FakePlayer 主手换成 AIR，真实手杖 handler 会删除该会话。
现在保留手杖并设为创造模式，逐物理步断言抓取归属始终有效。
原自由车体夹具的总世界参考变化还包含不受限的稳定补偿；最终限速取得阶段使用真实角度支撑，两个抓取阶段移除支撑。
严格方向误差验收仍为 `<0.05°`。早期失败记录保留，未用早期 PASS 代替最终源码验收。

## 最终验证

| 场景 | 最终记录 | 结果 |
| --- | --- | --- |
| 越限上下端点、有限/空处切换、两次创造手杖抓炮塔 | `build/parent-frame-edge-final/server/bearing-aim-edge-result.txt` | PASS；限速超出量 0.002631591°/步，端点误差 0.003474652°，抓取时车体旋转 0.000089197°，炮管方向误差 0.000574363° |
| 用户轴承/瞄具偏移、侧向行驶/倒车、近远目标取得 | `build/parent-frame-limit-final/server/bearing-finite-limit-result.txt` | PASS；2840 样本，真实地面落点峰值 0.002777017°，取得完成落点峰值 0.000813765°，限位期间方位误差 0.007555244° |
| 头瞄输入、零速度保持、解绑同伴、输入超时 | `build/parent-frame-input-regression/server/bearing-finite-aim-transitions-result.txt` | PASS；1720 物理步，头瞄超速量 0°，零速度炮管保持 0.000950240°，同伴解绑落点 0.000875952°，输入超时保持 0.000985007° |
| 前进/倒车、持续转动、前后倾斜、急停、有限/空处切换 | `build/parent-frame-combined-regression/server/bearing-finite-carrier-aim-result.txt` | PASS；3200 运动样本，真实地面和渲染枪口碰撞落点峰值 0.011865444°，独立方向机局部参考变化为 0 |
| 完整服务端功能回归 | `build/parent-frame-function-regression/server/bearing-integration-result.txt` | PASS；取得制动、限位编辑、红石、恢复原生约束、心跳、解绑、解散、抓炮塔/抓车体、双炮塔主动/保持混合 |
| 独立抓车体、零头瞄速度、释放后继续保持 | `build/parent-frame-isolated-staff-final/server/bearing-isolated-staff-accuracy-result.txt` | PASS；抓取期间 0.000560812° / 0.000602959°，释放后 0.000628293° / 0.000137519° |
| 原生预测对照 | `build/test-runs/native-articulated-prediction/bearing-articulated-prediction-result.txt` | PASS；232 案例，姿态差峰值 0.000024767°，位置差 0.000000725839 格；包含新增角度锁、两种物理步长和偏心拉力 |
| 完整构建和检查 | `build/parent-frame-release-build.log` | `build shadowJar -PcharacterizeCarrierHold=true` 成功；69 项任务，包含新父坐标检查、降低速度上限、共有方向机、独立驱动、弹道/显示/网络及原有功能检查 |
| 正式包校验 | `build/parent-frame-package-verification.txt` | PASS；105 个生产控制/稳定/准星/手杖兼容类与最终编译输出逐字节相同，必需 Mixin 注册，测试类和原生 DLL 未入包，codec 正确重定位 |

本轮服务端误差验收没有使用 characterization 绕过。
旧限位回归原本把不可达弹道方位当作越限时方向机的基准，`build/parent-frame-limit-regression` 保留其 2.407° 失败。
新行为按用户要求选择最接近视线的方向；越限段断言改为对真实视线方位检查，仍维持 `<0.05°`。
可达段的实际地面落点、首步返回和取得完成检查保持原来的要求。
独立抓车体夹具首次运行缺少无玩家世界的车体强制加载票，子结构未注册即失败，记录位于 `build/parent-frame-isolated-staff`。
补上与完整功能夹具相同的加载票后完成严格抓取和释放验收；没有修改生产加载逻辑。
完整功能场景保留原有取得阶段容差，不能把其全部阶段称为严格的 0.05° 验收。

## 验证范围

服务端夹具使用两个真实原生炮塔、CBC 中口径炮的枪口和加载弹药数据、五个真实固定焊接，以及真实控制器 tick 和手杖 handler。
新的极限场景使用合成块面输入；移动有限落点回归另检查真实地面碰撞查询。没有把这些数据称为实际发射炮弹的弹着实测。
手杖测试是创造 FakePlayer 的真实服务端抓取会话，尚未在用户坦克存档和客户端进行实操验收。
测试使用 Minecraft 1.21.1 / NeoForge 21.1.230 / 原版 Sable 2.0.5；用户游戏为 NeoForge 21.1.248。
HUD 一像素修正已包含在最终生产类校验中，未启动用户客户端复核其截图对应的瞄具渲染。
任意未建模外部关节、地形碰撞突变和网络条件下的数学零误差不在本轮已验证范围内。
完整构建沿用原项目的 `characterizeCarrierHold` 选项，旧简化自由偏心重载精度对照仍输出
`KNOWN FAILURE: production sustained hold accuracy; characterization only accepts functional checks`。
它没有被修成严格精度通过，不能由整个构建成功推断全部旧精度对照通过。

## 交付

[create-fire-control-0.7.8-parent-frame-stabilizer-fix.jar](../build/libs/create-fire-control-0.7.8-parent-frame-stabilizer-fix.jar)，14,665,442 字节。

SHA256：`6035686A07F6BA9DCEF0714A3AA7CD64757A4FC79081D3B9B4EBA280A2693B1E`。

[校验文件](../build/libs/create-fire-control-0.7.8-parent-frame-stabilizer-fix.jar.sha256)。
退出游戏后替换旧火控 JAR，mods 中仅保留一个火控版本并重启。多人客户端和服务端使用同一包，Sable 保持现有原版。
未自动安装到用户 mods，未修改用户存档。
