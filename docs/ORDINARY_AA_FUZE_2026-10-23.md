# 普通防空导弹近炸引信

普通防空导弹现在读取火控计算机防空导弹设置中的“近炸范围（0～10 格；0=碰炸）”，与现有高速防空弹采用相同的引信规则。

- 发射时记录计算机的范围设置，并保存到实体 `CreateFireControlOrdinaryAaFuse`；已发射弹不会因之后调整计算机而改变引信，存档重载和重新接管时也保留该值。
- 近炸检测使用导弹上一位置到当前位置的运动线段与 Sable 载具物理质心的最短距离，防止高速飞越时跳过目标。范围内含边界。
- 设置为 0 时关闭近炸通道，真实方块接触仍可触发碰炸。两个通道均遵守现有防空弹的 10 tick 出架保护。
- 命中或近炸沿用既有防空弹公共爆炸入口，并释放临时加载票、发送远端视觉移除消息。计算机被拆除后，导弹仍使用自身保存的引信值。
- 10 秒加速至 1 马赫、反坦克导弹同款驾束制导、区块自加载、远端渲染及 20 秒静默消失继续保留。20 秒超时先于引信判断，不产生超时爆炸。

验收已通过：七种真实发射器完整 400 tick 飞行；0、2、5、10 格设置；5 格边界和 5.25 格外侧；两端均在范围外而中途掠过目标的运动线段；0 格碰炸；出架保护；设置存储与重新接管；发射后修改计算机不改变该弹；失去计算机后的引信；20 秒静默超时；加载状态清理与远程爆炸通道。测试目标通过真实 Simulated 拼装建立 Sable 载具，使用其实际质量数据、碰撞形状与世界边界。

`testOrdinaryAirDefense`、`testSafetyRegressions` 与 `verifyShadedJar` 已通过。渲染和同步的五个关键类与前一已通过客户端验收的交付包逐字节一致。环境沿用 Minecraft 1.21.1、NeoForge 21.1.230、面包 2.6.0b6、Sable 2.0.6。

服务端结果：`run_ordinary_aa_fuse_verify/server/ordinary-aa-result.txt`；轨迹：同目录 `ordinary-aa-trajectories.csv`；成品核对的 35 个相关类散列：同目录 `tested-classes.json`。普通弹集成测试类未打入成品。

复现命令（测试目录需有接受 EULA 的本地测试服配置）：

```powershell
.\gradlew.bat --offline -I tools/ordinary-aa-checks.init.gradle testOrdinaryAirDefense testSafetyRegressions assemble runServer -PcbcRuntime=true -PrunRoot=run_ordinary_aa_fuse_verify '-Pmod_version=0.9.1-ordinary-aa-beam-fuse' '-PmianbaoJar=D:/Game/PCLII/.minecraft/versions/Create航空学/mods/mianbaos_modernwarfare-2.6.0b6-neoforge-explosion-opt.jar'
```

交付包：`build/libs/create-fire-control-0.9.1-ordinary-aa-beam-fuse.jar`（23,894,311 字节），附 `.jar.sha256`。

SHA-256：`e5e02489971fc3d8a3d3c168811d7ca43a879c5d1e4aefdc6177df55f787eee6`。
