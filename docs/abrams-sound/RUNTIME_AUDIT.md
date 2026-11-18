# Abrams FMOD Reference 运行与响度复查（2026-10-09）

## 结论与实际运行版本

最新源码默认 FMOD Reference，JVM 的 `create_fire_control.abrams.mix` 可覆盖；本轮没有再次改默认值。真实客户端 PID 21308 于 18:55:28 启动，运行目录为 `D:\Game\PCLII\.minecraft\versions\Create航空学`。通过只读 `jcmd VM.system_properties` 核对，没有 Abrams 模式覆盖参数，也没有打开声音调试。

该客户端启动日志确认加载 `mods/create-fire-control-0.8.1-ztz99a.2.jar`，文件时间 18:41:30，SHA-256 为 `4DB93112FE2662B58117D7E459BD21BC51068395AB52F04E762C85384B91D589`。`javap` 核对该 JAR 的静态初始化仍是 `System.getProperty(MODE_PROPERTY, "legacy")`。它与主工作区同名旧构建完全一致，与上次隔离副本交付的新默认 JAR 不同。因此，本轮观察到的正在运行的客户端实际选择 Legacy；这是试听变化不明显的直接原因。

扫描所有顶层活动模组 JAR，只找到一个包含 `GasTurbineSoundManager` 的模组；目录中的历史备份不是 `.jar` 后缀，没有发现同时加载两份火控的证据。启用的外部资源包也未替换 Abrams Ogg 或相关 sounds.json 条目。正在运行的 JVM 不会因改源码或生成新 JAR 自动切换。

本次交付 `build/libs/create-fire-control-0.8.1-ztz99a.2-abrams-reference-audit.jar`，以实际安装的 JAR 为模板，只替换六个 Abrams 声音类（管理器及四个内部类、Reference helper）。其余 2,502 个 ZIP 条目的解压后 SHA-256 均与安装版本一致，包含全部 99A 类、音频、动力/遥控/GUI/模型资源。新类与完整编译 JAR 对应条目一致，静态初始化默认字符串确为 `fmod_reference`。不要将两个 JAR 同时放进 mods。

## 两种模式实际差多少

条件统一：距离 0、发动机 GUI 100%、Minecraft Master/BLOCKS 100%、包络 1、climb 0、捆绑 JSON 音量 1。实际 Java 控制器和最终声音实例乘法路径均已检查；每个素材重新解码测 RMS。总能量按 `sqrt(sum((每轨最终 Gain × 该素材 RMS)^2))` 估计，假设各轨不相关，不能当成声卡录音或精确 LUFS；没有把 Gain 相加当响度。Legacy 的未选轨 tiny floor 已纳入本轮表格。

| RPM% / SU 利用率% | Legacy RMS dBFS | Reference RMS dBFS | Reference−Legacy dB |
|---|---:|---:|---:|
| 0 / 0 | -27.520 | -36.175 | -8.655 |
| 10 / 0 | -26.022 | -30.131 | -4.108 |
| 33 / 25 | -25.930 | -30.485 | -4.555 |
| 50 / 50 | -27.840 | -31.334 | -3.495 |
| 66 / 75 | -27.028 | -31.348 | -4.320 |
| 80 / 100 | -27.061 | -30.526 | -3.465 |
| 100 / 100 | -27.061 | -30.526 | -3.465 |

[SEVEN_SCENARIOS.md](SEVEN_SCENARIOS.md) 列出每种场景八轨的最终 Gain dB、差值、素材 RMS 和交叉补偿；[seven-scenarios.csv](seven-scenarios.csv) 保存 56 条逐轨数值。默认启动导出与显式 Reference 的 CSV 完全相同。

两种配方并不十分接近。负载轨本身减小约 6～12 dB，交叉时还移除旧补偿；总混音通常只减小约 3.5～4.6 dB，因为未改变的正常 RPM 组继续贡献能量，怠速差值则达到 8.655 dB。两模式使用相同音色素材、AutoPitch、Fade 和启停增益，所以频谱/音高与启动声音相近；这不意味着 Gain 相同。RPM 80% 与 100% 的层权重都是 RPM99，但 Pitch 仍变，RMS 模型因而相同。若在启动后 16.82 秒的交接完成前比较，主要听到的是两模式增益相同的启动样本。

