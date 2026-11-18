# 方向机左右限位

交付：`build/libs/create-fire-control-0.7.8-yaw-limits.jar`

SHA256：`E31CE89BDCCA7A998E8DC90C5B2F10207C71F049A7790A2BBBDD952989613952`

## 设置与行为

- 在方向机最大转速下方新增「方向机向左最大角度」「方向机向右最大角度」，支持滑块拖动和直接输入，范围 0–180°。
- 两侧默认均为 180，180 表示对应侧不限位；两侧均为 180 时没有原生角度止挡，可连续旋转多圈。单侧为 180 时可经过背面，另一侧设置的止挡仍有效。
- 两个角度选项下方新增限位中心方向，按东、南、西、北排列，选中项为绿色。默认南；分别对应轴承直接上级结构的 +X、+Z、−X、−Z，中心随载具旋转。
- 0° 表示对应侧不能离开中心；两侧为 0 时锁定在中心。
- 单个方向机及全局方向机设置均支持。不同轴承分别保存限位，返回及切换页面会暂存修改，点击完成后按操作顺序提交。
- 头瞄、有限目标、机械动力输入和稳定补偿均遵守限位；范围较宽时按合法扇区转向，不穿过背面的禁止扇区。
- 反作用力模式使用固定中心的原生铰链止挡；无反作用力模式对刚性保持姿态应用同一范围。旧存档缺少新字段时保持不限位；蓝图保留角度及中心方向。
- 网络协议由 23 更新为 24，客户端和服务端需使用相同版本。

## 验证

通过 `testYawLimits`、`testNativeYawLimits`、`testOpenControllerPayload`、`testBlueprintBearingSettings`、`testStabilizerDisabled`、`testStabilizedHeadSpeed`、`testFiniteAimTarget`、`testLegacyFiniteAim` 及 `shadowJar`。

新检查覆盖四个中心方向、倾斜上级结构、倒装轴承、左右非对称范围、超过半圈的合法转向、单侧不限位经过背面、零角度、旧存档默认值、网络往返和蓝图保存。原生 Rapier 场景验证实际止挡及不限位时多圈旋转。

打包检查确认 159 个相关生产类与最终编译输出逐字节一致，测试类和原生测试运行库未进入发行包。日志：`build/yaw-limits-verification.log`、`build/yaw-limits-package-verification.txt`。

尚未在游戏中检查新界面的实际显示和操作；未自动替换游戏模组或修改游戏存档。
