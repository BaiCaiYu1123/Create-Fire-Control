# 伪装多面体装甲

方块与物品名为“伪装多面体装甲”，方块、物品及方块实体 ID 为
`create_fire_control:camouflaged_polyhedral_armor`。旧
`copypoly:copycat_polyhedron` 通过注册表别名继续读取；存档、物品和蓝图
解析为同一新对象，无需重新放置。

## 复合装甲升级

复合装甲板用动力冲压机制作。在冲压机下方两格处放置工作盆，加入 2 块铁板和
1 块铜板，每次产出 1 块复合装甲板。
工作台和 3×3 动力合成器都能制作伪装多面体装甲。5 个锌锭摆成 X 形，占据
四角和中心，其余 4 格各放 1 块复合装甲板，每次产出 1 块装甲。

每块板升 1 级。原有和新制装甲默认 1 级，最高 100 级。物品提示会显示当前等级。

- 锻造台：模板槽留空，装备槽放入装甲，材料槽放入复合装甲板。每次取出一块
  升级后的装甲。若放入的板超过升到 100 级所需数量，多出的板留在材料槽。
  放入已经满级的装甲时不会生成成品或消耗板。
- 动力冲压机：把装甲和复合装甲板放入下方两格处的工作盆，每次冲压消耗一块板，
  升一级，无需加热。
- 动力搅拌器：在工作盆里放入装甲和板，用燃烧的烈焰人燃烧室加热后搅拌。
  每次处理一块装甲和一块板。

CBC 硬度和韧性按等级乘以 1 级的数值，原有体积或等效厚度规则继续参与计算。
安装终端弹道学时，继承的延展性、脆性和破片倍率也按等级乘算，这三项仍独立于
体积。升级保留物品上已有的形状和弹孔数据，等级随方块与掉落物保存。


## 装甲参数

以下体积规则用于默认模式。启用“全局等效装甲计算”后改用下一节的射线厚度规则。

伪装材质的硬度、韧性从 CBC 的 `BlockArmorPropertiesHandler` 读取，支持其
数据包覆盖；不是原版挖掘硬度。两项数值均乘以剩余实体体积占 1 立方米方块
的比例。例如半块铁体积继承铁块的 50%；四面体实际体积是包围盒的 1/6，
按 1/6 继承。凹槽、空腔和已移除的弹孔体积都不计入装甲。

形状编辑或更换伪装材质后即时重新读取。CBC 的装甲检查工具及弹丸使用相同
动态参数接口。普通单块编辑文档支持 256 个顶点。弹孔网格使用双精度坐标，
具有独立的容量上限。

安装终端弹道学时，**延展性、脆性、破片倍率完整继承伪装材质，不乘体积
比例**；形状编辑、穿孔不会改变这三项，更换伪装材质时重新读取。
数据包中的专用材质配置照常生效；没有专用配置时，使用完整伪装方块的
CBC 韧性选择终端弹道学的默认材质参数，避免薄装甲被误归为另一种材质。
实际穿透/破片求解和检测工具的红框读数采用相同规则，CBC 硬度/韧性仍按
剩余实体体积缩放。破片数量继续由终端弹道学综合其余参数计算，继承的是
材质的破片倍率。此接入仅在安装终端弹道学时启用，不增加强制依赖。

## 可选的全局等效装甲

在火控配置的“服务端配置 → 装甲与穿透 → 全局等效装甲计算”中开启，
**默认关闭**，由服务器统一生效并同步给客户端。文件为游戏实例
`config/create_fire_control-server.toml`：

```toml
[armor]
equivalentArmor = false
```

开启后，所有方块对 CBC 及继承其炮弹体系的附属弹丸使用同一套按射线计算的
装甲参数。方块注册的 CBC 硬度、韧性视为 1 米装甲的基准值；多面体使用伪装
材质的基准值。按命中位置和方向，取**单个方块内第一次接触到最后一次离开**
的距离 L（米），等效硬度和韧性分别为基准值乘 L。中间空腔计入这段距离；
完全穿过空槽或已有弹孔、不接触实体的射线不触发装甲命中。

