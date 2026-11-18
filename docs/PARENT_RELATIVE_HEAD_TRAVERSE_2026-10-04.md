# 开启稳定器时，头瞄追转使用上级结构转速

交付：`build/libs/create-fire-control-0.7.8-parent-relative-head-traverse.jar`

SHA256：`FB2B5B9511892B82FF1FF8022E5A2C080900E18F4B1C2931122A7CC223C72DFD`

大小：14,742,741 字节。

## 本次确认的行为

用户明确确认：方向机设定 30°/秒、车体同向旋转 20°/秒时，头瞄追转应为相对车体 30°/秒、世界约 50°/秒；停止追转后保持世界方向稳定。高低机对其直接上级结构采用相同方式，方向机与高低机分别决定是否正在追转。

上一版 `stabilized-head-speed-split` 的到位制动虽然分离了补偿，但受限轨迹仍沿世界方向推进，不能满足这一具体行为。本版改变轨迹本身。

## 修改

- 反作用力模式：每个轴独立记录相邻物理子步的上级姿态。头瞄追转时先运输该轴的参考方向，使其继承上级运动，再执行受限的相对转动。世界执行器的转速前馈包含这一上级运动；高低机前馈使用实际炮管所在的俯仰平面。到位制动继续只处理头瞄部分。
- 无反作用力模式：追转期间在每个物理子步继承上级完整姿态，随后游戏刻上的头瞄转动仍受相对速度限制。记录已经继承的轴向转动，避免有限目标自动补偿又把它抵消。该轴到位后恢复原来的世界方向保持。
- 到位前进入短制动范围，以世界目标结束追转，防止反向运动的车体使追转永远无法结束。继承运动本身若到达目标，也在目标处停止，避免携带炮管跨过目标。机械俯仰限制保留。
- 每个轴、每个炮塔独立保存状态，开关切换、绑定重建、机械输入、手杖接管时清除对应历史。
- 增加 `parentTraverse`、`parentTraverseDegS`、`worldCommandDegS` 诊断字段；无反作用力模式记录 `headStepDeg`。这些字段描述控制命令，并非实测物理转速。

本次生产修改限于 `BearingServoMath.java`、`BearingStabilizer.java`、`LegacyBearingStabilizer.java`。没有修改原生执行器、GUI、网络协议、分组绑定、有限弹道解算器或准星。关闭稳定器的持续追踪和 0.8 秒起停阻尼保留。

## 验证

- `testStabilizedHeadSpeed`：以跨时间的轨迹验证两种轴、倾斜父结构、20 Hz/120 Hz、同向车体 20°/秒时世界 50°/秒、反向车体 20°/秒时世界 10°/秒；到位后父结构继续旋转，世界参考保持不动。另验证无反作用力模式已经继承的运动不会被有限目标补偿抵消。
- `testStabilizerDisabled`、`testLegacyFiniteAim` 通过。
- 编译与 `shadowJar` 构建通过；包内 153 个相关生产类与最终构建输出逐字节一致，测试类和原生运行库未泄露。
- 与上一版交付包比较，1740 个模组类逐字节一致；差异限于上述三个源码的类与内部类，包含行号调试信息。`NativeBearingJoint` 和多炮塔分组类未变。

记录：`build/parent-relative-head-traverse-check.log`、`build/parent-relative-head-traverse-build.log`、`build/parent-relative-head-traverse-package-verification.txt`、`build/parent-relative-head-traverse-baseline-comparison.txt`。

检查时游戏加载的是上一版 `D1F38F0E...`，并已读取 16:21 的游戏日志。上述验证属于数学场景、代码和打包检查，尚未实机验证新包；未自动安装或修改存档。
