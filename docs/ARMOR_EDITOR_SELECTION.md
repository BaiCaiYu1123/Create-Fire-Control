# 装甲网格编辑器：选择模式和遮挡显示

2026-11-17，同时适用于未受损方块的 `PolyScreen` 世界/预览编辑器和受损装甲的 `ArmorMeshScreen` 网格编辑器。

- 顶部常驻“顶点 / 线条 / 面”，快捷键 `1 / 2 / 3`。单击和 Ctrl 多选只操作当前模式的元素。
- 空白拖动或 Ctrl 拖动按当前模式替换框选；空框清空旧选区。框选始终包含被遮挡的元素，不受透视开关影响。线按投影线段与框的交集选择，面按投影表面区域选择。
- 面沿未分割的共面三角形成组选择，不跨过用户连线；Shift 单击只选一片三角面。选中线和面仍可拖轴或输入坐标移动其顶点。
- Del 按当前模式删除元素；删面保留顶点，删玩家连线保留端点。撤销/重做保存模式及对应选区。
- “透视”开关立即保存到实例 `config/create_fire_control-visuals-client.toml` 的 `armorEditor.xray`。默认开启；重开界面和重启游戏后都保留选择。
- 关闭透视时，点、线及选面高亮按实际世界深度裁剪，单击也排除被挡住的元素。开启时，遮挡的点和线以半透明显示；面高亮仍遵守场景遮挡。
- 世界深度在 GUI 清除深度前复制到 GPU，着色器逐像素判断线和面；顶点按中心深度统一显隐，避免圆点被表面切碎。场景深度包含周围方块，投影复用既有 Sable 变换。读回深度仅用于鼠标附近的候选元素。
- 顶点半径、边框和线条宽度统一扩大到原来的 150%；三轴手柄尺寸保持原交互。
- 侧栏宽度 224 → 176 GUI 单位；按钮 18 高、21 行距。未选中时收起坐标栏，减少侧栏高度和留白。

验证入口：

```powershell
.\gradlew.bat testCopypoly --offline -PcopypolyJUnitDir=libs/copypoly-tests -PmianbaoJar="你的面包模组.jar"
.\gradlew.bat runClient --offline --init-script tools/armor-selection-checks.init.gradle -PcopypolyClientSmoke=true -PcbcRuntime=true -PrunRoot=run/armor-editor-selection-final -PmianbaoJar="你的面包模组.jar"
```

新增 8 项 JUnit 测试覆盖拾取、共面分组、三种框选、遮挡过滤和真实 TOML 保存/卸载/重载。专用客户端检查真实场景遮挡、透视开关、分类选择、分类框选、空框替换、删面撤销、重开配置以及紧凑布局，并保存截图。开发夹具与测试类不进入发行 jar。

新增 GPU 描线适配器和着色器位于 `third_party/copypoly/fire_control_adapter`，与该适配目录一样继续采用 MIT；火控网格编辑界面、选择逻辑及配置保持宿主原有许可。

普通方块入口补齐：直接通过方块的实际打开菜单路径验证三种模式、分类框选、线与面删除/撤销、右键加点、世界/预览深度、默认未贴材质框架自身遮挡、周围方块遮挡、共享透视设置和五秒红色提示。截图位于 run/armor-classic-frame-final/client/screenshots；主日志包含 CLASSIC_ARMOR_SELECTION_PASS。

