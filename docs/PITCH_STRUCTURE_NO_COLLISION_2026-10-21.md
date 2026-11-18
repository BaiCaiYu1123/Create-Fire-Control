# 高低机连接结构无碰撞

按用户指定方案，关闭反作用力模式下高低机所连接结构的物理碰撞。炮管可以穿过世界地面和平台，不再通过撑地把车体顶起。

`PitchStructureCollision` 移除高低机子结构的物理区块碰撞体，覆盖同一固定焊接组件的炮管及其已装配的 Create/CBC contraption。真实方块、质量、惯量、铰链和伺服参数继续保留；车体、上级方向机和无关结构保留碰撞。固定焊接旁路连回车体时保护整个车体组件。

`PitchStructureCollisionMixin` 阻止新加载区块、方块编辑和 contraption 重建重新添加碰撞。解绑、绑定失效、改为方向机或切回无反作用力模式后重新上传原碰撞形状；多个高低机共享同一焊接组件时按所有有效绑定计算。状态只存在运行期，不添加永久无碰撞 NBT，不覆盖玩家已有的工具枪无碰撞选择。服务器关闭时清理。

验证通过：

- `testPitchCollision`：高低机固定焊接组件选择、独立绑定共存、车体保护和解绑状态。
- `testNativePitchCollision`：Sable 2.0.3 和 2.0.6 原 DLL，40/120 Hz，真实世界体素地板。指定结构移除碰撞后落至地板以下，无关结构继续停在地板上；恢复后同一结构重新落在地板上，原刚体和关节保持有效。
- `testStabilizerDisabled` 和 `testNativeArticulatedPrediction`：现有手动控制及 232 组原生预测回归。
- 独立服务端 `PitchCollisionIntegration`：真实高低机、方向机和原生固定焊接炮管。高低机与焊接炮管均无碰撞，方向机及质量保留，覆盖方块编辑、反作用力/无反作用力切换、解绑、重绑和轴承角色修改。结果为 `PASS`。

服务端验证命令：

```powershell
.\gradlew.bat -I tools/pitch-collision-checks.init.gradle runServer --offline -PrunRoot=build/pitch-collision-integration -PcbcRuntime=true -PmianbaoJar=本地面包军火库JAR路径
```

发行包：`build/libs/create-fire-control-0.9.1-pitch-no-collision-fix.jar`。客户端和服务端替换各自原火控 JAR，每端只保留一份。
