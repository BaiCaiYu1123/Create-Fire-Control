# 连接显示器导弹发射音效对齐

显示器直接创建巡航导弹的路径曾对所有型号统一播放 `missilelaunch`，音量为 3.5、音调为 0.9。这会替换普通巡航导弹的原生脱架声，并改变陆基型号和发射筒的出筒声音。数学飞行不执行原生尾迹过程，因此还遗漏了过程中的延迟点火声。

修正后继续使用原版音效资源，按发射器和弹种保留原来的声音序列：

| 阶段/设备 | 原版音效事件 | 音量 | 音调 | 时间 |
| --- | --- | --- | --- | --- |
| 普通、低空、核巡航导弹脱架 | `minecraft:block.metal_pressure_plate.click_off`，BLOCKS 分类 | 5 | 0.6 | 发射时 |
| 陆基导弹独立发射方块 | `mianbaos_modernwarfare:missilelaunch`，NEUTRAL 分类 | 8 | 随机 0.8–1.0 | 发射时 |
| 发射筒/陆基发射器 | `mianbaos_modernwarfare:missilelaunch`，NEUTRAL 分类 | 6 | 0.8 | 发射时 |
| 普通、低空、核巡航导弹点火 | `mianbaos_modernwarfare:missile_launcher`，NEUTRAL 分类 | 2 | 1.0 | 引擎计时第 15 tick |
| 陆基型号点火 | `mianbaos_modernwarfare:missile_launcher`，NEUTRAL 分类 | 8 | 0.8 | 引擎计时第 10 tick |

实体飞行继续由原生尾迹过程播放点火声；数学飞行只补回声音事件，不恢复原生飞行逻辑或尾迹命令。一次点火记录避免引擎计时校正后重复播放。移除了把部分陆基型号原生点火音量从 8 强行改成 2 的 Mixin。

音效使用发射器或导弹在主世界中的实际位置。现有按键、发射流程、导引、弹速和弹药扣除方式保持不变；前一轮本体按键回退功能也保留在此次构建中。

## 验证方法

`src/missileSoundIntegration` 在实际 NeoForge 服务端运行，记录 `PlayLevelSoundEvent.AtPosition`，将面包原生过程和连接显示器接管路径的事件、分类、音量、音调及点火时序进行比较。覆盖八类巡航/陆基导弹、独立方块和发射筒、数学及实体飞行，并检查计时回退不会重复点火、发射筒不会重复扣弹。测试代码不进入发行 JAR。

```powershell
.\gradlew.bat --offline --no-configuration-cache -I tools/missile-launch-sound-checks.init.gradle runServer -PcbcRuntime=true -PrunRoot=run_missile_launch_sound_verify -PmianbaoJar=面包军火库JAR完整路径
```

结果写入测试目录的 `server/missile-launch-sound-result.txt` 和 `server/missile-launch-sound-details.txt`。
