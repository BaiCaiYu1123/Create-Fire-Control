# Abrams 音效修复报告

最新运行版本与七种工况复查见 [RUNTIME_AUDIT.md](RUNTIME_AUDIT.md)：当前游戏加载的旧 JAR 仍默认 Legacy；已补充可关闭的实际提交音量/资源来源日志及仅替换 Abrams 类的交付包。

已在独立工作副本审计、实现及验证。2026-10-09 默认已切换为 **FMOD Reference**，保留 Legacy 回退；没有重编码或改写任何 Ogg，没有修改 M1A2 动力、左右输出、遥控、驾驶、GUI/NBT 或 99A 生产文件。

修改前全部因素和公式见 [BASELINE_AUDIT.md](BASELINE_AUDIT.md)；原管理器、动力类、注册表和 JSON 文本快照在 [baseline/](baseline/)。Legacy 保留 ×4、2/3、启动 -3dB、全部 calibration=0、原 Fade 算法、+3dB补偿、tiny floor 和双重距离衰减；有效输入下 Gain/Pitch/Fade 已与旧版本逐位比对。

## 2026-10-09 默认模式复查

复查时，源码与 A/B PowerShell 脚本都默认 Legacy。现已将 `AbramsFmodReference.DEFAULT_MIX` 设为 `fmod_reference`，`GasTurbineSoundManager` 使用该默认值；A/B 脚本默认值同步更新，两个 Gradle init 脚本不再在缺少模式参数时强行注入 Legacy。

动态负载 Gain 的实际路径为 `updateMix → loadBusGain(smoothedLoad) → calculateLayerMix → volumes[4..7] → TurbineLoopSound.tick`。Reference 不再乘旧固定 `LOAD_BUS_GAIN`，RPM/load 交叉补偿均为 1；保留的 ×4、0.15、GUI 与 Master 等比例不随负载变化，不会抵消 Gain 自动化。没有修改既有混音公式。

验证脚本增加无 JVM 模式参数的启动路径，并直接读取真实管理器的 `FMOD_REFERENCE` 标志。还验证 load=0.85 与 load=1 在同一条 load99 音轨上的实际输出增益比：Reference 遵循自动化，Legacy 保持 1；最终负载增益绝对值也检查没有重复乘旧 Bus 或补偿。无参数默认、显式 Legacy、显式 Reference 三组均通过，每组覆盖 20,402 个混音组合。隔离副本另有 1,018 个源代码/资源文件的 SHA-256 与修改前一致，包括 M1A2 动力、99A 与音频。

本轮 `compileJava --offline --no-configuration-cache` 通过；完整 `build --offline --no-configuration-cache` 再次复现原有 `testNativeBearing` 的 `sustained carrier rotation holds every tested load before stopping` 失败。随后 `build --offline --no-configuration-cache -x testNativeBearing` 通过，包括本轮快照中的 99A 资源检查。没有修改轴承测试或动力代码。无参数默认与显式 Reference 的逐轨 CSV 文件完全一致。

默认 A/B 启动脚本的 `-DryRun -Offline` 通过。新 JAR 为 `.diagnostics/abrams-default-work/build/libs/create-fire-control-0.8.1-ztz99a.2.jar`；用 `javap` 核实打包后的管理器默认字符串确为 `fmod_reference`，并对 JAR 内十个 Abrams Ogg 逐一核对 SHA-256，均与源文件一致。

本轮仅修改 8 个文件：`GasTurbineSoundManager.java`、`AbramsFmodReference.java`、`AbramsSoundMixChecks.java`、`tools/run_abrams_sound_ab.ps1`、`tools/abrams-sound-ab.init.gradle`、`tools/abrams-sound-checks.init.gradle`、`tools/verify_abrams_sound.ps1` 及本报告。使用当前工作区的独立快照 `.diagnostics/abrams-default-work/` 编译，日志位于其 `validation/` 目录。已运行中的客户端需加载新 JAR 并重启才能使用新默认值，尚未进行本轮游戏内试听。

## 官方来源与素材审计

