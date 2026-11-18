# CopyPoly 1.9.1 — 独立 MIT 组件

本目录是 caca 的 CopyPoly 1.9.1 完整源码快照，来源为用户提供的
CopyPoly 1.9.1 源码快照。本目录的代码、资源、测试及文档使用本目录的
[MIT LICENSE](LICENSE)，Copyright (c) 2026 caca。原 Gradle 启动脚本等构建工具
保留其自身 Apache-2.0 声明。火控原创部分不使用 MIT；其他第三方内容为 MIT（虎式 I 音效除外）。

保留原 `com.caca.copypoly` 包及 `copypoly` 菜单/网络标识，没有将上游实现
迁入火控包。39 个生产 Java 文件中，37 个只添加 MIT/版权注释；
`Copypoly.java` 与 `PolyClient.java` 适配新注册 ID、旧 ID 别名及模型标识。
方块、物品和方块实体 ID 为 `create_fire_control:camouflaged_polyhedral_armor`，
旧 `copypoly:copycat_polyhedron` 存档通过注册表别名继续读取。
测试、GameTest、原始构建文件及文档一并保留；
`UPSTREAM_FILES.json` 记录导入文件的上游和本地 SHA-256，便于追溯。

火控通过仓库根目录的 `gradle/copypoly.gradle` 编译和打包此目录，而非执行
此处上游 `build.gradle`。所有运行功能随火控 JAR 发放，无需另装 CopyPoly。
两套 `@Mod`、客户端订阅者及 mixin 配置均保留；模组列表中的第二个条目用于
保留原注册与存档兼容。不要同时放入独立 CopyPoly JAR。

显示名称已改为“伪装多面体装甲”。`fire_control_adapter/` 内的渲染器
由原 MIT 材质投影代码改编，相机/投影门面复用上游编辑控件，均继续使用 MIT；
新增装甲、弹孔、可编辑穿孔网格、独立编辑界面/菜单/数据包与弹道功能代码
位于火控自己的源码及主模组 mixin 中，使用火控原创许可。
新 ID 所需的方块状态、物品模型和掉落表 JSON 在 adapter/resources 中保留 MIT。
共享挖掘标签由火控资源合并；火控创造物品栏直接引用 CopyPoly 注册物品。
MIT 全文及来源声明会进入发行 JAR。单元测试与 GameTest 不进入发行包。
`copypoly-1.9.1-sources.jar` 只包含本目录组件，方便单独审阅其 MIT 源码。

测试命令、集成入口及功能说明见 [整合文档](../../docs/COPYPOLY_INTEGRATION.md)。
上游操作与 API 说明保留在 [README.md](README.md) 和 `docs/`。
