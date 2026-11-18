# 虚拟炮镜

在火控计算机的「火控设置」中开启「虚拟炮镜」。开启后，「视角设置」中的炮镜 X/Y/Z 偏移滑块与输入框变灰且不可编辑，原偏移仍会保存，关闭后恢复使用。

- 单个高低机：默认位置为高低机中心向其头部前进一格，即俯仰轴位置。
- 多个高低机：必须共轴，视角取轴上两端支点的中点；朝向相反的共轴高低机也可使用。
- 同一高低机挂载多个已组装 CBC 火炮：按火炮的真实俯仰轴线到高低机的距离选择最近轴；共轴双炮取两端轴心中点，不使用炮管包围盒中心。
- 高低机未组装或不共轴时，界面禁用开关并提示原因，服务端也会校验。使用期间结构失效会关闭虚拟炮镜并退出炮镜。
- 开镜时仅当前玩家隐藏炮塔、高低机驱动的炮管子空间、嵌套高低机结构及组装火炮实体；退出炮镜后恢复。普通方块渲染、方块实体、CBC 实体与 Flywheel 实例化渲染均接入隐藏逻辑，载具底盘保留。
- 虚拟炮镜开镜时，火炮准星始终按当前方向投影至无限远，忽略方块、LOD 和弹道命中/未命中的有限落点；跟随误差与已有角度补偿显示保留。关闭虚拟炮镜或退出炮镜后恢复原准星行为，不改变实际弹道和火控瞄准计算。

设置通过 `VirtualScopeEnabled` 存档；当前网络协议版本为 60，客户端与服务端需要同时更新。

验证：

```powershell
.\gradlew.bat --offline testVirtualScopeGeometry testOpenControllerPayload testPodRenderVisibility testCannonRenderFrame
```

真实游戏检查（在独立 `runRoot` 运行，传入本机有效的面包军火库 2.7.0 或以上 JAR）：

```powershell
.\gradlew.bat --offline -I tools/virtual-scope-checks.init.gradle -PrunRoot=run_virtual_scope_verify -PcbcRuntime=true '-PmianbaoJar=完整路径/mianbaos_modernwarfare-2.7.0.jar' runServer
.\gradlew.bat --offline -I tools/virtual-scope-checks.init.gradle -PrunRoot=run_virtual_scope_verify -PcbcRuntime=true '-PmianbaoJar=完整路径/mianbaos_modernwarfare-2.7.0.jar' runClient
```

检查类放在独立 `virtualScopeIntegration` 源码集中，不会进入发行 JAR。

## Photon / Colorwheel 剔除兼容

开启 Iris 光影后，Colorwheel 会替换 Flywheel 的实例化嵌入对象。虚拟炮镜同时过滤这两种嵌入对象，使炮塔上的齿轮、建模方块及其动画子模型在普通炮镜中也能隐藏，退出后随下一帧正常变换恢复；无需开启热成像。

`VirtualScopeShaderClientChecks` 使用真实 Sable 炮塔与实例化齿轮，检查热成像关闭时的 Colorwheel 变换、隐藏与恢复，并保存画面对照。测试需提供 `scopeShaderChecks=true`、`scopeIrisJar`、`scopeSodiumJar`、`scopeVoxyJar`、`scopeColorwheelJar`，并在隔离运行目录中配置 Photon 光影。Colorwheel 是可选集成，没有安装时不会加载对应 Mixin。
