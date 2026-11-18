# Photon 热成像 / Voxy 专用兼容

安装文件：`deliverables/create-fire-control-0.9.3-photon-thermal.jar`。关闭游戏，将 mods 中现有的 Create: Fire Control JAR 替换为该文件，再启动游戏。光影继续使用原来的 `Photon-Shaders_System.Object[]_zh_CN.zip`。

## 触发条件

Iris 实际启用的光影名称为 Photon 系列，且火控热成像开启。兼容原包名、版本后缀和汉化后缀。关闭热成像或切换其他光影后，专用绘制路径不再执行。无需改配置或在切换热成像时重建渲染管线。

## 修复内容

- 修复 Voxy 0.3.8 不透明绘制挂接位置：实际绘制在 `renderTerrain`，外层 `renderOpaque` 内没有旧代码查找的调用；`renderTemporal` 的补绘也经过该位置。
- Photon 原本把远景颜色编码到 colortex1，并在后续阶段解码。热成像会跳过这些阶段，因此在实际 draw 调用处接管输出，将已有的直接颜色分支送入热成像的 colortex0。
- 使用单独的 framebuffer，共享 Voxy 的深度和模板纹理，保留近景遮挡和远景剔除。Photon 原有的颜色附件、MRT 路由保持完整。
- 透明远景使用与直接颜色相符的 alpha 混合；绘制结束或异常时恢复 framebuffer 和混合状态。
- 热成像采用原生分辨率，避免 Photon TAAU 缩放使远景与近景缓冲尺寸不符；在几何和遮挡剔除程序中同时关闭 TAA 抖动。普通视图保留 Photon 的原始抖动表达式和缩放配置。
- 只复用颜色、深度和模板纹理，额外拥有一个 framebuffer；管线释放时清理该对象。

成功进入路径时日志包含：`Enabled Photon/Voxy thermal direct-color route`。如果着色程序或缓冲不兼容，会记录 `Photon thermal Voxy route unavailable`，并保留原绘制目标。

## 验证

对照版本：Minecraft 1.21.1、NeoForge 21.1.230、Iris 1.8.14-beta.1、neo-voxy 0.3.8，以及上述 Photon 汉化包。

- `testThermalPipeline`：现有热成像检查，新增 Photon 名称隔离及 TAA 双分支检查。
- `testPhotonThermalGl`：隐藏 OpenGL 4.6 上下文中实际编译、链接、绘制并读回像素；检查不透明、补绘、透明混合、深度和模板遮挡、Photon MRT 隔离、状态恢复、窗口尺寸变化和 TAA 切换。
- `testPhotonVoxyHooks`：只读检查用户已安装 Voxy/Iris 的真实字节码，核对绘制、补绘、剔除、缩放、TAA 和光影名称接口。
- `tools/verify_thermal_pipeline.ps1` 和 `verifyShadedJar`：热成像资源及模组打包完整性。

完整存档中的画面仍需游戏内实测；独立 GPU 检查不覆盖整合包全部模组的交互，也未进行帧率基准测试。

复验命令（`<...>` 替换为实际 JAR 路径）：

```powershell
.\gradlew.bat --offline testThermalPipeline testPhotonThermalGl testPhotonVoxyHooks shadowJar verifyShadedJar '-PphotonVoxyJar=<Voxy JAR>' '-PphotonIrisJar=<Iris JAR>'
& .\tools\verify_thermal_pipeline.ps1
```