例如完整立方体正面穿过中心时 L=1，穿过对角线时约为 1.732；斜射靠近边缘
可能从侧面提前离开，因此不统一使用无限平板的 1/cos 公式。普通方块按碰撞
形状计算，多面体按实际编辑和穿孔后的表面计算。不同方块分别结算，不把整面
墙合并为一个阻力值。

CBC 方块穿透抗性检测工具和终端弹道学的高级装甲检测工具从准星命中处沿
视线扫描最多 20 米，累加准星命中的连续装甲的等效硬度和韧性。射线上遇到
块间空隙时结束累计，后面的装甲另算。厚度以毫米显示，最多 20,000 毫米。
单块内部仍用首次接触到最后离开的距离。Sable 上的装甲按结构当前姿态检测。

高级检测工具默认显示简洁读数。按住 Shift 可查看瞄准方块的材质参数、
完整性和上次命中记录。没有命中射线的普通参数查询显示 1 米基准。

终端弹道学继续处理穿透、质量损失、跳弹和后效。适配层补偿 CBC/终端弹道学
公式已有的入射角因子，避免把相同的斜向厚度加算两次；材质的延展性、脆性、
破片倍率仍使用原始材质参数。多面体专用的完整性/贴花/整块移除限制仍仅作用
于该装甲；普通方块保留终端弹道学的损伤行为。

停弹使用实际入口交点，不会把用于计算厚度的出口当作停止位置。贯穿后同一
发炮弹跳过已结算的接触，避免保留实体的方块在同一次射线循环中反复扣质量。
计算发生在命中或检测时，每个接触只求一次长度，并使用最多 128 项的线程
局部射线缓存；缓存不强持有旧编辑网格。没有按世界方块或每发炮弹逐帧扫描。

## 斜面跳弹

CBC 炮弹命中多面体时，对未受损斜坡和受损/编辑后的实体都使用真实网格
射线交点，避免用接触盒的阶梯表面决定入射角。命中结果携带完整的局部面
法线，经过命中结果复制后仍保留；CBC 在穿透回调结束后计算反弹速度时，
继续使用同一法线。Sable 结构命中的法线按当前姿态转换到世界坐标一次。

安装终端弹道学时使用它的跳弹条件和求解器；未安装时使用 CBC/CBC-MW
及已有机炮接入的跳弹条件。弹种的跳弹角度、概率、速度、装甲参数和关闭
跳弹的配置照常生效，正面命中不被强制改成跳弹。反弹方向沿真实斜面计算。
跳弹不会生成贯穿孔，也不会移除整块装甲。

精确射线接入仅针对 CBC 炮弹，普通实体的盒状接触以及原编辑器的轮廓路径
继续使用已有实现。终端弹道学仍为可选兼容，MIT 上游源码未作修改。

## 真实贯穿弹孔

CBC 与继承其弹丸基类的拓展弹药实际判定 `PENETRATE` 后，减去以入射弹道
为轴的方形通道。每个弹孔保存自己的横截面宽度。火控的尾翼稳定脱壳穿甲弹
现在为 **2×2 像素（2/16×2/16 格）**。斜射时通道随弹道倾斜，装甲表面的
开口投影可以大于弹芯横截面。

弹孔功能独立于“全局等效装甲计算”开关，默认模式也会生成。通用入口覆盖
CBC 基类的虚方法调用，附属弹丸覆盖原始命中方法时也能处理成功贯穿。
原版机炮使用累计方块损伤，原始返回值没有贯穿分支；命中多面体装甲时按
弹丸实际质量、速度及 CBC 材质阻力作单次贯穿判定，穿透后保留剩余装甲并
生成弹孔。安装终端弹道学时直接使用它的贯穿结果。

