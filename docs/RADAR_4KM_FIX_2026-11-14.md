# 约 4 km 无遮挡目标漏检修正

安装包：`deliverables/create-fire-control-1.0.4-radar-4km-fix.jar`。

以当前安装的 `create-fire-control-1.0.4-config-menu.jar` 为底包，替换编译验证后的 `RadarVisibility` 及其内部类。包内其他类、界面、资源、配置注册和导弹功能与该底包逐项保持字节一致。退出游戏后用新 JAR 替换现有火控 JAR，客户端和服务端一起更新，只保留一份；现有配置文件可继续使用。

## 原因与修正

雷达视线穿过未加载的地形时，会分批异步读取保存数据。旧代码按数据首次请求的时间使缓存过期。对于约 4 km 的路径，前半段数据可能在后半段读取完成前过期。飞机移动又会使逐目标的射线续查重置，最终可能一直处于未确认状态，导致无遮挡目标也无法出现。

现在按最后访问时间保留活动地形缓存，持续查询的前半段不会因读取时间较早而失效。区块成为已加载状态时，移除相应保存快照，以实时方块为准，防止卸载后重新使用旧地形。读取失败的数据仍按原时间间隔重试。

首次等待某个区段时，最多向前检查八个区段，利用原有读盘额度预读后续列；待确认数据仍须真正完成查询才允许显示。缓存容量和预算维持原值：512 个保存区块、每 tick 两次读取、四个在途读取的限制、每维度 16,384 处理步以及每轮 64 个重新确认目标。保留原扫描范围、速度门限、地形/Sable/Voxy 遮挡和流体规则。

冷缓存首次确认仍需异步等待。该修正消除的是反复过期造成的持续漏检，首次抵达未知地形的确认时间仍受 I/O 和共享预算影响。

## 验证

- 修改前：独立世界中，4,096 m 外放置真实 Sable 结构，雷达原点每次移动 0.5 m、每两 tick 调用生产扫描方法；320 次仍未确认，实际复现漏检。
- 修改后：机载和防空模式分别在 137 次、132 次冷扫描后确认目标，确认时距离约 4,028 m、4,031 m；随后各进行 20 次 351 km/h 移动扫描，保持接触点。
- 同一路径放置真实石墙后目标消失，拆墙后恢复。查询自身没有生成或加载地形区块。
- 原有 2 万候选目标、流体、旋转结构、RWR、红外/空对地锁定、Voxy LOD、全局配置和打包检查通过。
- 核对交付 JAR：仅雷达遮挡类发生变化，其他已安装功能保持字节一致，无集成测试类混入。

上述验证在独立测试世界完成；未将新 JAR 热替换到正在运行的游戏。计时中的游戏 tick 由测试单独推进，不表示实机固定等待某个墙钟时长。

证据：`build/radar-cold-4km-before.log`、`analysis/radar-long-range-20261114/final-verification.log`。使用独立编译目录，避免共享工作区中其他编译任务的输出干扰。

```powershell
.\gradlew.bat --offline -I tools/radar-long-range-build.init.gradle `
  -I tools/radar-scan-checks.init.gradle -I tools/global-config-checks.init.gradle `
  -PrunRoot=run_radar_cold_4km_final -PradarOcclusionChecks=true -PcbcRuntime=true `
  '-PmianbaoJar=<面包 2.7.1 JAR>' testGlobalFireControlConfig testVoxyLodRaycast `
  testRwrMissileApproach testInfraredMissiles shadowJar verifyShadedJar runServer
```
