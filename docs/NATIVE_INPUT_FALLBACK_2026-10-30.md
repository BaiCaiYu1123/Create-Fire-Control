# 按键映射本体备用路径

火控计算机、燃气轮机、99A 柴油引擎和虎式引擎统一使用 `FireControlInputRouter` 读取按键。现有按键配置、绑定方式、界面和动作编号保持不变。

## 自检与切换

- 正常情况下继续读取 Drive By Wire 信号，并和火控本体独立收到的有效输入比较。
- 接口缺失、反射调用异常、接口返回异常数据、按下信号丢失或松开后信号卡住时，自动使用本体输入。
- 一次操作会话中保持备用路径，避免两条线路反复切换造成重复触发。重新开始控制时再检测原线路。
- 自检失败写入日志，包含终端位置、维度、控制器种类，以及接口不可用或具体按键两条路径信号不一致的原因；无需增加操作步骤。

普通/高级遥控器使用本模组自己的 `remote_input_state` 网络包传输物理输入；全键盘无线控制器直接从本模组已验证的 `ControllerSessions` 读取输入，不经过线控信号仓库。普通遥控器按实际物理键状态采样，避免其他模组清除 `KeyMapping` 状态造成漏键。高级遥控器的输入仍读取现有配置档，不改变映射。

自检只比较客户端能采样的键盘、鼠标和滚轮输入，避免把不能采样的手柄摇杆判为故障。组合键仍要求全部按下；滚轮保留每格一个上升沿及中间松开帧。动作统一处理一次，两条路径不会同时派发同一个动作。

备用输入保留服务器对玩家、手持控制器/讲台、终端、维度和会话的验证。松键、退出、超时或终端移除会清除已接管的输出，即使线控信号仍卡在高电平。普通/高级遥控器沿用 12 tick 输入超时，全键盘控制器沿用 30 tick 心跳超时。存档中的映射字段不变。

网络协议版本为 55，客户端和服务端需要同时更新为同一份模组。

## 验证

```powershell
.\gradlew.bat --offline testRemoteInputRoute testWirelessControllerInputs testEngineBehaviors testOpenControllerPayload testMouseWheelBinding testFireControlWeaponInputs testLaserRangeCompensation

# 在隔离测试目录准备 eula.txt 和 server.properties；使用本机的运行依赖。
.\gradlew.bat --offline --no-configuration-cache -I tools/native-input-checks.init.gradle runServer -PcontrollerIntegration=true -PcbcRuntime=true -PwirelessControllerWireRuntime=true -PrunRoot=run_native_input_wire_verify -PmianbaoJar=面包军火库JAR完整路径

# 去掉线控运行依赖，验证本体路径不依赖其类或信号仓库。
.\gradlew.bat --offline --no-configuration-cache -I tools/native-input-checks.init.gradle runServer -PcontrollerIntegration=true -PcbcRuntime=true -PrunRoot=run_native_input_no_wire_verify -PmianbaoJar=面包军火库JAR完整路径
```

服务端测试会实际清空/卡住线控信号仓库，检查火控和三种引擎的按下、组合键、长按、释放、超时、退出与终端移除，并输出 `server/controller-integration-result.txt`。测试源码不进入发行 JAR。
