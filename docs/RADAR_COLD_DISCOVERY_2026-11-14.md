# 远距离雷达首次发现修复

交付：`deliverables/create-fire-control-1.0.4-radar-cold-discovery-fix.jar`。

SHA-256：`5593a7f37684d80fb5ef8dfa7448b59a82aaf805439452c77d203e340ae2ddf8`。

## 实机观测与复现

本次底包直接复制自实例 `D:\Game\PCLII\.minecraft\versions\Create航空学\mods\create-fire-control-1.0.4-config-multilang-flight.jar`，SHA-256 为 `68b86e9992b4ea266990cbd4a680f7bf5e1560460aa8c135855b59c6e88929a7`。其 RadarVisibility、ClientRadarVisibility、VoxyLodRaycaster 类族与此前 radar-missile-lock-fix 交付一致。

只读附加到游戏进程，在原游戏线程读取状态，不改类、不触发世界 tick、不改用户存档：

- 雷达原点约 `(51.51, 556.63, -1097.43)`，前方目标约 `(-21.65, 251.34, 851.21)`，距离 1974 m。
- 目标进入候选列表且在扫描锥内；服务端联系人为空，客户端接收的原始联系人也为空。
- 该目标处于 PENDING，捕获视线的进度为 `0.25392767`，没有确认样本。
- 地形缓存 512 项全部已完成，无失败项，无未完成项；本轮只请求了 2 个区块。
- 此次采集期间游戏暂停、tick 不变，不能把 300 个相同快照称为动态闪烁录像。
- 另一个约 5.48 km 的结构位于当前雷达扫描锥后方，不能用它证明距离过滤失败。

证据：`analysis/radar-cold-acquisition-20261114/snapshot-1.server.txt`、`snapshot-1.client.txt`、`monitor.log`。

此前运动矩阵先静止确认目标，再开始运动，未验证移动中的首次发现。本次新增夹具从第一次扫描前便开始横向运动，使用真实 Sable 方块结构及生产控制器扫描入口。当前安装底包在第一项 4 km 动对静用例中，12 次扫描仍未确认，测试失败。证据：`baseline.log`。这项失败表明未满足首次发现时限，不声称夹具已穷尽证明所有速度下永久不可发现。

## 修改

原代码将首次发现限制为每维度每 tick 2 个区块读取，已确认目标才能使用另 126 个读取额度。4 km 水平视线约跨越 256 列，单纯首次读取就需要至少约 128 轮，而运动捕获视线的有效期仅 12 ticks。移动不断引入新地形列，使第一次确认难以完成，后续大额度也无法启用。

- 首次发现可使用闲置的维护额度，仍共用每维度每 tick 最多 128 次异步读取。
- 新目标与已确认目标使用同样的有界 256 段前读；已有目标仍优先，维护最多 126 次，保留发现机会。
- 用完成回调维护未完成读取计数，取消每次申请都遍历最多 512 个缓存 Future 的操作。
- 沿用 2 个较低优先级后台解码线程、最多 128 个未完成请求、每维度 16384 步、单雷达每次最多 4096 步、每轮最多 64 条射线、512 个完整地形快照、8192 个高度摘要、各 4096 项目标缓存。
- 预测、预算不足及未完成数据仍不能确认新目标；没有放宽遮挡条件和已确认样本的运动有效期。

只替换 RadarVisibility 类族；底包其余 JAR 条目逐项字节一致。保留当时安装包中的多语言、配置、飞行等修改，不替换控制器、客户端或其他导弹代码。审计清单为交付 JAR 同名的 `.changes.json`。

## 交付包验证

所有集成验证使用 `tools/radar-packaged-matrix.init.gradle` 展开实际交付 JAR，禁用主源码重新编译。

