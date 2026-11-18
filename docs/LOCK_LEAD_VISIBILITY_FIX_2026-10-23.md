# 锁定提前量圈缺失修复

本次针对光电和地面雷达提前量圈不显示、时有时无的反馈检查了游戏日志、绑定和供弹配置。

## 修复

- `CannonBallisticsCompat.findCannonAmmunition` 原先只检查输入队列与物品仓库，漏掉 CBC 直接从已安装弹匣取弹的路径。当前转管炮使用创造弹匣，且只装填曳光弹通道。现在在弹匣副本上调用 CBC 自身的出弹选择，按实际顺序读取主弹/曳光弹及其组件；不会消耗炮弹或推进真实弹匣的索引。该读取同时供激光测距和锁定提前量使用。
- `CannonLockLeadTracker` 原先只通过玩家座椅查找火控。遥控炮镜已经进入另一个火控的瞄准会话时，其光电锁定框仍正常显示，但提前量无法从同一火控发送。现在与 `ServerAimSessionHandler` 共同使用 `ControllerTracker.playerViewController` 的选择规则，光电使用当前瞄准会话的火控，地面雷达使用雷达会话的火控，避免不同火控的目标混用。
- 增加限频的 `Cannon lock lead:` 服务端诊断，记录两类提前量状态、绑定/弹道可用性、当前导弹选择、实际炮弹、初速、重力、阻力与寿命。以后可以从日志区别数据没有生成、当前只跟踪导弹和弹道不可达。

普通防空导弹仍只进行地面雷达相机跟踪，不显示炮弹提前量圈；高速防空导弹不会进入普通防空导弹跟踪路径。协议保持 45。已有逐帧平滑、每次锁定只捕获一次、手动提前角及有限深度瞄准修复保留。

## 验证

新增 `tools/cannon-lead-integration.init.gradle` 与独立集成测试源集，在临时服务端世界加载真正的 CBC/CBC Modern Warfare，构造转管炮与同类创造曳光弹匣。目标数据由测试夹具提供，不读取用户存档。

检查内容：

- 读取到 `createbigcannons:flak_autocannon`，而不是通用兜底弹道。
- 完整光电及地面雷达 snapshot 含有有效提前量点。
- 连续 100 个更新周期中两类提前量始终可用，并随目标运动持续变化。
- 长时间计算不改变真实弹匣内容和索引。
- 无座椅绑定的遥控光电会话仍能生成应发送的提前量。
- 普通/高速防空导弹的显示与跟踪限制保留。

另外执行 `testCannonLead`、`testLockTrackingSmooth`、`testLaserRangeCompensation`、`testOpticalLockAim`、`testCannonRenderFrame`、`shadowJar` 和 `verifyShadedJar`。

测试源集仅在 init 脚本指定的测试服务器中加载，不进入交付 JAR。没有改写用户游戏安装或进入用户存档进行画面验收。

交付：`build/libs/create-fire-control-0.9.1-lock-lead-fix.jar`。
