# 火控全局配置菜单

主界面和暂停菜单的“Mod/模组”一行左侧均有 20×20 的火控计算机按钮；模组列表的配置入口也可打开同一菜单。主界面无需进入存档即可修改全局配置，返回时回到原主界面。一级菜单直接继承机械动力 Catnip 的 `BaseConfigScreen`，保留原生 200×16 分类按钮、返回箭头、标题渐变及布局；点击分类/操作按钮时驱动原生背景齿轮加速。顶部提示固定为英文 `Access Configs for Mod:`，模组名固定为 `Create:Fire Control`，分类与参数跟随 Minecraft 的游戏语言显示。

## 语言

设置菜单支持简体中文（`zh_cn`）、繁体中文（`zh_tw`、`zh_hk`）、英文（`en_us`）与俄文（`ru_ru`）。其他语言使用 Minecraft 的英文回退。标题、分类、参数、音效枚举选项、默认值提示与确认对话框均使用语言资源；切换语言并完成资源重载后，已打开的菜单也会刷新，并保留搜索、滚动位置和未保存修改。

语言只影响显示，不改变 TOML 路径、枚举存储值或配置默认值。翻译源位于 `tools/config_lang`，运行 `python tools/add_fire_control_config_lang.py` 合并资源（繁体转换需要 OpenCC）。界面不显示配置保存位置的底部说明文字。

## 分类

- 客户端配置：瞄准镜与光电吊舱、发动机音效、显示与视角；进入“发动机音效”后选择虎式或 99A。
- 通用配置：暂停菜单配置按钮的显示开关。主界面入口保持显示，隐藏暂停按钮后仍可从主界面或模组列表进入配置。
- 服务端配置：导弹参数与雷达。

瞄准镜与光电吊舱各提供最小倍率、最大倍率，共四项，默认范围均为 1–32 倍，配置允许 1–100 倍。启用对应视角后用滚轮在其范围内逐倍调整。当前倍率仅为客户端运行状态，不再随每次滚轮变化写入配置文件；重新启动后从最小倍率开始。修改上下限会立即约束当前倍率及平滑显示倍率。手工填入最小值高于最大值时，以最大值作为有效下限。旧配置的 `scope.zoom/podZoom` 是当前倍率记录，会由配置规格更新移除，不会被误用作新最小倍率。

“显示与视角”包含三组实例级客户端设置：

- 导弹尾烟：存在时长倍率默认 1.0，保留原有各类粒子寿命及随机范围。面包 2.7.1 的普通尾烟为 124 刻（6.2 秒），大型尾烟为 134 刻（6.7 秒），弹道飞行尾烟为 420–539 刻（21–26.95 秒）；优化火箭尾烟原为 32 刻（1.6 秒）。倍率只对新生成的尾烟生效，普通尾烟的原生贴图动画随时长同比调整。
- 机载 HUD：投弹指示圈、火箭弹预计落点、速度/马赫数/高度/武器列表文字的显示开关，均默认开启。字体颜色默认 `#DDE5E8`，保留原有各条文字路径的默认颜色和透明度；可填写 `#RRGGBB` 或 `#AARRGGBB`。开关作用于各自显示内容，设备的雷达/HUD 布局仍由设备保存。
- 第三人称头瞄视角：追随拖延默认 0.125 秒，为旧平滑响应参数 8/秒的等效时间常数，默认插值曲线保持一致；0 为立即追随瞄准圈，数值越大追随越慢。

“服务端配置 → 导弹参数与雷达 → 导弹弹道 → 弹道模式”提供“数学弹道”和“实体弹道”，默认数学弹道。选项保存在 `config/create_fire_control-server.toml` 的 `[flight] trajectoryMode = "MATHEMATICAL"`，实体模式为 `"ENTITY"`。保存后影响后续发射；已发射导弹保留所选路线。适用弹种沿用原模式指令，反辐射导弹仍固定使用数学路线，其他不支持切换的弹种沿用原路线。

原 `/cfc missile mode math/entity` 指令保留兼容，但现在修改同一全局配置文件。旧存档的 `data/create_fire_control_missile_mode.dat` 不再决定默认模式，也不再创建该世界配置。联机时由服务端统一决定，客户端只可在有管理员权限时修改。

