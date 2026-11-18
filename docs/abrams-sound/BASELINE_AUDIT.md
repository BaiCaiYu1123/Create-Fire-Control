# Abrams 混音修改前审计（2026-10-09）

审计已先于生产代码修改完成。独立副本：`.diagnostics/abrams-mix-work`；原工作区另一个活动任务为“新增99A柴油发动机”。`baseline/` 保存此次开始时的四个相关文本文件，完整 `src` SHA-256 清单在副本的 `baseline-manifest.json`。这些是工作区的未提交版本，不是 Git HEAD；恢复时只能恢复声音管理器，不能覆盖仍在开发的注册表、JSON 或动力类。

设 `D(db)=10^(db/20)`，`u=clamp(GUI音量/100,0,3)`，`e` 为主包络；`r=100*clamp(abs(当前左右输出最大值)/配置最大速度,0,1)`。原负载输入是整个 Create 网络的 `clamp(stress/capacity,0,1)`，不代表单台发动机扭矩。声音控制器每 tick 将负载向目标推进最多 `0.4*0.05=0.02`，加速参数推进最多 `0.89*0.05`。

原循环混音系数：

```
normal_i = rpmWeight_i * D(-6.02) * D(i==0 ? 4 : 2)
           * D(calibration_i=0) * rpmCompensation * e * D(-8)
load_i   = loadWeight_i * D(-4.02) * D(calibration_i=0)
           * loadCompensation * e * D(-8)
instance_i = 0.15 * u * 4 * customDistanceLoop * max(mix_i,0.0001)
startInstance = 0.15 * u * customDistanceShot * (1/1.5) * D(-3)
stopInstance  = 0.15 * u * customDistanceShot * (1/1.5)
backendGain  = clamp(instance * Minecraft方块音量,0,1)
audibleSample = decodedPCM * backendGain * nativeDistance * Minecraft主音量
```

`sounds.json` 没有额外音量或 Pitch 设置（两者默认 1），循环原生 attenuation_distance=96，启停=192。`ModSounds` 注册固定范围64，用于事件范围语义，并不覆盖客户端 SoundEngine 使用的 JSON attenuation_distance。

自定义距离函数：`clamp((R-d)/(R-a),0,1)`，距离小于等于 `a` 时为1。循环 `a=3,R=max(3.001,32u)`，启停 `a=4,R=max(4.001,64u)`；u=0直接静音。基类 NONE 被两子类 LINEAR 覆盖，运行时确实双重衰减。SoundEngine 在声源创建时固定 OpenAL 最大距离 `max(instanceAtCreation,1)*JSON距离`，Channel 使用 AL_LINEAR_DISTANCE_CLAMPED，reference distance=0、rolloff=1；原生系数为 `max(1-d/R_native,0)`。GUI 100% 时常见 R_native 为96/192；这还会造成创建时音量不同引起衰减差异。

淡化区间：RPM 1–10、30–45、65–80；负载0.01–0.10、0.30–0.45、0.65–0.80。`s=t²(3-2t)`，原 fade=`clamp(s+shape*s*(1-s),0,1)`。补偿通过单轨 gain 的平方和估算功率，`clamp(sqrt((1-t)gA²+t*gB²)/sqrt((wAgA)²+(wBgB)²),1,D(3))`，没有计算素材自身 RMS、Pitch 后频谱或相关性。RPM与负载分别补偿，不会相乘成 +6 dB，但两组的信号仍叠加。

Pitch：`p=2^((minST+parameter*(-minST/root))/12)`，`minST=12log2(minPitch)`；再乘加速+0–3半音，负载轨另外乘0.05Hz、±2.5半音LFO；代码限制0.35–2，Minecraft实际限制0.5–2。固定负载也会有有意的LFO音高变化。单轨音量系数本身没有LFO。

包络：恢复已运行状态时直接1；新启动16.82秒音频的最后1.5秒线性引入循环；普通attack0.4秒、停机release2.1538457秒。启停不经过循环的×4、-8dB Master或Bus；启动单独-3dB，两者共用2/3平衡补偿。旧模式反复启停可能叠加尚未结束的one-shot。

素材已取得官方文件并实际解码：十个当前文件44100Hz单声道Vorbis；官方虽以`.wav`命名，内容是44100Hz双声道Vorbis，XML中的48kHz字段与下载文件不符，测量以解码结果为准。两声道等权平均后与当前素材时间对齐（最佳lag=0），所有相关系数>0.9996，拟合增益+0.0115～+0.0541dB。未发现有意义的额外音量、归一化或压缩处理证据；不能据此断言用户历史上从未处理过。怠速两轨RMS差距当前12.7174dB、官方mono12.7084dB。具体测量见后续报告的audio-audit.json。

官方事件负载Bus=-6.02dB，GainEffect静态+2dB被load自动化替代，曲线两个点为(0.0042372881355932203,-10)、(1,-4)，没有curveShape，因此在dB域线性；小于首点保持-10。原固定-4.02实际来自-6.02+2，遗漏自动化。官方还有距离、损坏、master距离Gain及加速Gain等控制；本次保持现有GUI距离范围，不照搬官方720米/1000米距离曲线。

engine_66/99 首尾差约0.359/0.366（当前PCM）；须单独审计接缝，未经确认不改音频。全套音量算法在Legacy中保持，包括tiny floor、手动×4、2/3、+3dB补偿及双重距离衰减。
