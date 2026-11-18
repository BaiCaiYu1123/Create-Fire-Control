# 遥控器公开接口

入口是 `com.hooya.stabilizedturret.api.remote.RemoteControllerApi`。适用于 Minecraft 1.21.1 / NeoForge，当前模组版本 **0.9.1**，`API_LEVEL = 1`。

这套接口覆盖遥控器的功能逻辑：物品与配置、频道和组合键、终端配对、线缆连接、火控映射、发动机终端、讲台存取、操控会话以及第三方按键动作。接口没有模型动画、键帽动画或遥控器音效入口。`startControl` / `startLecternControl` 创建纯逻辑会话，不发送客户端操控表现包、不播放声音，也不让讲台进入动画或键帽下压状态。原来的玩家手动使用保持原有表现。

## 依赖与线程

开发时将完整模组 JAR 加入 `compileOnly`；游戏运行时安装完整模组及它的正常依赖。`create-fire-control-0.9.1-controller-api-sources.jar` 提供接口源码和本说明，供 IDE 查看，不用作为游戏模组安装。

```groovy
dependencies {
    compileOnly files('libs/create-fire-control-0.9.1.jar')
}
```

第三方模组的 `META-INF/neoforge.mods.toml` 可声明必需依赖：

```toml
[[dependencies.your_mod_id]]
modId="create_fire_control"
type="required"
versionRange="[0.9.1,)"
ordering="AFTER"
side="BOTH"
```

本次继续使用 0.9.1，早期同版本构建还没有这个接口，因此集成时应使用本文对应的成品 JAR。如果第三方模组允许早期构建共存，需要先按类名检查 `RemoteControllerApi` 是否存在，再加载集成代码；入口类的 `API_LEVEL` 用于区分接口能力，不能只凭模组版本号判断。

调用任何带 `ServerLevel` / `ServerPlayer` 的方法时必须在服务端线程；来自网络或异步任务的请求应先由调用方验证权限，再通过 `server.execute(...)` 或 NeoForge 网络处理器的 `enqueueWork(...)` 调用。接口不会替第三方网络包决定谁有权修改设备。涉及世界的方法只访问已加载方块，不强制加载区块。

`registerActions` 可以在公共初始化的 `enqueueWork` 中注册；服务端运行时取消注册应在服务端线程。纯数据对象、`supportedInputs` 和配置快照可供公共代码使用；创建或读取物品应在注册表就绪之后。

## 数据类型

所有类型均位于 `RemoteControllerApi` 中，不需要导入实现包。

| 类型 | 用途 |
| --- | --- |
| `Key` | GLFW 键码或从 0 开始的鼠标按钮编号，例如 `Key.keyboard(87)` 是 W，`Key.mouse(0)` 是左键，`Key.mouse(3)` 是侧键 MB4。`Key.scroll(true)` / `Key.scroll(false)` 表示滚轮上滚／下滚，存储名为 `scroll_up` / `scroll_down`。`Key.parse(...)` 可解析这些存储名。 |
| `Chord` | 一个或两个不同按键，`Chord.of(W, SHIFT)`；超过两键、重复键或未知键会被拒绝。 |
| `Binding` | 组合键、最多 48 字符的标签、两只频道物品、启用状态和 `signalStrength`（0–16，默认 16）。频道使用物品种类，忽略数量和组件；输入和返回的物品都做防御复制。 |
| `Configuration` | 退出键、配色、全部红石绑定。同一个主键只允许一条绑定；可用空绑定列表清空红石配置。 |
| `Color` | `ORIGINAL`、`RED_YELLOW`、`PURPLE_GREEN`、`BLUE_RED`；`next()` 对应切换颜色。 |
| `Terminal` | 维度、位置和持久身份 UUID。绑定保存 Sable 位置引用及终端身份，原位置换掉终端后不会继承旧连接。 |
| `ControlHandle` | 玩家 UUID 和本次会话令牌，防止旧句柄操作新会话或跨玩家操作。 |
| `ControlState` | 只读会话快照，包含有效物理输入、当前按住的输入、激活的输出，以及讲台和终端信息。 |

## 配置、绑定和火控映射

下面的代码在服务端线程执行。终端和火控计算机都已经存在于世界中。

