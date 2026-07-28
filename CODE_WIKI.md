# Stabilized Turret — Code Wiki

> 项目代号：`stabilized_turret`
> 显示名：**Create: Fire Control**（坦克火控系统）
> 作者：Hooya
> 适用平台：Minecraft 1.21.1 / NeoForge 21.1.230 / Java 21
> 许可证：MIT

---

## 目录

1. [项目概览](#1-项目概览)
2. [整体架构](#2-整体架构)
3. [目录结构](#3-目录结构)
4. [主要模块职责](#4-主要模块职责)
5. [关键类与函数说明](#5-关键类与函数说明)
6. [网络通信协议](#6-网络通信协议)
7. [Mixin 注入体系](#7-mixin-注入体系)
8. [热成像着色器管线](#8-热成像着色器管线)
9. [依赖关系](#9-依赖关系)
10. [项目运行方式](#10-项目运行方式)
11. [关键设计原则与算法](#11-关键设计原则与算法)
12. [附录：辅助工具与资源](#12-附录辅助工具与资源)

---

## 1. 项目概览

### 1.1 项目定位

`stabilized_turret` 是一个面向 **Create + Sable + Simulated + Create Big Cannons (CBC)** 生态的坦克火控计算机 Mod。它为 Minecraft 中的载具炮塔提供：

- **双向独立稳定**：在载具本体晃动时，方向机（yaw）与高低机（pitch）通过 Sable 物理约束保持世界空间稳定。
- **头瞄（Head Aiming）**：炮手坐进绑定座椅后，屏幕中心即为火炮目标点；客户端通过射线投射把目标点回传服务端驱动伺服。
- **炮镜视野**：基于 Sable `ClientSubLevel.renderPose` 的子层级相对瞄准镜，支持滚轮倍率切换。
- **热成像（Thermal）**：自定义 WHOT 后处理链 + 实体白热高亮 + 物理结构 ×2 高亮，模拟 War Thunder 风格夜视。
- **遥控头瞄**：通过 `DriveByWire`（普通或高级 `Create: Tweaked Controllers`）遥控器在远端终端授权操作火控。
- **蓝图可移植**：与 `sable_schematic_api` 集成，让控制器及其绑定随蓝图保存/放置迁移。

### 1.2 一句话总结

> **它把 Simulated 的 `swivel_bearing` 当作可伺服的物理旋转约束，再叠加一套基于 Sable Rapier 物理管线的世界锁定与瞄准闭环。**

控制器方块（`stabilizer_controller`）是核心入口，绑定 4 个槽位：**方向机 / 高低机 / 炮手座椅 / 火炮底座**，外加可选的火瞄瞄具。不重新注册任何 bearing 方块，而是直接驱动 Simulated 原生 swivel bearing 的内部 rotary constraint。

---

## 2. 整体架构

```
┌──────────────────────────────────────────────────────────────────────┐
│                     客户端（com.hooya.stabilizedturret.client）        │
│                                                                      │
│  ClientPayloadHandlers ─→ ClientAimState ─→ 瞄准镜/HUD/热成像渲染     │
│         ▲                          │                                 │
│         │                          │ 屏幕中心射线                     │
│   AimSessionPayload          AimDirectionPayload                    │
│         ▲                          ▼                                 │
└─────────┼──────────────────────────┼──────────────────────────────────┘
          │                          │
═══════════╪════════网络层 (NeoForge Payload)═════════════════════════
          │                          │
┌─────────┼──────────────────────────┼──────────────────────────────────┐
│         │                          ▼                                 │
│   ServerAimSessionHandler   StabilizerControllerBlockEntity         │
│         │                          │                                 │
│         │                          │ 驱动                             │
│         │                          ▼                                 │
│   ControllerTracker ──→ BearingStabilizer                            │
│         │                          │                                 │
│         │                          ▼                                 │
│   RemoteControlTracker     SwivelBearingBlockEntity                 │
│   RemoteTerminalDetector            (Simulated 原生)                  │
│         │                          │                                 │
│         │                          ▼                                 │
│   StabilizerBindingHandler   Sable Rapier 物理管线                   │
│         │                          ▲                                 │
│         │                          │                                 │
│   Mixin 包 (服务端 5 项) ──────────┘                                 │
│                                                                      │
│                  服务端（com.hooya.stabilizedturret.content）        │
└──────────────────────────────────────────────────────────────────────┘
```

### 2.1 三层分层

| 层 | 包路径 | 职责 |
|---|---|---|
| **客户端层** | `client.*` | HUD 绘制、瞄准镜渲染、热成像后处理、第一人称锁定、屏幕中心射线投射 |
| **业务层** | `content.controller.*` `content.bearing.*` | 控制器方块实体、绑定管理、轴承稳定物理策略、遥控追踪 |
| **网络层** | `network.*` | 7 个 Payload 的编解码与处理、Server/Client 双向通信 |
| **注册层** | `registry.*` | NeoForge DeferredRegister 注册方块/物品/方块实体/创造页 |
| **Mixin 层** | `mixin.*` | 5 个服务端 + 19 个客户端 Mixin，注入 Sable/Simulated/Create/Minecraft 原生代码 |
| **Compat 层** | `compat.schematic.*` | 与 Sable Blueprint API 集成的可移植绑定 |

### 2.2 核心数据流

**头瞄闭环**（每 tick）：

```
玩家屏幕中心
     │
     ▼ (客户端 ClientAimState.onClientTick)
屏幕中心射线 + ClipContext 投射
忽略自身载具子层级 (ownStructureClosure)
     │
     ▼ (AimDirectionPayload, C→S, 20 Hz)
服务端：StabilizerControllerBlockEntity.setDesiredAimRay
     │
     ▼
读火炮底座测量方向 CannonMountCompat.getAssembledCannonDirection
     │
     ▼
commandBearing(YAW/PITCH) → BearingStabilizer.commandAim
     │
     ▼
AxisLock.aimAtDirection 计算 world-space 误差角
     │
     ▼
applyBoundServo → SwivelBearing RotaryConstraint.setMotor
     │
     ▼ (Sable Rapier 物理步进)
enforceWorldLocks：每物理子步长继承父级 swing，丢弃 parent twist
```

---

## 3. 目录结构

```
stabilized_turret/
├── build.gradle                            ← 构建脚本（NeoForge 7.1.38 + Java 21）
├── gradle.properties                       ← 版本号定义
├── settings.gradle
├── README.md                               ← 操作说明
├── gradlew / gradlew.bat                   ← Gradle 9.2.1 Wrapper
│
├── libs/                                   ← 锁定版本的依赖 jar
│   ├── create-1.21.1-6.0.10.jar            ← 必需
│   ├── sable-neoforge-1.21.1-1.2.2.jar     ← 必需（或 compat-latest/2.0.3）
│   ├── create-aeronautics-bundled-1.21.1-1.2.1.jar
│   ├── simulated-neoforge-1.21.1-1.2.1-compile.jar
│   ├── interiors-1.21.1-neoforge-0.6.1.jar (可选)
│   ├── cbcmodernwarfare-0.0.6v+mc.1.21.1-neoforge.jar (可选)
│   ├── sable-schematic-api-0.4.0.jar (可选)
│   ├── sable-companion-common-1.21.1-1.6.0-compile.jar
│   ├── flywheel-neoforge-1.21.1-1.0.6-compile.jar
│   ├── ponder-neoforge-1.0.82-mc1.21.1-compile.jar
│   ├── registrate-MC1.21-1.3.0+67-compile.jar
│   ├── compat-latest/                     ← 升级路径预览（Sable 2.0.3 等）
│   └── extracted/                         ← CBC Neo Warfare 等 jar 解压
│
├── reference/                             ← 第三方源码副本，仅供 IDE 代码补全参考
│   ├── sable/                             ← Sable 完整源码（Rust + Java）
│   └── simulated/                         ← Simulated + Aeronautics + Offroad 源码
│
├── run_verify*/                            ← Gradle run 任务的工作目录（多个验证场景）
│   └── server/                            ← 服务端运行实例
│
├── tools/
│   └── convert_fire_control_model.ps1     ← Blockbench → MC 模型转换脚本
│
└── src/main/
    ├── java/com/hooya/stabilizedturret/
    │   ├── StabilizedTurret.java           ← @Mod 主入口
    │   │
    │   ├── registry/                      ← DeferredRegister 注册
    │   │   ├── ModBlocks.java
    │   │   ├── ModItems.java
    │   │   ├── ModBlockEntities.java
    │   │   └── ModCreativeTabs.java
    │   │
    │   ├── content/                       ← 核心业务
    │   │   ├── bearing/
    │   │   │   └── BearingStabilizer.java ← 物理策略核心（1500 行）
    │   │   │
    │   │   └── controller/
    │   │       ├── StabilizerControllerBlock.java
    │   │       ├── StabilizerControllerBlockEntity.java
    │   │       ├── BindingSlot.java        ← 4 槽位枚举
    │   │       ├── BindingRef.java         ← 绑定引用（持久化）
    │   │       ├── StabilizerBindingHandler.java ← 扳手交互
    │   │       ├── ControllerTracker.java ← 全局控制器索引
    │   │       ├── CannonMountCompat.java  ← CBC/CBC Neo Warfare 反射桥
    │   │       ├── WirelessPulseReceiver.java ← Create 红石链接接收
    │   │       ├── RemoteControlTracker.java ← 遥控会话状态
    │   │       ├── RemoteControllerCompat.java ← 遥控器类型识别
    │   │       └── RemoteTerminalDetector.java ← 终端归属扫描
    │   │
    │   ├── network/                        ← 7 个 Payload
    │   │   ├── ModNetworking.java          ← 注册入口
    │   │   ├── AimDirectionPayload.java    ← C→S 屏幕射线
    │   │   ├── AimSessionPayload.java      ← S→C 瞄准会话状态
    │   │   ├── OpenControllerPayload.java  ← S→C 打开 GUI
    │   │   ├── ControllerSettingsPayload.java ← C→S 配置变更
    │   │   ├── BindingSessionPayload.java  ← S→C 绑定会话
    │   │   ├── RemoteControlStatePayload.java ← C→S 遥控激活
    │   │   ├── SetWirelessFrequencyPayload.java ← C→S 频率设置
    │   │   └── ServerAimSessionHandler.java ← 服务端 tick 派发 AimSession
    │   │
    │   ├── client/                        ← 客户端逻辑
    │   │   ├── ClientPayloadHandlers.java  ← Payload 派发入口
    │   │   ├── ClientAimState.java         ← 客户端瞄准状态机（866 行核心）
    │   │   ├── ClientBindingState.java      ← 绑定会话状态
    │   │   ├── ClientScopeConfig.java       ← 瞄准镜倍率配置
    │   │   ├── StabilizerControllerScreen.java ← 配置 GUI（5 个分页）
    │   │   ├── ScopeHudRenderer.java        ← 瞄准镜 HUD
    │   │   ├── ThirdPersonAimHudRenderer.java ← 第三人称瞄准标记
    │   │   ├── ThermalPostProcessor.java   ← 热成像后处理链
    │   │   ├── ThermalSmokeFilter.java      ← 热成像粒子过滤
    │   │   ├── FireControlTooltipHandler.java ← 物品 tooltip
    │   │   └── RemoteControlClientHandler.java ← 遥控客户端
    │   │
    │   ├── mixin/                         ← Mixin
    │   │   ├── StabilizedTurretMixinPlugin.java ← 加载插件
    │   │   ├── RapierPhysicsPipelineMixin.java
    │   │   ├── SwivelBearingMotorMixin.java
    │   │   ├── SwivelBearingBlockEntityAccessor.java
    │   │   ├── CbcSableCollisionCallbackMixin.java
    │   │   ├── CbcSableLegacyCollisionCallbackMixin.java
    │   │   └── client/                    ← 19 个客户端 Mixin
    │   │
    │   └── compat/
    │       └── schematic/
    │           └── FireControlBlueprintCompat.java ← Sable Blueprint mapper
    │
    └── resources/
        ├── META-INF/neoforge.mods.toml    ← 模组元数据与依赖声明
        ├── stabilized_turret.mixins.json   ← Mixin 配置
        └── assets/stabilized_turret/
            ├── lang/en_us.json / zh_cn.json
            └── shaders/                    ← 热成像着色器
                ├── post/thermal.json
                └── program/thermal.{json,vsh,fsh}
```

---

## 4. 主要模块职责

### 4.1 注册层 `registry.*`

| 类 | 职责 |
|---|---|
| `ModBlocks` | 注册唯一方块 `stabilizer_controller`（`StabilizerControllerBlock`） |
| `ModItems` | 注册 `BlockItem` 对应控制器方块 |
| `ModBlockEntities` | 注册 `StabilizerControllerBlockEntity` |
| `ModCreativeTabs` | 注册 `main` 创造模式物品组 |

> 该 Mod **不**重新注册任何 bearing 方块，所有轴承都直接复用 Simulated 的 `simulated:swivel_bearing`。

### 4.2 业务层 `content.*`

#### 4.2.1 `content.bearing.BearingStabilizer`（核心物理策略）

**职责**：管理控制器绑定的 swivel bearing，在 Sable Rapier 物理管线下实现：

- 绑定状态维护（`BOUND` Map）与活动锁（`ACTIVE` Map）
- 物理属性维护：每物理子步前施加无碰撞/无重力 tag
- 世界锁定：每物理子步后调用 `enforceWorldLocks`，丢弃父级 twist、继承 swing
- 伺服驱动：通过反射访问 `SwivelBearingBlockEntity.handle` 私有字段，调用 `PhysicsConstraintHandle.setMotor` 实现位置伺服
- 角度限制：俯仰硬限位（`enforceRigidPitchLimit`），超限时取消轴向角速度
- 碰撞控制：移除绑定子层级的物理 section、刷新 CBC 火炮 contraption 碰撞
- 偏移约束重建：当控制器配置偏移与当前约束不一致时，反射重建 `RotaryConstraintConfiguration`

#### 4.2.2 `content.controller.StabilizerControllerBlockEntity`（火控计算机方块实体）

**职责**：

- 持久化 4 个 `BindingSlot` 绑定（YAW / PITCH / SEAT / CANNON_MOUNT）
- 持久化配置：俯仰限制、转速、第三人称高度、视角偏移、轴偏移、瞄准镜偏移
- 持久化状态：稳定器开关、头瞄开关、遥控头瞄、瞄准镜/热成像开关
- 持有 2 个 `WirelessPulseReceiver`：scope 频道 + thermal 频道，接入 Create 红石链接网络
- 每 tick（`serverTick`）：
  1. `removeInvalidBindings()` 清理失效绑定
  2. `maintainBoundBearings()` 给 YAW/PITCH 调用 `BearingStabilizer.maintainBinding`
  3. `calibratePitchFromCannon()` 从火炮方向校准俯仰正负方向
  4. 若有头瞄目标：`applyAimDirection()` 通过 `BearingStabilizer.commandAim` 闭环
- 实现 `BlockEntitySubLevelActor`：在子层级内也能 tick

#### 4.2.3 `content.controller.BindingRef`（绑定引用）

**职责**：持久化对某个方块的强引用，包含：

- `BlockPos pos`
- `UUID subLevelId`（若在子层级内）
- `ResourceLocation blockId`（验证方块未被替换）
- `boolean expectsBlockEntity` + `BlockEntity observedBlockEntity`（对象身份校验）

提供 `capture(Level, BlockPos)` / `isSameTarget(Level)` / `resolve(Level)` / `matchesEntityPosition` / NBT 持久化等。**关键保证**：同位置方块被破坏重建后不会继承旧绑定。

#### 4.2.4 `content.controller.StabilizerBindingHandler`（扳手绑定交互）

**职责**：

- `@SubscribeEvent RightClickBlock` HIGHEST 优先级：拦截 Create 扳手点击
- 模式 1：扳手右键控制器 → 进入绑定会话，按 `firstUnboundSlot` 选下一个槽位
- 模式 2：扳手右键目标方块 → 校验 + 绑定到当前槽位
- 槽位轮换：再次扳手右键控制器，调用 `BindingSlot.next()`
- 阻断 Create 旋转：绑定会话期间所有扳手点击都被 consume，避免误操作旋转轴承/座椅/瞄准镜
- 阻断破坏：`BreakEvent` LOWEST 优先级，触发 `ControllerTracker.invalidateBindingsAt`
- 保护已绑定的 swivel bearing：右键已绑定轴承的任何部分（含 plate）会 consume；蹲下扳手右键则 disassemble

#### 4.2.5 `content.controller.ControllerTracker`（全局控制器索引）

**职责**：基于 `WeakHashMap<StabilizerControllerBlockEntity>` 维护所有已加载控制器，提供：

- `findForVehicle(level, vehicle)` —— 找出炮手所坐座椅对应的控制器（用于头瞄）
- `findForPlayerAim(player)` —— 优先遥控会话，回退到座椅
- `findForRemoteTerminal(level, pos, type)` —— 通过终端找控制器
- `findBoundAssembledBearing(level, pos)` —— 判断某方块是否是已绑定的 bearing（用于 StabilizerBindingHandler 保护）
- `invalidateBindingsAt(level, pos)` —— 当某方块被破坏，触发所有控制器清理失效绑定

#### 4.2.6 `content.controller.CannonMountCompat`（火炮底座反射桥）

**职责**：通过反射调用 CBC/CBC Neo Warfare 的 `getContraption()` / `getContraptionDirection()` 方法，无需硬依赖：

- `isCannonMount(BlockEntity)` —— 检测是否拥有 `getContraption` 和 `getContraptionDirection` 方法
- `getAssembledCannonDirection(level, ref)` —— 取已组装火炮的世界方向
- `getMountedCannonPosition(level, ref)` —— 取火炮世界坐标
- `getMountedCannonEntityId/Uuid/SubLevelId` —— 取火炮实体标识（用于客户端隐藏）

#### 4.2.7 `content.controller.WirelessPulseReceiver`（Create 红石链接接收器）

**职责**：实现 `IRedstoneLinkable`，嵌入控制器作为无线脉冲接收端：

- 持有 `first` + `second` 两个 `Frequency`（Create 的红石链接频道对）
- `setReceivedStrength` 检测上升沿（0→正），触发 `risingEdgeCallback`（用于切换 scope/thermal 开关）
- `setFrequency(first, stack)` —— 在 GUI 中通过物品设置频率
- 接入 `Create.REDSTONE_LINK_NETWORK_HANDLER` 红石链接网络

#### 4.2.8 `content.controller.RemoteControlTracker` / `RemoteControllerCompat` / `RemoteTerminalDetector`

**职责**：实现 DriveByWire 系列的遥控头瞄：

- `RemoteControllerCompat.Type` 枚举：`NORMAL`（Create 原版 linked_controller）/ `ADVANCED`（Create Tweaked Controllers 的 tweaked_linked_controller）
- `RemoteControllerCompat.isTerminal(level, pos, type)` —— 判断是否是对应类型的 hub 终端方块（`drivebywire:controller_hub` 或 `drivebywire:tweaked_controller_hub`）
- `RemoteControllerCompat.findHeldBoundHub(player, type)` —— 从玩家主/副手读出绑定的 hub 位置（DataComponents.CUSTOM_DATA 中的 `DriveByWireHub`/`Hub` 字段）
- `RemoteControlTracker` —— 维护玩家会话（12 tick 超时），保存 `dimension + hubPos + type + expiresAt`
- `RemoteTerminalDetector` —— 在控制器自身所在子层级 + YAW/PITCH 绑定所在子层级中扫描是否含对应终端方块（体积上限 4,000,000 方块）

### 4.3 客户端层 `client.*`

| 类 | 职责 |
|---|---|
| `ClientPayloadHandlers` | Payload 派发入口：`handleAimSession`、`handleBindingSession`、`openController` |
| `ClientAimState` | 客户端瞄准状态机：屏幕中心射线、第一人称锁定、瞄准镜/热成像状态、CBC 火炮隐藏识别 |
| `ClientBindingState` | 绑定会话状态（仅 `boolean active`） |
| `ClientScopeConfig` | `ModConfigSpec` 持久化瞄准镜倍率（1.0~16.0） |
| `StabilizerControllerScreen` | 5 页 GUI：HOME / PITCH / YAW / VIEW / AIM_POINT |
| `ScopeHudRenderer` | 瞄准镜 HUD：圆形晕影、十字分划、AZ/EL/距离/倍率显示、热成像 WHOT 标识 |
| `ThirdPersonAimHudRenderer` | 第三人称下的双色标记：金色视角中心 + 粉色火炮落点 |
| `ThermalPostProcessor` | 热成像后处理链管理：在 Iris 之后、HUD 之前应用 `thermal.json` PostChain |
| `ThermalSmokeFilter` | 粒子过滤：热成像下隐藏 CBC/CBC Neo Warfare 的炮口烟雾/烈焰 |
| `FireControlTooltipHandler` | 物品 tooltip：按 Shift 显示稳定/瞄准/绑定/遥控 4 段说明 |
| `RemoteControlClientHandler` | 客户端检测 Create/Created Tweaked Controllers 遥控器状态，发送 `RemoteControlStatePayload` |

### 4.4 Mixin 层 `mixin.*`

详见 [§7. Mixin 注入体系](#7-mixin-注入体系)。

---

## 5. 关键类与函数说明

### 5.1 `StabilizedTurret`（主入口）

```java
@Mod(StabilizedTurret.MOD_ID)
public final class StabilizedTurret {
    public static final String MOD_ID = "stabilized_turret";
    public static final Logger LOGGER = LogUtils.getLogger();

    public StabilizedTurret(IEventBus modBus, ModContainer modContainer) {
        // 注册方块/物品/方块实体/创造页
        ModBlocks.REGISTER.register(modBus);
        ModItems.REGISTER.register(modBus);
        ModBlockEntities.REGISTER.register(modBus);
        ModCreativeTabs.REGISTER.register(modBus);

        // 注册网络 Payload
        modBus.addListener(ModNetworking::register);

        // commonSetup：可选加载 Sable Blueprint 兼容
        modBus.addListener(this::commonSetup);

        // 注册 Client 配置（瞄准镜倍率）
        modContainer.registerConfig(ModConfig.Type.CLIENT, ClientScopeConfig.SPEC);

        // 服务端 tick 监听：维护绑定、清理失效碰撞
        NeoForge.EVENT_BUS.addListener(BearingStabilizer::onServerTickPost);

        // Sable 物理事件：每物理子步前 maintainPhysicalProperties，之后 enforceWorldLocks
        SableEventPlatform.INSTANCE.onPhysicsTick(BearingStabilizer::maintainPhysicalProperties);
        SableEventPlatform.INSTANCE.onPostPhysicsTick((physicsSystem, timeStep) ->
                BearingStabilizer.enforceWorldLocks(physicsSystem));
    }
}
```

### 5.2 `BearingStabilizer`（物理策略核心）

#### 关键公开 API

```java
// 主维护入口：由 StabilizerControllerBlockEntity 每 tick 调用
public static void maintainBinding(
    SwivelBearingBlockEntity bearing,
    boolean yawAxis,                // true=方向机, false=高低机
    boolean decouplePhysics,       // 是否解耦物理（绑定后必为 true）
    boolean stabilizeWorld,        // 是否世界锁定
    boolean acceptMechanicalInput, // 是否接受 Create 机械输入（非头瞄时为 true）
    double maxDegreesPerSecond,
    double depressionLimitDegrees,
    double elevationLimitDegrees,
    Vec3 localOffset               // 约束锚点偏移
)

// 头瞄命令入口：由 commandBearing 间接调用
public static void commandAim(
    SwivelBearingBlockEntity bearing,
    Vec3 measuredWorldDirection,   // 当前火炮实测方向
    Vec3 requestedWorldDirection,  // 期望火炮方向
    boolean yawAxis,
    double maxDegreesPerSecond,
    double depressionLimitDegrees,
    double elevationLimitDegrees,
    Vec3 localOffset
)

// 俯仰正负方向校准（仅 PITCH 轴）
public static void calibrateElevationDirection(
    SwivelBearingBlockEntity bearing,
    Vec3 measuredWorldDirection
)

// 服务端 tick 收尾：清理失效碰撞、刷新 CBC contraption
public static void onServerTickPost(ServerTickEvent.Post event)

// Sable 物理子步前回调：施加无碰撞/无重力 tag
public static void maintainPhysicalProperties(SubLevelPhysicsSystem physicsSystem, double timeStep)

// Sable 物理子步后回调：执行世界锁定
public static void enforceWorldLocks(SubLevelPhysicsSystem physicsSystem)
```

#### 内部状态机（4 个静态 Map）

| Map | Key | Value | 用途 |
|---|---|---|---|
| `BOUND` | `SwivelBearingBlockEntity` | `BoundState(level, childId, yawAxis, decouplePhysics, lastHeartbeat)` | 跟踪所有"已被某控制器绑定"的轴承，供碰撞刷新判断 |
| `ACTIVE` | `SwivelBearingBlockEntity` | `AxisLock` | 跟踪当前正在进行世界锁定的轴承，含目标姿态与限位 |
| `COLLISION_DISABLED` | `UUID`（子层级 ID） | `CollisionState` | 维护已禁用碰撞的子层级，含 tag 与 removedSections |
| `CONTRAPTION_COLLISION_DISABLED` | `KinematicContraption` | `ContraptionCollisionState` | 维护已禁用碰撞的 CBC contraption |

#### `AxisLock` 内部类（关键算法容器）

```java
private static final class AxisLock {
    // 目标姿态（父级 swing 已叠加）
    Quaterniond targetOrientation;
    Quaterniond lastParentOrientation;
    Vector3d localOffset;          // 控制器配置的约束偏移
    Vector3d constraintOffset;     // 当前物理约束的实际偏移
    PhysicsConstraintHandle managedConstraint;

    boolean yawAxis;
    boolean stabilizeWorld = true;
    boolean elevationSignInitialized;
    double jointToElevationSign = 1.0;  // 关节角度到俯仰角的符号

    // 限位
    double depressionLimitDegrees = 90.0;
    double elevationLimitDegrees = 90.0;
    double zeroJointAngleDegrees;       // 关节零点（装配时的初始角度）

    // 伺服收敛
    boolean aimSettled;
    int aimStableTicks;
    double filteredAimErrorDegrees;
    double previousAimErrorDegrees;

    // 核心方法
    boolean initialize(SwivelBearingBlockEntity bearing); // 初始化目标姿态
    void aimAtDirection(bearing, measured, requested, maxAxisDegrees); // 头瞄伺服
    void rotateMechanically(bearing, angleDegrees);        // 机械输入
    void rotateWithLimit(bearing, axis, requestedDegrees); // 应用俯仰硬限位
    void enforce(SwivelBearingBlockEntity, physicsSystem); // 每物理子步执行
    void applyConstraintServo(bearing, handle);              // 通过 Sable servo 施加目标角度
}
```

#### 伺服算法（`aimAtDirection`）

```java
// 1. 投影到 joint 平面
currentProjected = current - axis * current.dot(axis)
desiredProjected = desired - axis * desired.dot(axis)

// 2. 计算角度误差
sine = axis.dot(currentProjected × desiredProjected)
cosine = clamp(currentProjected · desiredProjected, -1, 1)
errorDegrees = toDegrees(atan2(sine, cosine))

// 3. 滞回判定（防抖动）
if (aimSettled && |error| <= 0.35°) return;  // 锁定后稍宽
if (!aimSettled && |error| <= 0.12°)         // 收敛判定，需连续 3 tick
    aimStableTicks++; if (>= 3) aimSettled = true;

// 4. 低通滤波 + 比例收敛
filteredAimErrorDegrees += (error - filtered) * 0.45;
commandDegrees = filtered * 0.55;

// 5. 反向阻尼（< 1° 时弱化）
if (sign(error) != sign(prevError) && |error| < 1°)
    commandDegrees *= 0.35;

// 6. 应用限位 + 通过 rotateWithLimit 写入 targetOrientation
```

#### 世界锁定算法（`enforce`）

```java
void enforce(bearing, physicsSystem) {
    if (stabilizeWorld)
        inheritParentSwing(bearing);     // 仅继承 swing，丢弃 twist
    else
        inheritFullParentRotation(bearing); // 全继承父级旋转

    projectTargetOntoJoint(bearing);    // 投影到合法 joint 流形
    enforceRigidPitchLimit(bearing);    // 硬限位
    ensureConstraintOffset(bearing, child, pipeline); // 必要时重建约束
    enforcePhysicalPitchStop(bearing, child, pipeline); // 超限时取消轴向速度
    applyBoundServo(bearing);            // 写入 motor
}
```

#### Sable API 跨版本反射桥

```java
// Sable 1.x 的 RotaryConstraintHandle 在 2.x 移到了不同包，但 PhysicsConstraintHandle 接口二进制兼容
private static PhysicsConstraintHandle constraintHandle(SwivelBearingBlockEntity bearing) {
    // 反射读取 SwivelBearingBlockEntity.handle 私有字段
    // 缓存 Field 对象，失败后置 swivelConstraintHandleUnavailable = true 永久跳过
}

// 替换约束时反射调用 PhysicsPipeline.addConstraint，因为 1.x 与 2.x 的 config 类路径不同
private static PhysicsConstraintHandle replaceOffsetConstraint(...) {
    // 尝试两个类名：
    //   dev.ryanhcode.sable.api.physics.constraint.RotaryConstraintConfiguration (1.x)
    //   dev.ryanhcode.sable.api.physics.constraint.rotary.RotaryConstraintConfiguration (2.x)
    // 取第一个构造器反射实例化
    // 在 pipeline 的方法列表中按参数类型匹配 addConstraint 重载
}
```

### 5.3 `StabilizerControllerBlockEntity`（火控计算机）

#### 关键状态字段

```java
private final Map<BindingSlot, BindingRef> bindings = new EnumMap<>(BindingSlot.class);
private final WirelessPulseReceiver scopeReceiver;
private final WirelessPulseReceiver thermalReceiver;

// 物理配置
double depressionLimitDegrees = 10.0;
double elevationLimitDegrees = 20.0;
double yawSpeedDegreesPerSecond = 30.0;
double pitchSpeedDegreesPerSecond = 15.0;
double thirdPersonHeight;
double viewAimOffsetX, viewAimOffsetY;
Vec3 yawOffset, pitchOffset, scopeOffset;

// 瞄准状态
Vec3 desiredWorldDirection = new Vec3(0, 0, -1);
Vec3 desiredWorldTarget;
boolean hasAimDirection, hasAimTarget;
long lastAimUpdateGameTime = Long.MIN_VALUE;

// 功能开关
boolean stabilizerEnabled = true;
boolean headAimEnabled = true;
boolean remoteHeadAimEnabled;
boolean scopeEnabled, thermalEnabled;
```

#### 关键方法

```java
// 服务端 tick 入口（既被 BlockEntityTicker 调用，也被 sable$tick 调用）
private void serverTick() {
    removeInvalidBindings();
    boolean gunnerAiming = headAimEnabled
        && lastAimUpdateGameTime != Long.MIN_VALUE
        && level.getGameTime() - lastAimUpdateGameTime <= 5L;
    maintainBoundBearings(!gunnerAiming, stabilizerEnabled || gunnerAiming);
    calibratePitchFromCannon();
    if (!gunnerAiming) return;
    if (!hasAimTarget && !hasAimDirection) return;
    applyAimDirection();
}

// 接收来自客户端的屏幕射线
public void setDesiredAimRay(Vec3 cameraOrigin, Vec3 cameraDirection, Vec3 target, boolean hit) {
    Vec3 convergenceTarget = hit ? target
        : cameraOrigin.add(cameraDirection.normalize().scale(4096.0));
    setDesiredWorldTarget(convergenceTarget);  // 内部计算方向并 setChanged
    lastAimUpdateGameTime = level.getGameTime();
}

// 闭环：分别命令 YAW 和 PITCH 轴
private void applyAimDirection() {
    Vec3 measured = CannonMountCompat.getAssembledCannonDirection(level, mount);
    if (measured == null) return;  // 没火炮就没法瞄准
    Vec3 requestedWorldDirection = hasAimTarget
        ? desiredWorldTarget.subtract(getAimOrigin())
        : desiredWorldDirection;
    commandBearing(YAW, yawSpeed, measured, requested);
    commandBearing(PITCH, pitchSpeed, measured, requested);
}
```

### 5.4 `ClientAimState`（客户端瞄准状态机）

#### 核心数据

```java
private static boolean active, remoteControl, headAim, scoped, thermal;
private static Vec3 cannonAimPoint, scopePosition;
private static UUID cannonEntityUuid, cannonSubLevelId, pitchSubLevelId;
private static BlockPos cannonMountPos;
private static UUID cannonMountSubLevelId;

// 第一人称世界锁定
private static Vec3 lastObservedWorldLook = new Vec3(0, 0, 1);
private static float stableWorldYaw, stableWorldPitch;
```

#### 关键流程

```java
// 接收服务端 AimSessionPayload
public static void accept(AimSessionPayload payload) {
    if (!payload.active()) { restoreCameraAfterScope(); setThermal(false); return; }
    // ...更新所有字段
    if (!wasScoped && nextScoped) {
        // 进入瞄准镜：保存原相机模式，隐藏 GUI，捕获 stableWorldLook
        minecraft.options.hideGui = true;
        captureStableWorldLook(scopeToImpact);
    }
}

// 每 client tick
public static void onClientTick(ClientTickEvent.Post event) {
    if (scoped) minecraft.options.hideGui = true;  // 防 F1 切换
    if (shouldLockFirstPerson()) applyStableWorldLook(player);
    if (headAim) {
        AimRay ray = getScreenCenterRay(minecraft, player);
        PacketDistributor.sendToServer(new AimDirectionPayload(ray...));
    }
    updateThermalHighlights(minecraft, player);  // 给所有实体 setGlowingTag(true)
}

// 屏幕中心射线（忽略自身载具子层级）
private static AimRay getScreenCenterRay(Minecraft minecraft, LocalPlayer player) {
    Vec3 start = firstPerson && !scoped ? Sable.HELPER.getEyePositionInterpolated(player, 1.0F)
                                        : camera.getPosition();
    Vec3 direction = (firstPerson && shouldLockFirstPerson())
        ? directionFromStableAngles()  // 用稳定 yaw/pitch 重算
        : new Vec3(camera.getLookVector());  // 跟随相机
    if (!firstPerson && !scoped)
        direction = applyViewAimOffset(minecraft, camera, direction);  // 第三人称偏移
    Vec3 end = start.add(direction.scale(4096.0));
    ClipContext context = new ClipContext(start, end, COLLIDER, NONE, player);
    Set<SubLevel> ignored = ownStructureClosure(minecraft, player);  // 忽略自身结构链
    ((ClipContextExtension) context).sable$setSubLevelIgnoring(ignored::contains);
    HitResult result = minecraft.level.clip(context);
    // 关键：把 Sable 返回的 plot 坐标转回世界坐标
    Vec3 target = projectPhysicalHitToWorld(minecraft, start, direction, result.getLocation());
    return new AimRay(start, direction, target, hit);
}

// 第一人称鼠标输入（被 EntityTurnMixin 调用）
public static boolean turnStableFirstPerson(LocalPlayer player, double yawInput, double pitchInput) {
    if (!shouldLockFirstPerson()) return false;
    float sensitivity = isScoped() ? (float)(1.0 / getScopeZoom()) : 1.0F;
    stableWorldPitch = clamp(stableWorldPitch + pitchInput * 0.15F * sensitivity, -90, 90);
    stableWorldYaw = wrapDegrees(stableWorldYaw + yawInput * 0.15F * sensitivity);
    applyStableWorldLook(player);
    return true;  // 取消原版 turn
}
```

### 5.5 `SwivelBearingBlockEntityAccessor`（关键 Accessor）

```java
@Mixin(SwivelBearingBlockEntity.class)
public interface SwivelBearingBlockEntityAccessor {
    @Accessor("targetAngleDegrees")
    double stabilizedTurret$getTargetAngleDegrees();

    @Accessor("targetAngleDegrees")
    void stabilizedTurret$setTargetAngleDegrees(double angle);

    @Accessor("lastTargetAngleDegrees")
    void stabilizedTurret$setLastTargetAngleDegrees(double angle);
}
```

> 用于在 `applyConstraintServo` 中读写 Simulated 内部的目标角度字段。

---

## 6. 网络通信协议

### 6.1 Payload 总览

| Payload | 方向 | 触发时机 | 用途 |
|---|---|---|---|
| `AimDirectionPayload` | C → S | 客户端每 tick（头瞄激活时） | 发送屏幕中心射线 |
| `AimSessionPayload` | S → C | 服务端每 tick | 推送瞄准会话状态、火炮位姿、scope 位姿、隐藏 ID |
| `OpenControllerPayload` | S → C | 玩家右键控制器 | 打开配置 GUI |
| `ControllerSettingsPayload` | C → S | GUI 中点击应用 | 写入新配置 |
| `BindingSessionPayload` | S → C | 扳手交互 | 通知客户端绑定会话激活/结束 |
| `RemoteControlStatePayload` | C → S | 客户端每 5 tick 或状态变化 | 通知服务端遥控激活状态 |
| `SetWirelessFrequencyPayload` | C → S | GUI 中点击 A/B 按钮 | 设置 scope/thermal 频率 |

### 6.2 Payload 详细字段

#### `AimDirectionPayload`

```
double originX, originY, originZ          // 屏幕中心射线起点（世界坐标）
double directionX, directionY, directionZ  // 单位方向
double targetX, targetY, targetZ         // 射线命中点（若未命中 = origin + direction * 4096）
boolean hit                              // 是否命中方块
```

服务端验证：
- 所有字段必须 finite
- `direction.lengthSqr() >= 1e-8`
- `origin.distanceToSqr(player) <= 128*128`（防作弊）
- `target.distanceToSqr(origin) <= 8192*8192`（防远程开火）
- `target - origin` 必须与 `direction` 同向

#### `AimSessionPayload`

```
boolean active, remoteControl, headAim, scoped, thermal
double thirdPersonHeight, viewAimOffsetX, viewAimOffsetY
boolean cannonAimAvailable
double cannonAimX/Y/Z        // 火炮瞄准点（落点）
double cannonOriginX/Y/Z     // 火炮发射点
double scopeX/Y/Z            // 瞄准镜锚点
boolean scopePoseRelative    // 是否相对子层级坐标（若 true 客户端用 renderPose 变换）
int cannonEntityId           // 隐藏实体用
UUID cannonEntityUuid        // 隐藏实体用
UUID cannonSubLevelId        // 隐藏子层级用
UUID pitchSubLevelId         // 隐藏子层级用
BlockPos cannonMountPos     // 控制器绑定检查
UUID cannonMountSubLevelId  // 控制器绑定检查
```

**关键设计**：`scopePoseRelative` 字段。Sable 1.x/2.x 已验证的瞄准镜实现把锚点存在 plot 坐标，每帧用 `ClientSubLevel.renderPose` 变换。服务端每 tick 发世界坐标会因 10 Hz 限速产生抖动，因此当知道火炮所在子层级时发送 plot 相对坐标，客户端自己插值。

### 6.3 `ServerAimSessionHandler`

```java
@EventBusSubscriber(modid = StabilizedTurret.MOD_ID)
public final class ServerAimSessionHandler {
    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        // 1. 找出炮手对应的控制器（先查遥控会话，再查座椅）
        // 2. 若控制器为空 → 发 inactivePayload
        // 3. 否则：
        //    - 取 scope position（必要时转 plot-relative）
        //    - 取 cannon origin/direction/entityId/uuid/subLevelId
        //    - 取 pitch subLevel id
        //    - 发 AimSessionPayload(true, ...)
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        // 清理会话、关闭 scope、移除 ACTIVE
    }
}
```

---

## 7. Mixin 注入体系

### 7.1 Mixin 配置

```json
{
  "required": true,
  "minVersion": "0.8",
  "package": "com.hooya.stabilizedturret.mixin",
  "plugin": "com.hooya.stabilizedturret.mixin.StabilizedTurretMixinPlugin",
  "compatibilityLevel": "JAVA_21",
  "injectors": { "defaultRequire": 1 },
  "mixins": [/* 5 个服务端 */],
  "client": [/* 19 个客户端 */]
}
```

### 7.2 服务端 Mixin（5 项）

| Mixin | 目标类 | 注入类型 | 作用 |
|---|---|---|---|
| `StabilizedTurretMixinPlugin` | (IMixinConfigPlugin) | — | 加载插件：通过 ASM 读 CBC `ShellSubLevelImpactCallback.sable$onCollision` 签名（3 参 vs 4 参），决定装载 `CbcSableCollisionCallbackMixin` 还是 `CbcSableLegacyCollisionCallbackMixin` |
| `RapierPhysicsPipelineMixin` | `dev.ryanhcode.sable.physics.impl.rapier.RapierPhysicsPipeline` | `@Redirect` | 拦截 `add(KinematicContraption)` 中的 `sable$shouldCollide()`，对 `BearingStabilizer.shouldDisableContraptionCollision` 返回 true 的 contraption 禁用碰撞 |
| `SwivelBearingMotorMixin` | `dev.simulated_team.simulated...SwivelBearingBlockEntity` | `@Inject(TAIL)` of `updateServoCoefficients` | Simulated 更新完自己的 motor 系数后，调用 `BearingStabilizer.suppressMotor` 恢复火控的 servo 值 |
| `SwivelBearingBlockEntityAccessor` | 同上 | `@Accessor` × 3 | 暴露 `targetAngleDegrees` / `lastTargetAngleDegrees` 私有字段 |
| `CbcSableCollisionCallbackMixin` | `rbasamoyai.createbigcannons.compat.sable.ShellSubLevelImpactCallback` (`@Pseudo`) | `@Shadow` + 新增方法 | CBC 5.11.6（3 参） + Sable 2.x（4 参调用）兼容桥：4 参重载丢弃 `contactedPos` 转发到 3 参 |
| `CbcSableLegacyCollisionCallbackMixin` | 同上 (`@Pseudo`) | `@Shadow` + 新增方法 | CBC 5.11.7（4 参） + Sable 1.2.2（3 参调用）兼容桥：3 参重载把 `projectilePos` 同时作为 `contactedPos` 转发到 4 参 |

### 7.3 客户端 Mixin（19 项）

#### 簇 A：瞄准镜相机与视角控制（7 项）

| Mixin | 目标类 | 注入 | 作用 |
|---|---|---|---|
| `CameraMixin` | `net.minecraft.client.Camera` | `@Inject(TAIL)` of `setup` | 瞄准镜模式下用 `ClientAimState.getScopePosition` 重写相机位置和朝向；非瞄准但 Sable 子层级视角下用 `thirdPersonHeight` 抬高相机 |
| `GameRendererScopeZoomMixin` | `net.minecraft.client.renderer.GameRenderer` | `@Inject(RETURN)` of `getFov` | 瞄准镜下按 `2*atan(tan(fov/2)/zoom)` 公式缩放 FOV |
| `GameRendererAccessor` | 同上 | `@Invoker` | 暴露私有 `getFov(Camera, float, boolean)` |
| `EntityTurnMixin` | `net.minecraft.world.entity.Entity` | `@Inject(HEAD)` of `turn` | 拦截鼠标输入，若 `ClientAimState.turnStableFirstPerson` 接管则取消原方法 |
| `EntitySubLevelRotationHelperMixin` | `dev.ryanhcode.sable...EntitySubLevelRotationHelper` | `@Inject(HEAD)` of `shouldCameraRotate` | 第一人称头瞄时强制返回 false，抑制 Sable 的子层级相机旋转 |
| `LocalPlayerMixin` | `net.minecraft.client.player.LocalPlayer` | `@Inject(HEAD/TAIL)` of `removeVehicle` | 下坐骑前后保存/恢复 `lookAngle`，防止 Sable 二次应用视角转换 |
| `MinecraftCameraModeMixin` | `net.minecraft.client.Minecraft` | `@Inject(TAIL)` of `handleKeybinds` | 限制炮手相机类型仅在 FIRST_PERSON 与 Sable `SUB_LEVEL_VIEW_UNLOCKED` 间切换；进瞄准镜自动隐藏 GUI |

#### 簇 B：瞄准镜下隐藏绑定炮筒（5 项）

| Mixin | 目标类 | 注入 | 作用 |
|---|---|---|---|
| `EntityRenderDispatcherMixin` | `net.minecraft.client.renderer.entity.EntityRenderDispatcher` | `@Inject(HEAD)` × 3 | 在 `render` / `shouldRender` / `getPackedLightCoords` 三个入口拦截；隐藏 shouldHideScopedEntity 的实体；热成像下强制 FULL_BRIGHT |
| `ContraptionVisualMixin` | `com.simibubi.create.content.contraptions.render.ContraptionVisual` | `@Inject(HEAD)` of `setEmbeddingMatrices` | Flywheel 渲染路径下把 embedding 矩阵缩放为 0 |
| `SubLevelScopeVisibilityMixin` | `dev.ryanhcode.sable...VanillaSubLevelRenderDispatcher` | `@ModifyVariable(HEAD)` × 3 | 在 `renderSectionLayer` / `renderAfterSections` / `renderBlockEntities` 过滤掉 shouldHideScopedSubLevel |
| `SodiumSubLevelScopeVisibilityMixin` | `dev.ryanhcode.sable...SodiumSubLevelRenderDispatcher` (`@Pseudo`, `require=0`) | 同上 | Sable 1.x Sodium/Embeddium 渲染器 |
| `FancySubLevelScopeVisibilityMixin` | `dev.ryanhcode.sable...FancySubLevelRenderDispatcher` | 同上 | Sable Fancy 渲染器 |

#### 簇 C：热成像渲染（6 项）

| Mixin | 目标类 | 注入 | 作用 |
|---|---|---|---|
| `FogRendererMixin` | `net.minecraft.client.renderer.FogRenderer` | `@Inject(RETURN)` of `setupColor` | 热成像下雾色与背景清除色统一为 `0.12F` 中性灰 |
| `LightTextureMixin` | `net.minecraft.client.renderer.LightTexture` | `@Inject(HEAD)` of `updateLightTexture` + 2 `@Accessor` | 热成像下取消原版光照贴图更新，把 16×16 像素全填 `0xFFFFFFFF` |
| `LevelRendererThermalMixin` | `net.minecraft.client.renderer.LevelRenderer` | `@Inject(HEAD)` × 2 of `renderSky/renderClouds` | 热成像下完全跳过天空与云层 |
| `LivingEntityRendererMixin` | `net.minecraft.client.renderer.entity.LivingEntityRenderer` | `@ModifyExpressionValue` of `getWhiteOverlayProgress` | 热成像下让活体目标白热覆盖进度至少 `0.62F` |
| `ParticleEngineThermalMixin` | `net.minecraft.client.particle.ParticleEngine` | `@Redirect` of `Particle#render` | 通过 `ThermalSmokeFilter.shouldHide` 过滤炮口烟雾粒子 |
| `SubLevelThermalHighlightMixin` | `dev.ryanhcode.sable...VanillaSubLevelRenderDispatcher` | `@Inject(TAIL)` of `setupDynamicEffects` | 热成像下把 Sable 物理结构的 `COLOR_MODULATOR` 提升到 2.0 |

#### 簇 D：第三方模组兼容（1 项独立）

| Mixin | 目标类 | 注入 | 作用 |
|---|---|---|---|
| `ModernUiBlurHandlerMixin` | `icyllis.modernui.mc.BlurHandler` (`@Pseudo`, `require=0`) | `@Inject(HEAD)` of `blur` | 阻止 ModernUI 给 `StabilizerControllerScreen` 应用背景模糊 |

### 7.4 注入类型分布

| 注入类型 | 数量 |
|---|---|
| `@Inject` | 17 处 |
| `@Redirect` | 2 处 |
| `@ModifyVariable` | 3 处 |
| `@ModifyExpressionValue` (MixinExtras) | 1 处 |
| `@Accessor` | 5 处 |
| `@Invoker` | 1 处 |
| `@Shadow` | 多处 |
| `@Pseudo` | 4 处 |
| `IMixinConfigPlugin` | 1 处 |

---

## 8. 热成像着色器管线

### 8.1 后处理链路

```
minecraft:main ──[Pass 1: stabilized_turret:thermal]──→ thermal_swap ──[Pass 2: blit]──→ minecraft:main
```

- **Pass 1**：自定义 `thermal` 程序，把已渲染的场景重映射为热成像灰度
- **Pass 2**：MC 内置 `blit` 程序，把 thermal_swap 拷回主帧缓冲

> 没有 blur pass，模糊与外溢通过 fragment shader 内 5 邻域 + 3 像素偏移采样实现。

### 8.2 程序资源

| 文件 | 内容 |
|---|---|
| `assets/stabilized_turret/shaders/post/thermal.json` | PostChain 定义（2 个 pass） |
| `assets/stabilized_turret/shaders/program/thermal.json` | 程序描述（sampler: DiffuseSampler, uniforms: ProjMat/InSize/OutSize） |
| `assets/stabilized_turret/shaders/program/thermal.vsh` | 顶点着色器（全屏四边形） |
| `assets/stabilized_turret/shaders/program/thermal.fsh` | 片段着色器（热成像核心） |

### 8.3 片段着色器算法

```glsl
// 1. 亮度计算（NTSC 权重）
float center = dot(color, vec3(0.299, 0.587, 0.114));

// 2. 热源识别（三源）
float heatScore(vec3 c) {
    // 中性白热：实体已有白覆盖层 + Sable 结构已有渲染倍率
    float neutral = smoothstep(0.48, 0.86, hottest) * smoothstep(0.22, 0.72, center);
    // 红色热源：红石、岩浆、火
    float redHeat = ...;
    // 灵魂热源：灵魂火、灵魂火把
    float soulHeat = ...;
    return max(neutral, max(redHeat, soulHeat));
}

// 3. 局部细节与边缘（5 邻域）
float nearAverage = (left + right + up + down + center) / 5;
float signedDetail = clamp(nearAverage - center, -0.14, 0.20);

// 4. 3 像素热外溢
float nearbyHeat = max(4 邻域 3 像素偏移采样的 heatScore);

// 5. WHOT 调色板
float cold = 0.035 + pow(center, 1.12) * 0.32;       // 冷区
float whiteHot = 0.72 + center * 0.22 + signedDetail * 0.90;  // 热区
float thermal = mix(cold + signedDetail * 0.35, whiteHot, heat);

// 6. 传感器质感（不影响屏幕中心精度）
float scanLine = sin(gl_FragCoord.y * 1.5707963) * 0.006;
float grain = pseudoRandom();
float edgeFalloff = ...;  // 暗角

fragColor = vec4(vec3(clamp(thermal, 0.012, 0.985)), 1.0);
```

### 8.4 客户端集成

```java
// ThermalPostProcessor
@SubscribeEvent(priority = EventPriority.HIGHEST)
public static void renderAfterIris(RenderGuiEvent.Pre event) {
    if (!ClientAimState.isThermal()) return;
    ensureChain(minecraft);  // 懒加载 PostChain，处理 resize
    chain.process(partialTick);
    minecraft.getMainRenderTarget().bindWrite(false);  // PostChain 会 unbind，主目标需重新绑定
}
```

---

## 9. 依赖关系

### 9.1 模组依赖（来自 `neoforge.mods.toml`）

| 依赖 | 类型 | 版本范围 | 用途 |
|---|---|---|---|
| `neoforge` | required | `[21.1.230,)` | 模组加载器 |
| `minecraft` | required | `[1.21.1]` | 游戏版本 |
| `create` | required | `[6.0.10, 6.0.11)` | 红石链接网络、扳手、座椅 |
| `sable` | required | `[1.2.2, 1.2.3), [2.0.3, 2.0.4)` | Rapier 物理引擎、子层级 |
| `aeronautics_bundled` | required | `[1.2.1, 1.2.2), [1.3.0, 1.3.1)` | Create Aeronautics 物理载具 |
| `simulated` | required | `[1.2.1, 1.2.2), [1.3.0, 1.3.1)` | swivel bearing 等机械部件 |
| `interiors` | optional | `[0.6.1, 0.6.2)` | 内饰座椅（额外座椅类型） |
| `createbigcannons` | optional | `[5.11.6, 5.11.8)` | CBC 火炮 + 火炮底座 |
| `cbcmodernwarfare` | optional | `[0.0.6v+mc.1.21.1-neoforge]` | CBC 现代战争扩展（compact_mount） |
| `sable_schematic_api` | optional | `[0.4.0, 0.5.0)` | 蓝图保存/放置时迁移绑定 |

### 9.2 编译时 Jar 依赖（来自 `build.gradle`）

```
libs/
├── create-1.21.1-6.0.10.jar                 (implementation + localRuntime)
├── sable-neoforge-1.21.1-1.2.2.jar         (implementation + localRuntime)
│   └── 或 compat-latest/sable-neoforge-1.21.1-2.0.3.jar  (当 -PcompatLatest=true)
├── create-aeronautics-bundled-1.21.1-1.2.1.jar (implementation + localRuntime)
├── simulated-neoforge-1.21.1-1.2.1-compile.jar (compileOnly)
├── sable-companion-common-1.21.1-1.6.0-compile.jar (compileOnly)
├── ponder-neoforge-1.0.82-mc1.21.1-compile.jar (compileOnly)
├── registrate-MC1.21-1.3.0+67-compile.jar  (compileOnly)
├── flywheel-neoforge-1.21.1-1.0.6-compile.jar (compileOnly)
├── interiors-1.21.1-neoforge-0.6.1.jar      (compileOnly + localRuntime)
└── sable-schematic-api-0.4.0.jar           (compileOnly)
```

### 9.3 模块间依赖图

```
            ┌────────────────┐
            │ StabilizedTurret│
            │ (主入口)        │
            └─────┬──────────┘
                  │ registers
        ┌─────────┼──────────┬──────────────┐
        ▼         ▼          ▼              ▼
   ModBlocks  ModItems  ModBlockEntities  ModCreativeTabs
        │         │          │
        └─────────┴──────────┘
                  │
                  ▼
   ┌────────────────────────────────┐
   │ StabilizerControllerBlockEntity│
   └─────┬──────────────┬──────────┘
         │              │
         │ uses          │ owns
         ▼              ▼
   BearingStabilizer   WirelessPulseReceiver × 2
   CannonMountCompat   BindingRef × 4 (EnumMap<BindingSlot>)
   ControllerTracker   RemoteControllerCompat
   RemoteControlTracker
                       │
   ┌───────────────────┴────────────────┐
   │                                    │
   ▼                                    ▼
 Mixin (服务端)                     Mixin (客户端)
 - RapierPhysicsPipelineMixin       - CameraMixin
 - SwivelBearingMotorMixin          - GameRendererScopeZoomMixin
 - SwivelBearingBlockEntityAccessor - EntityTurnMixin
 - CbcSableCollisionCallback*       - SubLevelScopeVisibility* (3 个)
                                    - Thermal* (6 个)
                                    - EntityRenderDispatcherMixin
                                    - ContraptionVisualMixin
```

### 9.4 反射桥（关键兼容设计）

| 反射位置 | 目的 | 兼容目标 |
|---|---|---|
| `BearingStabilizer.constraintHandle` | 读取 `SwivelBearingBlockEntity.handle` 私有字段 | Sable 1.x 与 2.x 的 `RotaryConstraintHandle` 类路径不同，但 `PhysicsConstraintHandle` 接口二进制兼容 |
| `BearingStabilizer.replaceOffsetConstraint` | 反射调用 `PhysicsPipeline.addConstraint` | Sable 1.x config 类在 `constraint` 包，2.x 在 `constraint.rotary` 包 |
| `BearingStabilizer.contraptionSnapshot` | 反射调用 `child.getPlot().getContraptions()` | Sable 1.2 返回 fastutil `ObjectCollection`，2.x 改为 `java.util.Collection`，返回类型是二进制描述符的一部分 |
| `CannonMountCompat.findNoArgMethod` | 通过反射调用 `getContraption` / `getContraptionDirection` / `getController` | CBC 与 CBC Neo Warfare 不强制依赖，方法存在即可用 |
| `RemoteControllerCompat.readBoundHub` | 读 ItemStack 的 `DataComponents.CUSTOM_DATA` | 普通 Create `linked_controller` 用 `Hub` 字段，Create Tweaked Controllers 用 `DriveByWireHub` 字段 |
| `RemoteControlClientHandler.isAdvancedControllerActive` | 反射读 Create Tweaked Controllers 的 `TweakedLinkedControllerClientHandler.MODE` 字段 | 避免硬依赖 |
| `ClientAimState.getCannonControllerRef` | 反射读 CBC cannon entity 的 `controllerPos` 字段 | 客户端在 controller BlockEntity 同步前的过渡期识别 |

---

## 10. 项目运行方式

### 10.1 环境要求

- **JDK 21**（Java 21，toolchain 强制）
- **Gradle 9.2.1**（Wrapper 已包含）
- **NeoForge UserDev 7.1.38** 插件

### 10.2 构建命令

```powershell
# Windows
.\gradlew.bat build

# 输出：build/libs/create-fire-control-0.1.0.jar
```

构建产物 `archivesName` 为 `create-fire-control`，版本来自 `gradle.properties` 的 `mod_version=0.1.0`。

### 10.3 开发运行

```powershell
# 启动客户端
.\gradlew.bat runClient

# 启动服务端（nogui）
.\gradlew.bat runServer
```

工作目录默认 `run/<run_name>/`，可通过 `-PrunRoot=<dir>` 覆盖。

项目根目录已有多个验证运行目录（`run_verify*/`, `run_anchor_verify/`, `run_absolute_aim_verify/` 等），每个都包含完整的 server 启动配置、world 存档与日志，可用于复现特定 bug。

### 10.4 兼容构建（最新依赖）

```powershell
.\gradlew.bat build -PcompatLatest=true
```

`compatLatest=true` 时切换到 `libs/compat-latest/` 目录下的 Sable 2.0.3 / Simulated 1.3.0 / Aeronautics 1.3.0 jar 编译，并额外引入 `sable-rapier-1.21.1-2.0.3.jar` 作为 compileOnly 依赖。

### 10.5 部署到游戏

1. 构建 jar：`.\gradlew.bat build`
2. 将 `build/libs/create-fire-control-0.1.0.jar` 放入 `.minecraft/mods/`
3. 同时放入以下依赖 mod：
   - `create-1.21.1-6.0.10.jar`
   - `sable-neoforge-1.21.1-1.2.2.jar`（或 2.0.3）
   - `create-aeronautics-bundled-1.21.1-1.2.1.jar`（或 1.3.0）
   - `simulated-neoforge-1.21.1-1.2.1.jar`（或 1.3.0）
   - 可选：`interiors`, `createbigcannons`, `cbcmodernwarfare`, `sable_schematic_api`
4. 启动 Minecraft 1.21.1 + NeoForge 21.1.230

### 10.6 游戏内操作

| 操作 | 效果 |
|---|---|
| 右键控制器方块 | 打开火控计算机 GUI |
| 持扳手右键控制器 | 进入绑定模式，选择下一个槽位 |
| 持扳手右键目标方块 | 绑定到当前槽位 |
| 蹲下持扳手右键已绑定 bearing | 解散轴承装配 |
| 蹲下右键控制器（持物品） | 把物品写入下一个无线频率槽位 |
| 蹲下持物品右键控制器 | 同上 |
| 在 GUI 中点击 A/B 槽 | 用手中物品设置 scope/thermal 频率（空手清除） |
| 进入绑定座椅 | 自动激活头瞄，屏幕中心即为火炮目标 |
| 切换相机模式（F5） | 仅在第一人称与 Sable 子层级视角间切换 |
| 滚轮 | 瞄准镜模式下调整倍率（1x~16x） |
| 持 Create 遥控器 + 在终端 hub 附近 | 激活遥控头瞄（需控制器开启 remote_head_aim） |

---

## 11. 关键设计原则与算法

### 11.1 不重新发明轴承

> **"The controller binds directly to Simulated's original `simulated:swivel_bearing`. There is no separately registered stabilized bearing block."**

控制器不创建任何新 bearing，而是直接驱动 Simulated 原生 swivel bearing 的内部 rotary constraint。这意味着：

- 装配/解散/动力学完全由 Simulated 管理
- 火控只在 swivel bearing 已装配后介入，通过反射访问 `handle` 字段
- 解耦物理（`decouplePhysics=true`）后，火控把 Simulated 写入的 motor 替换为自己的目标值

### 11.2 物理与渲染解耦

- **服务端**：`RapierPhysicsPipelineMixin` 抑制物理碰撞，避免绑定炮筒与载具自碰撞
- **客户端**：5 个 Mixin（EntityRenderDispatcher + ContraptionVisual + 3 个 SubLevelScopeVisibility）抑制视觉渲染

两者独立运作，避免"穿模又挡视野"。

### 11.3 双轴稳定算法（twist-swing 分解）

```java
// inheritParentSwing
Quaterniond parentDelta = currentParent * conjugate(lastParentOrientation);
// 把 parentDelta 分解为绕 joint 轴的 twist + 垂直 swing
double projected = parentDelta.xyz · previousAxis;
Quaterniond twist = (previousAxis * projected, parentDelta.w);
Quaterniond swing = parentDelta * conjugate(twist);

// 仅继承 swing（twist 被丢弃 = 父级绕此轴的旋转不影响子级）
swing.mul(targetOrientation, targetOrientation);
```

> 父级 twist（绕 joint 自身轴的旋转）被丢弃，因为子级在该轴上有自己的伺服目标；父级 swing（垂直于 joint 轴的摆动）被继承，让炮塔跟随载具本体倾斜。

### 11.4 伺服滞回（防抖动）

```java
// 收敛阶段：连续 3 tick |error| < 0.12° → 锁定
if (!aimSettled && absoluteError <= 0.12) {
    aimStableTicks++;
    if (aimStableTicks >= 3) aimSettled = true;
}

// 锁定阶段：宽至 0.35° 才解锁（滞回）
if (aimSettled) {
    if (absoluteError <= 0.35) return;  // 锁定中
    aimSettled = false;  // 解锁
}

// 反向阻尼：< 1° 时弱化命令
if (sign(error) != sign(prevError) && absoluteError < 1.0)
    commandDegrees *= 0.35;
```

### 11.5 俯仰硬限位（两层防护）

1. **目标姿态限位**（`enforceRigidPitchLimit`）：每物理子步长重新投影 `targetOrientation` 到合法 joint 流形，超出 ±depression/elevation 的部分被夹紧
2. **物理速度限位**（`enforcePhysicalPitchStop`）：若实际物理 joint 已超出限位（被外部冲量推过），取消轴向角速度，让 Sable native servo 把它拉回

### 11.6 状态集中化

所有客户端 Mixin 都通过 `ClientAimState` 读取 `isScoped()` / `isThermal()` / `isActive()` 等状态，**单一状态源保证门控一致**。`ClientAimState` 接收 `AimSessionPayload` 后是唯一的写入点。

### 11.7 多渲染器兼容

簇 B 的 3 个 `SubLevelScopeVisibility*` Mixin 用**相同逻辑**覆盖 Sable 的 Vanilla/Sodium/Fancy 三种渲染分发器，确保不同客户端渲染配置下行为一致。`@Pseudo` + `require=0` 保证目标类不存在时不报错。

### 11.8 跨版本兼容

```java
// StabilizedTurretMixinPlugin.onLoad
// 用 ASM 读 CBC ShellSubLevelImpactCallback.sable$onCollision 的签名
int mask = readCallbackSignatureMask();
hasThreeArgumentCallback = (mask & 1) != 0;
hasFourArgumentCallback = (mask & 2) != 0;

// shouldApplyMixin
if (NEW_BRIDGE.equals(mixinClassName)) {
    return hasThreeArgumentCallback && !hasFourArgumentCallback;  // CBC 5.11.6 + Sable 2.x
}
if (LEGACY_BRIDGE.equals(mixinClassName)) {
    return hasFourArgumentCallback && !hasThreeArgumentCallback;  // CBC 5.11.7 + Sable 1.2.2
}
```

让单次构建同时兼容 CBC 5.11.6+Sable 2.x 与 CBC 5.11.7+Sable 1.2.2 两种组合。

### 11.9 屏幕中心射线的子层级忽略

```java
private static Set<SubLevel> ownStructureClosure(Minecraft minecraft, LocalPlayer player) {
    Set<SubLevel> seeds = new HashSet<>();
    seeds.add(Sable.HELPER.getTrackingSubLevel(player));  // 玩家正在观察的子层级
    seeds.add(Sable.HELPER.getVehicleSubLevel(player));  // 玩家所在载具子层级
    if (player.getVehicle() != null)
        seeds.add(Sable.HELPER.getContaining(player.getVehicle()));
    // 闭包扩展：通过 SubLevelHelper.getConnectedChain 找出所有相连的子层级
    // 用于避免射线命中自身炮塔
}
```

`connectedStructureClosure` 用迭代扩展算法处理双向依赖，覆盖 Sable 短暂同步窗口期。

### 11.10 Sable plot 坐标 → 世界坐标转换

```java
private static Vec3 projectPhysicalHitToWorld(Minecraft minecraft, Vec3 worldOrigin,
                                                Vec3 worldDirection, Vec3 hitLocation) {
    SubLevel hitSubLevel = Sable.HELPER.getContaining(minecraft.level, hitLocation);
    if (hitSubLevel == null) return hitLocation;
    // Sable 在 plot 空间做 clip，返回的 hitLocation 是 plot 坐标
    Vector3d projected = new Vector3d(hitLocation.x, hitLocation.y, hitLocation.z);
    hitSubLevel.logicalPose().transformPosition(projected);  // 转回世界
    Vec3 worldHit = new Vec3(projected.x, projected.y, projected.z);
    // 用实际射线方向重建命中点，消除渲染姿态/逻辑姿态差
    double hitDistance = worldOrigin.distanceTo(worldHit);
    return worldOrigin.add(worldDirection.scale(hitDistance));
}
```

> **关键修复**：Sable 在 plot 空间做 raycast，返回的 `BlockHitResult.getLocation()` 是 plot 坐标，必须转回世界坐标才能给服务端用。

---

## 12. 附录：辅助工具与资源

### 12.1 `tools/convert_fire_control_model.ps1`

**用途**：把 Blockbench 导出的 `java_block` 格式 `.bbmodel` 转换为 Minecraft 原生方块模型 JSON。

**调用**：

```powershell
.\tools\convert_fire_control_model.ps1 -SourceModel path\to\model.bbmodel
```

**工作流**：

1. 校验 `meta.model_format == "java_block"`
2. 解析内嵌 base64 PNG 纹理，写入 `textures/block/fire_control_computer_NN.png`（00 起）
3. 把 Blockbench 像素 UV 缩放到 MC 16 单位制
4. 保留 `rotation`/`cullface`/`tintindex`
5. Blockbench 三轴旋转 → MC 单轴 `{origin, axis, angle, rescale}`
6. 输出 `src/main/resources/assets/stabilized_turret/models/block/stabilizer_controller.json`
   - 父模型：`minecraft:block/block`
   - `render_type`: `minecraft:cutout`

### 12.2 翻译键命名空间

| 前缀 | 数量 | 用途 |
|---|---|---|
| `block.stabilized_turret.*` | 1 | 方块名 |
| `itemGroup.stabilized_turret.*` | 1 | 创造组名 |
| `message.stabilized_turret.*` | 11 | 玩家操作反馈（绑定成功/失败、频率分配、需扳手等） |
| `screen.stabilized_turret.*` | 25 | GUI 标签（俯仰/方向/视角/瞄准点/频道） |
| `tooltip.stabilized_turret.*` | 9 | 物品 tooltip（按 Shift 显示） |

中英双语完整对应，文件：`assets/stabilized_turret/lang/{en_us,zh_cn}.json`。

### 12.3 参考源码

`reference/` 目录包含：

- **`reference/sable/`**：Sable 完整源码（Rust rapier binding + Java 平台层），含 Rust Cargo workspace、fabric/neoforge 多 loader
- **`reference/simulated/`**：Simulated + Aeronautics + Offroad 多模块源码

仅供 IDE 代码补全/查阅 API 使用，不参与构建。

### 12.4 运行验证场景

| 目录 | 用途 |
|---|---|
| `run_verify/` | 基础验证 |
| `run_verify3/` | 含崩溃报告 `crash-2026-07-17_13.28.11-server.txt` |
| `run_verify4/` | 长时间运行（多日 log gz） |
| `run_anchor_verify/` | 锚点（约束偏移）验证 |
| `run_anchor_limit_verify/` | 锚点 + 限位验证 |
| `run_absolute_aim_verify/` | 绝对瞄准验证 |
| `run_offset_verify/` | 偏移验证 |
| `run_physics_fix_verify/` | 物理修复验证 |

每个目录都包含完整 server 实例（`server.properties`、`world/`、`logs/`、`.sable/natives/`），可用于复现 bug 或验证修复。

---

## 文档版本

- 生成日期：2026-07-20
- 适用代码版本：`mod_version=0.1.0`（gradle.properties）
- 对应构建：`stabilized_turret-2026-07-20-0102.jar`（reference/ 目录中存放）

> 本文档基于源码静态分析生成，部分行为描述（如 Sable 内部物理步进顺序、CBC 火炮实体生命周期）可能随依赖升级而变化。如发现行为不一致，请以源码为准。