火控接管的 33 个实际注册导弹型号（面包军火库 2.7.1）分别提供寿命、速度、动力段时长。DF-26 与共用实体的原弹道导弹独立设置。时间单位为秒；定速导弹使用方块/秒，反坦克导弹使用速度倍率以保留原有变速曲线。默认值逐项对照添加配置菜单前的 1.0.4 JAR 与面包原生字节码。

雷达射线检测默认关闭：雷达可以扫描方块后面的目标；手动开启后，地形和其他物理结构会遮挡扫描。服务器扫描和客户端额外的 Voxy 遮挡过滤共用此开关。该开关也统一控制红外格斗弹和空对地导弹的首次搜索、锁定保持与发射前遮挡校验；关闭时允许锁定遮挡后的目标，开启时完整遮挡会阻止锁定/发射。红外首次搜索仍须命中实际物理目标，开关不绕过导引头启用、射程、视场等条件。已有配置文件中的显式取值会保留，可从菜单关闭该项或设置 `radar.raycast = false`。

近炸引信、引信保险时间、雷达范围及视场、HUD 布局、炮塔限位与转速等设备选项只在对应设备界面设置，并随设备 NBT 保存；全局配置不提供这些选项。旧版全局弹道配置中的 `proximityFuzeRadius` 在加载配置时移除，防空/机载导弹继续使用发射计算机的引信设置，旧虚拟导弹路径使用所属显示器的引信设置。

## 配置文件

配置保存在当前游戏实例（独立服务端则为服务端实例）的 `config` 目录，所有存档共用，切换存档不会重置。

| 文件 | 内容 |
| --- | --- |
| `create_fire_control-server.toml` | 数学/实体弹道模式；各型号导弹寿命、速度、动力段时长；雷达射线检测 |
| `create_fire_control-common.toml` | 通用界面选项 |
| `create_fire_control-ballistics.toml` | 弹道导弹进阶物理与诱饵弹参数 |
| `create_fire_control-client.toml` | 瞄准镜和吊舱的独立倍率上下限：`minZoom/maxZoom/podMinZoom/podMaxZoom` |
| `create_fire_control-visuals-client.toml` | 尾烟存在时长倍率、投弹/火箭落点显示、机载 HUD 文字和颜色、第三人称头瞄拖延 |
| `create_fire_control-missiles-client.toml` | 导弹显示选项 |
| `create_fire_control-tiger-i-sound.toml` | 虎式发动机音效 |
| `create_fire_control-ztz99a-sound.toml` | 99A 发动机音效 |

旧进阶弹道与虚拟显示配置文件保留兼容用途，不再作为当前菜单中的设置入口。当前菜单只展示实际发射、显示和音效路径使用的选项。默认值来源、清理项与逐型号核查见 `CONFIGURATION_DEFAULTS_AUDIT_2026-11-14.md`。

在配置编辑器中使用保存按钮提交修改。服务端参数在单人游戏中由本机保存；联机时读取服务器同步的参数，管理员修改会保存至服务器的全局文件并广播。普通玩家只读。远程服务器参数不会写入客户端的本地配置文件，断开连接后恢复本地数值。

NeoForge 原生 `SERVER` 类型会写入每个存档的 `serverconfig`，因此这里使用实例级配置注册，并自行同步服务端参数；菜单仍按“服务端配置”展示。

## 验证

验证入口：

- `tools/global-config-checks.init.gradle`：实际 TOML 保存、重载、跨存档路径、远程快照隔离、断开恢复、非法数值拒绝。
- `tools/missile-settings-checks.init.gradle`：逐型号默认值及寿命、速度、动力段参数的独立修改。
- `tools/config-ui-checks.init.gradle`：实际游戏内中文菜单、按钮位置、分类导航及全局配置保存。
- `tools/config-language-checks.init.gradle`：实际客户端切换五种语言及法语英文回退，检查所有配置名称、枚举、提示、确认框与未保存修改保留。
- `tools/scope-zoom-checks.init.gradle`：倍率上下限默认值、旧配置迁移、100 倍缩放、滚轮不改写上下限文件及重载保存。
- `tools/missile-lock-toggle-checks.init.gradle`：同一堵实际方块墙、真实 Sable 目标与生产锁定逻辑，重复开关地形遮挡，验证红外格斗弹和两类空对地导弹的搜索、锁定保持及发射前即时检查。
- `tools/config-trajectory-checks.init.gradle`：真实服务端 22 种导弹发射后端、配置切换、在途路线固定、跨进程/跨存档保存及旧指令兼容（第二次运行加 `-PtrajectoryReload=true`）。
- `tools/radar-scan-checks.init.gradle`（`-PradarOcclusionChecks=true`）：真实区块与 Sable 结构遮挡、切换雷达开关、各雷达入口和扫描预算。
- `tools/missile-power-checks.init.gradle`：实体及数学导弹的寿命与动力段边界。
- `tools/client-visual-settings-checks.init.gradle`：独立读取原生粒子寿命和修改前相机参数，验证新显示设置默认值及 TOML 保存重载；实际客户端检查尾烟寿命/动画、HUD 隐藏及改色、投弹圈/火箭落点显示与第三人称头瞄追随。添加 `-PvisualLanguageChecks=true` 切换到多语言菜单验证；`-PvisualSnapshotJar=...` 可固定使用交付 JAR。

