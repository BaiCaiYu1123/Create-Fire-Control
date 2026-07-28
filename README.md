# Create: Fire Control

中文名：**机械动力：综合火控**

面向 Minecraft 1.21.1 / NeoForge 21.1.230 的装甲车辆与航空器火控模组。项目基于 Sable 与 Create Aeronautics 的物理子层系统，为物理轴承构成的方向机、高低机、光电吊舱、雷达、火炮与导弹设备提供统一的火控计算机。

## 功能范围

- 双向稳定、头瞄、刚性俯仰限制、轴承偏移与应力驱动控制
- 火炮瞄具、可缩放视角、热成像、第三人称瞄准点与光电锁定
- 雷达扫描、雷达锁定、地对空导弹与空对空导弹控制
- 面包军火库导弹发射器、挂架、激光制导器与机载雷达联动
- 电光吊舱 API 联动、吊舱视角和模型姿态控制
- Create / Drive By Wire 遥控器、操作座椅与 Sable 蓝图保存兼容
- CBC、CBC Neo Warfare 及多个可选附属的火炮、炮座和发射器兼容

## 版本与依赖

核心支持两套环境：

- Sable 1.2.2 + Create Aeronautics / Simulated 1.2.1
- Sable 2.0.3 + Create Aeronautics / Simulated 1.3.0

必需依赖为 Create、Sable 和 Create Aeronautics。CBC、CBC Neo Warfare、Create: Interiors、Drive By Wire、Sable Schematic API、面包军火库及电光吊舱相关模组均为对应功能的可选集成。

## 构建

本仓库不重新分发第三方模组 JAR。请按 [依赖说明](docs/DEPENDENCIES.md) 将开发用 JAR 放入本地 `libs/` 目录，然后执行：

```powershell
.\gradlew.bat build
```

为 Sable 2.0.3 / 航空学 1.3.0 构建：

```powershell
.\gradlew.bat build -PcompatLatest=true
```

产物位于 `build/libs/create-fire-control-0.1.0.jar`。

## 许可证

本项目源码采用 [MIT License](LICENSE)。第三方模组、其 API、名称、模型及资源仍由其各自权利人所有。
