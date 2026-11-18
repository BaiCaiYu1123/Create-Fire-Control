"""Create the review report and static A/B plot from measured audio and Java traces."""
import pathlib,sys,json,csv,shutil,zipfile
sys.path.insert(0,str(pathlib.Path('.tools/plot-python').resolve()))
sys.path.insert(0,str(pathlib.Path('.tools/python').resolve()))
import numpy as np
import soundfile as sf
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt

out=pathlib.Path('build/dist/ztz99a-transition');audio=out/'audio'
metrics=json.loads((audio/'render_measurements.json').read_text())
package=json.loads((out/'package_audit.json').read_text())
gain=json.loads(pathlib.Path('build/reports/ztz99a-gain/summary.json').read_text())
scenarios=['idle_low','low_mid','mid_high','high_idle','fast_accel','slow_accel','fast_decel','su_sweep','long_loop']
fig,axes=plt.subplots(3,3,figsize=(16,10),constrained_layout=True)
for ax,name in zip(axes.flat,scenarios):
    for version in ['before','after']:
        data,rate=sf.read(audio/f'{name}_{version}.wav',always_2d=True)
        block=4410;length=len(data)//block
        rms=np.sqrt(np.mean(data[:length*block].reshape(length,block,2)**2,axis=(1,2)))
        ax.plot(np.arange(length)*.1,rms,label=version,linewidth=.8,alpha=.85)
    ax.set(title=name,xlabel='Time (s)',ylabel='100 ms RMS (same fixed scale)',ylim=(0,.1));ax.grid(alpha=.2);ax.legend(fontsize=8)
fig.suptitle('99A original recordings / actual Java mixer traces — no loudness normalization',fontsize=15)
fig.savefig(out/'ab_rms.png',dpi=150);plt.close(fig)

checks=out/'checks';checks.mkdir(exist_ok=True)
for source,dest in [('build/ztz99a-transition-build.log','full-build.log'),
                    ('build/ztz99a-audio-only-release.log','audio-only-release.log'),
                    ('run_ztz99a_audio/client/ztz99a-audio-client-result.txt','minecraft-decoder-and-loop.txt')]:
    shutil.copyfile(source,checks/dest)
with zipfile.ZipFile(out/'trace-data.zip','w',zipfile.ZIP_DEFLATED) as z:
    for p in pathlib.Path('build/reports/ztz99a-transition').glob('*.csv'):z.write(p,p.name)
    for p in pathlib.Path('build/reports/ztz99a-gain').glob('*'):
        if p.suffix in ['.csv','.json']:z.write(p,'gain/'+p.name)
    for name in ['take_analysis.json','native_pitch_probe.json','audio_analysis.json']:
        z.write(pathlib.Path('reference/ztz99a')/name,'reference/'+name)
def link(label,path):return f'[{label}]({path.resolve().as_posix()})'
def row(name):
    a=metrics['rendered'][name+'_before.wav'];b=metrics['rendered'][name+'_after.wav']
    return f'| {name} | {a["rms"]:.6f} / {b["rms"]:.6f} | {a["peak"]:.6f} / {b["peak"]:.6f} |'
