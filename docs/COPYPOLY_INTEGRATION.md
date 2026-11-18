# CopyPoly 1.9.1 内置整合

火控发行包内置 CopyPoly 的全部运行代码与资源：材质伪装、世界内编辑器、
普通/自定义顶点、连线与拓扑、面增删、撤销重做、复制粘贴、变换、形状 API、
服务端校验与同步、存档/掉落/放置，以及 Sable 地形和子结构碰撞及渲染。

本次整合要求 Sable 2.0.6；完整保留的上游原生碰撞代码以此版本为基线。
本次实际服务器环境为 NeoForge 21.1.230、Create 6.0.10、Sable 2.0.6、
Aeronautics/Simulated 1.3.2，未将未测试的旧 Sable 版本继续标为支持。

物品 `create_fire_control:camouflaged_polyhedral_armor`（伪装多面体装甲）加入火控创造物品栏，也保留原版建筑方块
栏及新的工作台和动力合成器配方。潜行右键打开编辑器。详细操作见
[上游 README](../third_party/copypoly/README.md)。

普通单块编辑器最多 256 个顶点。顶点较多的凸形使用增量凸包计算，仍用原有
整数坐标判定。旧形状数据可直接读取，三角面和连线各有独立上限。

## 代码及许可边界

| 内容 | 路径/标识 | 许可 |
| --- | --- | --- |
| 火控原创代码 | `src/main/java/com/hooya/stabilizedturret` 中的原创部分 | 保留所有权利；第三方来源代码除外 |
| CopyPoly 代码、资源、测试、文档 | `third_party/copypoly/` | MIT，caca |
| CopyPoly 编译与打包接入 | `gradle/copypoly.gradle` | 火控自身许可 |
| 已有 Drive-By-Wire 来源代码、模型等资源 | `META-INF/notices/drivebywire.txt` 与 `drivebywire-model.txt` | 原 MIT |

没有把 CopyPoly 重命名到火控包。原始源码清单、哈希和导入改动说明保留在
独立目录。发行包同时提供 `create_fire_control` 和 `copypoly` 两个模组 ID，
通过注册表别名读取旧 `copypoly:copycat_polyhedron` 世界、方块实体和物品数据；
玩家只需安装火控 JAR，不能重复安装独立 CopyPoly。

NeoForge 的顶层 `license` 是整个模组文件的描述，因此写明混合许可、火控原创
部分保留所有权利以及 CopyPoly/Drive-By-Wire 的 MIT 例外；独立条目、版权声明及许可证
全文也明确标识 MIT。该例外不将火控其他代码授权为 MIT。

## 构建与验证

在仓库根目录执行：

```powershell
.\gradlew.bat testCopypoly compileCopypolyGameTestJava assemble
```

`testCopypoly` 运行上游 JUnit 测试，独立于火控现有测试；`check` 会包含它。
`assemble` 验证生产类、完整资源、双模组元数据、MIT 声明、合并标签与测试
排除情况，并生成火控发行 JAR 和仅含 CopyPoly 的 MIT 源码包。

离线环境可用 `-PcopypolyJUnitDir=本地JUnit目录 --offline` 指定已有 JUnit 5
及其 platform、OpenTest4J、API Guardian JAR；它们仅用于测试，不进入发行包。

可选的真实服务器 GameTest 使用独立开发目录，需备齐火控运行时依赖：

```powershell
.\gradlew.bat runCopypolyGameTestServer --offline -PcopypolyGameTests=true -PcbcRuntime=true -PmianbaoJar='D:\mods\mianbaos-modernwarfare.jar' -PrunRoot=run/copypoly-integration
```

该配置加载完整上游 GameTest（含 Sable 原生碰撞、组装、保存与网络测试），
测试夹具不会进入发行 JAR。当前火控注册路径需要 CBC 的运行时类型，因此
此验收命令启用 CBC。任务会检查实际非零测试通过报告，避免启动失败却被
启动器的零退出码误判为通过。客户端交互探针也保留在组件的 `src/gametest/`，
可通过 `-PcopypolyClientSmoke=true` 加载并使用上游 `copypoly.*Smoke` 属性启用。

当前 1.9.1 编辑流程使用 `lineSmoke` 探针（PowerShell 中带点的参数需整体加引号）：

```powershell
.\gradlew.bat runClient --offline -PcopypolyClientSmoke=true '-Pcopypoly.lineSmoke=true' -PcbcRuntime=true -PmianbaoJar='D:\mods\mianbaos-modernwarfare.jar' -PrunRoot=run/copypoly-client-line
```

上游较早的 `editorSmoke` 探针仍假定未选中顶点时存在坐标输入框，与 1.9.1
只显示所选点坐标的界面不符，会在测试夹具中抛出 `NoSuchElementException`。
该历史夹具原样保留；当前交互验收使用随同提供的 `lineSmoke`。

## 本次验证记录（2026-11-17）

- 上游 JUnit：15 个测试类、85 项测试，全部通过。本地离线使用 JUnit 5.14.4。
- 真实服务器：26 项必需 GameTest 全部通过，包括 Sable 原生碰撞及组装。
- 实际客户端：1.9.1 `lineSmoke` 通过，覆盖点击/拖线、任意顶点类型连线、
  世界/预览中的选线删除撤销、四点凹槽、多选、删面补面、5 秒缺口提示、
  封闭 O 形、复制粘贴、保存重开。已查看凹槽截图，模型、材质和编辑控件正常。
  日志位于 `run/copypoly-client-line/client/logs/latest.log`，10 张截图位于同级
  `screenshots/`；服务器通过报告位于
  `run/copypoly-integration/copypolyGameTestServer/logs/latest.log`。
- 导入完整性：92 个上游文件的本地 SHA-256 与导入清单一致。
- 发行内容检查覆盖全部生产类、资源、共享标签、双模组入口、独立许可证及测试排除。
- 未测试完整玩家整合包、光影及其他渲染优化模组组合。
