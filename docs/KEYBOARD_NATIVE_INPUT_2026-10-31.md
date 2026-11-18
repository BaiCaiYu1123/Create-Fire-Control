# 全键盘控制器本体映射链路

无线控制器信号处理器移回火控模组的创造标签页，合成配方不再要求安装 Drive By Wire。终端的原模型和贴图已按 MIT 许可复制到火控本地资源中，保留原几何、UV 和物品显示参数；只修改贴图命名空间。MIT 许可与来源说明随模组一起打包在 `META-INF/licenses/drivebywire-model-MIT.txt` 和 `META-INF/notices/drivebywire-model.txt`。外观与按键功能均无需加载线控模组。

原版连接与断开音效 `plug_in.ogg`、`plug_out.ogg` 同样原样收录到本地资源。右键绑定终端时播放连接音，配置页解除有效配对时播放断开音，均由服务端以原音量和音调触发一次；未安装 Drive By Wire 也正常播放。公开 API 的纯逻辑操作保持无音效。

## 输入链路

```text
键盘/鼠标/滚轮
  → WirelessControllerActionPayload
  → ControllerSessions：验证玩家、物品/讲台、会话、终端身份及有效按键
  → 本体键盘状态、组合键和滚轮脉冲
  → KeyboardControllerInputs
  → 火控计算机 / 燃气轮机 / 99A 柴油引擎 / 虎式引擎
```

键盘映射固定使用这条链路。不会读取 Drive By Wire 的 ControllerSignalStore，也不需要自检失败后再切换。普通 Create 和高级遥控器原有的兼容与回退路径保持可用。

安装 Drive By Wire 只增加独立输出分支：有效线缆频道 → WireNetworkManager → 实体线缆。功能映射不写入线控信号仓库，没有拉线的功能键也不发送线缆信号。线控仓库缺失、信号丢失或卡在高电平均不影响本体键盘映射。

## 发动机相关修正

- 新发动机默认录入本体键盘输入，可先配置按键再绑定终端。旧存档已保存的高级遥控器配置继续保留。
- 发动机直接绑定本体终端，不需要放置火控计算机；三种发动机均发布自己的有效按键。
- 保存发动机/火控映射或重新绑定终端时，立即刷新控制器使用的输入集合。一个终端连接多个火控时，收集所有火控的映射。
- 功能映射使用按下/松开状态，与无线红石和线缆的 0–16 输出强度分开。强度为 0 时，点火和其他功能键仍然有效。
- 长按点火只切换一次；组合键、滚轮逐格触发、退出键保留规则不变。
- 松键、停止操控、心跳超时、换手/换物品及终端被替换后，本体输出清零。

## 操作

1. 从火控本体标签页取得无线控制器信号处理器，或按原配方合成。
2. 手持全键盘无线控制器右键终端，完成绑定。
3. 持扳手先点击火控或发动机，再点击终端，沿用原有设备绑定方式。
4. 在原有按键设置页录入按键；右键启用控制器，使用原有退出键结束操控。

已有终端、物品 ID、配对身份和按键存档格式保留。客户端和服务端应使用同一份更新后的模组。

## 验证

`KeyboardNativeIntegration` 使用实际方块生命周期、原有绑定操作和键盘输入包，通过正常服务器 tick 验证三种发动机独立控制、双火控共享终端、未绑定前保存按键、零强度点火、运行中改键、存档重载、退出、超时及终端替换。测试不手动注册或逐个调用发动机/火控 tick。

分别在未安装和安装 Drive By Wire 的隔离服务器运行。安装时注入外部错误信号，验证本体不读取该信号，并验证长按中新增线缆可接收独立配置的强度。

```powershell
.\gradlew.bat --offline testRemoteInputRoute testWirelessControllerInputs testEngineBehaviors testOpenControllerPayload
.\gradlew.bat --offline --no-configuration-cache -I tools/keyboard-native-checks.init.gradle runServer -PcontrollerIntegration=true -PcbcRuntime=true -PrunRoot=run_keyboard_native_no_wire_20261031 -PmianbaoJar=面包军火库JAR完整路径
# 在另一个隔离 runRoot 加入 -PwirelessControllerWireRuntime=true，验证可选线缆。
```

结果文件为 `server/keyboard-native-result.txt`。既有控制器集成测试继续验证无线红石、组合键、滚轮、讲台、退出键和公开 API。测试源码及其他模组不会打入发行 JAR。
