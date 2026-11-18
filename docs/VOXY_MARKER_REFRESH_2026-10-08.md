# LOD 准星刷新修复

交付包：`build/libs/create-fire-control-0.8.1-laser-rangefinder-voxy-smooth.jar`。

上一版普通炮口准星对真实方块保存碰撞面，每帧用当前渲染炮口重算交点；对 Voxy 地形只保存世界坐标，每游戏刻约 20 次更新。这使 LOD 准星在转炮和车辆移动时出现阶梯跳动。

修复后 Voxy 查询同时返回命中体素的包围盒。每个渲染帧都以真实炮口的插值位置、朝向及已扣除补偿的准星方向，重新求该体素的射线交点。射线移出体素时，在当前帧立即重新查询真实碰撞和 LOD；每游戏刻还会定期刷新地形缓存。计算没有增加一刻的显示插值延迟。体素范围来自实际命中的精细层或粗层，Voxy section 引用在查询结束时照常释放。

激光测距仍由按键触发并保持原来的抬炮角；本次调整的是准星显示，网络包、武器射程和炮弹运动参数没有改变。

## 验证

- `testCannonRenderFrame` 新增同一物理刻内连续 25 帧的炮口平移/转动，检查 LOD 准星每帧都落在当前射线与体素的交点；覆盖离开体素、转向天空、背向命中及距离边界。
- `testVoxyLodRaycast` 检查实际层级遍历返回正确的 1 格/16 格体素范围及负坐标，并继续检查 section 释放、无缓存和预算限制。
- `testAimRaycastGeometry` 检查真实碰撞优先和 LOD 几何信息的保存。
- `testLaserRangeCompensation`、`testAimGroupPayload`、`testCannonImpactPrediction` 回归通过；执行 `shadowJar` 和 `verifyShadedJar`。

日志：`build/voxy-marker-refresh-validation.log`、`build/voxy-marker-refresh-package.log`。未在安装 Voxy 的实际游戏世界中进行帧率验收。