text=f'''# 99A 柴油发动机声音过渡修复 · 0.8.1-ztz99a.3

已实施 99A 专用声音修复。{link('交付 JAR',out/'create-fire-control-0.8.1-ztz99a.3.jar')}，{link('原版本 JAR',out/'rollback/create-fire-control-0.8.1-ztz99a.2.jar')}，{link('原音效源码快照',out/'rollback/99a-audio-before.zip')}。

交付包 SHA256：`{package['sha256']}`。
这次源码工作期间 `GasTurbineSoundManager.java` 和 `AbramsFmodReference.java` 被外部修改，本轮没有写入它们，也没有覆盖这些修改。为满足禁止改动 M1A2 的要求，交付包从原 .2 JAR 生成，只替换 99A 声音类、声音定义、参考数据及内部版本号；其余 **{package['otherEntriesByteIdentical']} 项逐字节相同**。请使用本目录的交付 JAR；`build/libs` 的整包会包含并行工作区的其他改动。机械动力、左右输出、模型、贴图、碰撞、GUI、遥控代码和原始 OGG 在交付包中均保持原样。详见 {link('包范围审计',out/'package_audit.json')} 和 {link('源码范围审计',out/'scope_audit.json')}。

## 1. 原来生硬的原因

- 三条负载轨套用同一半音公式，遗漏各自 AutoPitch 的播放速率斜率、低轨 −2 半音和 `rpm_result` 映射；还把 SU 混入音高。高转速交叉时，两份录音的实际转速尤其不匹配。
- 用同一个 smoothstep/sin/cos 替换五条独立 FMOD Fade Curve，并在 RPM10 把怠速切掉。官方怠速轨没有 RPM 淡出，它按 load 衰减。
- 原声音为流式播放，却依赖 Java 估计音频进度，在结束前 0.35s 重建声源。解码起播时刻与估计时钟不一致；每轨重启还改变混合相位，并产生重复声音峰值。
- revv/release 随机选录音、固定 Pitch1，触发阈值使用未归一化 Create RPM，可能与当前循环层的音色和升降速度不符。

## 2. AutoPitch 与 FMOD

实际素材映射已通过官方 AudioFile XML 确认，不能按沿用旧车型的模块名称判断音轨。XML 加本机官方事件银行核验了 **34 个可听音轨/转速点**，误差在 0.00002 播放速率以内。

| 原始音轨 | Root | PitchAtMinimum（播放速率） | 固定偏移 | 可听参数区间的速率范围 |
|---|---:|---:|---:|---|
| engine_00 | 无 AutoPitch | — | 0 | 1 |
| engine_33_load | 30 | 0.704999983 | −2 半音 | 约 0.637～1.023 |
| engine_66_load | 55 | 0.779999971 | 0 | 0.900～1.100 |
| engine_99_load | 100 | 0.104999997 | 0 | 0.687～1.000 |

`r = clamp((Create归一化RPM − 0.141242938) × 100 / (100 − 0.141242938), 0, 100)`

`Pitch = (PitchAtMinimum + (1 − PitchAtMinimum) × r / Root) × 2^(固定半音/12)`。
这是播放速率的线性曲线，不能把 minimum 当成半音。SU 不参与音高。呈现用 RPM 保留 0.25s 平滑并加入官方 seek50 单位/s；Pitch 在对数速率域再平滑 0.12s，均不改变真实 Create 转速。

Minecraft 实际限制是 0.5～2.0。所有可听区间的原生速率都能直接表达；高轨在完全静音区的 0.105 不能直接表达，所以静音轨在可听边界预备音高，没有对可听曲线硬截断。怠速保持 Pitch1。

没有发现正常四循环轨的 Pitch LFO。事件 master 的 climb 0～+3 半音保持默认0，因为现有发动机没有独立 climb 音频输入，不能把它映射到 SU；broken 层的音高自动化也没有套入正常循环。原事件短音效随机5半音改成状态匹配。Fade 使用 257 点原生银行响应插值；银行版本可能与最新 XML 有细微差别。另加交叉能量补偿及 Minecraft 20Hz 参数平滑，所以不宣称与 FMOD 输出逐采样一致。

## 3. 每条 Gain 的计算

`轨道dB = masterDb + loopsDb + 原素材校准dB + 单轨trimDb + SU曲线dB + 20log10(有效RPM权重)`。

`线性音量 = 10^(轨道dB/20) × 启停包络 × 单次入声包络 × GUI音量 × 距离衰减 × 峰值保护`。零权重完全静音。

| 循环轨 | 静态校准 dB | RPM 权重 | SU 自动化 |
|---|---:|---|---|
| engine_00 | −3.609275 | 恒定1 | 官方 layer00：约 +2→−80dB |
| engine_33_load | −12.337225 | 独立1～10淡入、30～45淡出 | 官方 layer06：0→约+6dB |
| engine_66_load | −11.454137 | 独立30～45淡入、65～80淡出 | 同 layer06 |
| engine_99_load | −10.790818 | 独立65～80淡入 | 同 layer06 |

保留现有 1001 点 SU dB 曲线及独立实际负载输入。静态校准仍为 `min(2, 0.12/RMS, 0.6/peak)` 再转 dB。三条负载轨在交叉区共同乘以 `1/sqrt(Σ权重²)`，保持各轨官方曲线比例并补偿能量隆起；时间平滑后再保持负载组目标能量。怠速与低轨共存是官方结构，1～10区间会自然增加负载音色，并不强求两者的总能量恒定。

默认 master −12dB、loops 0dB、revv/release −14dB、blips −13.75dB、启停 −4dB。SU 平滑1.975s，Gain 平滑0.20s、最大24dB/s；GUI及距离规则保留。峰值保护按单个持续循环声源计算，取消原来每轨双声源预留；单发动机峰值预算0.8，包含短音效和 GUI 音量。

## 4. 循环与加减速短音效

26条声音定义改为 `stream:false, preload:true`。每条循环轨使用一个 OpenAL 静态 buffer 持续循环，取消 Java 在每次接缝前0.35s重建声源。入声只淡入一次0.22s；引擎启动末尾0.75s交接保留，关闭时0.5s释放。加入实际起播事件时钟、Sable 声音代理解包、资源重载和静音恢复检测，保留弱引用生命周期与子空间位置投影。

原始 OGG 没有覆盖、重编码或重新剪接。原文件循环边界的瞬时跳变约处于各自普通样本变化的99.92～99.93百分位；因此本轮采用稳定的连续循环，保留素材接缝。尤其 engine_66_load 的首尾 RMS 还有差异，周期性音色/响度变化需要实际试听，数据连续不能证明接缝听不出来。

所有 revv/release/blips 都测量了实际时长和五段谐波线索，见 {link('素材测量',pathlib.Path('reference/ztz99a/take_analysis.json'))}。这些是匹配线索，不能冒称可靠物理基频。选择考虑当前 RPM 的循环音色、变化方向、速度、录音轨迹及置信度；严重不匹配时宁可跳过。播放速率在0.85～1.15之间匹配入声，并保持该速率，让录音自身升降转速，避免持续追赶造成额外变调。匹配误差另加负 dB trim。

快速变速阈值12归一化RPM/s，慢坡主要由循环完成。入声0.22s、自然尾声0.45s；方向反转、变速停止或音色误差扩大时0.45s淡出。动态短音效互斥；保留5.5s冷却及配置并发上限，blip 至少12s冷却且只在一次运动结束后、状态匹配时触发，取消纯随机触发。

## 5. 配置和试听

客户端配置为 `config/create_fire_control-ztz99a-sound.toml`。在 `[takes]` 设置：

```toml
auditionMode = "LOOPS_ONLY"     # 纯四循环
# auditionMode = "LOOPS_REVV"  # 四循环 + revv
# auditionMode = "LOOPS_RELEASE" # 四循环 + release
# auditionMode = "FULL"        # 完整，包括允许的 blips
enableRevv = true
enableRelease = true
enableBlips = true
triggerRpmPerSecond = 12.0
```

`[mix]` 可调总 Gain/分类 Gain，`[trackTrimDb]` 可逐素材调 Gain，`[loadAutomation]` 可调曲线端点和形状，`[smoothing]` 可调 RPM、seek、Pitch、SU 和 Gain 平滑。已有配置会保留旧数值；如果旧文件的 `transientsDb` 仍是 −10，请在试听时试用本轮推荐的 −14。

{link('加速：修复前',audio/'fast_accel_before.wav')} / {link('加速：修复后',audio/'fast_accel_after.wav')}；{link('减速：修复前',audio/'fast_decel_before.wav')} / {link('减速：修复后',audio/'fast_decel_after.wav')}。九种场景均有 before/after，快速加减速及 low_mid 还提供四种模式文件。`FULL` 只包含状态匹配后实际触发的短音效。

所有 WAV 共用原 OGG、相同 master 和固定scale1，**未分别自动响度归一化**。before 的控制曲线取自回退 JAR 的真实 Java 混音器，after 取自修改后的真实 Java 混音器。旧循环重启及短音效启动在离线预览中假设零解码延迟；离线线性重采样也不等同于实际 OpenAL 输出。前后必须保持播放器音量相同。

## 6. 构建和数据检查

{link('最终交付 JAR 专项测试日志',checks/'audio-only-release.log')}：成功，含34个原生 AutoPitch点、1,002,001组RPM/SU检查、独立校准、单轨平滑、峰值预算及完整资源验证。九种动态场景均通过；固定RPM/SU的控制器长循环为180s，控制参数没有周期性重置。

稳定扫描最大估算 RMS 为 {gain['maxRmsAtGui100']:.6f}。极端GUI300%和假设两条短音效齐满音量时保护后峰值≤0.800001。快速加速最大Pitch/tick约0.02194，Gain/tick约0.02049；快速减速分别约0.02196和0.01824。固定RPM改SU时Pitch变化为0。完整轨道权重、Pitch、Gain和输出估计见 {link('CSV和参考测量压缩包',out/'trace-data.zip')}。

| 场景 | 实际离线混合 RMS：前 / 后 | 实际离线混合峰值：前 / 后 |
|---|---|---|
{chr(10).join(row(n) for n in scenarios)}

实际波形仍有素材自身的起伏与轨道间相关性，原生 Pitch 改变了录音的播放相位，因此不能用等功率公式推断每个100ms窗口相同。短音效叠加和循环输出的测量峰值都未超出预算，详见 {link('完整音频测量',audio/'render_measurements.json')}。图中使用同一绝对音量尺度，没有把两条曲线分别对齐。

![前后实际波形的100ms RMS]({(out/'ab_rms.png').resolve().as_posix()})

按要求执行了 `gradlew.bat build -Pmod_version=0.8.1-ztz99a.3 --offline --console=plain`。完整构建仍失败在既有 `:testNativeBearing`：`sustained carrier rotation holds every tested load before stopping`；没有修改该机械测试或掩盖失败。{link('完整 build 日志',checks/'full-build.log')}。打包及最终音频专用 JAR 检查成功。

实际 Minecraft/JOrbis 解码的26条时长与测量一致，最大差异不到1微秒。OpenAL 原生循环探针有成功起播事件，但32s验证时客户端总音量变成0并停止声音，所以该项未通过；不能据此声称完成真实持续播放测试。{link('客户端解码及循环结果',checks/'minecraft-decoder-and-loop.txt')}。

## 7. 需要进入游戏确认及回退

本轮没有真人游戏听感验证，也没有把离线数学检查当作“听起来已经自然”的证明。请在同一音量下，先用 LOOPS_ONLY 听1～10、30～45、65～80和高速回落，再分别加入 revv/release；固定RPM变SU、固定RPM/SU长时间循环、频繁反向变速和 Sable 子空间中移动的发动机还需要游戏确认。重点听周期接缝、拍频、旧配置Gain是否过高，以及短音效与稳定转速的衔接。

安装时先退出游戏，只保留一个 Create Fire Control JAR，使用本目录的 .3 音频专用包。需要回退时用 rollback 中的 .2 JAR 替换，并保留/恢复自己的99A声音配置。原源码音效快照和本次对照数据都已保存。未替换用户实际游戏实例的 JAR。
'''
(out/'修复说明.md').write_text(text,encoding='utf-8')
print((out/'修复说明.md').resolve())