## 各层增益审计

| 参数 | 值 | 实际作用层 |
|---|---:|---|
| MASTER_ENGINE_VOLUME | 0.15 | 循环与启停实例的共同比例，配合 GUI 与自定义距离 |
| LOOP_VOLUME_GAIN | 4 | 仅循环；两模式共用，不随负载改变 |
| NORMAL_BUS_GAIN | -6.02 dB | 正常 RPM 四轨；正常单轨另外有 +4/+2 dB |
| LOAD_BUS_GAIN | -4.02 dB | 仅 Legacy 固定负载 Bus；Reference 不乘它 |
| MASTER_GAIN | -8 dB | 循环混音核只乘一次；启停不走循环核 |
| MAX_CROSSFADE_COMPENSATION | 上限 +3 dB | 仅 Legacy 的 RPM/load 重叠区各自计算 |
| 八个 CALIBRATION_DB | 全部 0 dB | 静态素材校准；没有按素材 RMS 自动归一化 |
| START_EVENT_TRIM_DB | -3 dB | 启动一次性音效，只乘一次；停机 trim=0 |
| ONE_SHOT_BALANCE_GAIN | 2/3 | 启停共同平衡比例；不用于循环 |
| GUI / Minecraft 音量 | GUI 0～300%；Master、BLOCKS | 独立的游戏设置，不能与 FMOD Bus 混为一项 |

Reference 循环公式：

```text
x0 = 0.0042372881355932203
q = clamp((smoothedLoad-x0)/(1-x0), 0, 1)
loadEffectDb = -10 + 6*q
loadBusGain = 10^((-6.02 + loadEffectDb)/20)
climbGain = 10^(3*smoothedClimb/20)
normal_i = wRPM_i * D(-6.02) * D(i==0?4:2) * calibration_i * envelope * D(-8) * climbGain
load_i = wLoad_i * loadBusGain * calibration_i * envelope * D(-8) * climbGain
assignedVolume_i = 0.15 * GUI/100 * 4 * customDistance * max(mix_i,0)
SoundEngineSourceGain_i = clamp(submittedVolume_i * BLOCKS, 0, 1)
audibleGainEstimate_i = SoundEngineSourceGain_i * MinecraftMaster
```

`D(d)=10^(d/20)`；submittedVolume 是 Minecraft 调用 `getVolume()` 时返回的值，还包含解析后的 JSON 音量。捆绑循环 JSON 没有额外 volume，默认 1。Legacy 的负载 Bus 固定为 D(-4.02)，保留交叉补偿和每轨 0.0001 floor；原生 LINEAR 再乘自定义距离，Reference 只用自定义距离。上述近距离表不受距离差异影响。

没有重复叠加旧 LOAD_BUS_GAIN，没有把线性 Gain 当 dB 相加，没有把 Master Gain 重复应用，也没有随负载变化的反向补偿抵消自动化。Reference 的 RPM/load 交叉补偿均为 1。保留的常数同时作用于两模式，不能把两配方变成相同输出。GUI 300% 网格检查每个声源系数均小于 1，Minecraft 单源 clamp 不会把这七种工况压成相同结果；Reference 保守峰值相加上界仍为 0.8180。

## 官方工程核实

已重新查询 Gaijin 当前 master，提交仍为 `07fd12efffbf20b2053d630ed5cf7ca3ddfa54a6`；当前事件 XML SHA-256 与上次缓存完全相同。

沿 `load` 的 effectChain 核实 GainEffect `{9a943c89-1591-4271-8339-6f576249d5c4}`，自动化属性为 `gain`，load 参数 ID `{8c0d50e7-2e1d-4569-9b02-83049e0735f3}`。两个点是 `(0.0042372881355932203,-10)`、`(1,-4)`，均没有 curveShape，按 dB 线性插值。自动化设置该效果器的 Gain，存储的基础 +2 dB 不再另外叠加。当前 Reference 的 Bus -6.02 dB 加自动化 Gain 关系正确，无需为了听感差异改曲线。正常组静态 Bus 也为 -6.02 dB，循环 Master GainEffect 为 -8 dB；另一 Master Gain 的 climb 曲线是 `(0,0),(1,3)`。