孔径读取飞行实体的 `getRenderedBlockState()` 或 `getItem()` 所指向的弹体模型，
排除零厚度尾翼面，并沿弹轴取实体横截面。不会读取装填用整装弹的物品模型。
CBC-MW 大口径 APDS 使用其独立飞行 partial，武器拓展 APFSDS 使用独立
`apfsds_shot_projectile` 模型，避免把弹托、药筒算进弹孔。

| 飞行弹体 | 孔宽（像素） |
| --- | ---: |
| 火控 APFSDS、CBC-MW 中口径 APFSDS、武器拓展 APFSDS 杆体 | 2 |
| CBC-MW 中口径 APDS | 4 |
| CBC-MW 中口径 AP、APHE、HE | 6 |
| CBC-MW 大口径 APDS 飞行弹芯 | 6 |
| CBC 标准大炮 AP/HE | 10 |
| 武器拓展双管炮普通 AP 飞行弹体 | 8 |

CBC 的机枪、机炮、重机炮使用精灵/曳光渲染，没有可测的实体弹芯模型，
采用 1、2、4 像素的弹体基准；这类弹药的 HVAP、APDS、APFSDS 弹芯分别
采用基准的 75%、50%、37.5%，不采用曳光光晕或通用实体碰撞框的粗细。
其他没有模型可读取的拓展弹丸回退到飞行实体宽度。

旧存档没有独立孔径字段的弹孔仍按原来的 3×3 像素读取；新孔分别记录直径，
不会在升级后缩小旧孔。较小弹芯穿过已有的大孔不会重复增加切割记录。

这是实际网格求差，有入口、出口和使用伪装材质的孔壁。精确射线检测可穿过
弹孔，后续弹丸不会因表面贴花而被挡住。被挡停或跳弹不会钻孔；“穿透一切”
使用其既有删除逻辑，不属于这个弹孔行为。

命中首先以方块本地坐标中的精确通道记录。多次命中的重叠部分按并集移除。
孔与形状、伪装材质一起同步、保存，掉落物及重新放置保留损伤，Create 结构
旋转/镜像时同步变换，Sable 组装后保持结构本地坐标。

### 方块接缝与交点

命中两块装甲的接缝或四块装甲的交点时，按弹孔的完整截面查找同一受击层
内覆盖的多面体装甲。所有部分共享同一条弹道、方形方向和孔径，分别从各块
实际实体中扣除相交部分，拼成一个连续的完整弹孔；斜射时入口和出口也沿同一轴线。

例如 2×2 像素弹芯正对四块完整装甲的交点入射，每块留下其中 1/4，而非只在
首先被射线选中的一块留下 1/4。每块的孔壁、顶点、碰撞与损伤存档独立保留，
仍可在该块编辑器中调整。

只有实际覆盖并切掉了材料的装甲才更新。相切但不相交、空腔、已经存在的孔、
普通方块和其他 Sable 结构不会被旁带切割。沿射击方向的后续装甲层仍需其自身
穿透判定，跨块补孔不会把后方整排装甲提前打穿。客户端同步跳过这发弹丸已处理的
相邻接触，避免在接缝处重复扣除同一次命中的穿透质量。

### 贯穿后的弹丸飞行

CBC 在客户端也会预测穿透。在服务端弹孔网格尚未同步到客户端时，同一发
弹丸必须跳过刚刚成功贯穿的装甲接触；否则射线循环会反复扣除同一块装甲
的穿透质量，最终将客户端弹丸误判成停弹，显示为留在孔口的弹芯。

成功 `PENETRATE` 的接触现在在两端分别记录，真实弹孔仍仅由服务端生成。
记录以实体对象身份区分，避免单机环境中数字 ID 相同的服务端弹丸与客户端
副本共用预测数据。大炮与中口径弹丸保留原穿透求解。未安装终端弹道学时，
机炮对这种装甲按 CBC 动量、材质硬度/韧性与跳弹参数判断单次贯穿，代替
原版机炮的整块累计损伤；普通方块仍由 CBC 原流程处理。

