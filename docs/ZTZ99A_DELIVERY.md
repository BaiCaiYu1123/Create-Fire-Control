# 99A 柴油发动机交付与验证记录

交付版本：Minecraft Java 1.21.1 / NeoForge / Create Fire Control `0.8.1-ztz99a.2`（材质缓存修复版）。新增注册 ID 为 `create_fire_control:ztz99a_diesel_engine`，中文名称为“99A 柴油发动机”。当前交付包位于 `build/dist/ztz99a/create-fire-control-0.8.1-ztz99a.2.jar`，SHA256 见同目录 `SHA256-texture-fix.txt`。客户端与服务端均需使用此包；网络协议仍为 27。

2026-10-09 材质问题：用户实例安装包 SHA256 与此前 Gain 交付包一致，模型、PNG、MTL、26 个 OGG 均存在于 JAR 内。然而 Lightspeed `1.21.1-2.0.2hotx2` 在 `verifyJarHash=false` 时按模块名、版本、JAR 文件名复用资源清单；实例的 `resourceLists/create_fire_control0.8.1-create-fire-control-0.8.1.lsc` 包含旧 M1A2 资源，却没有任何 `ztz99a` 路径。实际客户端日志报告 72 个方块状态缺模型、物品模型 FileNotFoundException 和 26 个 OGG 不存在。因此更新包的内部版本号，使它即使被改名为旧文件名也不会复用旧清单。同时将几何元数据移至 `assets/create_fire_control/ztz99a/geometry_manifest.json`，避免 Minecraft 误将它当作模型反序列化。

以后若原地替换同版本同文件名 JAR，可将实例 `config/lightspeed-common.toml` 的 `startup.verifyJarHash` 设置为 `true`，让缓存按文件内容校验；变更后需要重启。此次修复包通过内部版本变更解决问题，不要求修改该配置。旧 Gain 包保留在交付目录用于对照。

缓存回归验证：将用户旧 `.lsc` 清单复制到隔离目录，并仅将缓存文件名映射为开发客户端实际主路径名 `dieselIntegration`。保持版本 `0.8.1` 时，客户端复现同样的缺模型和 26 个 OGG 不存在警告，检查因 `visible baked quads` 失败。这是预期失败对照，不代表修复版构建失败。保留旧缓存，以新的内部版本运行同一检查；日志分别位于 `build/ztz99a-cache-old-client.log` 和 `build/ztz99a-cache-fixed-client.log`。客户端检查使用开发源集；发布 JAR 另用 `testZtz99aPackagedResources` 检查真实包内资源与材质绑定。修复包与之前 Gain 包的内容差异仅为 `META-INF/neoforge.mods.toml` 版本和几何元数据路径；所有主代码、模型、PNG 与 OGG 字节均相同。

修复版客户端检查 PASS：72 个四方向分片状态、物品模型的纹理图集 sprite、26 条音轨的运行时资源查询与事件、独立声音配置与 GUI；99A 的资源缺失和几何元数据模型解析错误均消失。截图为 `build/dist/ztz99a/ztz99a_texture_fix.png`。`compileJava testZtz99aDieselResources testZtz99aPackagedResources assemble --offline` PASS。经用户退出游戏并授权后，已安装至 `D:/Game/PCLII/.minecraft/versions/Create航空学/mods/create-fire-control-0.8.1.jar`（保留原文件名，内部版本已更新），旧包备份至同实例 `codex-backups/ztz99a-texture-fix-20261009/create-fire-control-0.8.1-before-texture-fix.jar`；安装后 SHA256 与交付包一致。

已实现独立模型、材质、物品、声音、设置消息及双动力输出。遥控输入、RPM 上限、加减速、换向经过零点、GUI、绑定与 NBT 继续复用现有 M1A2 实现。M1A2 声音管理器的算法和 Gain 数值未因 99A 修改。

## 模型与接口

