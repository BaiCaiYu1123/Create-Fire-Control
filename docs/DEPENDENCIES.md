# 本地开发依赖

`build.gradle` 使用本地 `libs/` JAR，避免在源码仓库重新分发其他模组的二进制文件。构建前请自行取得并放入下列位置。

## 基础环境（Sable 2.x）

多面体装甲的可选终端弹道兼容编译接口需要本地
`libs/cbc_terminal_ballistics-0.3.5-neoforge.jar`（也可用 `-PterminalBallisticsJar=完整路径`）。
此 JAR 仅用于编译，不进入发行包；安装终端弹道学时才启用对应 mixin。
开发运行时用 `-PterminalBallisticsRuntime=true`，同时提供其必需前置
`libs/copycats-3.0.11+mc.1.21.1-neoforge.jar` 或 `-PcopycatsJar=完整路径`。

多面体装甲的放置粒子回归可用 `-PeffortlessJar=完整路径` 临时加载本地
Effortless 3.4.0（LGPLv3）作为测试运行依赖。省略此参数即可验证未安装环境；
该模组不是火控的必要前置，其代码与 JAR 均不进入发行包。

- `libs/create-1.21.1-6.0.10.jar`
- `libs/compat-latest/sable-neoforge-1.21.1-2.0.6.jar`
- `libs/compat-latest/create-aeronautics-bundled-1.21.1-1.3.2.jar`
- `libs/compat-latest/simulated-neoforge-1.21.1-1.3.2.jar`（从 Aeronautics bundled JAR 中提取，仅供编译）
- `libs/compat-latest/sable-rapier-1.21.1-2.0.6.jar`（从 Sable 主 JAR 的 `META-INF/jarjar/` 中提取，仅供编译）

面包军火库是模组发行运行时的必需依赖，版本必须大于等于 2.7.1，对应 NeoForge 版本范围 `[2.7.1,)`。由于本仓库不分发其 JAR，请在需要本地启动或编译其公开类型时传入符合此版本要求的 JAR：

```powershell
.\gradlew.bat runClient -PmianbaoJar='D:\mods\mianbaos-modernwarfare.jar'
```

其余可选集成依赖的准确文件名以 `build.gradle` 中的 `compileOnly` 与 `localRuntime` 项为准。

CBC 5.11.7 运行时默认关闭。需要启动 CBC/CBC Neo Warfare 集成测试时使用 `-PcbcRuntime=true`；这不会改变编译期的可选兼容代码。

## 全键盘无线控制器开发与验收

编译线缆兼容层需要本地 API JAR：`libs/compat-latest/drivebywire-0.3.0.jar`。该依赖使用 `compileOnly`，不会进入发行包，也不会将 Drive By Wire 变成必要前置。

- `-PwirelessControllerWireRuntime=true`：本地运行时加入 Drive By Wire 0.3.0；省略时验证无线控环境。
- `-PcontrollerModelJar=完整JAR路径`：本地运行时加入高级遥控器；实际验证使用 1.21.1-1.2.7。未安装时新控制器使用 Create 原版遥控器模型。
- `-PcontrollerIntegration=true`：`runServer` 加载服务端验收 fixture，运行后退出并生成 `controller-integration-result.txt`。
- `-PcontrollerClientChecks=true`：`runClient` 加载中文界面、实际键鼠分发及配置草稿验收 fixture，截图后退出并生成 `controller-client-result.txt`。
- `-PrunRoot=run/controller-wire`：隔离验收世界与配置，不使用已有游戏存档。

```powershell
.\gradlew.bat testWirelessControllerInputs testOpenControllerPayload assemble --offline
.\gradlew.bat runServer --offline -PcontrollerIntegration=true -PcbcRuntime=true -PwirelessControllerWireRuntime=true -PrunRoot=run/controller-wire -PmianbaoJar='D:\mods\mianbaos-modernwarfare.jar'
.\gradlew.bat runClient --offline -PcontrollerClientChecks=true -PcbcRuntime=true -PwirelessControllerWireRuntime=true -PrunRoot=run/controller-client -PmianbaoJar='D:\mods\mianbaos-modernwarfare.jar' -PcontrollerModelJar='D:\mods\create_tweaked_controllers-1.21.1-1.2.7.jar'
```

`controllerIntegration` sourceSet 与单元验收类均不会进入发行包。应同时检查结果文件为 `PASS`，不能只依赖 Gradle 的启动任务退出码。构建任务及验收参数以 `build.gradle` 为准。

发动机与蓝图接口验收另加 `-PcontrollerBlueprintChecks=true -PblueprintLibraryJar=本地LDLib2完整JAR路径`。该选项只加入本地运行时依赖，不把蓝图 API 或 LDLib 打入发行包。
