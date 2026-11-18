# LOD 随车瞄准、测距与炮镜提示

交付包：`build/libs/create-fire-control-0.8.1-laser-rangefinder-voxy-tracking-fix.jar`。

用户确认先前没有抬炮是记错绑定按键。存档中激光测距为高级遥控器 `buttonB`，键盘 C。23:57 日志已有 `press-snapshot` 接收和弹道计算，例如距离 504.96 格、抬炮角 1.6692°。本次不再改遥控输入授权或弹道公式。

## 修复内容

1. `movingCameraFrame` 原先只接受 `BlockHitResult`。LOD 命中刻意没有伪造真实方块，因此没有携带随车相机帧，服务器只能追逐上一刻的世界命中点。新增 LOD 体素入口面法线，把同一个相机帧传给现有 `MovingAimRay`，以当前及预测相机位置重算目标。真实碰撞面仍保持原有坐标语义，LOD 不获得实体 UUID 或物理结构锚点。同步把 `MovingAimRay` 内的旧 8192 格限制改为共享的 65536 格网络视线限制。

2. 普通视线查询原先会不断细化到最细缓存，即使当前 Voxy 显示较粗的模型，粗模型的凸出轮廓可能被细数据中的空气否定。新增 `VoxyLodView`，按照已安装 NeoVoxy 0.3.8 的 section 投影面积判据、画面尺寸、`subDivisionSize`、炮镜 FOV 和 vanilla seam ring 选择查询细节。开镜放大时重新按放大后的视角细化。附近真实碰撞继续优先；空缓存、天空仍是未命中。弹道扫掠继续使用原有最细数据策略。

3. 炮镜 HUD 原先主动取消 vanilla action-bar 图层，所以服务器测距提示不可见。新增 `GuiOverlayMessageAccessor`，在炮镜遮罩和瞄准标记之后绘制测距成功、归零、不可用提示，沿用原有提示持续时间和淡出。覆盖默认及预设炮镜。

4. 为遥控输入瞬间的视线采样记录 `Laser rangefinder input snapshot`；涉及 Voxy 时记录 `Voxy rangefinder query`，包含命中/已知未命中/无数据、细节选择是否启用、查询 section/voxel 数量。显式输入查询不会被日常查询前八条的日志限额吞掉。此采样也随其他遥控器新按键沿发送；服务器 `accepted/solved/reset` 才是实际激光测距操作记录。

## 验证

- `testAimRaycastGeometry`：六个体素入口面；同一刻 25 帧侧向瞄准、前进和倒车；600、20000、47999 格目标；实际炮口标记与移动后的炮镜目标一致，旧固定世界点产生超过 2 格滞后。
- `testVoxyLodRaycast`：复现粗模型命中但精细缓存为空气；远处显示粗层时保留命中，16 倍放大和 vanilla seam ring 中恢复细层判断；保留无数据、射程边界、预算和 section 释放检查。
- `testAimGroupPayload`：远距离视线同时覆盖有/无随车相机帧；LOD 仍没有物理目标锚点。
- `testCannonRenderFrame`、`testLaserRangeCompensation`、`testFiniteAimTarget`、`testArticulatedDynamicRay`、`testOpenControllerPayload` 回归通过。
- `shadowJar` 和 `verifyShadedJar` 通过；交付包相关 class 与本次编译产物逐项比对。

日志：`build/voxy-moving-aim-validation.log`、`build/voxy-moving-aim-package.log`、`build/voxy-moving-aim-package-verification.txt`。

源码备份：`backups/voxy-moving-aim-pre-change-20261009-000004`。

尚未在用户游戏中验证新包。CPU 细节选择匹配当前版本的屏幕面积策略，不读取 GPU 当前已就绪的 mesh 列表；异步 mesh 更新、实际缺失的缓存或查询预算仍可能造成差异。本次日志可区分这些原因，不能把所有可见模型失配都宣称已消除。
