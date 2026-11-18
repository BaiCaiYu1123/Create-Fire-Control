# APFSDS 停弹后错误悬空的位置同步修复

交付包：`build/libs/create-fire-control-1.0.4-apfsds-stop-sync-fix.jar`。

玩家整合包 `Create航空学/logs/latest.log` 在 2026-11-17 09:19–09:20
记录了火控 APFSDS 穿透装甲后命中后方基岩并返回 `STOP`。因此本次显示
在空中的弹芯需要检查停弹位置同步，不能只依据穿甲成功来验证。

## 根因

自由飞行时由 RPL 精确运动包直接更新客户端实体的位置。原接收流程没有
同时更新 Minecraft 的相对移动包坐标基准。CBC 停弹后切回普通同步，
服务端使用最新位置作为基准，客户端却仍从旧发射位置计算相对位移，
导致弹芯在错误坐标静止。另外 CBC 忽略实体前两个客户端 tick 的 `lerpTo`，
高速弹丸在出生当帧命中时可能错过最后的位置修正。

修复前真实客户端回归中，一发不经过装甲的 APFSDS 已在服务端停于
`(0.5,102.5,20)`，客户端却停于 `(0.5,102.5,-2)`，相差 **22 格**。
这也确认该问题不局限于多面体装甲。

## 修复

`client.ApfsdsPreciseMotionMixin` 在原 RPL 接收及 CBC 运动处理之后，
仅为火控 APFSDS 同步相对移动包的坐标基准，并为前两个客户端 tick
补上精确位置和旋转。保留原 RPL 接收逻辑、完整速度、CBC 停弹、穿透与
跳弹判定，以及已有 Sable 同步路径。没有增加网络数据包或终端弹道学依赖。

## 验证

独立超平坦测试世界，实际服务端实体、客户端副本与真实网络包：

- 24 格/tick 高速弹，覆盖已追踪实体与出生当帧撞墙、斜射撞地。
- 包含普通基岩、先贯穿伪装装甲再撞基岩，以及实际组装的 Sable 方块墙。
- 每发停止后比较两端世界位置、停弹标记和结构坐标状态。
- 修复前基线日志：`run/apfsds-stop-terminal-baseline/client/logs/latest.log`。
- 未装/安装 Terminal Ballistics 0.3.5 两组各 9 发均通过，共 18 发；
  每发停止后的两端位置误差均为 0，停弹状态一致，客户端正常保存退出。
  两组日志分别为 `run/apfsds-stop-native-verified/client/logs/latest.log`、
  `run/apfsds-stop-terminal-verified/client/logs/latest.log`。
- 夹具位于 `src/armorIntegration`，不会进入发行 JAR；测试世界独立，
  没有修改玩家的存档、游戏配置或已安装的模组。
- `testApfsdsMotionSync testCopypoly assemble` 通过；包含 1,296 次 RPL
  同步包往返、发行类/资源、许可边界及测试夹具排除检查。

```powershell
.\gradlew.bat runClient --offline -PcopypolyClientSmoke=true -PapfsdsStopSmoke=true -PcbcRuntime=true -PmianbaoJar='D:\mods\mianbaos-modernwarfare.jar' -PrunRoot=run/apfsds-stop-native-verified
# 加 -PterminalBallisticsRuntime=true 验证终端弹道学环境。
```
