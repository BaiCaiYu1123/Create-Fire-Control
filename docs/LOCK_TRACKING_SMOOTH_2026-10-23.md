# 自动跟踪逐帧平滑

旧路径在 ClientTick.Post 中每秒约 20 次直接修改跟踪角度，雷达坐标又可能每 2 tick 才变化。光电会话收包时还把上一目标跟踪坐标改回网络样本，导致帧间跟踪基线反复被重置。这些处理会让视角呈现周期性的拉动。

光电与地面雷达跟踪现改在 RenderFrameEvent.Pre 中每个可见帧更新一次。Camera.setup 只消费结果，不在兼容渲染器可能重复调用的位置重复推进跟踪。首次启用相机的基准在自动跟踪之前初始化，避免后续相机初始化覆盖已计算的初次修正。

`LockTargetSmoother` 使用服务端已有的目标速度在坐标更新之间短时预测。新包到达时，先保留当前显示位置和速度，再通过衰减修正衔接权威样本，目标停止、反向或到包间隔变化不会造成位置/速度瞬间跳变。预测最长延伸 150 毫秒，长时间无数据不会无限漂移。游戏暂停时冻结运动时钟。

相机、光电框、锁定的地面雷达框和光电输入参考点消费同一帧的平滑目标。光电不再在收包时改写逐帧跟踪基线；相机原点采用实际渲染位置，避免 tick 末尾眼睛位置引入台阶。相机直接使用逐帧角度，不再为这些角度额外叠加一个游戏 tick 的插值。

新锁定/切换目标的事件标识、同目标上下车和切视角不重复居中、玩家手动提前角、普通/高速防空导弹区分、有限深度瞄准和炮口提前量计算继续保留。数据包格式及协议 45 沿用上一版。

验证命令：

`gradlew.bat -I tools/cannon-lead-checks.init.gradle testLockTrackingSmooth testCannonLead testOpticalLockAim testCannonRenderFrame shadowJar verifyShadedJar --offline --console=plain`

新增测试检查 30/60/144/240 FPS 和 10/20 Hz 更新组合、匀速目标的等步长移动、到包抖动的位置/速度连续性、停止/反向、断流预测上限、不同帧率相同的捕获响应、双方运动下手动提前角以及上下车不再次捕获。

未进入用户游戏实机验收。交付包：`build/libs/create-fire-control-0.9.1-smooth-lock-tracking.jar`。