另有与装甲无关的停弹同步问题：RPL 精确位置没有更新客户端相对移动包的
基准，撞墙/撞地后切回普通同步时可能把弹芯移回旧位置。已针对火控 APFSDS
修正基准交接和出生前两 tick 的位置修正，见
[实机复现与修复记录](APFSDS_STOP_SYNC_2026-11-17.md)。

## 编辑弹孔的顶点和边线

潜行右键打开受损装甲时，会显示**完整剩余实体的可编辑顶点与边线**，包括
孔口与孔壁。这里直接编辑真实表面，不是调节贴花或另一套不可见的弹孔参数。
未受损方块继续使用原 CopyPoly 编辑器。

编辑点不再包含 CSG 剖分的面中心、平面内辅助点或内部三角对角线。
普通点沿用金色标记，自定义点和新增孔角点沿用紫色标记，选中、编号与
三轴使用原编辑器的像素缩放规则。原有控制点顺序、类型及显式连接会保留。

完整立方体的简单方孔只新增入口 4 点和出口 4 点，总计 **16 个控制点**
（原方块 8 点 + 弹孔 8 点）；三角化仅用于内部表面表示。斜孔跨越多个面、
复杂凹形、多孔相交或已有控制点时，保留描述真实边界所必需的额外点。
旧版本保存的冗余剖分网格在打开编辑器时也会精简，实际表面几何保持一致。

两种编辑器均使用火控计算机的黄铜边框、米色底板、深色标题栏及黄铜按钮，
应用按钮使用同款绿色。受损装甲界面直接复用火控按钮与边框绘制，未受损
方块的世界/表格界面由火控客户端样式层适配，上游 MIT 源文件保持独立。

- 点击顶点或边线选中；Ctrl 多选，空白处拖动框选；Tab 逐点选择。
- 顶点拖到同一平面表面上的另一个顶点，可添加/取消显式连线。内部三角
  对角线不会全部暴露；连线通过局部翻边保留实体表面。
- 拖动 XYZ 箭头移动所选顶点/边线，默认步长 1 像素，Ctrl 为 0.1 像素。
  坐标框以像素显示选择中心，可输入精确坐标进行整体平移。
- 可细分边线、按选择顺序补面、删除所选面、撤销/重做和整体镜像；
  Shift+镜像按钮绕该轴旋转 90°。
- 复制/粘贴保留完整网格和弹孔（Ctrl+C/V），重置实体恢复完整方块
  （Ctrl+R）。坐标输入框中的复制粘贴仍处理文字。
- 「清除弹孔」会立即清除当前方块记录的全部弹孔，更新碰撞并关闭编辑器，
  保留已保存的自定义形状、顶点、连线、伪装材质和装甲等级。编辑尚未应用时
  按钮暂停使用。已经应用成自定义网格的孔壁会随该形状保留。
- 右键拖动转向、中键平移、滚轮缩放、WASD/空格/Shift 移动相机。
- 应用前服务端检查范围、封闭拓扑、面方向、退化面、重复面、自相交、
  编辑权限、距离及版本冲突。受击或其他玩家修改后需要重新打开。

应用后完整网格成为装甲的实际形状，原通道已经烘焙到可编辑的孔壁中；
后续命中继续从这个实体切削新的孔。关闭或取消会清除本地预览；成功应用的
顶点、三角面、孔壁与伪装材质一起同步、存盘及保留于掉落物。网格上限为
8192 个顶点、16384 个三角面。编辑不把入射点强制取整到原 0.1 像素网格。

Minecraft/Sable 的实体接触使用轴对齐盒分解：垂直孔可精确表示；斜孔的
接触近似精度为 1/4 像素，取保守空隙；编辑后小于网格间距的孔也会排除
与孔壁相交的接触单元，避免碰撞盒封住真实通道。视觉网格和
射线检测使用双精度几何，和普通编辑文档的点数上限分别处理。

## 击穿性能

