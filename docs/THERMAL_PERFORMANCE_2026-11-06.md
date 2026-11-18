# 光影热成像性能修复

安装文件：`deliverables/create-fire-control-1.0.3-thermal-performance.jar`。关闭游戏后，用此文件替换 mods 中现有的 Create: Fire Control JAR，只保留一个火控模组文件。包含此前的 Photon/Voxy 和 qModeling 热成像兼容，以及当前工作区已有的瞄具改动。

## 已定位的问题

用户最新游戏日志在 17:50:05.731 开始出现 `GL_INVALID_OPERATION: Array object is not active`，与 qModeling 热成像兼容路径首次绘制相邻。在其后约 23 秒内共有 3414 条同类错误。

Minecraft 的 `BufferUploader` 缓存上一次立即绘制使用的顶点缓冲。Flywheel、Colorwheel 和 Voxy 通过原始 OpenGL 调用切换或解除 VAO 绑定时，不会同步这个缓存；兼容绘制若直接复用缓存，就可能在 VAO 未绑定时上传或绘制。前一版只保存了帧缓冲和视口，没有处理这项衔接。

此外，旧的 qModeling 兼容顶点源使用单个共享构建器，切换模型部件的渲染层就会立即上传和绘制。实体模型交替使用 solid、cutout 等材质时，绘制数量会随部件数增长。

## 修改

- 进入和退出模型兼容绘制时使原版立即绘制缓存失效，并保存、恢复调用方的 VAO 和顶点缓冲绑定。
- qModeling 兼容绘制使用按 RenderType 分开的构建器，合并同层部件；在绘制各层之前同步上传器缓存，透明排序层置于普通层之后。
- 复用构建器，清理未使用层的内存，保持嵌套模型的独立缓冲与异常释放。

仍只在启用光影的热成像中使用这条 qModeling 路径。没有降低热成像分辨率、裁掉模型或关闭 Voxy 地形。

## 验证和边界

`testThermalModelBatchGl` 使用真正的 Minecraft BufferUploader 和不可见 OpenGL 上下文：先制造缓存仍有效但原始 VAO 已解除的场景，确认旧路径触发 GL_INVALID_OPERATION，再验证修复后的上传无错误、颜色正确、外层 VAO 恢复。1024 个交替使用两种渲染层的模型部件合并为 2 次上传和绘制；复用缓冲不会再次绘制旧模型。

`testQModelingThermal`、`testPhotonThermalGl`、`testThermalPipeline`、热成像资源检查和 `verifyShadedJar` 均通过。

上述绘制次数是测试场景结果，不代表真实存档的帧率倍数。尚未重新进入用户存档测量平均帧率、GPU 时间或卡顿；普通模型几何仍会在光影热成像期间由 qModeling 的备用渲染器生成。

```powershell
.\gradlew.bat --offline testThermalModelBatchGl testQModelingThermal testPhotonThermalGl testThermalPipeline shadowJar verifyShadedJar '-PqModelingJar=<qModeling JAR 的绝对路径>'
& .\tools\verify_thermal_pipeline.ps1
```
