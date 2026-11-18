# 雷达遮挡后的记忆框与闪烁

交付包：`deliverables/create-fire-control-1.0.4-radar-memory.jar`。

SHA-256：`1cb4907bdf67d57505fefab432e7275fa7107b49550007a85715bb1eab27fe17`。

## 行为

- 机载雷达保留最后一次确认过的目标 60 ticks，即正常 20 TPS 下 3 秒。
- 防空及连接显示器雷达保留 40 ticks，即 2 秒；显示器采用本项目的防空时长规则。
- 遮挡后的记忆框按 2 Hz 明暗变化，每 10 ticks 一个半余弦周期，透明度约 20%～100%。参考来源见 `WAR_THUNDER_RADAR_MEMORY_REFERENCE_2026-11-14.md`。
- 在期限内重新确认同一目标，立即替换预测位置并停止闪烁，刷新最后观测时间；下一次丢失重新计时。
- 持续遮挡不能延长记忆；到期删除。关闭雷达、切换机载/地面显示模式、客户端换世界及时间回退清除客户端记忆。
- 服务端保留短暂锁定意图，恢复真实检测时自动接回同一锁定。到期或手动解锁后不会因该意图再次锁定，手动换锁会覆盖旧意图。

## 真实扫描与显示分离

`RadarContactMemory` 是纯显示缓存，最多 128 项；数据只能来自原来真实扫描且通过客户端 Voxy 过滤的联系人。失去检测后只用最后的位置、速度及包围盒外推，不读取隐藏目标实体的新坐标。测距随预测位置更新，高度使用最后观测高度加预测垂直位移。

`RadarLockMemory` 每个控制器最多记录锁定、选择两个意图，存放在弱引用键表中。`getRadarContacts()`、`getRadarLockedSubLevelId()`、`getRadarLockedSubLevel()`、`getAircraftGuidanceTargetId()` 仍只暴露真实扫描状态。网络包可以携带记忆框的目标 ID，但隐藏目标不出现在网络联系人的坐标列表中。

RWR、发射检查和自动攻击不读取客户端记忆；显示器服务端的 `getContacts()` 仍返回原来的真实列表。记忆期间不显示地面火炮的实时提前量，也不使用其平滑实时目标位置。其他导弹的飞行、制导和红外/空对地遮挡后端未改。

没有改变射线、异步读取、解码线程和地形缓存预算。记忆层不添加射线或区块读写；客户端每个 tick/输入列表最多更新一次，每个渲染帧只计算闪烁颜色。网络格式保持不变。

## 打包与保留功能

以当前安装的 `create-fire-control-1.0.4-preview-large-structure.jar` 为底包，SHA-256 为 `3022b157a799c079eff5f35ff6c8e5776afb91b030b57a6bf1ba448eb9b615d2`。

对 7 个现有类只替换审查过的方法；几何批次增加一个透明度字段；其余方法通过 ASM 规范化逐项验证，保留共 911 个其他方法。新增 3 个辅助类族；所选方法引用的 lambda 单独重命名，防止共享源码的编译序号覆盖底包其他 lambda。JAR 其他条目逐项字节一致。

特别核对以下类族与底包字节一致：RadarVisibility、ClientRadarVisibility、VoxyLodRaycaster、InfraredTargeting、机载/防空导弹制导处理器、FireControlSettings。大型结构预览、已有远距离修复及当前配置均保留。详细清单为交付包同名 `.changes.json`。

## 验证

- `build-corrected.log`：编译、着色 JAR 检查、记忆状态单元检查、选择性方法补丁审计通过。
- `artifact-final.log`：使用实际交付 JAR 的 40/60-tick 精确超时、外推、恢复刷新计时、清理、20000 输入下缓存上限、2 Hz 公式，以及真实 HUD/显示器/网络类链接检查通过；Voxy/RWR 原有回归通过。
- `integration-corrected.log`：使用交付 JAR 的生产服务器控制器和真实 Sable 结构验证机载/防空遮挡、期限内接回、再次遮挡、精确到期、手动取消和关闭雷达；验证隐藏目标的网络坐标列表为空、真实锁定/制导为空、RWR 无照射。
- 同一生产服务器运行机载/防空在 4 km 的静对静、静对动、动对静、动对动，每项连续 60 次持锁；红外/空对地四组合各 40 次持锁及各 4 次发射前复查；真实墙体拒射、流体恢复均通过。
- 横向 351 km/h、冷 4 km、地形缓存压力、贴地飞行、预算耗尽及真实墙体回归通过；横向用例单次最高约 0.67/0.78 ms，仅是一次性测试世界测量。

首轮集成测试发现“接回锁定后未刷新服务端记忆起点”，已修正，并保留失败日志 `integration.log`；修正后所有上述检查通过。证据统一位于 `analysis/radar-memory-20261114/`。

尚未用新 JAR 启动用户的实际世界观察画面，也没有逐一重跑所有导弹的发射、制导和命中全过程。

## 已安装

安装前确认游戏进程已退出，只剩 Gradle 守护进程，并核对原包、交付包哈希。备份旧包后，将交付包复制到原文件名，安装后再次核对哈希，mods 中只有一份火控 JAR：

`D:\Game\PCLII\.minecraft\versions\Create航空学\mods\create-fire-control-1.0.4-preview-large-structure.jar`

旧包备份（在 mods 外）：

`D:\Game\PCLII\.minecraft\versions\Create航空学\codex-backups\radar-memory-20261114\create-fire-control-1.0.4-preview-large-structure.jar`

未编辑用户存档。下次启动该实例即加载新包；多人环境服务端也需使用相同版本。