来源固定为 [Gaijin 官方工程 07fd12efffbf](https://github.com/GaijinEntertainment/fmod_studio_warthunder_for_modders/tree/07fd12efffbf20b2053d630ed5cf7ca3ddfa54a6)，[车外 engine 事件](https://github.com/GaijinEntertainment/fmod_studio_warthunder_for_modders/blob/07fd12efffbf20b2053d630ed5cf7ca3ddfa54a6/Metadata/Event/%7B2503c204-9bba-4a87-ab7a-8c04fc67ac34%7D.xml)。通过 SingleSound→AudioFile→assetPath 核对八轨均为 update/engine_*，没有使用 *_inside。十个下载文件还通过官方 Git blob SHA-1 检查。结构摘录见 [fmod-evidence.json](fmod-evidence.json)。

实测当前文件都是44.1kHz单声道Vorbis；官方 `.wav` 文件实际也是Vorbis容器，44.1kHz双声道。AudioFile XML 的48kHz是元数据，不能用它代替实际采样率。统一成 `mono=(L+R)/2` 后再比较。原生立体声的RMS/LUFS与单声道读数不能直接混用；完整原生和mono指标均保存于 [audio-audit.json](audio-audit.json)。

以下RMS、sample peak和LUFS统一为mono；RMS和sample peak单位dBFS。Crest=sample peak−RMS；DR100=P95−P10的100ms块RMS差，它是本次明确定义的动态范围指标，不是EBU LRA。短循环不宜据此宣称标准LRA。峰值不是过采样true peak。

| 文件 | 当前/官方秒 | 当前/官方mono RMS | 当前/官方mono Peak | 当前/官方mono LUFS | 当前/官方 Crest | 当前/官方 DR100 | 拟合Gain差 | 波形相关 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| engine_00 | 8.402721/8.400000 | -24.134/-24.171 | -11.998/-12.022 | -25.024/-25.062 | 12.136/12.149 | 3.682/3.690 | +0.0367 dB | 0.999829 |
| engine_00_load | 8.402721/8.400000 | -11.417/-11.462 | -0.650/-0.665 | -13.212/-13.258 | 10.767/10.797 | 3.804/3.808 | +0.0466 dB | 0.999925 |
| engine_33 | 9.601451/9.600000 | -14.161/-14.184 | -1.806/-1.850 | -14.686/-14.709 | 12.354/12.334 | 2.240/2.219 | +0.0224 dB | 0.999777 |
| engine_33_load | 9.601451/9.600000 | -11.271/-11.310 | -0.143/-0.242 | -12.031/-12.065 | 11.128/11.068 | 2.592/2.576 | +0.0385 dB | 0.999869 |
| engine_66 | 7.201088/7.200000 | -15.726/-15.742 | -2.963/-2.979 | -15.902/-15.917 | 12.763/12.763 | 1.496/1.493 | +0.0139 dB | 0.999701 |
| engine_66_load | 7.201088/7.200000 | -13.375/-13.411 | -0.781/-0.761 | -13.035/-13.070 | 12.594/12.650 | 1.007/0.991 | +0.0355 dB | 0.999834 |
| engine_99 | 9.000635/9.000000 | -16.357/-16.371 | -2.843/-2.832 | -16.229/-16.242 | 13.513/13.539 | 1.220/1.224 | +0.0115 dB | 0.999651 |
| engine_99_load | 9.000635/9.000000 | -11.946/-11.978 | -0.529/-0.618 | -12.082/-12.113 | 11.417/11.360 | 1.925/1.929 | +0.0307 dB | 0.999811 |
| engine_start_load | 16.821406/16.800000 | -9.275/-9.324 | 0.010/-0.058 | -10.466/-10.546 | 9.285/9.265 | 15.729/15.708 | +0.0541 dB | 0.999943 |
| engine_stop_load | 14.249796/14.233560 | -9.434/-9.481 | 0.005/-0.006 | -10.836/-10.887 | 9.439/9.475 | 10.812/10.815 | +0.0517 dB | 0.999941 |

各文件最佳时间偏移均为0采样；拟合增益只有+0.0115～+0.0541dB，100ms分块的增益也基本一致，残差约低于源信号31～39dB。**没有确认曾额外修改过音量，也没有发现有意义的放大、归一化、压缩或硬削波证据**；这些差异与立体声转mono后再次有损编码吻合，不能从编码格式或近0dB峰值反推处理历史。启停文件少量>1的decoded sample是很小的有损编码过冲，不构成平顶削波证据。

`engine_00_load−engine_00` 的RMS差当前12.7174dB、官方mono12.7084dB，差距来自官方素材。单声道转换改变的是声道能量关系，不是额外单轨音量补偿。没有采样率转换的必要；审计脚本在不同采样率时使用resample_poly再对齐。

## 新模式公式与范围

设 `D(d)=10^(d/20)`、`x0=0.0042372881355932203`、`l`为已经以0.4/秒平滑的声音负载：

```text
q = clamp((l-x0)/(1-x0),0,1)
loadEffectDb(l) = -10 + 6*q
loadBusGain(l) = D(-6.02 + loadEffectDb(l))
climbGain(c) = D(3*clamp(c,0,1))
normal_i = wRPM_i * D(-6.02) * D(i==0?4:2) * D(calibration_i) * envelope * D(-8) * climbGain
load_i = wLoad_i * loadBusGain(l) * D(calibration_i) * envelope * D(-8) * climbGain
loopInstance_i = 0.15 * GUI/100 * 4 * customDistanceLoop * max(mix_i,0)
```

两个官方load点均没有curveShape，因而准确实现dB线性插值，首点以外保持端点值。GainEffect的静态+2dB被automation设置替代；没有额外乘D(+2)，也没有再乘旧D(-4.02)。低/高负载总Bus Gain为-16.02/-10.02dB（0.1581248/0.3155005）；相对于旧固定-4.02，分别低12/6dB。新的climb master Gain对应官方第二个GainEffect的(0,0dB),(1,3dB)，与原有加速Pitch独立。

循环×4、0.15总比例、正常轨+4/+2dB、-8dB Master和启停2/3与启动-3dB都保留。启停公式不变，只在Reference中使用单一自定义距离衰减；反复启停限制一个当前one-shot和一个100ms淡出尾音，防止十几秒旧样本不断叠加。静音循环可以先创建，Reference无tiny floor，固定八轨不随RPM/负载变化重新分配；Minecraft Master/BLOCKS静音后可在解除静音时重建八轨。

默认负载依旧是整个Create网络利用率，只是声音代理值，不是单机输出扭矩。多人、多发动机共享网络、惰转和输入切换时尤其不能把它当真实发动机扭矩。保留该代理避免引入未经标定的新动力解释；可用声音层debug_load固定输入做A/B，不发送任何动力包、不修改SU。

## 与完整官方FMOD仍有的差异

- Reference补全了load及climb Gain。官方还有Distance对两组fader和master Gain的自动化、故障相关曲线、项目级输出路由/预设效果等；没有照搬720/1000米距离行为，而保留现有GUI范围和基础Bus/Master调校。因此Reference是受当前模组约束的对照混音，不是整个战争雷霆事件的逐样本复刻。
- 原八轨FadeCurve端点/shape字段、AutoPitch root/min和LFO都保留。现有`smoothstep + shape*s*(1-s)`是原代码的近似函数；FMOD公开文档描述shape方向，但未给出其内部映射公式。没有把它宣称为准确FMOD曲线，精确shape仍需FMOD Studio导出采样或参考bank测量。官方Gain自动化这次读取到的两条曲线是无shape的直线，已准确实现。
- 原AHDSR以dB定义的attack/release与当前线性振幅包络不是同一个函数；启动16.82秒/最后1.5秒交接是模组已有呈现逻辑，本次保持。
- Pitch最低值仍受Minecraft的0.5下限限制（原管理器虽然设0.35，实际后端仍会限制）；载荷LFO为原有0.05Hz、±2.5半音，所以固定参数时音高仍会自然变化，不能将其误认作Gain随机波动。

FMOD语义依据：[Automation设置属性值](https://qa.fmod.com/t/using-a-parameter-to-automate-another-parameter-makes-it-read-only/21114)、[多个dB曲线相加](https://qa.fmod.com/t/documentation-on-automation-curve-combination/18171)、[Fade shape定义](https://www.fmod.com/docs/2.04/studio/scripting-api-reference-project-model-sound.html)。

## 交叉区与音量数据

Reference的RPM和load补偿均为1。原+3dB上限没有被删除，仍只在Legacy运行。源码旧补偿忽略素材自身RMS，不能据它认定官方需要补偿。实测素材的能量和原始曲线可能造成交叉区内平滑的谷值；为避免把官方素材差异再归一化，本次没有强行拉平。两组分别计算，不会出现RPM补偿×load补偿的乘法叠加。

以下是距离0、GUI100%、Minecraft Master/BLOCKS100%、包络1、加速0、LFO基准音高时的**无相关性总RMS估计**：`sqrt(sum((gain_i*sourceRMS_i)^2))`。这是能量模型，不是实际声卡总音量，实际混音受相位相关、Pitch和频谱影响。GainSum也只是系数和，不当作最终响度。

| RPM/load | Legacy RMS估计dBFS | Reference RMS估计dBFS | 差值dB |
|---|---:|---:|---:|
| 0/0 | -27.520 | -36.175 | -8.655 |
| 0/1 | -28.006 | -33.000 | -4.994 |
| 5/0.05 | -27.153 | -36.443 | -9.290 |
| 50/0.5 | -27.840 | -31.334 | -3.495 |
| 100/1 | -27.061 | -30.526 | -3.465 |
| 5.5/0.055 | -26.900 | -35.752 | -8.853 |
| 37.5/0.375 | -26.539 | -33.224 | -6.685 |
| 72.5/0.725 | -27.606 | -34.242 | -6.636 |

[legacy-mix.csv](legacy-mix.csv) 与 [fmod_reference-mix.csv](fmod_reference-mix.csv) 包含八条轨的Gain、代码Pitch、后端Pitch和总能量估计，覆盖稳定场景、同时交叉区、加速、减速、启动交接、停机释放。CSV的Gain是GUI100%/近距离循环系数；为便于基线比较，Legacy未选轨的0.0001 floor未计入CSV（公式审计单独包含），估计误差很小。

验证覆盖每种模式20,402个RPM/load/climb组合；边界在RPM±0.0001、load±0.000001两侧检查，单轨Gain差不超过1e-5，没有Gain不连续跳变。GUI300%所有单源系数<1；Reference最坏保守峰值相加上界0.8180（约-1.74dBFS），不依赖相位取消。Legacy保守上界1.0966，说明旧配方在极端同相假设下没有这一保证；实际诊断PCM未出现超限，没有为了通过测试改变旧模式。

额外生成了两份89.25秒的**离线诊断PCM试听**（实际Java Gain/Pitch、原素材、LFO、启停，不是OpenAL或FMOD录音），无归一化、无削波：Legacy峰值0.2786，Reference0.1941；总体RMS -27.114/-30.511dBFS。位于 `.diagnostics/abrams-mix-work/reference/{legacy,fmod_reference}-preview.wav`。时序：五种稳定工况各4秒→10.05秒加速→10.05秒减速→16.9秒启动交接→14.25秒停机→18秒反复启停。人耳偏好仍需实际游戏验证。

## 循环接缝：单独建议，未修改素材

| 文件 | 当前首尾差 | 普通相邻采样最大差 | 官方mono首尾差 | 额外解码采样 | 按官方帧数结束时当前首尾差 |
|---|---:|---:|---:|---:|---:|
| engine_66 | 0.359387 | 0.150640 | 0.032874 | 48 | 0.032105 |
| engine_99 | 0.366246 | 0.175715 | 0.004957 | 28 | 0.005514 |

两处接缝都超过文件内部所有普通采样跳变，OpenAL静态buffer的硬循环会重复这处宽带瞬态，有产生周期咔哒声的风险。当前循环比官方多48/28采样；按官方长度取结束点时跳变降到0.0321/0.00551，明显支持尾部编码/解码长度问题，不支持整段压缩或音量处理。最终是否可听取决于播放Pitch、遮蔽和混音，不能只凭差值宣称一定爆音。

建议优先检查Ogg最后granule position与有效PCM长度，尝试无重新编码的重封装/长度修正；若长度修正仍不够，试听5–10ms运行时头尾交叉淡入或零交叉点。普通SoundInstance只有每50ms音量更新，不能准确修复约0.6–1.1ms的尾帧问题；实现样本级运行时交叉需要专用AudioStream/解码缓冲支持，不能把每次循环重建声源当作可靠方案。未经用户确认，不执行重封装、删帧或重编码；这些建议与Gain修复独立。Vorbis不能声称可像PCM一样直接剪切采样后仍无损编码。

## 距离验证

检查Minecraft 1.21.1 SoundEngine/Channel源码确认：子类LINEAR覆盖基类NONE；SoundEngine把JSON距离×max(创建时音量,1)传给OpenAL，原生线性衰减随后又乘自定义衰减。Reference仅把两子类attenuation设NONE；relative=false继续保留定位，GUI自定义范围不变，JSON/注册项不改。

GUI100%循环在16块：旧自定义16/29≈0.5517，再乘原生(1−16/96)=0.8333得到0.4598；Reference只用0.5517。24块旧0.2069、新0.2759；32块两者静音。启停32块旧(32/60)*(1−32/192)=0.4444，新0.5333；64块两者静音。移除双重衰减会让范围内远处更响，这是修复乘法重复的结果，未扩张静音边界。

## 验证与人工试听

- `gradlew.bat compileJava --offline`：通过。
- 首次审计的 `gradlew.bat build --offline`：失败在原有 `testNativeBearing`：`sustained carrier rotation holds every tested load before stopping`。当时隔离副本临时恢复旧管理器重跑同一测试，完全复现同一失败；恢复新代码后 `build --offline -x testNativeBearing` 通过。没有修复或跳过而声称完整build全绿。
- `tools/verify_abrams_sound.ps1 -Offline`：无参数默认和两个显式 JVM 模式都通过；默认选择、最终负载 Gain、旧混音逐位回归、官方端点/中点/Seek、RPM/load同时交叉、有限值、GUI300%系数、真实控制器启停包络与反复启停循环检查通过。
- 十个素材实际解码、波形比较；八轨注册路径存在；没有写入音频。独立副本所有原有src文件仅声音管理器发生变化；99A/柴油文件、M1A2动力类和十个Abrams Ogg的哈希与快照一致，见 [protected-sha256.json](baseline/protected-sha256.json)。主工作区的99A仍可被另一个对话独立更新。
- A/B启动脚本dry-run通过。没有完成游戏内人耳试听；八个真实OpenAL声源的可听效果、低FPS参数阶梯、移动舰体定位、资源重载、类别静音恢复以及100ms中断尾音仍应在客户端实测。调试日志输出每轨最终Gain估计、Pitch、RMS估计、分配数和实际playing数，帮助核对八轨。

人工A/B应在同一存档、相同GUI百分比、相同距离、相同Minecraft音量下比较：怠速0/高负载、低速低负载、中速中负载、高速高负载、平滑加速/减速、启动/关闭/反复启停。不要通过提高GUI来抵消Reference差异后再宣称两者相同。接缝、启停主观衔接、LFO和受网络负载代理影响的表现重点听取。

## 切换与复现

普通启动器的客户端JVM参数：

```text
-Dcreate_fire_control.abrams.mix=fmod_reference
```

一键切回旧混音：改为 `-Dcreate_fire_control.abrams.mix=legacy` 并重启客户端；直接移除参数则默认 FMOD Reference。默认值在 `AbramsFmodReference.DEFAULT_MIX` 中定义，由 `GasTurbineSoundManager.REQUESTED_MIX` 读取。模式只在客户端类首次加载时读取，修改源码后需加载新构建并重启客户端，不写NBT，不改存档。

开发客户端快捷启动（两模式共用run_abrams_ab）：

```powershell
.\tools\run_abrams_sound_ab.ps1 # 默认 FMOD Reference
.\tools\run_abrams_sound_ab.ps1 -Mix fmod_reference
.\tools\run_abrams_sound_ab.ps1 -Mix legacy
# 声音层固定负载，不改变动力网络：
.\tools\run_abrams_sound_ab.ps1 -Mix fmod_reference -Load 0.5
.\tools\verify_abrams_sound.ps1 -Offline
.\tools\fetch_abrams_reference.ps1
# Python环境需要 numpy、soundfile、scipy、pyloudnorm：
python tools/audit_abrams_sound.py --reference .diagnostics/abrams-reference --output .diagnostics/abrams-reference/audio-audit.json
```

可直接加 `-Dcreate_fire_control.debug_engine_sound=true` 开启每0.5秒日志，`-Dcreate_fire_control.abrams.debug_load=0.5`用于固定测试输入；不提供load参数即恢复网络代理。日志rmsEstimate采用此次捆绑素材RMS，在资源包替换素材时只作参考，不是响度归一化。PowerShell脚本可用 `-ProjectRoot` 指向隔离副本；客户端模式参数必须进游戏JVM，不能只用Gradle自身的 `-D`。

## 本次文件

生产：`src/main/java/.../client/GasTurbineSoundManager.java`、新增 `AbramsFmodReference.java`。测试：新增 `src/test/java/.../client/AbramsSoundMixChecks.java`。工具：`audit_abrams_sound.py`、`fetch_abrams_reference.ps1`、`verify_abrams_sound.ps1`、`abrams-sound-checks.init.gradle`、`run_abrams_sound_ab.ps1`、`abrams-sound-ab.init.gradle`。文档/数据：本目录报告、基线快照、protected SHA、audio/fmod JSON与两份逐轨CSV。`build.gradle`、`gradle.properties`、`sounds.json`、`ModSounds`及所有99A文件均没有回写。

完整原始下载、基线源码清单、构建日志、诊断试听在忽略的 `.diagnostics/abrams-mix-work/`；它仍可作为可恢复工作副本使用。没有切换分支、reset、clean或覆盖别人的未提交代码。