轴向方孔直接从基础盒求差；斜孔/编辑表面通过数值运算栅格化并合并接触盒。
碰撞形状直接填充压缩体素网格，去掉逐盒 `Shapes.joinUnoptimized` 和最终
全网格优化。服务端和客户端均在已有网格上增量减去新孔，避免重复计算
全部历史通道。

同机旧单孔案例：轴向 368 个盒，斜向 2146 个盒，斜向形状合并约 856 ms。
新案例分别为 4、312 个盒。预热后的中位数记录：轴向接触分解约 0.04 ms、
形状构建约 0.06 ms；斜向约 11–15 ms、1–2 ms。这些是本地几何阶段的测量，
实际游戏耗时仍取决于结构、孔形和机器环境。

复杂网格优化：碰撞更新先按三角面的所在平面筛出邻近体素，再执行原精度的
相交判定。编辑网格按坐标范围查找需要焊接的边界点，量化后位置相同的
重复拖动会复用现有预览。用旧版和新版处理同一份 24×24 拼片网格，焊接耗时
中位数从 8.20 ms 降到 2.23 ms，输出拓扑一致。同机三处斜孔的碰撞盒构建
为 4.65 ms 和 4.48 ms，输出碰撞盒一致。

## 放置粒子兼容

Effortless 3.4.0 的建造操作会直接调用原版敲击粒子接口；原版接口对空形状
调用 `bounds()` 会抛出 `No bounds for empty shape`。多面体的空开放表面、
完全被通道移除的实体，以及接触网格无法表示的极薄实体均可能提供空形状，
因此该问题并非每次放置都触发。

火控客户端在生成这类装甲的敲击粒子前检查形状，为空时只跳过此次粒子。
不填充原有空隙、不删除编辑网格或弹孔，也不改变碰撞及弹道计算。
非空装甲和其他方块继续使用原粒子行为；修复无需安装 Effortless。

## 终端弹道学

针对安装的 **CBC Terminal Ballistics 0.3.5-neoforge**，保留其原始穿透计算、
质量损失、速度衰减、跳弹及穿透后效/破片生成。兼容层不重写它的穿透公式。

仅命中这个多面体装甲时关闭终端弹道学的完整性累计和降级、材质整体碎裂、
整体破坏、裂纹进度、弹孔/擦痕贴花以及临时移除整块装甲的穿越处理。贯穿的
弹丸自身可以跳过已处理的接触，其他实体仍与剩余装甲碰撞。成功贯穿的 CBC
及拓展弹丸另外生成上述真实弹孔。

普通方块仍执行终端弹道学原机制；后效命中的普通方块也保留原有处理。
未安装终端弹道学时，大炮与中口径弹丸继续使用 CBC/CBC-MW 原始穿透判定；
机炮采用上述仅作用于多面体装甲的单次判定。在成功贯穿时保留装甲实体并
生成真实弹孔。

## 代码与许可

CopyPoly 原代码及资源独立保存在 `third_party/copypoly`，继续使用 MIT。
`fire_control_adapter` 下由原材质投影器改编的 `PolyArmorModel`，以及复用原
相机/投影/描线的 `ArmorEditorView` 门面，也明确使用 MIT。新装甲几何、损伤、
网格编辑界面/菜单/数据包、CBC/终端弹道和 Sable 接入代码位于火控自己的
源码目录，使用火控原创部分的许可。
既有线控来源部分继续保留 MIT。终端弹道学和 Copycats+ JAR 不进入发行包。

## 开发验证命令

