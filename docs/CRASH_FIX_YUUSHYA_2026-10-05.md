# 2026-10-05 展示方块渲染崩溃修复

## 原因

报告来源：`F:/我的世界文件/报错/错误报告-2026-10-5_15.02.48.zip`。

15:02:41 客户端在区块模型生成过程中抛出 `java.util.ConcurrentModificationException`，
首个模组栈帧是 Yuushya Modelling 2.4.1 的 `ShowBlockModel.getQuads` 第 54 行。
正在处理的方块是 `yuushya:showblock`，报告坐标为 `(20481033, 985, 20528135)`。

核查实际安装 JAR 的字节码可见：`NeoShowBlockModel` 将方块实体的
`getTransformData()` 原始列表传给公共模型，公共模型随后用 `ArrayList` 迭代器直接遍历。
方块实体重载组件时，`ITransformDataInventory.load` 会清空并重新填充同一个列表；
与异步区块模型生成重叠即可触发报告中的异常。回归测试使用这个真实重载方法，
确定性复现了未修复的迭代器异常。日志没有提供发生冲突的写入线程栈，因此无法确定
当时是哪一次组件更新触发了冲突。

开发依赖和游戏安装的方块建模 JAR 的 SHA256 一致：
`EEDE4BD24A1B20AEB007C03761903AED8061B9B826DC68349A8CA146C9944AE5`。

## 修复

- 在公共 `ShowBlockModel` 的组件迭代入口改用独立快照，覆盖普通展示方块及其派生模型。
- 从数组复制组件，不遍历正在更新的源列表；跳过并发清空可能留下的空项。
- 复制位置、旋转、缩放、方块状态和显示状态，防止后续编辑改变正在生成的模型。
- 保留原有几何生成和火控炮管后坐渲染逻辑。
- 此修复只要求安装方块建模，无需 CBC Neo Warfare；未安装方块建模时跳过该 Mixin。

## 验证

执行：

```powershell
.\gradlew.bat testYuushyaShowBlockSnapshot testYuushyaItemRenderPlan assemble --console=plain
```

结果：`BUILD SUCCESSFUL`。

验证包括原异常复现、组件重载后快照稳定、变换向量独立、空项处理，以及
20,000 次快照与 16,207 次真实组件重载并发执行。现有物品模型渲染回归和发行包
依赖重定位检查通过。还核对了安装版本的组件迭代调用、NeoForge 模型调用路径和
可选模组启用条件。

随后检查实际游戏启动日志，15:21:39.735 记录了
`Mixing client.YuushyaShowBlockQuadSnapshotMixin ... into ... ShowBlockModel`，
确认新修复已在实际模组环境成功应用。核查时当前启动日志没有
`ConcurrentModificationException`。原存档的崩溃场景尚未实机复测，
启动成功不能替代该场景复测。

## 交付与安装

游戏原火控 JAR 与当前完整源码构建还存在稳定器等其他差异，因此另提供以
崩溃时已安装 JAR 为基础的最小补丁包，只新增两个修复类、更新 Mixin 插件主类
和 Mixin 配置，合计四个归档条目。插件修复前的字节码与原安装版一致；
其余归档内容逐项校验保持原样。

最小修复包：
`F:/codex/stabilized_turret/backups/yuushya-crash-2026-10-05-150241/create-fire-control-0.8.1-yuushya-render-fix.jar`。

当前游戏火控 JAR：`F:/PCLII/.minecraft/versions/Create航空学/mods/create-fire-control-0.8.1.jar`。
安装前复核时，这个文件已被更新为工作区的完整构建并包含本次修复。
为保留现有其他修改，本次没有再以最小补丁包覆盖它；实际启动日志确认了修复应用。

原包备份：
`F:/codex/stabilized_turret/backups/yuushya-crash-2026-10-05-150241/create-fire-control-0.8.1-before-yuushya-render-fix.jar`。

最小修复包 SHA256：`EA15458F2FA2584B8E315DF3FE8F4882C8045B28547C0FBB7732DC7D5A300F5A`。

当前游戏 JAR SHA256：`A9F7605F9B66F791725EA572E3D5640C75CA642BFE3F6B479647F212300148DB`。

原包 SHA256：`23FE62992978BC74EB9B871176435C932B3C2189EBF55A1A9DD727B777B33292`。

本次核查未修改存档或其他模组。当前启动已加载修复，可加载原来的场景进行复测。
如需使用最小补丁包或回退原包，须在游戏关闭后覆盖上述火控 JAR。
