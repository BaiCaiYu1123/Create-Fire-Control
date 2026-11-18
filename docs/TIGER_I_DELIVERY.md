# 虎 I 发动机 .3 修复交付报告

本次完成两项修复：两个独立半轴回到发动机所在同一个方块的中心；默认音效改为 `FMOD_REFERENCE`，保留 `LEGACY` 回退。已有 97 个发动机 Ogg 文件与修复前 SHA-256 完全一致，没有重编码、覆盖或删除。声音架构已修正，但实测与原生 FMOD 仍有明显差异，不能称为完全还原。

交付目录：`build/dist/tiger_i_fix/`。安装文件为 `create-fire-control-0.8.4-tiger-i.3.jar`；使用 Minecraft 1.21.1 / NeoForge / Create 6.0.10 及当前项目依赖。替换旧版 Create Fire Control JAR，同一实例只放一个版本。源码 ZIP 是当前项目的虎式增量及基线，不能当作独立空项目构建。

## 半轴位置、模型与兼容

现在只放置一个 `create_fire_control:tiger_i_engine` 方块。左右两个 Create `SHAFT_HALF` 都以该方块的模型坐标 `(8,8,8)` 为中心，分别朝左右渲染；不再把半轴平移到相邻方块中心。左右相邻位置可放普通 Create 传动轴连接。

中央控制器持有两个同位置的独立 `GeneratingKineticBlockEntity` 端口，各自只向一个方向传播，各自使用独立动力网络。控制器本体生成速度为零。专用 Mixin 处理 Create 对同位置端口的邻接、源身份和网络容量检查；实际服务器已验证外接轴及消费者能得到动力，左右速度不会被合并。RPM、SU 容量公式和控制数值沿用原虎式控制器。中央碰撞箱仍为一格。

用户提供的 `V8_Diesel_RESTORED_20_CUBES.bbmodel` 实际包含 21 个立方体、252 个三角形及 512×512 内嵌纹理。保留全部几何、UV、纹理和尺寸，围绕 `(8,8,8)` 绕 Y 轴 +90°：`x'=z, y'=y, z'=16-x`。本次只修正半轴布局，没有另换机体或纹理。旧 `part=left/right` 方块状态仍可读取；中央初始化时只清除同朝向、匹配旧部件标记的虎式输出格，普通邻接轴不受此清理影响。现有行为设置、绑定、GUI 和持久化设置继续读取；新增端口速度/负载字段只用于客户端同步。

## 1. 旧版声音为什么差距大

已根据 `backups/tiger_i_fix_before/` 核对旧代码：七轨先做族内等功率归一化，再由 SU 的 `cos/sin` 互斥选择两组；不同录音另按 0.12/0.15 RMS 目标校正，叠加自定义 Master 和峰值 headroom。这样会改写官方轨道间电平及同时叠加关系。旧版还有猜测的淡化窗口/端点、未接入的 Climb、RPM 导数阈值加四秒冷却和频谱匹配的 revv、约 32 格音量相关衰减。它们是此前的 Minecraft 适配逻辑，不能作为官方虎式事件的还原结果。

初次审计还遗漏了串联父总线及共享效果器，现已把来源明确的增益纳入参考链路。参数预设文件名不能当参数 ID：revv 所绑定 `{f08e41df-abbc-4ae7-b86f-99cbd744e16c}` 实际是 `loadswitch`，普通 `load` 是另一对象，不能混淆。

## 2. 实际修复内容

- Reference 去掉 SU 两组互斥、统一 RMS、族内等功率补偿和自定义动态 headroom。
- 恢复真实 AudioFile 引用、逐轨静态 Volume、健康状态组音量、RPM Gain、串联总线和已解析共享增益；自动化值替代同属性基值，避免重复叠加。
- 以全 RPM 原生扫频表插值七条轨道的淡化权重，接入独立 AutoPitch 与三条受作用轨道的 Climb。
- revv 使用 loadswitch 进入区间触发、五条录音随机且避免立即重复、最多两条短音效；取消参考模式的四秒冷却和频谱择优。
- 参考模式取消 Minecraft 重复衰减，使用原生测得的距离曲线、单独高频衰减和 Sable 世界声源投影；静音远声源及时释放。
- 默认 Reference，保留 Legacy 和四种分层试听开关。补充实际 FMOD bank 渲染、两模式离线 WAV、CSV、实测 OpenAL 生命周期和审计报告。

