# 虚拟炸弹显示平滑（2026-11-06）

`client/VirtualBombVisuals` 原来每份 `BombFlightPayload` 都直接替换预测轨迹，渲染位置又从新轨迹重新外推，误差会在包到达时跳变。模型朝向也直接使用当前刻速度。

新增 `client/BombRenderInterpolation`：每个虚拟炸弹独立保存显示的前后位置/速度，客户端游戏刻结束时从旧显示位置继续运动，用三刻衰减纠正与权威预测的误差。`VirtualBombFlight` 本身保持原积分，仅作为目标轨迹；每帧位置和速度在显示前后帧间插值。快照接收不直接改变显示位置，过时/重复服务器刻号不再重置预测。保留原删除、生命周期、维度切换、光照、模型、可见范围和爆炸报文。

本轮没有改 `MianbaoBombProjectileHandler`、`MianbaoBombBallistics`、`VirtualBombFlight` 的服务端/物理实现和协议字段，没有增加对面包新 API 的依赖。原生炸弹由面包项目的 `BombFlightSmoothing` 修复，两者各自处理原生与虚拟显示，互不重复计算真实物理。

`src/test/.../client/BombRenderInterpolationChecks` 与 `build.gradle/bombRenderInterpolationChecks` 验证快照误差下的帧连续、插值和正常前进，并纳入 check。还须通过组合客户端的实际 `BombFlightPayload` 接收/显示回调，验证缺包预测、重复包、位置帧边界和移除。详细主模组接入、移植要求及最终证据见面包项目两份《更新移植必读-额外改动保留说明.txt》、EXTRA_CHANGES_KEEP_ON_PORT.md 与 BOMB_FLIGHT_SMOOTHING_2026-11-06.md。

本轮验证结果：无 Sable、真实 Sable 2.0.6、Sable 2.0.6/火控 1.0.3beta 服务端专项各 3 项通过，覆盖全部14类炸弹的低速完整状态、原生移动顺序与重力/阻力/制导参数一致性，以及缺包预测、同刻多次同步、误差平滑、有限外推和落地。无Sable客户端和真实Sable/火控客户端均通过14类实际实体的6,048个运动刻、36,288个帧位置、611个实际模型跨刻边界检查；受控无动力轨迹最大误差及模型边界跳变量均为0。组合客户端另外通过648个火控虚拟炸弹刻，覆盖真实报文编解码、快照回调、1/3/6刻间隔、重复/过时包、帧连续与删除，代理没有重复注册原生实体。火控完整build/check及新增显示检查通过。该结果为自动化受控场景，未进行完整整合包人工试玩或FPS测量。