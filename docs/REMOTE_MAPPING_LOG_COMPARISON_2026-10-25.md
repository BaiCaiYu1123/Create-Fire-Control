# 全键盘遥控映射反馈：两组日志对照

## 结论与边界

两组日志均未直接记录全键盘遥控映射的数据包处理异常。第二组 debug 能确认输入 Mixin、遥控事件监听和 Drive By Wire 的 `ControllerSignalStore` 类已加载，因此“这些类或输入钩子完全不存在”的解释不适用于第二组；它仍不能证明 `getSignals` 接口探测成功或每次输入已到达火控。

用户补充确认：第二例火控在 Sable 拼装载具上；视频中关闭再打开绑定页仍保留按键；同终端上的发动机映射也不生效；手持全键盘控制器已经右键终端配对。因此不再把“映射没有保存”或“未配对”作为当前主要解释。重点转为两类消费者共用的终端发现、有效按键枚举和信号读写链路。

初次排查只添加了隔离服务器的 Sable 输入复现检查。随后用户要求修复，已完成实现及回归；修复行为、旧存档处理和产物见 [修复记录](REMOTE_MAPPING_FIX_2026-10-25.md)。以下保留修复前的诊断证据。

## 日志能确认的事实

| 项目 | 第一组 `latest (2).log` | 第二组 `latest (3).log` / `debug (1).log` |
| --- | --- | --- |
| 火控文件名 / 内部版本 | `create-fire-control-0.9.4.jar` / 0.9.3 | 相同 |
| Drive By Wire | 0.3.0 | 0.3.0，文件名注明 Sable 兼容 |
| NeoForge | 21.1.248 | 21.1.248 |
| Sable | 2.0.6 | 2.0.5 |
| 全键盘输入钩子 | INFO 日志没有加载细节 | 键盘、鼠标、物理按键 Mixin 已应用，遥控监听已注册 |
| `ControllerSignalStore` | 没有接口探测结果 | 类元数据存在，并被 Flight Control 的采样 Mixin 变换 |
| Create Flight Control | 未安装 | 0.8.3 |
| Fusion 模型加载错误 | 大量存在 | 没有同类错误 |

版本文件名与内部元数据不同只能说明不能凭文件名识别构建，不能据此断定它一定是旧代码。

第二组的 `ToolGunValueSettingsMixin` 确实有错误，但所属模组在第一组不存在，不能作为两例的共同必要原因。`ControllerSignalStoreMixin` 的注入日志也不能单独证明该模组吞掉了输入。最后的 `Manually triggered debug crash` 是手动触发调试崩溃，不是火控按键映射抛出的崩溃。

## 两类消费者共用的运行链路

1. `ControllerSessions.resolveHub` 根据物品内 `Hub` 的 `BindingRef` 和 `Processor` UUID 查找终端；地址失效时尝试已加载终端目录。
2. `ControllerActionRegistry.channels` 从绑定该终端的火控及发动机收集按键。火控通过 `ControllerTracker.findForRemoteTerminal`，发动机通过 `GasTurbineBlockEntity.channelsForTerminal` 发现。
3. 两者都要求终端 `BindingRef.isSameTarget` 成功。它检查维度、Sable 载具 UUID、方块 ID、已观察方块实体实例；按键字符串存在并不证明这个检查成功。
4. 会话仅将活跃消费者需要的按键放进 `Actions` / `Effective` / `EffectiveOutputs`。客户端仅向服务器发送接管的输入；键帽动画同时反映其他物理按键，因此动画不能证明有效输入已传输。
5. 按下有效键后，`ControllerWireBridge.set` 写入 Drive By Wire 的 `ControllerSignalStore`，火控和发动机随后通过 `DriveByWireCompat.signals` 读取。

本地 `drivebywire-0.3.0.jar` 的反编译确认：`ControllerSignalStore.setSignal` 和 `getSignals` 分别委托给 `WireNetworkManager.setSource`、`getSourceSignals`，信号储存依赖 Level 和终端坐标。`setSource` 在没有物理线缆时也保存来源值，并未按新终端方块类型拒绝写入。此结论仅针对本地依赖，尚无反馈用户实际 JAR 的哈希。

## 保存路径的独立缺口

