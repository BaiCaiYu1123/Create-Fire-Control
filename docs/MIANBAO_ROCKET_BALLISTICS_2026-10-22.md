# 面包火箭巢弹道修复（2026-10-22）

后续实现已改为无实体数学飞行，以下为当时的弹道对照记录。当前行为与验证见 [VIRTUAL_ROCKETS_2026-10-22.md](VIRTUAL_ROCKETS_2026-10-22.md)。

对照 `E:\Codex\mianbao\mianbaos_modernwarfare` 源码及其 `2.6.4` 构建产物。

- 面包的 `ProjectileFlightSupport` / `SableProjectileReleaseMixin` 已负责载具速度继承，`inherits_carrier_motion` 标签包含普通和三种重型火箭弹。火控移除额外的载具速度叠加及首 tick 速度恢复，保留区块票据、安全期、物理结构碰撞和远程同步。
- 预测初速仍包含一次发射点的刚体速度（平移及角速度贡献），这是读取预测输入，不会修改真实火箭弹。
- 原生第一 tick 先执行 `AbstractArrow`，再由尾迹过程缓存速度。空气阻力为 `0.99F`、水中为 `0.6F`，首刻重力为 `0.05`。之后普通弹每刻降低竖直速度 `0.025`，重型弹为 `0.03`，水平速度保持缓存值。持续按箭矢阻力与重力积分会明显低估射程。
- 服务端与 Voxy LOD 预测共用 `MianbaoRocketFlight`。网络传递所选弹种的下降参数及首刻阻力，协议版本升为 35；客户端和服务端需一起更新。
- 预测期限与面包原生 `shijian >= 300` 超时一致，为 15 秒；第 10 tick 开始检测碰撞，与现有安全期一致。
- 整枚实体漏 tick 时，在补步后执行原生飞行过程，再广播速度；仅 `AbstractArrow` 因未加载地形跳过时，实体后续本来就会执行原生过程，不重复调用。
- 原生发射反射接口优先使用五参数版本并传入 `BlockState`，同时保留旧四参数接口兼容。

验证：`testMianbaoRocketBallistics` 使用独立闭式位置公式验证逐 tick 轨迹和地面扫掠交点，覆盖普通/重型弹、移动载具初速、水中首刻阻力、安全期、超时及无效输入。另运行 `testOpenControllerPayload`、`testMianbaoLauncherAmmo`、`testVoxyLodRaycast`。

测试不代替游戏内实射。中型巢的原生随机散布仍然存在；激光制导重型弹的落点表示无制导弹道，飞行途中捕获激光目标后的转向无法由发射前的无制导预测表示。Voxy 的地形精度取决于已有 LOD 数据。
