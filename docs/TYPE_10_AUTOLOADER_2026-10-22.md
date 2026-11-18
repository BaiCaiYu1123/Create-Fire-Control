# 尾仓装弹机与专用炮闩

新增 `create_fire_control:type_10_loader`（尾仓装弹机）及
`create_fire_control:type_10_cannon_breech`（10式专用炮闩），均加入火控创造标签页。
炮闩和现有专用炮闩一样，在 CBC / CBC Neo Warfare 集成启用时注册。

## 使用与库存

- 放置后空仓。手持现有尾翼稳定脱壳穿甲整装弹右键，每次装入一发，最多 13 发。
- 模型 `apfsds` 下的弹位按数字排序：`APFSDS-1` 至 `APFSDS-11`、`APFSD-12`、`APFSDS13`。
  首次装入显示 1 号，依次显示到 13 号。保留原模型名称和全部 410 个立方体。
- 与 MZ/AZ 一样，用机械动力扳手先选装弹机，再选普通、固定或紧凑火炮底座。
  该底座组装的火炮须使用 10式专用炮闩；其他炮闩不会消耗尾仓弹药。
- 空膛自动开始装填，射击后沿用现有自动装弹桥接提取弹壳并开始下一轮。
  只有成功向炮膛送入整装弹后才扣除当前编号最大的一发，满仓消耗顺序为 13→1。
  空仓停止。沿用原有开始循环后锁定补弹、耗尽后解锁的机制。
- 未绑定时，红石上升沿仍可像 MZ/AZ 一样播放一次动作并消耗一发。
  持续红石、忙碌期间的新脉冲和空仓均不会重复启动。

## 模型、声音与时序

原样使用 `尾仓.bbmodel` 的 4.4 秒 `animation` 动画，保留所有骨骼目标、时间和值，
包括源模型中 `APFSDS-1` 的动作。每轮播放同一个原始动画；库存独立处理，
推弹成功时只从库存隐藏数字最大的一发。
各运动通道的关键帧按时间递增输出，并把整秒写成 `3.0` 形式，
避免 JavaScript 自动提前整数属性。GeckoLib 4.9 按 JSON 文件顺序烘焙关键帧，
必须保留此输出顺序，才能匹配 Blockbench 按时间排序的播放结果。
服务端在 1 秒发出开闩指令、2.65 秒（第 53 tick，对应源动画 2.64 秒）确认推弹并扣库存、
3.6 秒发出关闩指令，4.4 秒结束本轮动作。

`10式off.bbmodel` 共 36 个立方体，其中 `group` 下 9 个立方体为开闩活动件。
开闩沿炮闩模型局部 Y 轴平滑下移 `5/16` 格，即 5 像素；其余 27 个立方体不参与开闩。
保留现有专用炮闩的后坐行为。

`4秒.mp3` 已转成 48 kHz 单声道 Vorbis，长度约 4.415 秒，在原动画 0 秒音效点播放。
音量以 MZ/AZ 两款现有装弹机的平均 BS.1770 综合响度校准，保留同样的播放音量和距离衰减：
MZ 为 -17.329 LUFS，AZ 为 -16.844 LUFS，尾仓最终为 -17.077 LUFS，
比上一版约降低 9 dB，解码峰值 -5.206 dBFS。校准报告为 `build/type-10-audio-balance.json`。
库存、开闩/推弹/关闩待办和炮座绑定均保存为 NBT。
增加 Sable 跟踪玩家的冗余动画同步、蓝图绑定映射和现有弹架殉爆集成。

资源重建工具：

```powershell
node tools/convert_type_10_models.js 'C:/Users/asus/Downloads/尾仓.bbmodel' 'C:/Users/asus/Downloads/10式off.bbmodel'
python tools/prepare_type_10_audio.py 'C:/Users/asus/Downloads/4秒.mp3'
python tools/render_type_10_models.py 'C:/Users/asus/Downloads/尾仓.bbmodel' 'C:/Users/asus/Downloads/10式off.bbmodel'
```

音频工具使用本工作区 `.tools/audio-python` 的 PyAV 解码和 `.tools/python` 的 SoundFile 编码；
模型预览工具使用 NumPy/Pillow。三份输入原文件的 SHA-256：

| 输入 | SHA-256 |
| --- | --- |
| 尾仓.bbmodel | `04B9DC44735C346BBBB434505A2EC601F1B745913C98179B904B0C6AD069B439` |
| 10式off.bbmodel | `B833B2395413BE8AAEF445DB3FB34D2990D047958299EA6EFF4A313596F94ECE` |
| 4秒.mp3 | `77682E69B0FD14C1764F762F24B7417881CA6EE9FE83DC251205CD08F521C2D7` |

## 验证

```powershell
.\gradlew.bat testType10LoaderState testAzLoaderState testMzLoaderPersistence --offline
.\gradlew.bat -I tools/type-10-checks.init.gradle runServer --offline -PrunRoot=build/type-10-integration -PcbcRuntime=true '-PmianbaoJar=E:/Codex/mianbao/mianbaos_modernwarfare/build/libs/mianbaos_modernwarfare-2.6.3-neoforge.jar'
.\gradlew.bat assemble --offline
```

自动检查覆盖全部 8192 种弹位掩码、补入和消耗顺序、全部原始动画关键帧与骨骼目标的逐项比较、
音效资源、炮闩分组及 5 像素位移。原始动画测试夹具取自上传的模型，独立于资源转换器。
使用实际 GeckoLib 解码器和缓动器，在整个 4.4 秒动画中每 20 毫秒对比原始动画的所有运动轴（共 13,260 项），
同时检查各解析段没有负时长，避免仅比较 JSON 数值时漏掉播放顺序错误。
MZ/AZ 原回归通过。

隔离服务端使用真实注册的 CBC 底座、组装火炮、专用炮闩和整装弹，推进生产逻辑验证了：
空仓放置、容量限制、错配 AZ 炮闩不装填、13 轮自动推弹及倒序扣弹、
推弹前后 NBT 重建、炮膛被占用时不扣弹、空仓停止、耗尽后补弹、红石循环和同步包编解码。
结果为 `build/type-10-integration/server/type-10-integration-result.txt`。
模型静态预览为 `build/type-10-preview/models.png`；未进行实际游戏客户端画面验收。

发行包：`build/libs/create-fire-control-0.9.1-bustle-loader-animation-fix.jar`，
同时更新 `build/libs/create-fire-control-0.9.1-type-10-autoloader.jar`。
测试服务端夹具不进入发行包。
