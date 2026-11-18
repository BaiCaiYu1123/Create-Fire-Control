# 普通防空导弹四倍机动

火控计算机接管的普通防空导弹继续采用驾束制导，机动参数调整为原值的四倍：

| 参数 | 调整前 | 调整后 |
| --- | --- | --- |
| 最大转向角 | 4°/tick | 16°/tick |
| 偏离瞄准线时的回正系数 | 0.08 | 0.32 |
| 横向修正速度上限与前进速度之比 | 0.22 | 0.88 |

仅普通防空弹选择四倍参数，共用算法的反坦克导弹仍使用原参数。转向改变速度方向，动力曲线仍为 10 秒加速到 1 马赫；20 秒静默消失、计算机近炸范围、区块自加载和远端渲染继续保留。

“四倍”指三项机动参数，而非任意场景下实际转角或命中率恒为四倍。小角度时会直接对准瞄准线，不强制继续转向。固定位置的 90° 瞄准线切换测试，捕获方向所需 tick 从 23 降至 6。

验证命令：

```powershell
.\gradlew.bat --offline -I tools/ordinary-aa-checks.init.gradle testOrdinaryAirDefense testSafetyRegressions assemble runServer -PcbcRuntime=true -PrunRoot=run_ordinary_aa_agility4_verify '-Pmod_version=0.9.3-ordinary-aa-agility4' '-PmianbaoJar=D:/Game/PCLII/.minecraft/versions/Create航空学/mods/mianbaos_modernwarfare-2.6.0b6-neoforge-explosion-opt.jar'
```

测试服配置和轨迹位于 `run_ordinary_aa_agility4_verify/server/`，编译输出位于 `build-ordinary-aa-verify/`。

验证已通过：`testOrdinaryAirDefense`、`testSafetyRegressions`、`verifyShadedJar`，以及七种真实发射器完整 400 tick 飞行与近炸引信集成测试。2793 条存活轨迹样本中的最大转角为 16°/tick，最大速度仍为 17.015 格/tick。每枚实测导弹在瞄准线切换后均验证达到 16° 的转向；0/2/5/10 格近炸、0 格碰炸、出架保护、存储恢复、失去计算机、静默超时和加载状态清理均通过。反坦克导弹的旧驾束算法对照也通过。

交付包：`build/libs/create-fire-control-0.9.3-ordinary-aa-agility4.jar`，23,918,441 字节，附 `.jar.sha256`。35 个相关类（含内部类）与验收编译输出散列一致，未包含集成测试类。

SHA-256：`eadd5f19f65fa1123bac99f706d4b02cf76ddca44c045711457d870f73f4101d`。