## 3. 官方七轨完整引用关系

参考仓库：[Gaijin 官方 FMOD 工程](https://github.com/GaijinEntertainment/fmod_studio_warthunder_for_modders)。固定提交 `07fd12efffbf20b2053d630ed5cf7ca3ddfa54a6`，车外 engine 事件 `{e4b1917d-d838-4490-8cb1-b8f43f4b1a94}`。所有素材来自 `Assets/tanks/engines/panzer6/update/`，未混入虎王、车内或其他发动机。

|SingleSound ID|AudioFile 素材|XML 组|
|---|---|---|
|d3246191-3789-40e9-9d34-88da7e470955|idle_load.wav|load|
|bc1344e7-5a9d-42de-a4aa-1b9cf78bd498|slow_not_load.wav|load|
|bd31b4d6-6511-43b1-99cf-9e8e5d1da080|med_not_load.wav|load|
|7283bc5b-1e87-4242-9b12-28d6d9060498|fast_not_load.wav|load|
|5f60fce8-1084-4942-8256-421877131fb1|slow_load.wav|not_load|
|6a892b7b-ad6d-4a11-94c9-ee4de77d6a3a|med_load.wav|not_load|
|e0d662eb-45d2-4fb7-a2f0-305167a82c44|fast_load.wav|not_load|

文件名后缀与组名不同，判断依据是 GroupTrack → SingleSound → AudioFile 对象关系。用户原 ZIP 的 31 个录音已在早期导入时核对官方 Git blob；`idle_not_load` 为全静音，不作为第八循环。30 个可播放单声道副本及声音事件保留。release/blips 保留资源，Reference 不播放；`source_mapping.json` 的 `used` 表示已导入资源，并不表示 Reference 当前会触发。

完整 ID 图见 `tiger_i_reference/audio_fix_audit.json`：379 个可达对象；17 个未缓存引用是外部 mixer master、snapshot、send/return、平台和 sidechain 对象，不是七轨核心 Gain、AutoPitch 或 Fade 对象。缺失对象及未复制效果在报告中明示。

## 4. AutoPitch、Fade、Volume、Gain 对照

`rpm` 范围为 0～100，使用声音 RPM 对声音最大 RPM 的绝对值归一化，不直接把 Create RPM 数值当 FMOD 参数。

|素材|静态 dB|XML 播放窗口|淡入|淡出|Root|PitchAtMinimum|健康组 dB|
|---|---:|---|---|---|---:|---:|---:|
|idle_load|8|0～16.591902|无|2～16.591900|14.5|0.955000043|-2|
|slow_not_load|2|1.434980～55.515682|1.434980～15|33.094200～55.515682|25|0.800000012|-2|
|med_not_load|1|33.094200～83.677208|33.094200～55.515682|67.629000～83.677208|50|0.600000024|-2|
|fast_not_load|4|67.629000～100|67.629000～83.677208|无|75|0.699999988|-2|
|slow_load|-4|1.434980～40|1.434980～15|25～40|25|0.800000012|6|
|med_load|-2|25～75|25～40|60～75|55|0.539999962|6|
|fast_load|0|60～100|60～75|无|100|0.579999983|6|

健康 `broken=0` 自动化将两组设为 −2/+6 dB，替代组基值 −3.02 dB。它不是 SU 或 `load` 自动化。

普通淡入 Curve Shape 为 −0.2547189、淡出 +0.25471893；slow_load 淡出 +0.340737492、med_load 淡出 +0.541490972、fast_load 淡入 −0.49234125。完整点和形状在 `profile_reference.json`。Reference 运行时使用本机原生事件 0～100、步长 0.25 的 401 行权重扫频表，连续插值，不强行归一化功率或改端点。表中原生边缘尾部可能与固定 XML 窗口不同；本机 bank 与固定源码是不同构建，这项差异保留，不能声称是从 XML 重建的完整 Studio 工程。Legacy 仍保留旧分段曲线表及补偿。

基础播放速率：`p(r)=m+(1-m)*r/Root`；Root 处为 1，使用线性速率域而非半音域。静音轨预备 Pitch 钳在最近 XML 窗口边界；声音 Pitch 在 log(rate) 中按 0.08 秒平滑。Reference 声音 RPM 上升跟随目标、下降使用官方参数 seek 100 单位/秒；机械 RPM 不受影响。

|串联节点或效果|Reference 处理|
|---|---|
|SingleSound Volume|上表静态 dB|
|EventMixerGroup Volume|健康 −2/+6，替代基值|
|事件 Master|+2 dB|
|engines_enemies 总线|−8 dB|
|enemy 父总线|−16 dB|
|RPM Compensator|0 时 −3 dB，9.7457627 时 0 dB，区间内插值|
|共享 Gain_tank_engines_enemy_ai|近距离 Distance +4，前方 Direction 约 +2；合计约 +6 dB|
|revv 的 Tank_AI_Load_Speed_Gain|自动化 +8 替代基值 +6；局部另 −2，合计 +6 dB|
|Reference 自定义 Master|默认 0 dB|

近距离前方、未乘 Fade 时，循环 `dB = Static + Group +2−8−16+6+RPMCompensator`。怠速 rpm=0 得到 −13 dB；revv 得到 −10 dB。共享 Direction 的中心节点为 1.5254°，精确 0° 约 +2.0336 dB；当前方向映射用约 +2 为前方基准、+6 为后方，属于小幅近似。最终还乘 GUI 音量、启停包络和距离/方向幅度；`10^(dB/20)` 才是线性 Gain。

## 5. Climb Pitch 的准确部分与近似部分

已核对作用目标为 `slow_load/med_load/fast_load`，前四轨不受 Climb 影响。端点为 climb=0.004237288 → 0 半音，0.995762712 → +6 半音；中间线性。计算 `s=6*clamp((climb−0.004237288)/(0.995762712−0.004237288),0,1)`，额外速率为 `2^(s/12)`，再乘基础 AutoPitch。

Minecraft 参数映射是明示的适配：正声音 RPM 加速度经 0.15 秒平滑，35 个归一化 RPM 单位/秒达到 climb=1；attack 0.12 秒、release 0.65 秒。稳定转速时平滑回零，不永久保持 +6 半音。没有宣称这是战争雷霆车辆内部的 Climb 生成算法。全 RPM 扫描验证当前 Pitch 在 Minecraft 0.5～2 的范围内，不因 Climb 错误截断。

## 6. SU 在 Reference 中如何处理

SU 仍供机械容量、控制和调试显示使用。固定声音 RPM、其他音效参数不变时，改变 SU 不改变七轨权重、Gain 或基础 Pitch。Reference 的健康状态固定 broken=0，同时保留两组官方轨道；没有把 SU 当 FMOD 的 broken、climb 或 loadswitch。端口 load 取两个网络利用率的较大值，仅供原控制器逻辑和 Legacy 声音映射。

## 7. RMS 和额外补偿

旧源码中的校准量实测为：

|轨道|旧校准 dB|
|---|---:|
|idle_load|−5.71154|
|slow_not_load|+0.39584|
|med_not_load|−3.23394|
|fast_not_load|−6.70532|
|slow_load|+2.43348|
|med_load|−3.97480|
|fast_load|−7.96455|

Reference 完全不应用这七个值，不加入 +3 dB 或等功率交叉补偿，也不做按峰值自动压低全部声音的旧 headroom。Legacy 保留这些逻辑供对照。当前两模式都使用相同旧单声道 Ogg；本次没有为了更响而修改文件。

早期生成的单声道副本已有 PCM 范围裁剪、左右算术平均和一次 Vorbis 编码，med_load 还有首尾各 8 ms 的接缝修复；这些处理本次全部保持字节不变。源双声道与旧副本的差异见 `audio_analysis.json` 和资源 `spatial_manifest.json`。没有用 RMS 匹配抵消这些差异。

## 8. Revv 如何触发和叠加

原事件 MultiSound `{f4ff643f-6ec1-4995-a7f4-fdfc6e7c0697}` 绑定 `loadswitch`，start=0.1、length=0.9、maxVoices=2，引用 revv-001～005。原生改变普通 load 没有触发效果，改变 loadswitch 才会触发。

MC 的明确近似映射为 `clamp((声音目标RPM−当前声音RPM)/25,0,1)`；沿用参数上升 seek=2、下降 seek=4，每次进入 >=0.1 区间触发一次，留在区间不会按计时重复，回落后可以再次进入。随机五选一并避免立即重复。最多两条短音效（包括启停声），启动交接期间抑制 revv。没有旧四秒冷却、频谱选择或 RMS 拉齐。

XML Randomizer `amount=5` 的序列化单位不能直接断言为 ±5 半音。240 次原生触发实测 −1.1807～+1.1691 半音，因此默认 ±1.2 半音，可配置；记录见 `native_revv_random_range.json`。每条短声固定 Pitch 播放，不套 Climb 倍率。已有录音的动态轨迹自然叠加在循环声上；Reference 不套旧自定义短音效入/出包络，远离或移除时仍做 0.45 秒释放。

|素材|时长秒|原双声道 RMS / Peak|旧单声道 RMS / Peak|
|---|---:|---|---|
|revv-001|3.00|0.26704 / 1.08944|0.25766 / 0.98687|
|revv-002|3.00|0.26357 / 1.04324|0.25775 / 0.98715|
|revv-003|3.00|0.27813 / 1.09588|0.26776 / 0.97608|
|revv-004|2.99|0.28155 / 1.07128|0.27461 / 0.99307|
|revv-005|3.00|0.30171 / 1.06735|0.29066 / 0.98637|

声调匹配分析的分段 harmonic-comb cue 已保留在 audio_analysis 中，它是频谱提示，不是经验证的发动机基频或物理 RPM。Reference 不依赖这个提示选录音。Climb 和 revv 都已按来源纳入，但二者的 MC 参数映射是否同时过强仍需实际试听。release/blips 不属于该 engine 事件的直接七轨架构，Reference 始终不触发它们。

## 9. 距离、方向和高频衰减

事件基础属性 min=3、max=1000、DopplerScale=205 经共享 Spatializer 覆盖：有效配置范围 3～400、dopplerMultiplier=0。因此没有把 1000 当恒定听距，也没有把 205 当实际速度倍率。

Reference 使用 `Attenuation.NONE` 取消 Minecraft 的额外衰减/音量相关半径，单独应用本机原生 bank 测得的相对距离表：

|映射距离|相对幅度|
|---:|---:|
|≤3|1|
|32|0.89563|
|64|0.81959|
|100|0.76400|
|128|0.73470|
|150|0.71750|
|200|0.40470|
|210|0.16586|
|215|0|

距离与 GUI 音量独立；默认一格按一米映射，`distanceScale` 可调。215 的截止来自共享自动化后的实际响应，不能从 Spatializer max=400 推出 400 内恒定播放。超过有效可听区不保留七个静音循环；默认额外 cull 上限 256 格。Direction 使用声源相对监听者朝向的水平角度，近距离基准已计入共享 Gain，监听方向前后只追加剩余约 0～4 dB，避免双算。

高频用 Tiger 专属 OpenAL EFX low-pass：3 格内 unity，200 格处默认 HF Gain −12 dB。这是 MC 空气吸收近似，不是官方完整多段 EQ。EFX 不可用时正常回退；filter 随 Channel 销毁显式释放。Sable 子空间声源先投影到世界坐标，移动声源仍通过原委托处理。

## 10. 两模式实际对照与可复现 WAV

十种场景均有 Reference/Legacy 离线 WAV 和每 50 ms 的 Java CSV：怠速、低/中/高 RPM、缓慢/急加速、减速、固定 RPM 的 SU 扫描、稳定 60 秒、启动与熄火。CSV 记录权重、有效 dB、线性 Gain、Pitch、Climb、loadswitch、启停包络及短音效。`predictedAllocatedSources` 是分配数模拟，不是假装实际 OpenAL 源数。

另有十份通过本机安装的 War Thunder 编译 bank 和原生 FMOD runtime、相同事件 GUID 的实际 WAVWRITER_NRT 渲染。它们是实际 bank 音频，不是 Java 模拟；但它们不是由固定 XML 重建，也不包含战争雷霆 gameplay/snapshot 状态。参数按 Java 场景驱动，监听位置 3 米，声源 scalar 1.5，与离线 GUI=150% 相同。DSP clock 调度并移除设置预滚，原生 16-bit PCM 无损转换到 FLOAT；没有逐文件响度归一化。运行时退出 Studio 返回 ERR_INTERNAL，先显式关闭 Core 输出成功完成 WAV；每个场景隔离进程，源码保留这一操作记录。

`native_bank_start_stop_rpm_only.wav` **只驱动已运行 engine 事件的 RPM 参数**，没有原生 start/stop 事件。它不能证明启动/熄火原版交接还原。Reference/Legacy 的对应离线 WAV 才包含 MC 启停包络和 start/stop 录音。

|场景|Reference RMS / Peak|Legacy RMS / Peak|实际 bank RMS / Peak|
|---|---|---|---|
|怠速|0.03109 / 0.14045|0.01279 / 0.05780|0.05478 / 0.25284|
|低速|0.05818 / 0.26794|0.04209 / 0.19515|0.12970 / 0.51358|
|中速|0.11571 / 0.42139|0.04164 / 0.15340|0.17166 / 0.64166|
|高速|0.18584 / 0.56699|0.04221 / 0.13169|0.22610 / 0.70709|
|缓慢加速|0.15195 / 0.62212|0.03966 / 0.18800|0.19809 / 0.70709|
|急加速|0.17252 / 0.74226|0.03852 / 0.15467|0.21245 / 0.70709|
|稳定 60 秒|0.11728 / 0.43669|0.04200 / 0.15822|0.17572 / 0.69781|

完整十场景、100 ms RMS 窗口、原生 bank 活动 Channel 数及文件哈希在 `audio/measurements.json`。原生最高峰多次约 0.70709，反映原生链路特征；Reference 没有额外归一化到该峰值。不同声道格式、已有单声道裁剪和完整 DSP 缺失会影响对比，这个表不能代替听感验收。

旧 Legacy 的 SU 互斥/RMS/补偿会使不同 RPM 的 RMS 接近；Reference 恢复原轨叠加及电平关系，随 RPM 的输出变化明显。两模式离线文件使用同一批单声道素材、统一源标量，不进行单独归一化；离线采样不包含实际设备、EFX、真实循环相位和 Source 调度。Legacy 短音效轨迹是离线模拟，不能当作完整 SoundManager 录音。

## 11. 修改及交付文件

本次音效核心：`TigerISoundManager.java`、`TigerISoundProfile.java`、`TigerIGain.java`、`TigerITakes.java`、`TigerISoundConfig.java`；新增 `TigerIReferenceFade.java`、`TigerISpatialAudio.java`、`mixin/client/TigerIAudioChannelMixin.java`。

本次半轴核心：`TigerIEngineBlock.java`、`TigerIEngineBlockEntity.java`、`TigerIEngineRenderer.java`；新增 `TigerIKineticPortMixin.java`、`TigerIRotationPortMixin.java`、`TigerITorqueAccessor.java`、`TigerIKineticNetworkMixin.java`；更新 `create_fire_control.mixins.json`、旧 left/right 空标记模型及 `convert_tiger_i_bbmodel.py` 的中心输出记录。

验证/工具：`TigerIResourceChecks.java`、`TigerIntegration.java`、`TigerIAudioClientChecks.java`、`extract_tiger_i_profile.py`、`probe_tiger_i_reference.py`、`audit_tiger_i_fix.py`、`render_tiger_i_reference_ab.py`、`render_tiger_i_model.py`、`package_tiger_i.py`，及 init Gradle、参考 XML/JSON 和此报告。当前增量源码 ZIP 还含此前虎式注册、网络消息、材质和资源，详细 SHA-256 清单见 `source_manifest.json`；`tiger_i_fix_changes.patch` 提供有 `.2` 快照基线的文件差异及新增核心类，完整当前工具和测试源码另在 ZIP 中。

未改变 M1A2/99A 的音效或动力值。原有其他发动机声音事件逐项相等，97 个现有 Ogg 的哈希全部相等。`.1`、`.2` 交付目录保留。没有公开发布，也不打包 FMOD DLL、SDK 或 War Thunder banks。

## 12. 编译与测试结果

|验证|结果及范围|
|---|---|
|compileJava + testTigerIResources|PASS：SU 独立、Reference Gain/完整 Fade、AutoPitch/Climb、loadswitch 进入/回落、空间映射、Legacy 补偿、无效输入、30 声音资源与十场景导出|
|实际 runServer|PASS：23 阶段，四朝向静态世界及四个真实 Sable body，独立网络、普通轴/消费者、Creative Motor 容量、控制、持久化、移除及燃气轮机对照|
|实际 runClient|PASS：30 Ogg 经 Minecraft JOrbis 解码，四朝向 V8 252 三角形烘焙，默认 Reference/FULL、GUI 标题、7 循环各只启动一次且持续 32 秒|
|实际 OpenAL EFX|available=true、active_filters=7；停声五 tick 后源及滤波器释放|
|完整 build|FAIL：原有 testNativeBearing 在 NativeBearingChecks.java:42 的 `sustained carrier rotation holds every tested load before stopping` 断言；该轴承代码本次未修改|
|最终 build -x testNativeBearing|PASS：其余构建/注册检查与现有回归通过，最终 JAR 已重建|

日志：`build/tiger_i_fix_final_audio_checks.log`、`build/tiger_i_fix_server.log`、`build/tiger_i_fix_client.log`、`build/tiger_i_fix_build.log`、`build/tiger_i_fix_final_build.log`。实际客户端报告和预览随交付保存；源码 ZIP 的 verification 目录含日志和结果。不能把排除轴承测试后的成功写成完整 build 全部通过。

可复现命令：

```bat
gradlew.bat compileJava -I tools/tiger-i-checks.init.gradle testTigerIResources --offline --no-configuration-cache --console=plain
gradlew.bat build --offline --no-configuration-cache --console=plain
gradlew.bat build -x testNativeBearing --offline --no-configuration-cache --console=plain
python tools/extract_tiger_i_profile.py
python tools/audit_tiger_i_fix.py
python tools/render_tiger_i_reference_ab.py --native
python tools/package_tiger_i.py
```

`--native` 需要本机相同 bank、runtime 路径及 NumPy/soundfile 环境；哈希记录用于识别构建。缺少它们时只能生成明确标注 offline 的两模式 WAV，不能冒充原生结果。

## 13. 如何确认和切换模式

客户端配置文件为 `config/create_fire_control-tiger-i-sound.toml`。默认如下；建议改配置后重启客户端再试听。

```toml
[reference]
mode = "FMOD_REFERENCE"
layers = "FULL"
referenceMasterDb = 0.0
referenceRevvDb = 0.0
referenceStartStopDb = 0.0
climbFullAcceleration = 35.0
climbAttackSeconds = 0.12
climbReleaseSeconds = 0.65
loadswitchFullDemand = 25.0
revvRandomSemitones = 1.2

[spatial]
distanceScale = 1.0
cullDistance = 256.0
highFrequencyLossDb = -12.0

[debug]
enabled = true
```

`mode="LEGACY"` 切回旧混音。JVM 覆盖：`-Dcreate_fire_control.tigerSoundMode=FMOD_REFERENCE` 或 `LEGACY`。分层：`-Dcreate_fire_control.tigerSoundLayers=BASE_ONLY|WITH_CLIMB|WITH_REVV|FULL`，分别为七轨、七轨加 Climb、七轨加 revv、包含启停交接的完整混音；这里的竖线表示任选一个值。

初始化日志显示 `Tiger I audio mode=FMOD_REFERENCE layers=FULL`；debug 每秒输出 RPM、SU、Climb、loadswitch、权重、Pitch、线性 Gain、有效 dB、短音效、实例数和实际 soundManager 活动源数。TOML 的旧键保留：`mix.masterDb`、RMS/自定义短声类别和 SU 组配置用于 Legacy；Reference 用独立 Master/短声键、官方健康组值，但两模式共用 GUI 音量、loopsDb 和逐轨 trim，做对照时保持它们默认值。

## 14. 仍需实际试听和 Studio 验证的部分

已提供原生 bank 对照，而不是声称完整还原。仍未复制 FMOD 的多段 EQ、压缩/限幅、完整 sidechain、snapshot、耳机返回通道及战争雷霆 gameplay 遮挡。安装 bank 与固定 XML 的版本差异和 serialized randomizer 单位仍应通过相同版本 Studio 构建确认。方向曲线、高频衰减、Climb/loadswitch 的 MC 输入映射、启停声与循环的交接属于已说明的近似。

实际客户端测试覆盖静态循环、滤波器、资源和模型；还没有把所有动态加速、快速反复启停、多人多发动机和移动声源场景完整录制为端到端 Minecraft WAV。需要实际试听急/缓加速是否自然、Climb 与 revv 是否重复增强、交叉区间是否空/突响、60 秒稳定运行和移动 Sable 的空间听感。Native RPM-only 启停文件不用于原生启停验收。不要用统一 RMS 或额外 Master 补偿掩盖剩余音色/DSP 差异。

音频 A/B ZIP 含 30 个未逐文件归一化 WAV、完整测量 JSON 和 20 个 CSV；SHA256SUMS 覆盖安装包、源码、报告、预览和试听文件，可用来确认同一版本。
