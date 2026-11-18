# 全键盘控制器讲台朝向修复

讲台方块模型的 Y 轴旋转与渲染矩阵使用相反的正方向。此前遥控器直接使用 `facing.toYRot() - 180`，北、南方向正常，但东、西方向会将遥控器的前后偏移和倾斜转向讲台的反面。

讲台遥控器改用 `180 - facing.toYRot()`，与讲台方块的旋转一致。位置偏移、倾斜和展开／收合动画共用修正后的局部坐标。尺寸、手持位置、物品显示以及模型资源保持原样，沿用当前工程版本 0.9.3。

验收按实际 blockstate 中的四个旋转值检查模型基点、前后偏移和台面倾角，并在隔离客户端分别捕获北、东、南、西四个朝向的展开和收合画面。原有手持、按键下压、配色、资源重载、讲台动画及接口无动画／音效检查一起执行。

安装产物为 `build/lectern-facing/create-fire-control-0.9.3.jar`。它以当前整合包的 JAR 为基础，只更新 `WirelessLecternControllerRenderer.class`，保留已安装的导弹制导调整。常规源码构建另保存在 `build/libs/`；两者差异和校验值见 `build/controller-lectern-facing-audit.json`。