```powershell
.\gradlew.bat testCopypoly --offline -PcopypolyJUnitDir=libs/copypoly-tests
.\gradlew.bat runCopypolyGameTestServer --offline -PcopypolyGameTests=true -PcbcRuntime=true -PmianbaoJar='D:\mods\mianbaos-modernwarfare.jar' -PrunRoot=run/poly-armor-native
.\gradlew.bat runCopypolyGameTestServer --offline -PcopypolyGameTests=true -PcbcRuntime=true -PterminalBallisticsRuntime=true -PmianbaoJar='D:\mods\mianbaos-modernwarfare.jar' -PrunRoot=run/poly-armor-terminal
.\gradlew.bat runClient --offline -PcopypolyClientSmoke=true -PpolyArmorClientSmoke=true -PcbcRuntime=true -PmianbaoJar='D:\mods\mianbaos-modernwarfare.jar' -PrunRoot=run/poly-armor-client
.\gradlew.bat runClient --offline -PcopypolyClientSmoke=true -PpolyArmorFlightSmoke=true -PcbcRuntime=true -PmianbaoJar='D:\mods\mianbaos-modernwarfare.jar' -PrunRoot=run/poly-armor-flight-client-native-flat
.\gradlew.bat runClient --offline -PcopypolyClientSmoke=true -PpolyArmorFlightSmoke=true -PcbcRuntime=true -PterminalBallisticsRuntime=true -PmianbaoJar='D:\mods\mianbaos-modernwarfare.jar' -PrunRoot=run/poly-armor-flight-client-terminal-flat
.\gradlew.bat runClient --offline -PcopypolyClientSmoke=true -PpolyArmorRicochetSmoke=true -PcbcRuntime=true -PmianbaoJar='D:\mods\mianbaos-modernwarfare.jar' -PrunRoot=run/poly-armor-ricochet-client-native
# 添加 -PterminalBallisticsRuntime=true 验证终端弹道学的客户端跳弹。
.\gradlew.bat runClient --offline -PcopypolyClientSmoke=true -PpolyArmorParticleSmoke=true -PcbcRuntime=true -PmianbaoJar='D:\mods\mianbaos-modernwarfare.jar' -PrunRoot=run/poly-armor-placement-particle-native
# 添加 -PeffortlessJar='D:\mods\effortless-1.21.1-3.4.0.jar' 验证实际 Effortless 粒子入口。
```

终端弹道学的开发运行需要同时提供其 Copycats+ 前置，详见 `DEPENDENCIES.md`。

## 验证记录（2026-11-17）

全局等效装甲与原装机炮更新：140 项 JUnit 全部通过；原生 CBC 与终端弹道学
环境各 54 项服务器 GameTest 通过，未装环境按安装状态跳过 CTB 专属用例。
新增覆盖正面/面对角线/体对角线、不同命中位置、U 形空腔、普通半砖、1 米
基准、求解器入射角补偿、检测快照与材质特性、世界/旋转 Sable 坐标，以及
实际停弹后连续 tick 和改形移除支撑后的正常释放。

原装 CBC 炮闩与弹药的真实发射测试保留默认弹体质量，覆盖 AP 机炮弹、
高射弹及机枪弹，并在附属已注册时测试 APDS/APFSDS；分别验证开关开启和
关闭后的实际飞行穿孔。既有多口径、可编辑弹孔、跨块拼孔及 Sable 回归也
全部通过。测试只要求真正能够击穿的装甲生成孔，停弹与跳弹不强制钻孔。

配置检查通过全局 TOML 保存/重载、远程服务端权威值和断线恢复。真实中文
客户端验证了“服务端配置 → 装甲与穿透”的默认关闭显示、修改保存、恢复和
返回路径，已查看实际开关截图。1,296 次精确运动包往返检查通过；发行包
类/资源与 MIT/火控许可边界检查通过，可选依赖与测试夹具未打入发行包。

日志：`analysis/cannon-bores/equivalent-native.log`、
`analysis/cannon-bores/equivalent-terminal.log`、
`analysis/cannon-bores/equivalent-config-ui.log`。截图：
`run/equivalent-armor-config-ui/client/config-ui-qa/13-equivalent-armor-server.png`。

