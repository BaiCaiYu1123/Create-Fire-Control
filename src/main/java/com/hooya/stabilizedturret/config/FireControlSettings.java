package com.hooya.stabilizedturret.config;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.electronwill.nightconfig.core.io.ParsingException;
import com.electronwill.nightconfig.toml.TomlParser;
import com.hooya.stabilizedturret.content.missile.MissilePhysicsConfig;
import com.hooya.stabilizedturret.network.FireControlConfigStatePayload;
import com.hooya.stabilizedturret.network.SetFireControlConfigPayload;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PermissionsChangedEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.util.*;
import java.io.IOException;
import java.nio.file.Files;
import java.util.function.Consumer;

/** Instance-wide files. Gameplay settings are COMMON only to avoid NeoForge's per-save SERVER directory. */
public final class FireControlSettings {
    public static final String SERVER_FILE = "create_fire_control-server.toml";
    public static final ModConfigSpec SERVER_SPEC, COMMON_SPEC;
    public static final ModConfigSpec.BooleanValue RADAR_RAYCAST;
    public static final ModConfigSpec.BooleanValue EQUIVALENT_ARMOR;
    public static final ModConfigSpec.BooleanValue SHOW_PAUSE_BUTTON;
    public enum TrajectoryMode { MATHEMATICAL, ENTITY }
    public static final ModConfigSpec.EnumValue<TrajectoryMode> MISSILE_TRAJECTORY_MODE;
    private static final int MAX_CHANGES = 256;
    private static final Object REMOTE_VIEW_LOCK = new Object();
    private static volatile Map<String, Object> remoteValues;
    private static Map<String, Object> localValues;
    private static volatile boolean remoteEditable;
    private static volatile boolean remoteConnectionPending;

    static {
        var server = new ModConfigSpec.Builder();
        server.translation("create_fire_control.configuration.flight").push("flight");
        MISSILE_TRAJECTORY_MODE = server.comment("Default route for newly launched supported fire-control missiles. In-flight routes stay fixed. Anti-radiation missiles retain their forced mathematical route.")
                .translation("create_fire_control.configuration.flight.trajectoryMode")
                .defineEnum("trajectoryMode", TrajectoryMode.MATHEMATICAL);
        server.pop();
        server.comment("雷达扫描设置，由服务器统一生效。")
                .translation("create_fire_control.configuration.radar").push("radar");
        RADAR_RAYCAST = server.comment("开启：方块会阻挡雷达扫描及红外格斗弹、空对地导弹的目标搜索、锁定和发射前校验；关闭：这些判定忽略方块遮挡。")
                .translation("create_fire_control.configuration.radar.raycast")
                .define("raycast", false);
        server.pop();
        server.translation("create_fire_control.configuration.armor").push("armor");
        EQUIVALENT_ARMOR=server.comment("全局等效装甲：对所有 CBC 及附属弹丸，按射线首次接触至最后离开的距离换算方块硬度和韧性。方块基础值视为 1 米装甲；中间空腔计入厚度。兼容检测工具与终端弹道学。默认关闭。")
                .translation("create_fire_control.configuration.armor.equivalentArmor")
                .define("equivalentArmor",false);
        server.pop();
        MissileSettings.register(server);
        SERVER_SPEC = server.build();
        var common = new ModConfigSpec.Builder();
        common.comment("通用界面选项，保存在当前游戏实例的 config 目录。")
                .translation("create_fire_control.configuration.general").push("general");
        SHOW_PAUSE_BUTTON = common.comment("在暂停菜单显示火控配置快捷按钮。")
                .translation("create_fire_control.configuration.general.showPauseButton")
                .define("showPauseButton", true);
        common.pop();
        COMMON_SPEC = common.build();
    }

