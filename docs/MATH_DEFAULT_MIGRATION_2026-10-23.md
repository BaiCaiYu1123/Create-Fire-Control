# 数学弹道默认启用与旧存档迁移（2026-10-23）

> 2026-11-14 更新：以下为旧版行为记录。当前版本改为实例级 `config/create_fire_control-server.toml` 的 `flight.trajectoryMode`，默认 `MATHEMATICAL`，可在设置菜单选择 `ENTITY`。原指令写入同一全局文件，旧存档 `.dat` 不再作为默认模式来源。详见 `FIRE_CONTROL_CONFIGURATION_2026-11-14.md`。

支持数学飞行的火控接管导弹默认使用 `math`。新存档直接启用；旧版本世界保存的 `Mathematical=false` 在首次加载新版时自动迁移，不再沿用旧的实体默认值。旧版本显式选过实体的世界也按本次要求执行一次迁移。

```mcfunction
/cfc missile mode
/cfc missile mode entity
/cfc missile mode math
```

第一条查询当前模式。后两条需要权限等级 2，分别开启实体路线和恢复数学路线。新版 `entity` 指令的显式选择保存到世界，重启后继续有效；后续正常更新不反复覆盖这个选择。

## 保存规则

世界模式仍保存在主世界的 `data/create_fire_control_missile_mode.dat`，新增 `DefaultPolicyVersion=1` 和 `EntityModeExplicit`。只有当前策略版本下的显式实体标记可以启用实体路线。启动服务器时主动读取并迁移模式，不需要发射或执行查询指令才能触发迁移。

每枚支持数学模式的导弹在选定路线时记录策略版本。旧版本保存的未版本化实体路线，在恢复时根据迁移后的世界模式重新选择；本策略下已经选定路线的在途弹，在指令切换时保留各自路线。

数学模式的适用范围沿用原来的 `GuidedMissileMode.eligible`，不扩充弹种：当前安装版本的 22 种高速防空、空空、空地、激光与巡航导弹，以及已有旧版别名。普通防空弹、便携变体、光学/电视制导弹和未被火控接管的原生弹药不因此进入数学路线；独立弹道导弹体系也保持原路线。

## 验证

单测覆盖新存档默认值、旧实体/数学设置迁移、显式实体授权保存和恢复、切回数学后清除授权、无授权的 false 值、旧弹路线迁移及在途路线固定。

实际服务端验证通过独立新世界和预置旧版 `Mathematical=false` 文件的世界运行。覆盖 22 个真实弹种加入世界时实际进入的实体/数学后端、三种排除弹种、未接管原生导弹、旧版实体弹恢复、真实控制台指令与在途路线固定。另重启测试世界，核验新版实体指令选择的跨进程保存与恢复。

同时保留并验证上一版防空弹原版粒子三倍放大修复，包含三种原版粒子配方和八种接管命中/反导场景。没有修改弹道积分、比例制导、过载限制或伤害计算。

日志和构建审计保存在 `.diagnostics/math-default-20261023/`。服务端测试夹具通过 `tools/aa-explosion-checks.init.gradle` 的 `modePolicyChecks` 开启，不进入正式 JAR。

三个独立进程验证结果：`new-world.log` 和 `legacy-world.log` 启动时均为 `startupMath=true`，`restart-explicit-entity.log` 为 `startupMath=false`，三次均正常保存退出并 `BUILD SUCCESSFUL`。最后切回数学后，实际保存文件为 `Mathematical=true`、`DefaultPolicyVersion=1`、`EntityModeExplicit=false`。

交付：`deliverables/math-default/create-fire-control-0.9.1-math-default.jar`，23906496 字节，同时保存到 `build/libs/`。26 个弹道、制导、伤害与防空爆炸特效类和上次交付逐字节相同。SHA-256：

```text
34f8205dc0f2147ea7a11bd9b05cf8f822bc67cc3edeeb184c0f0321963547af
```