```java
import com.hooya.stabilizedturret.api.remote.RemoteControllerApi;
import com.hooya.stabilizedturret.api.remote.RemoteControllerApi.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import java.util.List;

Key w = Key.keyboard(87);
Key shift = Key.keyboard(340);
Chord combination = Chord.of(w, shift);

ItemStack remote = RemoteControllerApi.createController();
Configuration settings = new Configuration(
    Key.keyboard(258), // 默认退出键 Tab
    Color.BLUE_RED,
    List.of(new Binding(combination, "前进", new ItemStack(Items.IRON_INGOT),
                        new ItemStack(Items.REDSTONE), true, 7)));

RemoteControllerApi.configure(level, remote, settings);
RemoteControllerApi.bindTerminal(level, remote, terminalPos);
player.setItemInHand(InteractionHand.MAIN_HAND, remote);
player.containerMenu.broadcastChanges();

RemoteControllerApi.bindFireControl(level, computerPos, terminalPos);
RemoteControllerApi.mapFireControl(level, computerPos,
    FireControlAction.MACHINE_GUN, combination);

// 修改已持有或已放在讲台中的遥控器，使用会同步变化的入口。
RemoteControllerApi.configureHeld(player, InteractionHand.MAIN_HAND, settings);
RemoteControllerApi.configureLectern(level, lecternPos, settings);
```

`configure`、重新配对和解绑会停止该物品身份的旧会话并释放输出，保留其他物品数据。修改手持物品请用 `configureHeld` / `bindHeldTerminal`；修改讲台物品请用 `configureLectern` / `bindLecternTerminal`，这些入口也负责对应同步。`configure` / `bindTerminal` 适合尚未放入背包或讲台的物品。

每个 `Binding` 的 `signalStrength()` 作用于无线红石频道和可选的实体线缆输出；完整组合键触发时输出该值，释放任一键或结束操控时归零。设为 0 时不输出红石信号，火控／发动机功能映射仍按实际按键状态生效。只使用线缆的按键也可用两只空频道物品建立绑定，保存独立强度。五参数旧构造器继续可用，默认 16；新增六参数构造器的最后一个参数指定强度，超出 0–16 会抛出 `IllegalArgumentException`。旧物品缺少强度字段时按 16 读取，强度随物品、讲台及 Sable 蓝图保存。动作回调仍表示按键按下／松开，不承载模拟信号值。

`saveController` / `loadController` 保存和恢复完整物品，保留身份、终端、频道、组合键和配色。它们用于恢复原物品；要创建独立的新遥控器，请用 `createController` 再应用配置与配对。Sable 蓝图继续使用已注册的映射器，共同复制讲台和终端时会自动重映射身份和连接。

## 纯逻辑操控

```java
ControlHandle handle = RemoteControllerApi.startControl(
    player, InteractionHand.MAIN_HAND).orElseThrow();

RemoteControllerApi.press(player, handle, w);
RemoteControllerApi.press(player, handle, shift); // 两键齐全才触发对应频道
RemoteControllerApi.release(player, handle, w); // 释放任一键立即关闭组合键输出
RemoteControllerApi.setInputs(player, handle, java.util.Set.of()); // 清空所有按住的键
RemoteControllerApi.stopControl(player, handle);
```

滚轮使用 `RemoteControllerApi.scroll(player, handle, true, 3)` 排队发送三格上滚；`false` 表示下滚，次数为 1–16。每格产生独立的按下与释放，适用于无线频道和线控输出，并遵守每键强度。不要把滚轮作为长期按住的键发送；API 操控仍不触发动画和音效。滚轮脉冲排队期间可继续更新其他按键，结束会话会丢弃剩余脉冲。

也可以每个游戏刻发送完整的 `setInputs(player, handle, heldKeys)` 快照。输入不变时至少每 30 个游戏刻内调用一次 `keepAlive` 或 `setInputs`；建议每 5 个游戏刻调用，失去心跳后释放所有输出。`press` / `release` 是增量操作，长按不重复产生按下边沿；多个键和多个频道可以同时操作。

退出键仍被保留：输入退出键会停止会话，返回 `false`。死亡、离线、换维度、移走手持物品、主手切换槽位、超出讲台距离或取回遥控器会释放输出。API 和玩家原生会话共享终端和讲台独占规则；占用中返回空值，接口不会抢走其他会话。已有同一 API 会话时重复启动返回原句柄。

`controlState(player)` 可读取 API 或玩家原生会话；`apiControlled()` 区分来源。只有 API 创建的会话可以通过句柄输入和停止；旧令牌及其他玩家的令牌不会生效。纯逻辑会话不接管真实键盘、鼠标或玩家视角，第三方模组提供输入；原生操控仍保持左右鼠标键接管规则。

## 为第三方设备注册按键动作

