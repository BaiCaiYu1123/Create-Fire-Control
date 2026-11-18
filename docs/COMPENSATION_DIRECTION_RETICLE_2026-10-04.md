# 扣除炮口补偿后的方向准星

交付包：`build/libs/create-fire-control-0.7.8-compensation-direction-reticle.jar`。

SHA256：`F987A48E3EC73414A592C609120B17426A171F31A7639E115FF0EA69177F9242`。

大小：14,758,932 字节。

## 当前含义

- 炮口补偿开启：火炮继续补偿车速偏移和下坠；普通准星取实际炮口方向，再扣除同一有限目标逆解与几何方向之间的补偿旋转。
- 炮口补偿关闭：普通准星显示真实炮管射线，火炮不进行这两种瞄准补偿。
- 准星保留真实炮塔跟踪误差，不直接使用玩家要求的方向。无方块的视线没有有限距离补偿，按实际方向显示远处标记，不搜索炮弹的最终地面落点。
- 客户端每帧取实际渲染炮口，再在世界坐标中扣除网络同步的补偿旋转；沿炮管轴的滚转不会把重力补偿方向一起旋转。射线离开缓存碰撞面时立即丢弃该面上的旧点。
- 每个炮塔组各自处理测得的炮口与补偿。真实炮口帧、实际炮弹、稳定器、头瞄控制和连接显示器/吊舱弹道保持原实现，网络结构不变。

20:02 的游戏日志记录 `markerMode=ballistic-impact`，其中一次天空方向对应的标记最终落在地面 `y=138`。上一交付的普通准星确实采用炮弹落点，而本次按用户新的要求改为扣除补偿后的方向射线。

## 验证

扩展现有 `testCannonRenderFrame` 检查：23/160 格有限目标、左右移动车速、两侧真实跟踪误差、渲染姿态变化、沿炮管滚转，以及炮口指向空中但弹道最终落地的情况。检查证明扣除补偿后到位方向为几何方向，未到位误差仍保留，物理炮口帧未改，方向射线不取地面弹道交点。

`shadowJar` 构建通过；154 个相关生产类与最终编译输出逐字节相同，中文说明匹配。检查记录：`build/compensation-direction-reticle-final-build.log`、`build/compensation-direction-reticle-package-verification.txt`。

与修改前备份对比，`requestedGroupAim`、`getCannonBallisticLaunchDirection`、`updatePodControlledCannon` 方法及七份物理/弹道/显示器/协议源码保持不变。记录：`build/compensation-direction-reticle-isolation.txt`。

备份：`backups/compensation-direction-reticle-pre-change-20261004-200907/`。

补偿数据按游戏刻同步，实际炮口方向每个渲染帧更新。没有自动替换游戏模组或进行实机复测。