1. 打开火控界面的 `OpenControllerPayload` 已携带 `controllerSubLevelId`。
2. 常规设置包 `ControllerSettingsPayload` 用坐标和船体 ID 调用 `ControllerTracker.findLoadedForSettings`，再根据实际方块实体校验编辑距离。
3. 全键盘映射包 `SetFireControlInputPayload` 只携带 `BlockPos`，客户端提交映射时没有传递界面中已有的船体 ID。
4. 该保存处理仍调用 `canEditNearby(player, p.pos)`，随后执行 `player.level().getBlockEntity(p.pos)`，终端检查也使用玩家所在的 Level。校验或查找失败时直接返回，没有失败回包或日志。
5. 频道配置页同样使用这个包；普通和高级遥控映射的 `SetRemoteKeyBindingPayload` 也保留类似的旧地址处理。

这可以导致另一类“录入后重新打开丢失”的问题，但与本次已保留按键、发动机同样失效的证据不符，不能用修复这个保存包代替验证当前运行故障。

## 最短验证步骤

已完成的外部确认不再重复要求用户验证：按键保留、已配对、拼装载具上的火控和发动机均失效。

- 使用真实 Sable plot、正常 `onLoad` 及服务器 tick，验证会话 `HubValid`、`Actions` / `EffectiveOutputs`、终端实时信号、火控输入和发动机点火。
- 比较直接在载具上连接与连接后再拼装/移动/焊接的情况，区分读取世界实例问题和引用未更新问题。
- 对反馈用户实际使用的火控和 Drive By Wire JAR 核对哈希及接口，避免拿同名的其他构建代替验证。

## 修复前的隔离实测结果

已用当前源码、NeoForge 21.1.230、Sable 2.0.6、Rapier 2.0.6、本地 Drive By Wire 0.3.0 运行真实服务器。检查经过正常方块实体 `onLoad` 和服务器 tick，并非只向内存字段注入数值。客户端的真实键盘与反馈整合包尚未在此检查中复现。

| 场景 | 终端识别与有效键 | 火控与发动机 |
| --- | --- | --- |
| 直接在载具上放置并连接 | 终端正确，`Actions` / `Effective` 包含 W、A | 按键使火控机枪输入激活、发动机点火 |
| 地面连接后用真实 Sable `assembleBlocks` 拼装，再启用同一遥控器 | 遥控器按终端 UUID 找到新坐标；两类消费者键名均保留；`Effective=[]` | 火控终端绑定为 `null`；发动机终端引用仍指向原地面坐标，两者验证均失败 |
| 拼装完成后只重新连接火控和发动机到终端 | 未重新录入按键；有效键恢复 | 正常服务器 tick 后，火控输入与发动机点火均恢复 |

已复现的机制是：终端搬迁后手持遥控器有 UUID 重定位逻辑，火控和发动机却只保留原 `BindingRef`。火控 `removeInvalidBindings` 将失效绑定删除，发动机 `hasValidAdvancedHub` 返回 false。它们因此不再作为活跃消费者贡献按键，会话不接管这些按键，也不会输出对应信号。按键名称另行保存，所以重新打开页面仍能看到键名。

这解释了一个与用户现象一致、已经实测的共同失效流程；用户尚不能确认反馈者是先绑定再拼装，故不能认定两例一定由该流程触发。蓝图恢复、焊接和反馈整合包中的其他交互不能只凭本次拼装测试一并宣告验证。

临时恢复措施已验证：载具拼装完成后，重新将火控和发动机连接到全键盘控制器的终端，无需重新录入按键。正式修复应让这两类消费者也能按终端持久身份刷新引用，并保留终端替换、维度隔离和蓝图复制时的身份校验，不能全局取消 `BindingRef` 检查。

初版检查文件为 `src/controllerIntegration/java/com/hooya/stabilizedturret/content/remote/SableRemoteMappingIntegration.java`，启动配置为 `tools/remote-mapping-checks.init.gradle`。当时的结果为 `PROBE COMPLETE: assembly fault=true; direct mapping and rebinding recovery verified`，完整输出仍在 `build/remote-mapping-probe.log`。该检查随后已改为严格断言无需重连的修复回归，当前结果及产物见修复记录。

可在现有本地依赖齐全时复跑：

```powershell
.\gradlew.bat runServer --offline --no-configuration-cache -I tools/remote-mapping-checks.init.gradle -PcontrollerIntegration=true -PcbcRuntime=true -PwirelessControllerWireRuntime=true -PrunRoot=run/controller-mapping -PmianbaoJar='本地面包模组JAR绝对路径'
```

日志分析阶段未修改发行功能代码；后续修复仍未改动工程版本号或用户安装包。