本次测试日志和菜单截图位于 `analysis/config-menu-20261114`。

多语言与弹道模式补充验证通过：`analysis/config-language-20261114-final.log` 检查 1680 个配置名称及实际语言重载、英文回退、枚举显示和菜单保存；`analysis/config-trajectory-20261114-first.log`、`analysis/config-trajectory-20261114-restart.log` 分别在 world-a/world-b 启动，验证 22 个真实导弹类型的后端切换、在途路线固定和同一全局文件的跨进程/跨存档持久化。预置的旧存档实体模式文件未影响数学默认值，且文件内容未被改写。默认值基准与全局配置单测、数学弹道回归、最终打包检查均通过。

原生菜单与倍率范围补充验证：`analysis/config-zoom-ui-20261114-build.log` 通过旧倍率文件迁移、独立范围、1–32 默认值及 100 倍上限验证；按用户要求替换的两项当前倍率不再与旧默认值比较，其余 148 项原有默认值和 33 种导弹参数核验通过。`analysis/config-native-zoom-20261114-client.log` 检查 1692 个配置名称，同时逐项比较原生菜单几何尺寸，验证点击齿轮力反馈及真实滚轮事件、运行时范围更新和多语言。截图位于 `analysis/config-native-zoom-20261114/client/config-language-qa`。

导弹遮挡开关补充验证：`analysis/missile-lock-toggle-20261114-server.log` 直接使用交付 JAR 的固定快照，通过同一堵真实方块墙后的反复开关测试，覆盖 IR 和直射/攻顶 AGM 的初次搜索、持续锁定、发射前即时遮挡判定及零额外区块加载。红外初次搜索的物理命中与地形遮挡分开处理：关闭开关只跳过其地形阻挡校验。`analysis/missile-lock-toggle-20261114-build.log` 中红外逻辑与全局配置保存/联机同步回归及打包检查均通过。

主界面补充验证：火控计算机图标显示在“Mod/模组”按钮左侧；不加载存档即可进入中文配置和服务端参数页，关闭配置返回原主界面。游戏内暂停入口及原有保存、放弃、取消流程也通过客户端回归检查，日志为 `title-entry-client-build.log`。

验证结果：上述配置测试、逐型号变更测试、中文客户端导航及保存/放弃/取消流程、雷达实际遮挡开关测试、导弹动力段集成测试均通过。额外验证了五种反坦克导弹的 3 秒/25 秒寿命、延长动力段和 2 倍发射速度，以及原有制导、防空拦截、红外、RWR 与低空巡航回归。最终 `assemble` 与打包依赖检查通过，集成测试类未进入发布 JAR。

2026-11-15 显示与视角补充验证：`analysis/client-visual-settings-20261115-build.log` 通过独立默认值审计及配置保存重载；`analysis/client-visual-settings-20261115-client.log` 使用交付 JAR 的固定快照，在实际客户端验证 0.5/1/2 倍原生尾烟寿命与动画、优化火箭尾烟、地面发射烟云保持原样、HUD 文字开关/颜色、投弹圈/火箭落点开关及零拖延/默认相机响应。`analysis/client-visual-settings-20261115-language.log` 通过五种语言、法语英文回退和 1746 个配置名称检查，实际资源重载后保留原有默认值与未保存编辑。新增菜单及 HUD 截图分别位于 `analysis/client-visual-language-20261115/client/config-language-qa` 和 `analysis/client-visual-settings-20261115/client/visual-settings-qa`；测试类未进入交付 JAR。
