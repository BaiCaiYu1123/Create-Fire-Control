# 侧向移动时炮管轴线与落点准星分离的诊断

## 结论

用户进一步确认：此现象只在反作用力模式且头瞄命中方块时出现，无反作用力模式和无方块方向瞄准没有相同现象。源码中确实存在对应的模式差异，不能仅用“CBC 普遍继承速度”解释两种模式的表现。

反作用力模式保留炮塔、炮管的真实物理速度；有限目标逆解使用非零结构速度。无反作用力模式则在物理后处理时强制清零受控结构的速度，并在有限目标逆解中明确使用零继承速度。因此，反作用力模式需要侧向射击补偿，无反作用力模式没有相同的横向补偿。这来自两种实现的运动与弹道基准差异，并非仅凭截图能认定的轴控制耦合。

当前安装的 CBC 5.11.7 会让普通 CBC 炮弹继承 Sable 物理结构的速度。炮塔相对车体转到约 90° 后，车体前进速度主要成为炮弹的横向初速度。有限目标火控为了让预测落点到达目标，会让炮管向相反方向补偿。炮管轴线与白色落点圆圈因此可以分离。

白色圆圈由真实炮口姿态、炮弹初速、结构速度、重力、阻力和场景碰撞计算，表示预测落点，不表示炮管轴线的直线延长。第三人称和炮镜的观察位置也不同，有限距离下还存在视差。无限远头瞄采用方向保持，因此没有相同的有限目标弹道补偿。

## 配置与发射实现

- 游戏目录：`F:/PCLII/.minecraft/versions/Create航空学`。
- `config/createbigcannons-server.toml:378`：`sableProjectilesInheritPhysicsObjectVelocity = true`。配置和存档目录搜索未发现另一个覆盖此选项的文件。
- 已安装 `createbigcannons-5.11.7+mc.1.21.1.jar` 的 `createbigcannons.mixins.json` 注册了 `compat.sable.EntitySubLevelUtilMixin`。
- `CBCMixinPlugin.shouldApplyMixin` 在 Sable 已加载时启用该兼容；日志记录 Sable 2.0.5 已加载。
- `EntitySubLevelUtilMixin.createbigcannons$kickEntity$mul` 针对 `AbstractCannonProjectile` 检查上述配置，启用时用 `Sable.HELPER.getVelocity` 填充继承速度，随后交回 Sable 的速度换算与叠加过程。
- Modern Warfare 的 `AbstractMediumcannonProjectile` 继承 `AbstractCannonProjectile`，也满足该判断。
- 此前只查看 Sable 核心后提出的“普通 CBC 炮弹可能未继承速度”假设，已被 CBC 自己的兼容注入排除。

## 本模组对应实现

- `CannonBallisticsCompat.analyticalLaunchDirection` 从目标所需的世界初速度中减去 `profile.carrierVelocity`，得到炮管应指向的方向。
- `CannonRenderFrame.from` 用实际渲染炮管方向乘炮弹初速，再加结构速度。
- `ClientAimState.renderCannonPoint` 用上述初速度追踪弹道与场景碰撞，得到白色落点圆圈。
- `LegacyBearingStabilizer.AxisLock.enforce` 在位姿投影前后调用 `pipeline.resetVelocity(child)`，无反作用力模式的结构位置虽然跟随车体，其物理速度却被清零。
- `LegacyFiniteAimSolver.error` 解算时给 `CannonBallisticsCompat.withFrame` 传入 `Vec3.ZERO`。
- `StabilizerControllerBlockEntity.requestedGroupAim` 明确按模式选择 `target.currentDirection(level)` 或 `target.currentDirection(level, Vec3.ZERO)`。
- 无方块头瞄由 `measuredAimGroup` 返回方向准星，且 `requestedGroupAim` 不创建有限弹道目标，没有同一种横向弹道补偿。

## 游戏日志证据

2026-10-04 的 `logs/latest.log` 记录：

| 时间 | 视角 | 预测落点与头瞄视线的角误差 | 继承速度，格/刻 |
| --- | --- | --- | --- |
| 17:39:42.540 | 炮镜 | 0.000668° | (0.008524, -0.000932, 0.341190) |
| 17:40:32.799 | 第三人称 | 0.006565° | (-0.279056, -0.006754, -0.809479) |
| 17:42:55.324 | 炮镜 | 0.004170° | (-0.623146, -0.006906, 0.572662) |
| 17:43:05.319 | 炮镜 | 0.003595° | (-0.467825, -0.007180, 0.539655) |

第三人称 17:40:32.799 的相机帧 `age=0`、`renderOriginError=0.0`。这些样本支持移动补偿后预测落点接近视线；它们不能证明所有时刻都完全稳定。17:40:12.540 仍有 0.144643° 的误差，17:51:02.892 还有较大误差，后者附近服务端记录 `camera=direction`，不能直接归为同一种有限目标稳态偏差。

## 范围

本轮只核对截图、日志、配置和已安装模组的字节码，没有修改生产源码、游戏配置或存档，也没有构建新包或进行实机射击验证。截图没有对应的精确日志时间，因此以上角误差属于相近测试场景的证据，而非截图逐帧测量。若要求运动中炮管轴线也始终对齐目标，需要同时改变实际炮弹的速度继承和对应预测；仅删除火控补偿会让实际落点偏离目标。
