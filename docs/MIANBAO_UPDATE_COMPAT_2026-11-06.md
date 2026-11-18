# 2026-11-06 面包新 ID 与武器站兼容

适配面包 `2.7.0-beta` 的最新原版更新，火控自身维持 `1.0.2`。本轮兼容全部实现在火控端，不能在后续移植时搬回面包侧。

## 原因与接入点

1. 上游将 `weaponcontrolsystem_1/2` 改名为 `weapon_station_type_1_sand/green`。`content/controller/MianbaoLauncherIdMigration.java` 保留旧/新别名，恢复既有 `BindingRef`，不能丢失维度、位置、子层身份或其他绑定数据。
2. 新增 `weapon_station_type_2_green/sand`。`content/controller/MianbaoRemoteWeaponStationCompat.java` 同时识别一型/二型双色四款新武器站，继续通过面包公开 API 使用既有角度、射击、弹药和惯性功能。
3. 面包上游新增原生投射物逐实体区块票据。`mixin/MianbaoNativeProjectileTicketsMixin.java` 在 `NativeProjectileChunkLoading.update` 入口识别火控发射/接管标记，释放可能存在的原生票据并取消原生更新；火控沿用自身票据生命周期。保留 `create_fire_control.mixins.json` 注册。不能在面包实现中识别这些火控标记，也不能扩大为清理其他对象的区块票据。

## 必须保留

保留原有导弹/火箭/机炮爆炸 HEAD 拦截、核爆仅视觉/仅地形公共入口、超视距消息和重复抑制、普通防空弹原生红石桥、挂架高度/离架/双发/四联、远距弹点火交接。该补丁不能绕开这些公共入口，面包也不能增加对火控的强制依赖。

## 验证

- 正式 `build -PcharacterizeCarrierHold=true --offline` 通过，84 个任务。
- 正式火控包 + 面包 + 真实 Sable 2.0.6 / 航空学 1.3.2 服务端组合 36 项通过，包含新旧 ID 绑定恢复、四款新武器站识别与实际红石开火、原生票据/火控接管边界、爆炸与历史挂架回归。
- 两份正式 JAR 的独立 Sable 2.0.6 存档测试：125 个旧 ID、508 个旧状态、18 个旧 BE 根 ID，四艘新旧/原版对照载具；完全停服后检查磁盘，并用两个新进程重读。每次四艘载具保留，库存/组件/武器站角度、原生与火控绑定均保留。最终贴图修订包额外重读一次并核对 SHA256。
- 成品没有 GameTest 类、测试清理器、测试结构或 Sable 实现；原有 JCodec/JAAD 媒体库继续由 Shadow 重定位并打包。

完整证据：`E:/Codex/mianbao/.upstream-update-20261106/`；主报告：`E:/Codex/mianbao/mianbaos_modernwarfare/UPSTREAM_MERGE_2026-11-06.md`。未做用户完整整合包人工试玩。
