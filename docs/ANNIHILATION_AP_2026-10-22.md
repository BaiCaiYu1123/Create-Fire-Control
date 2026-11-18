# 穿透一切

交付包：`build/libs/create-fire-control-0.9.1-pierce-everything.jar`。

物品 ID：`create_fire_control:annihilation_medium_cannon_cartridge`（完整炮弹）、
`create_fire_control:annihilation_medium_cannon_round`（弹头）。
实体 ID：`create_fire_control:annihilation_medium_shell`。

完整炮弹位于火控创造物品栏，并加入 CBC-MW 中口径弹药标签。
独立“穿透一切弹头”只作为 CBC 炮弹内部载荷保留，隐藏于创造物品栏及搜索页，
并加入 `c:hidden_from_recipe_viewers` 标签。
物品使用用户提供的“创造穿甲弹.bbmodel”，保留 19 个立方体及实际使用的原始内嵌贴图，
加附魔光效以便区分，共用 APFSDS 弹壳。飞行时继续使用现有 APFSDS 弹体模型。
源文件保存在 `reference/pierce_everything/source.bbmodel`，可通过
`tools/import_pierce_everything_model.ps1` 重新导入；预览工具为
`tools/render_pierce_everything_model.py`。
装填接口、可用火炮类型、两倍初速倍率、1% 线性阻力与现有 APFSDS 一致。
重力为零，使用现有精确运动同步和尾迹修复。
APFSDS 弹头的物品栏图标按实际旋转后投影范围居中，只调整 GUI 平移，飞行模型与手持姿态不变。

## 命中行为

- 先尝试一次 2,100,000,000 点伤害。已经由伤害击杀的生物保留正常死亡、掉落和死亡动画，
  不强制删除，也不写入 UUID 删除记录；再次扫过其尸体时不重复伤害。
- 只有这次伤害未能完成击杀时，才对非玩家生物调用 Entity 内的直接移除接口，绕过
  `hurt`、`setHealth`、`kill`、`remove` 等覆写。血量归零但拦截 `die()` 的生物也进入删除分支。
- 被删除生物的 UUID 记录在世界 SavedData 中；同 UUID 实体重新加入世界时被拒绝。
  玩家沿用正常伤害和重生流程。
- 支持 NeoForge 多部件生物，命中部件时处理其父实体。
- 沿每 tick 的实际飞行线段遍历所有体素，把命中方块直接设为空气，
  不检查硬度、CBC 护甲或破坏权限，不调用破坏、掉落或爆炸接口。
  删除容器前先移除方块实体，避免库存掉落。
- 无穿透次数、距离或方块种类上限；绕过 CBC 常规每 tick 100 次碰撞循环，
  不消耗弹体质量、不跳弹、不因命中而落地。
- 对相交 Sable 结构，将完整线段转换到结构的 plot 坐标，再执行相同删除操作。

## 现有装弹机

MZ、AZ 和 10 式装弹机均可右键装入“穿透一切”，也可与原 APFSDS 混装。
每个槽位单独保存弹种，并按原装弹顺序生成对应的完整 CBC 炮弹送入炮膛。
新弹种位掩码随方块实体保存，装填关键帧前后重载不会把新弹变回旧弹。
旧存档缺少弹种信息时仍按原 APFSDS 读取。三种装弹机内部弹药几何、贴图、动画保持原资源。

## 寿命与区块

出生时确定绝对到期时间：世界游戏时间 + 200 tick（正常 20 TPS 时为 10 秒）。
全局服务器 tick 独立维护活动炮弹，到期直接删除，不依赖炮弹自身执行 tick。
实体类型禁用保存，因此区块完全卸载或服务器停止后不会留下可再次加载的过期弹体。
NBT 额外保留到期时间，兼容外部工具恢复实体时拒绝已过期炮弹。
不额外给炮弹加载区块；离开可运行区块后的清理由全局生命周期完成。

## 验证命令

```powershell
.\gradlew.bat runServer -PannihilationIntegration=true -PcbcRuntime=true '-PrunRoot=build/annihilation-verify' '-PmianbaoJar=E:/Codex/mianbao/mianbaos_modernwarfare/build/libs/mianbaos_modernwarfare-2.6.3-neoforge.jar' --offline --console=plain
```

测试运行在隔离世界，仅监听 `127.0.0.1:25597`，完成后自动退出。
结果和具体检查记录位于 `build/annihilation-verify/server/annihilation-integration-*.txt`。
集成测试代码不进入正式 JAR。

验证通过：真实 CBC 装填发射、原 APFSDS 初速与阻力、无重力飞行、精确运动同步；
三种装弹机混装、真实炮膛入弹和关键帧前后存档恢复；普通生物只承受一次伤害并保留正常死亡；
单 tick 删除连续 161 个基岩/屏障/容器且容器库存不掉落；强制删除模拟锁血 Boss 并拒绝
同 UUID 重新生成；旋转 Sable 结构中的方块删除；炮弹自身 tickCount 始终为 0 时，
仍在第 200 个服务器 tick 被移除。`testApfsdsMotionSync`、`testCannonImpactPrediction`、
`shadowJar` 和 `verifyShadedJar` 通过；最终 JAR 已核对生产类、Mixin、语言、弹药标签及模型资源。

该测试使用拒绝伤害、锁血、覆写移除并拒绝普通投射物命中的模拟 Boss。
未安装具体泰坦模组版本；任意模组自行生成新 UUID 的替身不属于同实体复活拦截范围。