放置粒子崩溃修复：修复前通过真实 BlockItem 放置后调用 Effortless 3.4.0 的
`MinecraftParticleEngine.crack`，在第二次放置（空开放表面）复现了报告中的
`No bounds for empty shape`。修复后，安装与未安装 Effortless 的独立客户端
各通过 144 次放置：空开放表面、完全穿孔、接触网格以下的极薄实体各 24 次
安全跳过粒子；完整装甲、普通弹孔和缺少方块实体的回退状态各 24 次保留粒子。
另检查普通石头粒子与极薄实体的实际体积，两组均正常保存并退出。
日志：`run/poly-armor-placement-particle-baseline/client/logs/latest.log`、
`run/poly-armor-placement-particle-fixed/client/logs/latest.log` 和
`run/poly-armor-placement-particle-native/client/logs/latest.log`。
`testApfsdsMotionSync testCopypoly assemble` 通过：136 项单元测试、1,296 次
同步包往返及发行完整性/许可边界检查均通过。发行包已检查粒子保护的类与
配置一致，未夹带 Effortless、终端弹道学、Copycats+ 或测试夹具。
交付包：`build/libs/create-fire-control-1.0.4-poly-armor-placement-fix.jar`。

跨方块弹孔更新：新增几何回归覆盖两块接缝、四块交点、三个射击轴、
0.75/2/6/10 像素孔、斜向总体积、相切不切割、空腔及重复命中缓存。
完整 136 项 JUnit 通过；原生与终端弹道学环境各 48 项 GameTest 通过，含 2/10 像素炮弹实际飞行
命中交点、一次贯穿生成四块拼孔、后方装甲层不被提前打孔、斜孔共轴、停弹
不扩孔，以及旋转 Sable 结构内的四块拼孔和各部分可编辑/保存检查。
日志：`analysis/cannon-bores/shared-hole-*.log`、
`run/armor-shared-hole-native/copypolyGameTestServer/logs/latest.log` 和
`run/armor-shared-hole-terminal/copypolyGameTestServer/logs/latest.log`。

斜坡跳弹更新：原生弹道与终端弹道学环境各 44 项注册 GameTest 的套件通过，
未装环境按安装状态跳过 CTB 专属用例。新增覆盖真实射线法线、命中结果复制、
四朝向的原始/打孔后编辑斜坡、实际反射速度、反弹后继续飞行、大炮/中口径炮/
机炮、正面命中不强制跳弹、关闭跳弹，以及旋转 Sable 结构的世界法线和求解器。
受控跳弹测试的高韧性材质和概率配置只作用于独立测试夹具。
两组真实客户端均通过原始/编辑斜坡的独立跳弹预测，以及服务端/客户端继续
反弹飞行检查，并正常保存退出。日志：
`run/poly-armor-ricochet-native/copypolyGameTestServer/logs/latest.log`、
`run/poly-armor-ricochet-terminal/copypolyGameTestServer/logs/latest.log`、
`run/poly-armor-ricochet-client-native/client/logs/latest.log`、
`run/poly-armor-ricochet-client-terminal/client/logs/latest.log`。
修复前回归复现了未受损斜坡命中阶梯盒、掠射被判为贯穿的问题。
`testApfsdsMotionSync testCopypoly assemble` 通过：125 项单元测试、1,296 次
同步包往返及发行完整性/许可边界检查均通过。
交付包：`build/libs/create-fire-control-1.0.4-poly-armor-ricochet-fix.jar`。

多口径弹孔更新：125 项 JUnit 通过；原生弹道与终端弹道学环境各 39 项
GameTest 通过。新增发射用例覆盖 15 种实际弹药，包括 CBC 大炮、机炮、机枪，
CBC-MW 中口径 AP/APDS/APFSDS 与大口径 APDS，火控 APFSDS，武器拓展
双管炮 AP/APFSDS，机炮拓展 APFSDS，以及先进技术重机炮 AP/APDS/APDSFS。
校验飞行弹体孔径、真实通道、可编辑封闭网格与保存读取。

