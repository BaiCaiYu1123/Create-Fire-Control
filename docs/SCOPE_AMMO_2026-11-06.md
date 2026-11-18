# 炮镜穿甲弹余量

炮镜左下角、倍率文字上方显示本模组尾翼稳定脱壳穿甲弹的物品图标，右侧显示装弹机余弹与炮闩内实弹的合计数量。

- 以当前炮镜所属炮塔组的火炮为准，通过炮闩内的 `create_fire_control:apfsds_medium_cannon_cartridge` 或绑定装弹机的同类待装弹识别弹种。已识别的火炮在退壳、空膛和装填期间持续显示，实际装入其他弹种后隐藏。
- 合计绑定给这门火炮供弹的 MZ、AZ 或 10 式装弹机中的同类弹药，以及炮闩里的一发。MZ / AZ 在送弹完成至动画结束的交接期间，同一发炮弹计数一次。
- 只剩炮闩内一发时显示 `1`，射出最后一发后持续显示 `0`。数量由服务器逐 tick 同步，库存消耗、补充和装弹机重新加载后自动刷新。
- 使用炮弹现有物品模型作为图标，按窗口高度缩放，兼容默认炮镜、预设炮镜、热成像和 GUI 缩放。

新增 `scope_ammo` 数据包，当前工程网络协议版本为 **60**，联机需要同时更新客户端和服务端。无需配置开关；保留上一版车体 / 炮塔方向图。

验证命令：

```powershell
.\gradlew.bat testScopeAmmo testScopeOrientation testMzLoaderPersistence testAzLoaderState testType10LoaderState assemble --offline
```

独立客户端检查包含三种真实装弹机、实际 CBC 组装火炮、混装库存、不同火炮的库存隔离、多装弹机合计，以及逐 tick 的射击和完整装填周期。回归检查在送弹前、送弹后和关闭炮闩后分别重载存档，核对合计数量，并覆盖最后一发和归零状态。

```powershell
.\gradlew.bat runClient --offline -I tools/scope-orientation-checks.init.gradle '-PrunRoot=run_scope_ammo_reload_verify' '-PscopeAmmoChecks=true' '-PcbcRuntime=true' '-PmianbaoJar=面包军火库2.7.0或以上JAR的完整路径' '-Pgeckolib_version=4.9.3'
```

结果位于 `run_scope_ammo_reload_verify/client/scope-orientation-client-result.txt`，截图位于该目录下的 `scope-orientation-qa/`。
