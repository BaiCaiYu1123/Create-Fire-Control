# 全键盘控制器：载具拼装后终端映射失效修复

## 修复行为

火控计算机和发动机连接全键盘控制器终端后，Sable 拼装或再次搬迁终端会改变终端坐标及方块实体实例。现在两类设备保存终端的持久 UUID，并在使用连接前刷新坐标、载具 UUID 和运行时实例，无需重新连接或录入按键。

终端暂时不可用时，火控保留可恢复的身份引用，同时停止使用该连接。检查连接先确认区块已加载，不通过读取终端主动加载区块。同坐标新放置的终端使用不同 UUID，不能继承旧连接；多个同 UUID 实例造成搬迁目标不明确时，不随机选取目标。普通及高级终端仍使用原来的连接类型。

遥控会话中的终端坐标变化后，会清理旧坐标上的信号，并向新终端重新写入仍按住的键及其自定义信号强度，不依赖玩家再按一次。

蓝图复制同步映射终端、火控、发动机和讲台内遥控器的终端 UUID。副本内部使用同一个新 UUID，不连接回原载具。终端的身份信息也随客户端方块实体同步；蓝图或存档恢复身份后，已加载终端目录会重新索引。

## 旧存档

- 旧格式且仍然有效的连接在读取、使用时自动补全终端身份。
- 旧版本已经删除的火控终端连接，或已失效且没有终端 UUID 的旧发动机引用，不能安全推断原目标，需要重新连接一次。按键设置保留，无需重录。
- 没有全局放宽 `BindingRef` 的方块替换和维度校验；坐标及载具 UUID 保持不可变，搬迁时生成新的引用，避免修改映射键。

## 验证

实际隔离服务器使用 NeoForge 21.1.230、Sable/Rapier 2.0.6、本地 Drive By Wire 0.3.0。载具检查经过正常 `onLoad` 及服务器 tick：

1. 直接在 Sable 载具上连接，火控输入和发动机点火正常。
2. 旧格式连接升级后，在地面连接、按住输入再真实拼装：两类设备保留连接，火控输入和发动机点火正常，旧坐标释放信号，新终端收到默认强度 16。
3. 两类消费者的存档序列化与读回；终端临时消失时停止输入并保留身份。
4. 同坐标替换终端不能继承连接；恢复原身份的方块实体能恢复连接，目录同步更新。
5. 从既有 Sable plot 再次拼装搬迁后，无需重连，火控与发动机仍能操作。
6. 重复终端身份无法抢占搬迁连接；重复实例移除后可唯一定位；跨维度引用不解析。

`run/controller-mapping/server/controller-mapping-result.txt` 应为：

```text
PASS: assembly, reassembly, persistence, reconstruction and identity isolation
```

另外运行完整遥控器服务端回归，包含 Create 无线频道、物理线缆、自定义信号强度、滚轮脉冲、双键组合、Shift+右键保护、火控映射、燃气轮机/99A/八缸发动机、蓝图终端身份映射、讲台和公开 API。结果文件为 `run/controller-mapping-full/server/controller-integration-result.txt`。

输入路由、火控动作和滚轮绑定检查与 `assemble` 构建另行运行。打包会检查 JCodec 重定位；集成检查类不进入发行 JAR。日志分别为 `build/remote-mapping-fix.log`、`build/remote-mapping-full.log` 和 `build/remote-mapping-build.log`。

最终源码的载具回归、完整遥控/蓝图回归及上述输入检查均已通过，最终组合构建为 `BUILD SUCCESSFUL`。发行 JAR 已额外检查压缩包完整性、修复类存在、测试类及可选依赖未混入、元数据版本未变化。

此处验证的是已复现的拼装引用失效问题。反馈用户的操作顺序和完整整合包尚未直接复现，不把本次结果作为所有映射问题均已排除的证据。

## 产物

工程的 `mod_version=0.9.3` 保持不变。修复包位于 `build/remote-mapping-checks/libs/create-fire-control-0.9.3.jar`；没有替换用户游戏目录中的其他构建。

产物大小为 24,003,475 字节，SHA-256 为 `280c22c481a5716868a3742dbb9c0e94be91e00ec412f81c835cb8b2f3684155`。

开发复跑使用 `tools/remote-mapping-checks.init.gradle` 隔离构建目录：默认运行载具回归；完整遥控回归另加 `-PremoteMappingProbe=false -PcontrollerBlueprintChecks=true -PblueprintLibraryJar=本地LDLib2路径`。运行时仍需 `-PcontrollerIntegration=true -PcbcRuntime=true -PwirelessControllerWireRuntime=true` 和本地面包模组路径。