八轨 AutoPitch root/min 与官方数值一致：RPM 为 `(1,.99),(33,.705),(55,.705),(100,.43)`；load 为 `(.01,.99),(.275,.675),(.55,.705),(1,.77)`。文件名 engine_66 不代表 root 必须为 66。Minecraft 后端 Pitch 有 0.5～2 限制，代码 0.35 下限不能绕过它。load LFO 保持 0.05 Hz、±2.5 半音，固定负载下有音高变化，不是 Gain 波动。

Reference 仍是适配模组的对照混音。官方还有 Distance、朝向、故障、传动、其他组和项目路由/效果，当前没有复制整个事件；保留模组 GUI 范围与静态呈现比例。原 FadeCurve 的 shape 字段相同，但 `smoothstep + shape*s*(1-s)` 是已有近似函数，未证明是 FMOD 内部函数；AHDSR 也不是 FMOD 的逐样本复刻。本轮没有改变这些近似来夸大听感差异。

## 音频与负载

十个 Ogg 的 SHA-256 与旧审计一致，重新解码结果也一致：当前 44.1 kHz 单声道；官方源文件虽名为 WAV，实际是 44.1 kHz 双声道 Vorbis，XML 的 48 kHz 是元数据。官方转 mono 后的怠速负载/正常 RMS 差为 12.7084 dB，当前为 12.7174 dB，差异来自官方素材。

十文件相对官方 mono 的拟合增益仅 +0.0115～+0.0541 dB，相关性 0.99965～0.99994，没有确认有意义的放大、归一化、额外压缩或硬削波。启停解码峰值稍大于 1 的微小 Vorbis 过冲不能据此认定削波。engine_66/99 的首尾跳变仍为 0.35939/0.36625，可能产生循环瞬态；按官方帧数结束时下降到 0.0321/0.00551。接缝风险独立于 Gain，本轮没有重编码、裁剪或替换音频。完整测量见 [runtime-audio-audit.json](runtime-audio-audit.json)。

声音负载仍取 Create 已同步的 `stress/capacity`。Create 的 network NBT 会写入/读取 Stress 和 Capacity；源码没有固定负载或漏用目标值的迹象。真实声音控制器的输入路径已拆出可直接检查的参数更新方法：固定 RPM 时把目标从 0 改为 1，每 tick 0.02，50 tick 达到 1；反向也通过，负载音轨随之切换，Bus 选择与最终系数检查通过。

这验证了声音层响应，不能证明当前存档中每台机器的网络数据实际发生变化：本轮运行客户端没有新诊断日志。多台发动机共享同一网络时获得相同利用率，它表示整个网络而非单机扭矩；增加发电机容量可能降低利用率，固定设备可能使它长期停在同一区间。保留网络代理与 Seek 0.4/秒；没有修改 Create 实际输出。可仅在声音层固定 debug_load/debug_rpm 对比，代价是暂时不代表真实设备负载；生产中移除调试参数即可。若未来估算单机负载，需要有分担输出的数据依据，不宜用 RPM 简单代替扭矩。

## 本轮实施与验证边界