    public static void register(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.COMMON, SERVER_SPEC, SERVER_FILE);
        container.registerConfig(ModConfig.Type.COMMON, COMMON_SPEC, "create_fire_control-common.toml");
        container.registerConfig(ModConfig.Type.COMMON, MissilePhysicsConfig.SPEC, "create_fire_control-ballistics.toml");
        modBus.addListener(FireControlSettings::onReload);
        NeoForge.EVENT_BUS.addListener(FireControlSettings::onLogin);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, FireControlSettings::onPermissionsChanged);
    }

    public static boolean radarRaycastEnabled() {
        var remote = remoteValues;
        return remote != null && remote.get("radar.raycast") instanceof Boolean value
                ? value : SERVER_SPEC.isLoaded() ? RADAR_RAYCAST.get() : RADAR_RAYCAST.getDefault();
    }
    public static boolean equivalentArmorEnabled(){
        var remote=remoteValues;
        return remote!=null&&remote.get("armor.equivalentArmor") instanceof Boolean value?value:
                SERVER_SPEC.isLoaded()?EQUIVALENT_ARMOR.get():EQUIVALENT_ARMOR.getDefault();
    }

    public static TrajectoryMode missileTrajectoryMode() {
        var remote = remoteValues;
        return remote != null && remote.get("flight.trajectoryMode") instanceof TrajectoryMode value
                ? value : SERVER_SPEC.isLoaded() ? MISSILE_TRAJECTORY_MODE.get() : MISSILE_TRAJECTORY_MODE.getDefault();
    }

    public static boolean mathematicalMissileFlight() {
        return missileTrajectoryMode() == TrajectoryMode.MATHEMATICAL;
    }

    public static boolean showPauseButton() {
        return COMMON_SPEC.isLoaded() ? SHOW_PAUSE_BUTTON.get() : SHOW_PAUSE_BUTTON.getDefault();
    }

    /** Numeric access stays authoritative on remote clients, including effect clocks. */
    public static double number(String path, double localDefault) {
        var remote = remoteValues;
        return remote != null && remote.get(path) instanceof Number value ? value.doubleValue() : localDefault;
    }

    public static boolean serverValuesSynced() {
        return !remoteConnectionPending;
    }

    public static boolean canEditServer() {
        return !remoteConnectionPending && (remoteValues == null || remoteEditable);
    }

    /** Called by the configuration screen. Connected remote values never get saved to a local file. */
    public static void saveServerChanges(Map<String, Object> changes) {
        var copy = Map.copyOf(changes);
        if (remoteValues != null) {
            if (remoteEditable) PacketDistributor.sendToServer(new SetFireControlConfigPayload(encode(copy)));
            return;
        }
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) server.execute(() -> applyChanges(copy));
        else applyChanges(copy);
    }

    public static void handleChanges(ServerPlayer sender, CompoundTag tag) {
        if (!sender.hasPermissions(2)) {
            sendSnapshot(sender);
            return;
        }
        var changes = decode(tag);
        if (changes != null) applyChanges(changes);
        sendSnapshot(sender);
    }

    private static boolean applyChanges(Map<String, Object> input) {
        var values = validatedChanges(input);
        if (values == null) return false;
        var changedSpecs = new LinkedHashSet<ModConfigSpec>();
        values.forEach((path, value) -> {
            var spec = owner(path);
            set(spec.getValues().get(path), value);
            changedSpecs.add(spec);
        });
        changedSpecs.forEach(ModConfigSpec::save);
        broadcast();
        return true;
    }

    /** Reject unknown keys, non-finite numbers and out-of-range values before applying any of a packet. */
    static Map<String, Object> validatedChanges(Map<String, Object> input) {
        if (input.isEmpty() || input.size() > MAX_CHANGES) return null;
        var result = new LinkedHashMap<String, Object>();
        for (var entry : input.entrySet()) {
            String path = entry.getKey();
            if (path == null || path.length() > 160) return null;
            var spec = owner(path);
            if (spec == null) return null;
            ModConfigSpec.ConfigValue<?> field = spec.getValues().get(path);
            Object value = normalize(field.getDefault(), entry.getValue());
            if (value == null || !field.getSpec().test(value)) return null;
            result.put(path, value);
        }
        return result;
    }

    private static Object normalize(Object example, Object input) {
        if (example instanceof Boolean) return input instanceof Boolean ? input : null;
        if (example instanceof Number && input instanceof Number number) {
            double value = number.doubleValue();
            if (!Double.isFinite(value)) return null;
            if (example instanceof Integer) return value == Math.rint(value) && value >= Integer.MIN_VALUE
                    && value <= Integer.MAX_VALUE ? Integer.valueOf((int) value) : null;
            if (example instanceof Long) return value == Math.rint(value) && value >= Long.MIN_VALUE
                    && value <= Long.MAX_VALUE ? Long.valueOf((long) value) : null;
            return Double.valueOf(value);
        }
        if (example instanceof Enum<?> enumeration && input instanceof String name) {
            for (Object constant : enumeration.getDeclaringClass().getEnumConstants())
                if (((Enum<?>) constant).name().equals(name)) return constant;
            return null;
        }
        return example.getClass().isInstance(input) ? input : null;
    }

    private static ModConfigSpec owner(String path) {
        for (var spec : gameplaySpecs())
            if (spec.getValues().get(path) instanceof ModConfigSpec.ConfigValue<?>) return spec;
        return null;
    }

    private static List<ModConfigSpec> gameplaySpecs() {
        return List.of(SERVER_SPEC, MissilePhysicsConfig.SPEC);
    }

    private static Map<String, Object> snapshot() {
        var result = new LinkedHashMap<String, Object>();
        for (var spec : gameplaySpecs()) forEach(spec.getValues(), field -> result.put(
                String.join(".", field.getPath()), spec.isLoaded() ? field.get() : field.getDefault()));
        return result;
    }

    private static void forEach(UnmodifiableConfig group, Consumer<ModConfigSpec.ConfigValue<?>> action) {
        for (Object entry : group.valueMap().values()) {
            if (entry instanceof ModConfigSpec.ConfigValue<?> field) action.accept(field);
            else if (entry instanceof UnmodifiableConfig child) forEach(child, action);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void set(ModConfigSpec.ConfigValue field, Object value) { field.set(value); }

    public static CompoundTag encode(Map<String, Object> values) {
        var tag = new CompoundTag();
        values.forEach((path, value) -> {
            if (value instanceof Boolean flag) tag.putBoolean(path, flag);
            else if (value instanceof Number number) tag.putDouble(path, number.doubleValue());
            else if (value instanceof Enum<?> enumeration) tag.putString(path, enumeration.name());
            else if (value instanceof String string) tag.putString(path, string);
        });
        return tag;
    }

    private static Map<String, Object> decode(CompoundTag tag) {
        if (tag.size() > MAX_CHANGES) return null;
        var values = new LinkedHashMap<String, Object>();
        for (String path : tag.getAllKeys()) {
            Tag value = tag.get(path);
            if (value instanceof ByteTag byteTag) values.put(path, byteTag.getAsByte() != 0);
            else if (value instanceof NumericTag number) values.put(path, number.getAsDouble());
            else if (value instanceof StringTag string) values.put(path, string.getAsString());
            else return null;
        }
        return values;
    }

    public static void receiveSnapshot(CompoundTag tag, boolean editable) {
        // Integrated servers share their spec with the client; avoid replacing server-owned values.
        if (ServerLifecycleHooks.getCurrentServer() != null) return;
        var decoded = decode(tag);
        var validated = decoded == null ? null : validatedChanges(decoded);
        if (validated == null) return;
        synchronized (REMOTE_VIEW_LOCK) {
            if (localValues == null) localValues = snapshot();
            remoteValues = Map.copyOf(validated);
            remoteEditable = editable;
            remoteConnectionPending = false;
            applyRemoteView();
        }
    }

    private static void applyRemoteView() {
        var remote = remoteValues;
        if (remote == null) return;
        remote.forEach((path, value) -> {
            var spec = owner(path);
            if (spec != null && spec.isLoaded()) set(spec.getValues().get(path), value);
        });
    }

    public static void clearRemoteView() {
        synchronized (REMOTE_VIEW_LOCK) {
            remoteConnectionPending = false;
            remoteValues = null;
            remoteEditable = false;
            if (localValues != null) {
                localValues.forEach((path, value) -> {
                    var spec = owner(path);
                    if (spec != null && spec.isLoaded()) set(spec.getValues().get(path), value);
                });
                localValues = null;
            }
        }
    }

    public static void beginConnection() {
        synchronized (REMOTE_VIEW_LOCK) {
            remoteConnectionPending = ServerLifecycleHooks.getCurrentServer() == null && remoteValues == null;
        }
    }

    private static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) sendSnapshot(player);
    }

    private static void onPermissionsChanged(PermissionsChangedEvent event) {
        if (!event.isCanceled() && event.getEntity() instanceof ServerPlayer player) {
            // This event runs before the player list changes; use its new level for the UI snapshot.
            PacketDistributor.sendToPlayer(player, new FireControlConfigStatePayload(
                    encode(snapshot()), event.getNewLevel() >= 2));
        }
    }

    private static void sendSnapshot(ServerPlayer player) {
        PacketDistributor.sendToPlayer(player, new FireControlConfigStatePayload(
                encode(snapshot()), player.hasPermissions(2)));
    }

    private static void broadcast() {
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) for (var player : server.getPlayerList().getPlayers()) sendSnapshot(player);
    }

    private static void onReload(ModConfigEvent.Reloading event) {
        if (!gameplaySpecs().contains(event.getConfig().getSpec())) return;
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) server.execute(FireControlSettings::broadcast);
        else synchronized (REMOTE_VIEW_LOCK) {
            if (remoteValues != null) {
                // The file watcher may reload this machine's file during a remote session. Keep it for logout.
                var reloaded = (ModConfigSpec) event.getConfig().getSpec();
                // Read the file, because another client packet may already have overlaid the live ConfigValues.
                try (var reader = Files.newBufferedReader(event.getConfig().getFullPath())) {
                    var disk = new TomlParser().parse(reader);
                    var replacement = new LinkedHashMap<>(localValues);
                    forEach(reloaded.getValues(), field -> {
                        Object value = normalize(field.getDefault(), disk.get(field.getPath()));
                        replacement.put(String.join(".", field.getPath()), value != null && field.getSpec().test(value)
                                ? value : field.getDefault());
                    });
                    localValues = replacement;
                } catch (IOException | ParsingException ignored) {
                    // A transient external replacement keeps the last valid local snapshot for disconnect.
                }
                applyRemoteView();
            }
        }
    }

    private FireControlSettings() {}
}
