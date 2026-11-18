**核心结论：公开脚本可以确认，两圈在 HUD 绘制层是由独立坐标和单一尺寸控制的二维圆形；不能确认其上游坐标、尺寸采用什么角度映射算法。尤其不能把大圈说成“物理视锥边界的严格透视投影”，也不能认定自由观察时两圈始终固定在屏幕中央。**

以下仅使用公开游戏资料。查询日期为 **2026 年 10 月 25 日**；脚本分析固定到一个提交，避免把不同版本混用。

### 1. 证据与版本

War-Thunder-Datamine 是第三方公开解包仓库，**不是完整游戏源码**。本次读取的提交为：

`aa326f10c6ba7d523647572368ead2e4d51ffb4d`

其 [`version`](https://github.com/gszabi99/War-Thunder-Datamine/blob/aa326f10c6ba7d523647572368ead2e4d51ffb4d/version) 文件为 **2.59.0.86**。这代表该数据快照，不等于所有平台当前正式客户端版本。

| 编号 | 具体来源 | 能证明什么 |
|---|---|---|
| S1 | [官方 Wiki：Infrared Missiles in War Thunder](https://wiki.warthunder.com/129-infrared-missiles-in-war-thunder) | 固定式、解锁式导引头；大小圈的操作含义；雷达随动、头盔瞄准 |
| S2 | [官方 Wiki：CDK Missile Variables](https://wiki.warthunder.com/cdk/2452-cdk-missile-variables)，Seekers 部分 | `fov`、`lockAngleMax`、`angleMax` 等参数含义 |
| S3 | [`rocketaamaim.nut`，L8–49、L53–112](https://github.com/gszabi99/War-Thunder-Datamine/blob/aa326f10c6ba7d523647572368ead2e4d51ffb4d/gui.vromfs.bin_u/reactivegui/rocketaamaim.nut#L8) | 两圈的绘制、位置、尺寸、可见性、颜色、信噪比附加环 |
| S4 | [`rocketaamaimstate.nut`，L4–42](https://github.com/gszabi99/War-Thunder-Datamine/blob/aa326f10c6ba7d523647572368ead2e4d51ffb4d/gui.vromfs.bin_u/reactivegui/rocketaamaimstate.nut#L4) | HUD 接收哪些状态字段 |
| S5 | [`interopgen.nut`，L5–18](https://github.com/gszabi99/War-Thunder-Datamine/blob/aa326f10c6ba7d523647572368ead2e4d51ffb4d/gui.vromfs.bin_u/reactivegui/interopgen.nut#L5) | 状态通过 interop 接口更新，而非在该绘图脚本中计算 |
| S6 | [`aircrafthud.nut`，L102–130、L156–164](https://github.com/gszabi99/War-Thunder-Datamine/blob/aa326f10c6ba7d523647572368ead2e4d51ffb4d/gui.vromfs.bin_u/reactivegui/aircrafthud.nut#L102) | 上层 HUD 显示开关、普通色与警告色的来源 |
| S7 | [Gaijin DagorEngine 示例，L20](https://github.com/GaijinEntertainment/DagorEngine/blob/2baf19472d60b96b437b2f9e549a3a6c8958cfd8/prog/tools/dargbox/gamebase/samples_prog/_basic/render/vector_canvas.ui.nut#L20)；[绘制实现，L155–175](https://github.com/GaijinEntertainment/DagorEngine/blob/2baf19472d60b96b437b2f9e549a3a6c8958cfd8/prog/gameLibs/daRg/canvasDraw.cpp#L155) | `VECTOR_ELLIPSE` 参数是中心和半径，不能把组件 `size` 直接误读成圆直径 |

S7 用于解释公开 UI 指令语义；它不代表《战争雷霆》闭源部分的完整实现。

### 2. 必须区分的四种位置

| 对象 | 含义 | 不能混同的对象 |
|---|---|---|
| **外侧大圈，Gimbal** | 发射前导引头可偏转、维持目标跟踪的角度范围提示 | 导弹有效射程、命中概率范围、导引头瞬时视场 |
| **搜索／跟踪小圈，Tracker** | 当前导引头观察方向附近的视场提示 | 鼠标飞行指令圈、整个最大离轴范围 |
| **玩家视角** | 相机当前朝向；自由观察可以改变它 | 飞机机头方向 |
| **飞机瞄准标记** | 必须再分鼠标瞄准指令与机炮实际瞄准标记 | 两者都不自动等于导引头方向 |

官方 Wiki 将小圈关联到 `fov`，将大圈关联到发射前 `lockAngleMax`；另外将发射后的偏转范围列为 `angleMax`。因此，“万向架角”“发射前最大离轴角”“导引头 FOV”不能互换。

对于固定式导引头，发射前观察方向固定向前；解锁式导引头锁定后可以偏转跟踪。雷达随动能够在红外尚未锁定时，先把导引头指向雷达跟踪目标；头盔瞄准则允许自由观察方向参与目标指示。

补充代码证据：[`airsight.nut` L247–290](https://github.com/gszabi99/War-Thunder-Datamine/blob/aa326f10c6ba7d523647572368ead2e4d51ffb4d/gui.vromfs.bin_u/reactivegui/airsight.nut#L247) 的机炮标记使用独立的 `FixedGunDirectionX/Y`，并没有复用 `TrackerX/Y`。

### 3. Gimbal／Tracker 数据怎样到达画面

S4、S5 可以追踪到以下接口：

| HUD 字段 | 更新接口 | 绘图用途 |
|---|---|---|
| `GimbalX/Y` | `aamAimGimbalXUpdate`、`aamAimGimbalYUpdate` | 大圈位置 |
| `GimbalSize` | `aamAimGimbalSizeUpdate` | 大圈二维尺寸 |
| `GimbalVisible` | `aamAimGimbalVisibleUpdate` | 大圈独立显示开关 |
| `TrackerX/Y` | `aamAimTrackerXUpdate`、`aamAimTrackerYUpdate` | 小圈位置 |
| `TrackerSize` | `aamAimTrackerSizeUpdate` | 小圈二维尺寸 |
| `TrackerVisible` | `aamAimTrackerVisibleUpdate` | 小圈独立显示开关 |
| `GuidanceLockState/Snr` | 同样按前缀、后缀生成 | 两圈颜色及信噪比附加图形 |
| `AamSightOpacity` | 同上 | 两圈前景透明度 |

**追踪在这里遇到边界：这些公开脚本注册并消费更新值，没有给出产生坐标、尺寸、可见性的完整上游算法。**

还有两个容易误读的细节：

- 两圈均使用 `[VECTOR_ELLIPSE, 0, 0, 100, 100]`。其中 `0,0` 是局部圆心；`100,100` 是半径参数，不是左上角加宽高。
- 因而不能看到 `size=[GimbalSize,GimbalSize]` 就断言“大圈直径等于 GimbalSize”。按公开 Dagor 指令语义，它对应半径尺度；实际边缘还受线宽、线对齐等影响。见 S3、S7。

### 4. 大圈到底是不是严格透视投影？

**已证实：绘制层是二维圆形提示。**

S3 中：

```text
大圈：pos = [GimbalX, GimbalY]
      size = [GimbalSize, GimbalSize]

小圈：pos = [TrackerX, TrackerY]
      size = [TrackerSize, TrackerSize]
```

两个方向使用同一个尺寸；该组件没有：

- 视锥边界采样；
- 球面边界逐点投影；
- 随观察方向改变的椭圆长短轴；
- 随机体滚转而改变的椭圆旋转角。

**可以据此作出的判断：**它更适合描述为“表示角度范围的二维 HUD 圆”，而非一般情况下严格投影的物理视锥轮廓。物理圆锥轴与相机轴不重合时，其边界严格透视投影通常不会继续保持这种等半径圆形。

**不能据此确定：**`GimbalSize` 是按角度线性比例、`tan`、其他近似公式，还是附带限幅规则生成；`GimbalX/Y` 是否采用某种特殊参考方向。上游完全可能使用相机投影信息计算中心和尺度，但公开绘图脚本没有展示公式。

因此，“二维绘制”与“完全不使用三维角度／投影数据”也不是同一回事。

### 5. FOV、分辨率和运动关系

这里的 **相机 FOV** 与 **导引头 `fov`** 是两个独立概念。

| 场景 | 可以确认的规则 | 尚不能确认的细节 |
|---|---|---|
| 普通跟随相机 | 两圈由各自位置字段驱动；脚本未硬编码屏幕中心 | 普通相机下大圈中心与机头标记的准确偏移 |
| 鼠标瞄准 | 机炮标记与导引头圈使用不同字段 | 飞机尚未追上鼠标指令时，大圈具体跟哪个参考方向移动 |
| 机头偏转 | 固定式与解锁式导引头有不同指向行为 | 每帧屏幕位置、相机滞后与平滑公式 |
| 自由观察，无头盔指示 | 不能仅因相机转动就假定导引头重新指向 | 圈是否保留、移出画面、受限或隐藏的完整规则 |
| 自由观察，有头盔指示 | 自由观察方向可以用于导引头指示 | 所有机型和状态下是否精确居中；到达离轴极限时如何处理 |
| 解锁式锁定跟踪 | 小圈表示的指向可随目标变化，不必保持向前 | 运动平滑、短时预测和丢失后的回归轨迹 |
| 滚转 | 圆本身旋转后外形不变；该绘图组件没有滚转变形逻辑 | 偏轴圆心怎样随相机与机体滚转变化 |
| 相机缩放／FOV 改变 | 该组件不自行计算 FOV 缩放，直接消费 `Size` | 原生端使用的精确缩放公式 |
| 分辨率变化 | 坐标、尺寸没有在该组件中再套 `sw/sh` 百分比；线宽使用 `hdpx(...)` | 上游按高度、宽度、宽高比和 UI 缩放生成数值的完整关系 |

**所以，自由观察时“所有圈都固定屏幕中央”没有得到证明；“所有圈都跟机头走”同样没有得到证明。**应分别考虑相机、机体参考方向、导引头方向和头盔指示模式。

还发现一项版本陷阱：2.59.0.75 最初出现过“修复俯冲／爬升时自由观察的 IRST HMD 不居中”说明，但官方随后明确删去了该条。它既涉及 **IRST HMD**，也已被撤回，不能拿来证明红外导弹两圈当前必然居中。

### 6. 搜索、捕获、锁定、丢失与武器切换

**颜色的代码证据比“绿搜红锁”更精确。**S3 的大小圈使用同一条件：

```text
使用警告色 =
    GuidanceLockSnr > 1.0
    或
    (GuidanceLockSnr < 0 且 GuidanceLockState >= RESULT_TRACKING)
```

否则使用普通色。S6 传入的普通色是 `crosshairColorOpt`，警告色是 `AlertColorHigh`。所以脚本没有把普通色硬编码为绿色，也不能将所有红圈都简化解释为一个布尔锁定标志。

| 状态／事件 | 公开证据支持的描述 | 证据限制 |
|---|---|---|
| 启动、准备 | Wiki 描述开启后出现圈，准备完成后进入活动搜索 | 准备时间依导弹而异 |
| 活动搜索 | Wiki 描述普通色圈闪烁，并伴随搜索音 | 当前脚本未给出固定闪烁频率 |
| 捕获过程 | HUD 有信噪比值和附加环形显示 | 没找到独立“捕获中”状态对应的完整显示时序 |
| 锁定 | Wiki 描述默认变为红色实圈、音调改变 | 当前代码还结合 SNR，不能只看状态名称 |
| 丢失 | 颜色继续按状态与 SNR 条件计算 | 何时隐藏、何时回中、延迟多少均由上游决定 |
| 切换武器 | 上层有 `isAAMSightActivated` 等显示门控 | 该绘图脚本没有武器切换后的完整复位流程 |
| 弹药耗尽 | 两圈各有独立 `Visible` | 圈组件本身没有 `AamCount==0` 判断，无法证明消失的准确帧或延时 |
| 发射后 | 这些圈不能直接视作已发射导弹的实时视场显示 | 后续显示下一枚还是关闭，取决于上游状态 |

Wiki 支持“搜索闪烁、锁定实圈”的常规描述，但不能由此推出一个适用于所有版本、配置和导弹的固定频率。

S3 本身没有定时器或循环闪烁动画；`Visible`、`Opacity` 等却可被外部更新。因此也不能反过来说“游戏不会闪”。

另一个细节是：当 `GuidanceLockSnr>=0` 时，小圈附近还绘制 SNR 附加圆弧。对应 [`airhudstyle.nut` L53–57](https://github.com/gszabi99/War-Thunder-Datamine/blob/aa326f10c6ba7d523647572368ead2e4d51ffb4d/gui.vromfs.bin_u/reactivegui/style/airhudstyle.nut#L53)，满值时甚至改画更大的完整圆。**截图中小圈附近的附加环，不一定代表另一层物理视场。**

### 7. 视频核验的实际完成情况

查到官方视频：

- [Heat-seeking missiles 101](https://www.youtube.com/watch?v=omcme8er-7o)
- [对应官方新闻页，2018-12-26](https://warthunder.com/en/devblog/current/5975)

官方明确说明该视频针对 1.85 更新时期的红外导弹使用。

**本次未能取得可逐帧检查的视频画面，因此不提供未经核验的时间戳，也没有将它用作自由观察、FOV 缩放或滚转规律的证据。**这意味着本次完成的是公开脚本与文档核验，尚未完成你要求的实机视频运动交叉验证。

### 8. 已证实规则、未确定问题与复刻建议

**已证实规则**

1. 大圈与小圈表达不同含义，且使用独立的位置、尺寸、可见性字段。
2. 绘图层画等半径二维圆，不包含严格视锥边界投影逻辑。
3. 导引头瞬时视场、发射前离轴范围、发射后偏转范围必须分开。
4. 普通色可配置；警告色判断结合 SNR 与跟踪状态。
5. 当前公开组件没有提供固定闪烁频率。

**未确定问题**

- `GimbalX/Y/Size`、`TrackerX/Y/Size` 的原生生成公式；
- 普通相机、自由观察和头盔指示下的精确中心规则；
- FOV、宽高比、分辨率及 UI 缩放的完整换算；
- 丢失、切换武器和耗尽时的逐帧可见性时序；
- 闪烁频率、占空比以及是否随状态变化。

**轻量 HUD 复刻建议——以下是实现选择，不是已证实的战雷算法：**

- 保留相机方向、飞机参考轴、导引头方向、鼠标指令四套独立数据。
- 大圈采用“参考方向的屏幕位置＋圆形角度尺度”，小圈采用导引头方向的屏幕位置；分别控制显示。
- 对准相机中心时，可用  
  \[
  r\approx\frac{H}{2\tan(FOV_y/2)}\tan(\alpha)
  \]
  作为角度到像素半径的近似。大圈用发射前离轴半角；小圈用瞬时视场半角。**该公式不能标注为战雷原算法。**
- 头盔指示先改变导引头指令方向，再计算小圈位置；不要让“自由观察”直接把所有圈强制居中。
- 搜索、准备、锁定、丢失分别管理；闪烁周期做成可调参数。
- 若目标是贴近已查到的战雷绘图层，使用二维圆即可。严格投影视锥边界属于另一种表现方案，现有证据不支持把它当成战雷的精确复刻。
