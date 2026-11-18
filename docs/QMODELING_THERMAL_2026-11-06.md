# qModeling 2.0 RP 光影热成像兼容

兼容补丁位于火控模组，输出文件：`deliverables/create-fire-control-1.0.3-qmodeling-thermal.jar`。关闭游戏，将 mods 中原来的 Create: Fire Control JAR 替换为该文件；继续使用现有 qModeling、Photon 和 Voxy。

## 原因与处理

qModeling 2.0.6rp 通过 Flywheel/Colorwheel 加速显示模型。它的普通方块实体渲染器在加速开启时会跳过模型主体。Photon 等光影使用独立的颜色附件和后续合成阶段，而火控热成像会绕过光影的这些阶段；加速模型因此可能无法进入最终热成像画面。

启用光影且开启热成像时：

- 局部改变 qModeling 的渲染选择，让它使用自己的普通模型渲染器，保留其部件变换、ModelData、动画和物品展示逻辑。
- 将模型立即绘制到 Iris 的热成像颜色和世界深度缓冲，避免进入原始主画面或光影的打包附件。独立顶点缓冲不提前提交其他实体的待绘制数据。
- 通过 qModeling 的可见性机制隐藏相应的 Flywheel 实例，补齐动画子实例的隐藏逻辑。关闭热成像时刷新暂停动画的可见性，然后恢复加速显示。
- 嵌套模型使用独立缓冲；绘制完成或异常时恢复帧缓冲和视口。退出热成像或资源重载时释放缓存的顶点构建器。

普通视图和无光影的热成像保留原有渲染路径。未安装 qModeling 时不应用这些兼容 mixin。未修改 Flywheel 全局后端，也不在切换热成像时重建加速模型。

由于光影热成像期间使用普通模型渲染器，大量复杂 qModeling 模型的 CPU 开销可能高于加速路径；本次没有进行帧率基准测试。

## 验证

对应用户安装的 Minecraft 1.21.1、NeoForge 21.1.230、qModeling 2.0.6rp、Flywheel 1.0.6、Colorwheel 1.3.2 和 Iris 1.8.14-beta.1。

- `testQModelingThermal`：只读检查实际 qModeling JAR 的渲染入口、普通模型与物品展示选择、实例可见性、动画字段和子实例方法；检查有/无光影、热成像关闭、瞄准会话结束和未安装 Iris 的条件。
- `testPhotonThermalGl`：保留 Photon/Voxy GPU 回归检查，并实际绘制、读取像素，验证模型写入热成像颜色缓冲、世界深度遮挡、嵌套绘制、异常恢复和视口恢复。
- `testThermalPipeline`、`tools/verify_thermal_pipeline.ps1`、`verifyShadedJar`：现有热成像与打包检查。

这些检查没有启动用户的整合包或进入存档，不能代替完整游戏画面的实测。

```powershell
.\gradlew.bat --offline testThermalPipeline testQModelingThermal testPhotonThermalGl shadowJar verifyShadedJar '-PqModelingJar=<qmodeling-2.0.6rp.jar 的绝对路径>'
& .\tools\verify_thermal_pipeline.ps1
```

兼容路径首次绘制时日志包含：`Enabled qModeling shader-pack thermal geometry route`。