- 默认值、既有增益常数、音频和所有动力功能保持原样。
- 新增可关闭的来源/模式日志，标记 `reference-audit-v2`；输出 classSource、JVM 覆盖情况、真实 RPM、混音 RPM、network ID、SU 利用率、目标/平滑负载、八轨 Gain dB、assignedVolume、submittedVolume、声音事件/素材 ID 与来源资源包、播放数及 RMS 估计。
- 默认每台发动机每 20 tick 输出诊断，`create_fire_control.abrams.debug_interval_ticks` 可调整，最低 10 tick；没有每 tick 输出。启停仅记录发生时的事件。
- submittedVolume 捕获 SoundEngine 本来会调用的 `super.getVolume()` 返回值，没有为了记录音量再次采样资源包随机音量。assigned/submitted 是最近声音 tick/后端请求的观测值，可能比当前层 Gain 晚一个 tick，不应把这一瞬间偏差当 Seek 错误。
- 增加声音层固定 RPM% 输入，配合已有固定负载复现七种工况；不会发送动力包，也不会改变真实 RPM、SU 或 GUI。
- 检查从混音核扩展到真实控制器参数更新与最终实例乘法，保留 Legacy 逐位回归；新增七种工况逐轨报告与只替换 Abrams 类的打包工具。
- `compileJava --offline --no-configuration-cache` 通过；默认、Legacy、Reference 各 20,402 组合与控制器负载响应检查通过。
- 又使用 `verify_abrams_sound.ps1 -Offline -PackagedJar <交付JAR>` 重跑三种模式，排除 main 编译目录并核对两个生产类的 CodeSource 确实是交付 JAR；三组均通过。A/B 工具还支持固定声音 RPM% 与 `-NoDebug`。
- 完整 `build --offline --no-configuration-cache` 复现已有 `testNativeBearing` 断言 `sustained carrier rotation holds every tested load before stopping`。没有修改轴承测试或动力代码；`build --offline --no-configuration-cache -x testNativeBearing` 通过，包括 99A 资源检查。

已通过真实客户端证据核实的是：当前进程/参数、启动日志、安装 JAR、实际默认分支和资源包列表。新 Gain 的数值、Seek、连续性、边界/NaN、上限和包络是 Java 控制器/离线测试；新 JAR 尚未在正在运行的游戏中重启试听。不会将世界外检查或 RMS 模型称为客户端录音验证。

## 在游戏里验证

1. 保存并退出客户端，把旧火控 JAR 备份到 mods 以外，换用本轮交付的 `*-abrams-reference-audit.jar`；mods 中只留一个活动火控 JAR。其他模组、99A 音效和资源包无需调整。
2. 客户端 JVM 参数加入 `-Dcreate_fire_control.debug_engine_sound=true`，可显式加入 `-Dcreate_fire_control.abrams.mix=fmod_reference`。进入有运行中 M1A2 的世界，日志应出现 `Abrams diagnostics=reference-audit-v2 mode=fmod_reference`，classSource 指向新 JAR。缺少这行时先核对启动器游戏目录/JAR/日志参数。
3. 近距离、GUI 100%、固定游戏音量，等启停交接结束再比较。`-Dcreate_fire_control.abrams.debug_rpm=33 -Dcreate_fire_control.abrams.debug_load=0.25` 可固定声音工况；实际机械转速和网络负载仍保持正常。assignedVolume 在稳定近距离应对应逐轨表中的线性 Gain；layerGainDb 尚未包含实例层 0.15×4，不能与最终 Gain dB 直接比较。
4. 保持全部条件，仅将 JVM 模式改为 `legacy` 并重启做 A/B。开发快捷命令：`tools/run_abrams_sound_ab.ps1 -Mix fmod_reference -Rpm 33 -Load 0.25` 与 `-Mix legacy`；`-NoDebug` 关闭日志。
5. 验证真实 SU：移除两个固定输入参数，保持机械 RPM，增减设备负载；观察 networkLoadPct 先改变、soundTargetLoad 跟随、smoothedLoad 以 0.02/tick 变化，负载 Bus 与 load 轨改变。同网络多发动机日志应具有相同 network ID 与利用率。若 raw 利用率不动，先排查设备是否属于该网络以及同步数据，而不是放大整体音量。
6. 结束验证后移除调试参数或设 `-Dcreate_fire_control.debug_engine_sound=false`。保留 `-Dcreate_fire_control.abrams.mix=legacy` 可回退；移除模式参数恢复默认 Reference。

本轮项目文件：两个 Abrams 生产类、`AbramsSoundMixChecks.java`、A/B PowerShell 与 init.gradle、验证 init.gradle/脚本、`compare_abrams_mixes.py`、`package_abrams_sound_patch.ps1`、本报告及关联逐轨/音频数据。独立 worktree 的编译日志和原文件快照保存在 `.diagnostics/abrams-runtime-audit/`。