```java
Registration registration = RemoteControllerApi.registerActions(
    net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("your_mod", "machine"),
    new ActionProvider() {
        @Override
        public java.util.Set<Key> inputs(net.minecraft.server.level.ServerLevel world,
                                        net.minecraft.core.BlockPos terminal) {
            // 只在该终端确实连接了你的设备且该动作可用时返回对应按键。
            return hasConnectedMachine(world, terminal) ? java.util.Set.of(w) : java.util.Set.of();
        }

        @Override
        public void input(net.minecraft.server.level.ServerLevel world,
                          net.minecraft.core.BlockPos terminal, Key key, boolean pressed) {
            setMachineInput(world, terminal, pressed);
        }
    });

// 不再使用时，在服务端线程释放注册；仍按住的键会收到 false。
registration.close();
```

`inputs` 是查询，不应修改世界，不能返回 null。`input` 在服务端线程按边沿调用；按键松开、动作移除、会话结束及取消注册都会发送释放。该按键同时被红石或其他设备使用时，移除你的动作也会释放你的回调，其他输出继续正常工作。注册 ID 必须唯一，重复注册会报错，重复关闭无影响。

键盘与终端绑定、火控／发动机功能映射、第三方动作和无线红石逻辑均由本模组提供，不要求 Drive By Wire。键盘输入包经本模组验证会话后，直接供火控和发动机读取；安装 Drive By Wire 只增加实体线缆输出。未安装时 `wiresAvailable` 返回 false，线缆操作返回 false，其余功能正常运行。

## 接口索引

| 功能 | 方法 |
| --- | --- |
| 物品、身份、支持的输入 | `createController`、`isController`、`identity`、`supportedInputs` |
| 读取／写入配置 | `configuration`、`configure`、`configureHeld`、`configureLectern` |
| 完整物品保存／恢复 | `saveController`、`loadController` |
| 终端查询、配对与解绑 | `terminal`、`boundTerminal`、`bindTerminal`、`unbindTerminal`、`bindHeldTerminal`、`unbindHeldTerminal`、`bindLecternTerminal`、`unbindLecternTerminal` |
| 线缆查询、连接与断开 | `wiresAvailable`、`wiredInputs`、`actionInputs`、`connectWire`、`disconnectWire` |
| 火控终端绑定、读取与解绑 | `bindFireControl`、`fireControlTerminal`、`unbindFireControl` |
| 火控按键映射 | `mapFireControl`、`clearFireControlMapping`、`fireControlMappings` |
| 燃气轮机／99A／八缸发动机终端 | `bindEngine`、`engineTerminal`、`unbindEngine`，使用发动机控制部件的位置 |
| 讲台存取与交换 | `mount`、`retrieve`、`swapLectern`、`lecternController` |
| 操控会话及输入 | `startControl`、`startLecternControl`、`controlState`、`setInputs`、`press`、`release`、`scroll`、`keepAlive`、`stopControl` |
| 打开原生配置页 | `openConfiguration`，仅打开界面，不激活操控 |
| 注册第三方动作 | `registerActions` 与 `Registration.close` |

`retrieve` 返回一只完整物品并恢复普通讲台，调用方负责把它放入背包；接口不会额外生成掉落物。`mount`、`retrieve`、`swapLectern` 不播放声音。讲台纯逻辑操控沿用距离限制与单用户规则，不改变显示模型。

## 火控动作枚举

| `FireControlAction` | 功能 |
| --- | --- |
| `SCOPE` | 开启炮镜 |
| `THERMAL` | 热成像 |
| `OPTICAL_LOCK` | 光电锁定 |
| `ANTI_TANK_MISSILE` | 反坦克导弹发射 |
| `ANTI_AIR_MISSILE` | 防空导弹发射 |
| `RADAR_LOCK` | 雷达锁定 |
| `AIRCRAFT_WEAPON` | 机载武器发射 |
| `CYCLE_AIRCRAFT_WEAPON` | 向下循环切换武器（保持旧编号 7） |
| `CYCLE_AIRCRAFT_WEAPON_UP` | 向上循环切换武器（新增编号 16） |
| `CYCLE_AIRCRAFT_RADAR_TARGET` | 切换机载雷达目标 |
| `POD_VIEW` | 光电吊舱视角 |
| `AIR_TO_GROUND_LOCK` | 空对地导弹锁定 |
| `COUNTERMEASURE` | 发射热诱弹 |
| `FREE_LOOK` | 自由视角 |
| `TELEVISION_GUIDANCE` | 进入／退出电视制导视角 |
| `MACHINE_GUN` | 机枪开火 |
| `LASER_RANGE` | 激光测距 |

映射使用一个或两个按键。清除映射使用 `clearFireControlMapping`；未映射的动作不出现在 `fireControlMappings` 的结果中。