| 首次发现条件 | 机载雷达 | 防空雷达 |
| --- | --- | --- |
| 4 km，动对静 | 第 4 次扫描确认，后续持续持锁 | 同左 |
| 4 km，动对动，双方反向横移 | 第 4 次扫描确认，后续持续持锁 | 同左 |
| 4 km，静对静 | 第 4 次扫描确认，后续持续持锁 | 同左 |
| 4 km，静对动 | 第 4 次扫描确认，后续持续持锁 | 同左 |
| 按实机坐标差构造的高空斜向视线，动对静 | 第 2 次扫描确认，后续持续持锁 | 未测该几何形状 |
| 19 km，静对静，1188 列实际存档地形数据 | 第 11 次扫描确认，后续持续持锁 | 未测该距离 |

平台速度 351 km/h，目标速度 234 km/h；每次扫描推进 2 ticks，间隔等待 50 ms 让异步 I/O 完成。4 km 各项共扫描 52 次；19 km 共 72 次。任何一次未完成请求均不超过 128，任何一次读取均不超过 128，扫描未加载任何地形区块。19 km 地形是只读导出的真实 NBT 副本，写入一次性测试世界后由生产异步读取链路读取，不向用户世界写入。

冷发现证据：`verified-cold.log`。首次场景含单次 26.92 ms 峰值，未剔除；后续场景各自最高 0.56–1.53 ms。这里没有将首次峰值归因于一个未经分析的具体原因，也不将这些独立测试测量视为实机 FPS 保证。

四个雷达各 20000 个冷目标、同时横向移动、连续 16 轮的额外压力测试通过：64 次扫描平均 1.33 ms、单次最高 13.77 ms，合计 2048 次异步读取，观测到的未完成峰值 98。全程共用 128 请求/16384 步及固定缓存上限，零区块加载。这项测试验证压力下的资源边界，不表示 80000 条冷视线能在 16 轮内全部确认。证据：`cold-crowd.log`。

既有集成回归全部通过：机载/防空四种运动组合连续 60 次持锁；红外/空对地四种组合连续 40 次持锁和各 4 次发射前复查；石墙遮挡/同 tick 拒射、水/岩浆、含水固体、其他物理结构、显示器雷达静态遮挡、RWR、低空地形、包围盒抖动、预算耗尽及 20000 目标压力。20000 近目标扫描 2.54 ms；20000 个 2 km 目标扫描 3.01 ms。证据：`regression.log`。

交付类链接、Voxy 层级/流体/水浸固体/共享预算、RWR Voxy 遮挡、客户端待定航迹保留等检查通过。64 份实际地形 NBT 共两轮各 1024 次解码，第二轮平均 CPU 0.412 ms/列。证据：`artifact-smoke.log`。

本次没有重新测试所有导弹的完整发射及命中，未改变其他导弹飞行制导；之前相关验证的范围及未解决失败见 `RADAR_MISSILE_MOTION_MATRIX_2026-11-14.md`。显示器雷达四种运动组合及用户实际世界中的新包运行效果仍未在本次验证。

## 使用

当前游戏进程仍运行旧 JAR，未覆盖运行中的文件。退出游戏后用交付包替换原火控 JAR，mods 内只留一份火控；多人环境客户端和服务端同时更新。尚未用此新包重启用户实际场景，因此不能声称实机问题已经彻底消失。

## 重现命令

先在新的 `runRoot/server` 下复制测试用的 `server.properties` 并设置 `eula=true`。冷发现测试：

```powershell
.\gradlew.bat --offline --no-configuration-cache `
  -I tools/radar-packaged-matrix.init.gradle -I tools/radar-scan-checks.init.gradle `
  -I tools/radar-cold-acquisition.init.gradle `
  '-PrunRoot=run_radar_cold_verified_20261114' '-PradarColdAcquisitionOnly=true' `
  '-PradarOcclusionChecks=true' `
  '-PradarMatrixJar=deliverables/create-fire-control-1.0.4-radar-cold-discovery-fix.jar' `
  '-PcbcRuntime=true' `
  '-PmianbaoJar=D:/Game/PCLII/.minecraft/versions/Create航空学/mods/mianbaos_modernwarfare-2.7.1.jar' `
  runServer --console=plain
```

另加 `'-PradarColdCrowdOnly=true'` 仅运行冷目标压力测试；移除 `radarColdAcquisitionOnly` 运行既有完整雷达/锁定集成回归。每次使用新的测试世界目录。