模型来源为用户提供的 `150HB-.bbmodel`，155 个几何元素、512×256 原贴图。截图的两箭头指向 `chuan_dong_ji_gou` 传动机构的左右侧面。模型没有命名的轴孔或轴心元素，因此采用该机构实际枢轴 `(0, 7.2361, 16.76123)` 确定 Y/Z 轴线；两侧主壳体表面为 X=±18.5。此轴线基于模型几何，仍需最终游戏内视觉确认它与作者预期的机械接口一致。

仅平移 `[8, 0.7639, -8.76123]` 像素，比例始终为 1；烘焙原有嵌套组旋转和 UV。新模型占 3×3×2，共 18 格。保留原比例所需的真实格数超过 M1A2 的三格结构，因而同步调整了控制格、端口 BE、连接判断、动力传播与碰撞。

面朝 NORTH 时，相对控制格：

| 几何位置 | 左侧 | 右侧 |
|---|---|---|
| 原模型壳体侧面轴线点（像素） | `(-18.5, 7.2361, 16.76123)` | `(18.5, 7.2361, 16.76123)` |
| 转换后的壳体轴线点（像素） | `(-10.5, 8, 8)` | `(26.5, 8, 8)` |
| 实际输出格（方块） | `(-1, 0, 0)` | `(1, 0, 0)` |
| 向外连接面中心（像素） | `(-16, 8, 8)` | `(32, 8, 8)` |
| 输出朝向 | WEST | EAST |

使用 Create 原版 `SHAFT_HALF`；其 8 像素长轴根部与外壳重叠 2.5 像素，轴心与相邻标准传动轴中心重合。端口仅暴露向外轴面，发动机内部不连接两个机械网络。端口各自作为普通 Create 发电源传播转速、提供基础 64 SU 容量。柴油机跳过 M1A2 可变传动比 Mixin；通过基类获得已有 flicker、误拆及相关兼容保护，没有扩大到其他 Create 方块。

| 发动机朝向 | 左输出格／朝向 | 右输出格／朝向 |
|---|---|---|
| NORTH | `(-1,0,0)` / WEST | `(1,0,0)` / EAST |
| EAST | `(0,0,-1)` / NORTH | `(0,0,1)` / SOUTH |
| SOUTH | `(1,0,0)` / EAST | `(-1,0,0)` / WEST |
| WEST | `(0,0,1)` / SOUTH | `(0,0,-1)` / NORTH |

以上为放置、整组结构旋转后的坐标；扳手交互继续用于原有信号处理器绑定。碰撞按原几何生成 1/16 方块分辨率的保守轮廓，每格均裁剪到本格内，并支持四个方向。原始几何、实际碰撞、物品中心分别管理。

## Gain Automation