孔径单元回归覆盖 0.75、1、2、4、6、10 像素孔，旧 9 数值弹孔格式保留
3 像素宽度，以及同轴扩孔、较小重复弹芯不增加切割记录与增量同步。
日志：`analysis/cannon-bores/`、`run/armor-all-cbc-native/copypolyGameTestServer/logs/latest.log`
和 `run/armor-all-cbc-terminal/copypolyGameTestServer/logs/latest.log`。

以下为先前版本的验证记录：

- JUnit：116 项全部通过，含上游 85 项、装甲几何/重复命中 23 项及编辑选择/偏好 8 项。
  覆盖 8 个孔角点、原控制类型/显式连线、旧剖分迁移、增量同步及性能记录。
- 未装终端弹道学：38 项注册服务器 GameTest 的套件通过，CTB 专属用例
  按安装检测跳过；验证可选兼容没有引入缺类错误或强制依赖。
- 安装 Terminal Ballistics 0.3.5：38 项真实服务器 GameTest 全部通过，
  验证实际求解器、穿透后效、普通方块原机制及装甲专用限制。
- 新增材质参数回归：比较完整方块、半体积、1/160 体积及穿孔后的检测快照，
  三项材质参数一致，硬度/韧性继续缩放；换材质后即时更新。实际 APFSDS
  击穿的破片数量使用完整伪装材质的倍率，保留 CTB 对其余因素的计算。
- 两组服务端均覆盖编辑实体的存档、掉落重放、再次真实击穿、Sable 原生
  碰撞更新及旋转结构中的本地弹孔坐标。新增实际弹丸 tick 连穿两层装甲并
  击碎后方玻璃，以及数字 ID 相同的两端副本不共享接触缓存。
- 两组真实客户端飞行回归均通过，且正常保存并退出：默认质量 APFSDS
  在弹孔网格尚未收到时提前预测两次 tick，继续穿过两层装甲；随后实际
  服务端飞行与客户端显示均继续前进，击碎后方玻璃。此测试在修复前复现
  客户端质量被重复扣为零、弹芯误停在孔口，修复后通过。
  日志分别为 `run/poly-armor-flight-client-native-flat/client/logs/latest.log`
  和 `run/poly-armor-flight-client-terminal-flat/client/logs/latest.log`。
  飞行夹具独立使用超平坦临时世界，避免普通地形生成任务干扰测试退出。
- 真实客户端（含终端弹道学）：APFSDS 飞行命中、正常/倾斜材质孔壁、形状
  改动、孔边选取、坐标输入、XYZ 拖动、细分、撤销/重做、C2S 应用、重开
  保留顶点以及第二次实际击穿全部通过；客户端正常退出。
  新版本另验证新 ID、原 8 点与新增 8 个孔角点的类型、16→17→16 的
  细分/撤销、保存后实际孔口变化以及重新击穿。
  日志：`run/poly-armor-client-terminal/client/logs/latest.log`，
  截图：同目录下 `screenshots/armor-*.png`，已查看编辑界面及保存重开截图。
- `testCopypoly assemble` 通过，发行检查覆盖类/资源完整性、双模组身份、
  MIT 与火控许可边界、测试夹具排除及可选第三方模组不被打入发行包。
- 92 个上游文件的本地 SHA-256 与独立导入清单一致；记录了两处 MIT 源码
  的 ID/模型迁移及资源引用、翻译变更。
- 黄铜 UI：中英文真实客户端均通过受损装甲编辑及未受损方块的世界/表格
  界面切换、应用保存验证，已查看三种界面截图并修正米色底板上的文字对比度。
  使用火控计算机的实际按钮/边框绘制；样式层不改写上游编辑器源文件。

实际环境：NeoForge 21.1.230、Create 6.0.10、CBC 5.11.7、CBC Neo Warfare
0.0.6v、Sable 2.0.6、Aeronautics/Simulated 1.3.2；终端弹道组另加
Terminal Ballistics 0.3.5 与 Copycats+ 3.0.11。完整玩家整合包和光影组合未验证。
