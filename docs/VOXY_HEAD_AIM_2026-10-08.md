# Voxy 远景地形瞄准与激光测距

交付包：`build/libs/create-fire-control-0.8.1-laser-rangefinder-voxy.jar`，包含上一版的按键激光测距和独立车速补偿。

普通头瞄、激光测距按下时的视线快照、普通炮口准星现在复用光电吊舱已有的 `VoxyLodRaycaster`。先查询真实方块和外部 Sable 结构；没有命中时，沿同一方向查询最远 48,000 格内的 Voxy 缓存体素。读到地形后，将它作为世界坐标下的有限瞄准点，火控从真实炮口对准这个点。

查询从粗 LOD 逐级细化；只有粗数据时使用粗体素表面。命中位置的精度取决于 Voxy 已保存的数据，不能从渲染缓存恢复不存在的细节。没有安装 Voxy、没有缓存或射线没有命中时，沿用无命中行为。对无命中方向按激光测距会清除距离与抬炮角。

激光测距仍只在按键触发时计算下坠抬炮角，保持到下一次测距。准星继续减去已保存的抬炮角和车速补偿。读取远景地形不会延长炮弹寿命或射程；超出当前弹药的可解弹道时，沿用不可用提示并保留该武器原先的补偿。

客户端查询上限与服务端校验通过 `AimRayLimits` 统一：真实方块射线保持 4,096 格，LOD 射线为 48,000 格，瞄准和测距目标包上限为 65,536 格。玩家相机距离、遥控输入权限、光电锁定参考点和物理子体锚点校验继续生效。包格式未变，网络协议仍为 26。

炮口准星的普通射线，包括渲染帧持有的炮口路径，也使用该回退。LOD 命中不伪造真实方块面或 Sable 锚点。后续刷新修复保存命中体素范围，每渲染帧用当前炮口射线重算准星；射线移出体素时立即重查，缓存数据仍每游戏刻定期更新。详见 [LOD 准星刷新修复](VOXY_MARKER_REFRESH_2026-10-08.md)。实际弹道的短段扫描继续使用已有碰撞和 Voxy 弹道查询，不向每段插入长距离直线查询。

## 验证

- `testVoxyLodRaycast` 使用模拟 section API 调用真实的反射读取与层级遍历，覆盖 24,000 格外命中、正负坐标、粗层回退、均匀 section、细层空气、无缓存、距离边界、预算停止和引用释放。
- `testAimRaycastGeometry` 覆盖真实命中优先、LOD 有限命中、缺失数据保持无命中，以及既有物理面裁剪。
- `testAimGroupPayload`、`testLaserRangeCompensation` 覆盖 20,000 / 48,000 / 65,536 格目标传输、越界拒绝、远距离弹道计算、固定抬炮角和无命中清零。
- 同时运行 `testCannonRenderFrame`、`testFiniteAimTarget`、`testLegacyFiniteAim`、`testOpenControllerPayload`、`testCannonImpactPrediction`，并执行 `shadowJar`、`verifyShadedJar`。

日志：`build/voxy-head-aim-validation.log`、`build/voxy-head-aim-package.log`。未启动安装 Voxy 的实际游戏世界进行验收。