参考来源：[Gaijin 官方 ztz99/engine XML](https://github.com/GaijinEntertainment/fmod_studio_warthunder_for_modders/blob/master/Metadata/Event/%7B63385eb2-f16b-4574-9740-3f308964a659%7D.xml)。通过 EventMixerGroup → volume Automator → AutomationCurve → AutomationPoint 解析，资源 `gain_reference.json` 保留原 UUID、端点、curveShape 与 XML 的 SHA256。

load 参数 UUID 为 `{5ef70fb5-a09f-4cf2-bf00-99ac247b0865}`。实际取值：

| 曲线 | 起点 | 终点 | 起点 curveShape |
|---|---|---|---|
| layer00 | load=0，+2 dB | load=1，−80 dB | `0.822146177` |
| layer06 | load=0，0 dB | load=`0.9971751412429378`，+6 dB | `−0.450167418` |

**默认曲线采用原生 FMOD 响应采样。** 在本地 War Thunder bank 中找到相同事件 UUID，固定 rpm=33、speed=0、building_density=0、broken=0、climb=0、volume_ducking=0，仅改变独立的 load 参数；通过 FMOD 原生 API 读取唯一的递减音量总线及递增音量总线。分别采样 1,001 个 load 点。原生怠速总线起点包含固定混音基线，测得 +2.370392696 dB，因此移除／归一该固定基线和区间，将响应映射到官方 XML 的 +2／−80 dB；负载总线直接为 0／+6 dB。以 `native_load_response.json` 保留原始来源哈希和归一响应。

默认 shape 使用原生采样表在 **dB 域**作分段插值；表的归一位置映射到 XML 起点／终点（layer06 在 0.997175141 后保持 +6 dB）。Java 的 2,002 个参考采样点均与此原生响应数据逐点核对，误差小于 1e−5 dB。运行时只读取已生成的 Java 表，不依赖 FMOD DLL 或本地 War Thunder 安装。

**精度边界：这是原生 bank 响应经固定基线和 XML 端点标定后的采样曲线，不是重新编译该 XML 事件得到的逐样等价实现。** 采样间隔为 load=0.001。来源 bank SHA256 为 `d5d55f1bc0ebdf91f972b680cf6cb2452b2e7ff39ccf934b8f8c42ee3a527e3b`。用户手动修改 curveShape 时，[FMOD 公开文档](https://www.fmod.com/docs/2.04/studio/scripting-api-reference-project-model-sound.html)未提供精确形状公式，故自定义 shape 作连续的单调近似：shape 从原版值调向零时，在原生响应和直线 dB 之间插值；增强时以指数函数扭曲原生表的参数；反向形状使用单调指数函数。shape=0 时为直线 dB。细微调节不会突然切换到另一条不相关曲线。默认两个原版 shape 始终使用原生表；修改端点会缩放这份原生响应。所有曲线超出端点时保持端点值，不使用线性振幅混合。

| SU load | 怠速自动化 dB | 负载自动化 dB |
|---|---:|---:|
| 0 | +2.000 | 0.000 |
| 0.25 | +1.541 | +3.418 |
| 0.50 | −0.377 | +4.974 |
| 0.75 | −8.383 | +5.682 |
| 1 | −80.000 | +6.000 |

**两项输入互相独立。** RPM 为左右实际已应用输出绝对值的最大值，除以当前配置的最大输出转速后映射到 0～100。SU 来自每个输出格实际 Create `stress/capacity`，限制到 0～1；声音使用两侧负载率的较大值，反映较重负载侧。一侧停止时不以零 RPM 伪造另一侧负载。两个端口都不可提供有效容量时 load=0。并行外部发电机存在时，这是所在机械网络的使用率；Create 未提供逐台发电机的负载分摊。

**循环音轨公式。** 第 i 条音轨的目标 dB 为：

`D_i = masterDb + loopsDb + measuredCalibrationDb_i + trackTrimDb_i + loadCurveDb_i(SU)`

`voiceVolume = RPMWeight_i × 10^(smooth(D_i)/20) × engineEnvelope × seamEnvelope × GUIVolume × distanceGain × headroomGain`

其中：

- `engine_00` 使用 layer00 负载曲线；静态校准约 −3.609 dB。
- `engine_33_load` 使用 layer06 曲线；静态校准约 −12.337 dB。
- `engine_66_load` 使用 layer06 曲线；静态校准约 −11.454 dB。
- `engine_99_load` 使用 layer06 曲线；静态校准约 −10.791 dB。
- 默认 `masterDb=−12`、`loopsDb=0`，所有单轨 trim=0。RPM 重叠区为 1～10、30～45、65～80，以 smoothstep 后的 sin/cos 权重作等功率交叉淡入淡出。三个负载层的校准 RMS 相同，所以它们之间换层不会因原录音响度不同而产生增益峰值。
- RPM 时间常数 0.25 秒，SU 时间常数 1.975 秒；目标 dB 再以 0.20 秒时间常数和每秒 24 dB 最大变化量平滑。GUI 音量另以 0.15 秒平滑。这些只影响声音呈现。
- 保留现有 99A pitch 算法、测量长度的启停衔接、Sable 位置变换、距离衰减和声音生命周期。启动音长 3.64517 秒，末尾 0.75 秒交接循环；不延迟 Create 动力。
- 原录音跨首尾存在不连续，使用两份非硬循环原素材，以 0.35 秒淡入淡出连接文件边界；最多两份同层声音重叠，不重编码源素材。

**短音效公式。** 每个 revv、release、blip、start、stop 录音均使用自己的静态校准和单轨 trim：

`shotDb = masterDb + categoryDb + measuredCalibrationDb_sample + trackTrimDb_sample`

`shotVolume = 10^(smooth(shotDb)/20) × shotEnvelope × GUIVolume × distanceGain × headroomGain`

默认 revv/release 组 −10 dB，blips 组 −13.75 dB，启停组 −4 dB；起始约 80 ms、末尾约 150 ms 平滑包络。加减速由实际 Create RPM 变化率触发；同组受冷却与素材长度限制，默认 revv/release 冷却至少 5.5 秒，blip 至少 12 秒；并发最多 2。启停会终止上一次过渡短音效。变体随机选取，避免连续重复；文件序号不被当作 RPM 编号。

**静态校准。** 每个原 OGG 均完整解码测量 RMS、最大绝对峰值及持续时间。校准振幅 `C=min(2,0.12/RMS,0.6/peak)`，再转为 `20log10(C)` dB。不存在原先的 0.5 振幅下限，较响的负载录音可以衰减超过 6 dB。它是可复现的 RMS／峰值校准，最终感知响度仍需要试听微调。

**叠加峰值控制。** 每台发动机按测量的最大原始峰值，保守预留每个循环两份声音、当前所有短音效及 GUI 音量。超过默认 0.8 振幅预算时统一缩小本台声音；快速保护、0.3 秒缓慢释放。该预算是每台发动机的音量控制，不是整个 Minecraft 混音总线的采样级 limiter；多台发动机与其他游戏音效总叠加仍需实际场景试听。

调试配置自动生成在 Minecraft 的 `config/create_fire_control-ztz99a-sound.toml`：`mix` 提供总 Gain、循环／短音效／启停组 Gain 及峰值预算，`trackTrimDb` 提供全部 26 个素材的独立 dB，`loadAutomation.layer00/layer06` 提供曲线端点与 shape，`smoothing`、`takes` 提供平滑、冷却和并发参数。配置重载后目标 dB 平滑追踪；M1A2 配置与声音曲线独立。

## 测试结果与边界

| 检查 | 结果与范围 |
|---|---|
| `compileJava --offline` | PASS |
| `testZtz99aDieselResources --offline` | PASS；包括 1,002,001 组 RPM×SU 扫描、源曲线端点、2,002 个原生响应点、校准、时间平滑、峰值预算、72 个模型变体和 26 个 Vorbis 资源 |
| 实际 Minecraft 客户端 | PASS；模型烘焙、贴图、渲染器、物品显示、独立 GUI、配置加载及 26 个声音事件解析；截图已检查 |
| 实际服务端及 Sable | PASS；四个普通世界朝向 + 四个 Sable 装配体 + 一台 M1A2 参考夹具，共 11 个启停、正反转、原地／行驶转向阶段 |
| 普通 Create 连接 | PASS；传动轴、齿轮箱、真实消耗 SU 的风扇，独立输出速度、64 SU 基础容量和实际负载读数 |
| 持久化与拆除 | PASS；BE 类型、配置、五个输入、绑定、启停状态 NBT 恢复；无效设置拒绝；任一输出格拆除后无 99A 残块 |
| 素材与交付包 | PASS；26 个 OGG 与 ZIP 原始文件逐字节一致；发布包包含新代码和 72 个状态，不含测试夹具 |
| 完整 `build --offline` | **FAIL**；已有 `testNativeBearing` 断言 `sustained carrier rotation holds every tested load before stopping` 失败。重复独立执行同样失败。本次未修改轴承代码，不能宣称完整构建通过 |
| `assemble --offline` | PASS；发布 JAR 正常生成并通过 shaded JAR 检查 |

密集扫描结果：GUI 100% 时最大校准循环 RMS 为 `0.060142487`；每 0.1 RPM 参数点的最大 RMS 差为 `0.001297798`，每 0.001 SU 点为 `0.00008113403`，均符合连续曲线的斜率界限。默认未限幅的保守叠加峰值在 GUI 300%、两份边界声音及两条最响短音效假设下可达 `1.8662564`，因此保留峰值预算控制；受控后上限为 `0.8`（浮点计算误差约 1e−7）。自动化自身无端点外增益过冲，最大每 tick dB 变化为约 `1.2`。

扫描原始表、摘要、图分别位于 `build/reports/ztz99a-gain/sweep.csv`、`summary.json`、`gain_validation.png`。服务端／客户端结果位于 `run_ztz99a_verify/server/diesel-integration-result.txt` 和 `client/diesel-client-result.txt`。日志位于 `build/ztz99a-integration.log`、`ztz99a-client.log`、`ztz99a-final-gain-check.log`、`ztz99a-final-build.log`。

**尚未完成的验证：** 重新编译官方 XML 与当前 bank 的完整逐样等价比较，以及自定义 shape 的原生校准；真实游戏场景中的听感、快速移动／多机混音、远距离衰减和全部声音卸载情形；使用实际遥控器界面的完整绑定操作、真实 Sable 移动状态 GUI 交互；Simulated Coasters 的专门实机场景；整合包所有其他模块及所有 M1A2 行为的全面回归。服务端测试使用测试专用信号处理器桥接驱动共享遥控算法，不应等同于真实遥控器操作全流程通过。已验证 M1A2 参考夹具的 11 个控制阶段，且未替换其模型／注册 ID／音频算法。

## 源码与资源清单

下列路径均相对于项目根目录 `E:/Codex/stabilized_turret`。

新增源码：

- `src/main/java/com/hooya/stabilizedturret/content/diesel/Ztz99aDieselEngineBlock.java`
- `src/main/java/com/hooya/stabilizedturret/content/diesel/Ztz99aDieselEngineBlockEntity.java`
- `src/main/java/com/hooya/stabilizedturret/content/diesel/Ztz99aDieselEnginePart.java`
- `src/main/java/com/hooya/stabilizedturret/content/diesel/Ztz99aDieselEngineCollisionShapes.java`
- `src/main/java/com/hooya/stabilizedturret/client/Ztz99aDieselSoundManager.java`
- `src/main/java/com/hooya/stabilizedturret/client/Ztz99aDieselSoundProfile.java`
- `src/main/java/com/hooya/stabilizedturret/client/Ztz99aAudioCalibration.java`
- `src/main/java/com/hooya/stabilizedturret/client/Ztz99aDieselGain.java`
- `src/main/java/com/hooya/stabilizedturret/client/Ztz99aFmodLoadResponse.java`
- `src/main/java/com/hooya/stabilizedturret/client/Ztz99aDieselSoundConfig.java`
- `src/main/java/com/hooya/stabilizedturret/client/renderer/Ztz99aDieselEngineRenderer.java`
- `src/main/java/com/hooya/stabilizedturret/network/OpenZtz99aDieselEnginePayload.java`
- `src/main/java/com/hooya/stabilizedturret/network/SetZtz99aDieselEngineSettingsPayload.java`

修改源码：

- `src/main/java/com/hooya/stabilizedturret/StabilizedTurret.java`
- `src/main/java/com/hooya/stabilizedturret/registry/ModBlocks.java`
- `src/main/java/com/hooya/stabilizedturret/registry/ModItems.java`
- `src/main/java/com/hooya/stabilizedturret/registry/ModBlockEntities.java`
- `src/main/java/com/hooya/stabilizedturret/registry/ModSounds.java`
- `src/main/java/com/hooya/stabilizedturret/registry/ModCreativeTabs.java`
- `src/main/java/com/hooya/stabilizedturret/content/turbine/GasTurbineBlockEntity.java`
- `src/main/java/com/hooya/stabilizedturret/content/turbine/GasTurbineBindingHandler.java`
- `src/main/java/com/hooya/stabilizedturret/client/GasTurbineScreen.java`
- `src/main/java/com/hooya/stabilizedturret/client/ClientPayloadHandlers.java`
- `src/main/java/com/hooya/stabilizedturret/client/ConnectedDisplayClientRegistration.java`
- `src/main/java/com/hooya/stabilizedturret/network/ModNetworking.java`
- `src/main/java/com/hooya/stabilizedturret/network/SetGasTurbineSettingsPayload.java`
- `src/main/java/com/hooya/stabilizedturret/mixin/GasTurbineRotationPropagatorMixin.java`
- `src/main/java/com/hooya/stabilizedturret/compat/schematic/FireControlBlueprintCompat.java`
- `build.gradle`

新增资源：`assets/create_fire_control/blockstates/ztz99a_diesel_engine.json`、`models/block/ztz99a_diesel_engine/` 内 18 份 OBJ／MTL／模型 JSON 和整体物品 OBJ、`ztz99a/geometry_manifest.json`、`models/item/ztz99a_diesel_engine.json`、`textures/block/ztz99a_diesel_engine.png`、`sounds/engine/ztz99a/` 内 26 份原 OGG、`gain_reference.json` 与 `native_load_response.json`；`data/create_fire_control/loot_table/blocks/ztz99a_diesel_engine.json`。以上位于 `src/main/resources/`。

修改资源：`src/main/resources/assets/create_fire_control/sounds.json`、`lang/zh_cn.json`、`lang/en_us.json`。

新增测试：`src/test/java/com/hooya/stabilizedturret/client/Ztz99aDieselResourceChecks.java`、`Ztz99aDieselGainChecks.java`；可选实机源集 `src/dieselIntegration/java/` 下 `DieselIntegration.java`、`DieselClientChecks.java` 及测试用 `ControllerSignalStore.java`。该源集不进入发布 JAR。

保留工具：`tools/convert_ztz99a_bbmodel.js`、`inspect_ztz99a_audio.py`、`extract_ztz99a_gain.py`、`sample_ztz99a_fmod_gain.py`、`render_ztz99a_model.py`、`plot_ztz99a_gain.py`。转换器从给定 bbmodel 确定性生成资源；音频分析仅读取文件。原生采样工具读取用户本地 FMOD DLL／bank，在无设备音频模式执行，生成曲线数据，不修改或重新分发 bank。一次性源码插入脚本已经移除，避免重复运行导致重复注册。

## 全部素材的静态 Gain

下表由原 OGG 实测生成。最终播放时还要加入各组 Gain、总 Gain、单轨 trim；四条循环再加入对应 load 自动化并乘 RPM 权重。持续时间为原素材长度。

| 素材 | 静态校准 dB | 时长（秒） | Gain 组／负载曲线 |
|---|---:|---:|---|
| `engine_00` | -3.609 | 6.10771 | loops / layer00 |
| `engine_33_load` | -12.337 | 6.45263 | loops / layer06 |
| `engine_66_load` | -11.454 | 8.05057 | loops / layer06 |
| `engine_99_load` | -10.791 | 7.05388 | loops / layer06 |
| `engine_blips-001` | -6.832 | 1.35998 | blips |
| `engine_blips-002` | -7.328 | 1.15998 | blips |
| `engine_blips-003` | -10.454 | 1.28560 | blips |
| `engine_blips-004` | -9.341 | 1.91998 | blips |
| `engine_blips-005` | -10.111 | 2.07624 | blips |
| `engine_blips-006` | -6.015 | 1.49175 | blips |
| `engine_blips-007` | -8.391 | 1.82036 | blips |
| `engine_blips-008` | -8.368 | 1.34998 | blips |
| `engine_release-001` | -4.650 | 4.08594 | transients |
| `engine_release-002` | -4.700 | 3.89998 | transients |
| `engine_release-003` | -4.822 | 4.05710 | transients |
| `engine_release-004` | -6.961 | 4.17154 | transients |
| `engine_release-005` | -7.902 | 4.96063 | transients |
| `engine_release-006` | -5.434 | 4.44948 | transients |
| `engine_release-007` | -4.603 | 4.69483 | transients |
| `engine_revv-001` | -10.891 | 4.35506 | transients |
| `engine_revv-002` | -10.476 | 3.23098 | transients |
| `engine_revv-003` | -10.912 | 3.60000 | transients |
| `engine_revv-004` | -10.429 | 3.67370 | transients |
| `engine_revv-005` | -10.044 | 2.87129 | transients |
| `engine_start` | -4.992 | 3.64517 | startStop |
| `engine_stop` | -1.232 | 3.47998 | startStop |
