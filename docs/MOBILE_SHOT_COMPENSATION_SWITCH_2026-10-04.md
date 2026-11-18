# 移动射击炮口补偿开关与头瞄时序修正

> 本文记录此前交付。当前开关已统一为“炮口补偿”，同时控制下坠补偿、车速补偿和准星含义，见 [后续修改](MUZZLE_COMPENSATION_2026-10-04.md)。

交付包：`build/libs/create-fire-control-0.7.8-mobile-shot-compensation-head-aim.jar`

SHA256：`43EA2C433A40D3A75DB7907E4CE2F61CD24E028B4D421CCF1BDCA40DCEDB2B6F`

大小：14,743,940 字节。

## 修改前备份

`backups/mobile-shot-compensation-pre-change-20261004-180000/current-source.zip`

备份包含当前工作目录中的 `src`、`gradle`、`docs`、`tools` 和根目录源码/构建文件，共 942 个文件；包含本次修改之前的未提交改动。已逐文件比较 ZIP 内容与原文件的 SHA256。现有依赖仍位于工作目录 `libs`，没有复制构建缓存或游戏存档。

备份 SHA256：`0E2A5BED07A861B691127D2B3B4F68B56D1A28B5823CAB176239BCFB7B383FB7`。

## 开关

- 位于火控计算机的“火控设置”，只有选择“反作用力”实现时显示；切换实现立即刷新此页。
- 文案：`是否开启移动射击炮口补偿`，后缀显示开启/关闭。
- 提示：`移动时自动补偿炮弹因车体移动所带来的偏移`。
- 默认开启；旧存档没有新字段时保持开启。关闭状态写入 `MobileShotCompensationEnabled`，重新打开界面或加载存档时恢复。切到无反作用力模式时保留该偏好。
- 开启：保留现在的物理轴承有限目标弹道补偿。
- 关闭：物理轴承的有限目标逆解使用零继承速度，当前目标解算与物理子步预测都遵循该选择；炮口不再因车体的运动速度而作反向弹道修正。重力修正、有限距离视差、车体位姿变化后的目标跟踪、俯仰限制和头瞄转速规则仍保留。
- 此开关不改变实际炮弹继承速度，也不伪造落点圆圈。关闭后，移动射击的实际/预测落点可能偏离头瞄目标，这是取消补偿的结果。
- 设置包和打开界面包均同步新字段，网络协议由 19 更新为 20。

## 补偿的来源与显示器隔离

对比用户提供的 `create-fire-control-source-0.7.8.zip`：原版 `CannonBallisticsCompat` 已采样车体速度，通用弹道逆解也已经减去这一速度；原版物理轴承头瞄的 `applyAimDirection` 使用几何会聚方向。将通用弹道逆解接入反作用力物理轴承头瞄，是重做后的实现。

新开关仅进入 `requestedGroupAim` 创建的物理轴承瞄准目标，作为每个 `FiniteAimTarget` 的策略传递到当前和预测解算。没有修改共享弹道求解器、炮弹发射继承处理、连接显示器、吊舱控制、落点预测或客户端渲染。

与修改前备份比较，`getCannonBallisticLaunchDirection`、`updatePodControlledCannon`、`measuredAimGroup` 方法保持一致；`CannonBallisticsCompat`、`CannonProjectileInheritanceHandler`、`PodControlledCannonBallistics`、`ClientAimState`、`CannonRenderFrame` 源码逐字节未变。记录见 `build/mobile-shot-compensation-display-isolation.txt`。

## 偶发头瞄停顿

已读取游戏 `logs/latest.log` 至 2026-10-04 18:04 左右。17:50:58 日志记录反作用力模式关闭自稳定，后续控制诊断多次记录 `commandAge=1`；较早 17:23:01 还有一次服务端落后 3712 ms 的告警。当前日志按约十秒间隔记录控制状态，不能定位用户每次短暂停顿的准确物理子步，也不能据此排除其他原因。

源码中存在严格的当前刻判断：命令只要比物理子步早一刻，就会被视为不活动，转动速度或有限目标历史被清除。改为允许当前刻和前一刻的命令持续执行，避免控制器刷新与物理回调处于游戏刻边界时中断起步阻尼和跟踪。超过一刻、未来时间和未收到命令的状态仍无效；机械输入、关闭自稳定和手杖接管的分支保持原有规则。

这是对确定存在的时序脆弱点的修复，尚未实机证明它覆盖所有偶发卡顿。

## 验证

- `testOpenControllerPayload`：新设置在两个方向的网络编解码中保存，现有显示器设置包检查通过。
- `testFiniteAimTarget`：正向/反向横向移动，关闭后消除速度补偿，开启保留补偿；重力修正、目标身份和移动相机策略保留。
- `testStabilizerDisabled`：跨游戏刻的有效命令不中断阻尼加速，过期命令不会继续；原有手动运动和无上级驱动检查通过。
- `testStabilizedHeadSpeed`：保留两种实现、两个轴的上级相对追转规则，30°/秒加同向车体 20°/秒得到世界 50°/秒，到位后继续世界稳定。
- 编译与 `shadowJar` 通过，包内 153 个相关生产类与最终构建输出一致，没有打入测试类或物理运行库。与上一交付包比较，1726 个模组类未变；差异限于本次修改的源码类及其内部类。

记录：`build/mobile-shot-compensation-check.log`、`build/mobile-shot-compensation-head-aim-build.log`、`build/mobile-shot-compensation-package-verification.txt`、`build/mobile-shot-compensation-baseline-comparison.txt`。

未自动替换游戏模组或修改存档，未进行实机复测。
