package com.hooya.stabilizedturret.network;

import com.hooya.stabilizedturret.content.bearing.YawLimits;
import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.locale.Language;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.fml.loading.LoadingModList;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

public final class OpenControllerPayloadRoundTrip {
    public static void main(String[] args) throws IOException {
        // Component codecs also initialize hover-event registries in this standalone test.
        LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        List<String> normalKeys = IntStream.range(0, com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity.INPUT_ACTION_COUNT)
                .mapToObj(index -> "normal_" + index)
                .toList();
        List<String> advancedKeys = IntStream.range(0, com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity.INPUT_ACTION_COUNT)
                .mapToObj(index -> "advanced_" + index)
                .toList();
        List<OpenControllerPayload.BoundDevice> devices = List.of(
                new OpenControllerPayload.BoundDevice(
                        "YAW", 0, "block.test.yaw", new BlockPos(4, 5, 6),
                        true, "SERIAL", 2, true, 0, 180, 180, 40, false, true, 0, -.5, 0,
                        new YawLimits(36.75, 112.5, YawLimits.WEST)),
                new OpenControllerPayload.BoundDevice(
                        "CANNON_MOUNT", 1, "block.test.mount", new BlockPos(7, 8, 9),
                        false, "POD_CONTROL", 3, false)
        );
        net.minecraft.nbt.CompoundTag inputState=new net.minecraft.nbt.CompoundTag();
        inputState.putBoolean("WIRELESSAvailable",true);inputState.putString("test","two-input metadata");
        inputState.putIntArray("GroundWeaponActions", new int[]{17, 18, 19});
        inputState.putIntArray("AircraftWeaponActions", new int[]{20, 22, 28, 42});
        OpenControllerPayload original = new OpenControllerPayload(
                new BlockPos(1, 2, 3),
                UUID.fromString("00000000-0000-0000-0000-000000000020"),
                1.0, 2.0, 3.0, new YawLimits(85.5, 180, YawLimits.EAST), 4.0,
                true, false,
                true, false,
                5.0, 6.0, 7.0,
                8.0, 9.0, 10.0,
                11.0, 12.0, 13.0,
                14.0, 15.0, 16.0,
                6,
                true, false, 63.25, true, false, true, false,
                17.0, 18.0,
                19.0, 20.0, 21.0, 22.0, 23.0, 24.0, 25.0,
                26.0, 27.0, 28.0, 29.0, 30.0, 31.0, 32.0, 33.0, 34.0,
                35.0, 36.0,
                true,
                Component.translatable("item.minecraft.diamond").withStyle(ChatFormatting.AQUA), Component.literal("自定义频道 / Custom channel").withStyle(ChatFormatting.GOLD),
                Component.literal("thermal_a"), Component.literal("thermal_b"),
                Component.literal("optical_a"), Component.literal("optical_b"),
                Component.literal("missile_a"), Component.literal("missile_b"),
                Component.literal("aa_a"), Component.literal("aa_b"),
                Component.literal("radar_a"), Component.literal("radar_b"),
                Component.literal("air_fire_a"), Component.literal("air_fire_b"),
                Component.literal("weapon_a"), Component.literal("weapon_b"),
                Component.literal("target_a"), Component.literal("target_b"),
                Component.literal("pod_a"), Component.literal("pod_b"),
                Component.literal("ground_a"), Component.literal("ground_b"),
                Component.literal("flare_a"), Component.literal("flare_b"),
                Component.literal("free_look_a"), Component.literal("free_look_b"),
                Component.literal("television_a"), Component.literal("television_b"),
                Component.literal("laser_range_a"), Component.literal("laser_range_b"),
                normalKeys, advancedKeys, devices, inputState,
                true, "virtual_scope_not_coaxial"
        );

        RegistryFriendlyByteBuf buffer =
                new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        OpenControllerPayload.STREAM_CODEC.encode(buffer, original);
        OpenControllerPayload decoded = OpenControllerPayload.STREAM_CODEC.decode(buffer);
        SetFireControlInputPayload inputEdit=new SetFireControlInputPayload(original.pos(),14,2,"mouse_0",
                net.minecraft.world.item.ItemStack.EMPTY,net.minecraft.world.item.ItemStack.EMPTY);
        RegistryFriendlyByteBuf editBuffer=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);
        SetFireControlInputPayload.STREAM_CODEC.encode(editBuffer,inputEdit);
        if(!inputEdit.equals(SetFireControlInputPayload.STREAM_CODEC.decode(editBuffer))||editBuffer.isReadable())
            throw new AssertionError("Terminal input edit did not round trip");
        FireControlInputsStatePayload inputAck=new FireControlInputsStatePayload(original.pos(),inputState,true);
        RegistryFriendlyByteBuf ackBuffer=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);
        FireControlInputsStatePayload.STREAM_CODEC.encode(ackBuffer,inputAck);
        if(!inputAck.equals(FireControlInputsStatePayload.STREAM_CODEC.decode(ackBuffer))||ackBuffer.isReadable())
            throw new AssertionError("Authoritative input state did not round trip");
        if (!original.equals(decoded)) {
            throw new AssertionError("OpenControllerPayload round trip changed data");
        }

        if (!(decoded.scopeFirst().getContents() instanceof TranslatableContents translated)
                || !translated.getKey().equals("item.minecraft.diamond")
                || !decoded.scopeFirst().getStyle().equals(original.scopeFirst().getStyle())
                || !decoded.scopeSecond().equals(original.scopeSecond())) {
            throw new AssertionError("Frequency names lost translation keys, styling or custom names");
        }
        testFrequencyLocalization(decoded);
        YawLimits yawLimits = new YawLimits(73.5, 110.25, YawLimits.NORTH);
        ControllerBearingSettingsPayload bearing = new ControllerBearingSettingsPayload(original.pos(),
                original.controllerSubLevelId(), "YAW", new BlockPos(4, 5, 6),
                180, 180, 40, false, true, 0, -.5, 0, yawLimits);
        RegistryFriendlyByteBuf bearingBuffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        ControllerBearingSettingsPayload.STREAM_CODEC.encode(bearingBuffer, bearing);
        if (!bearing.equals(ControllerBearingSettingsPayload.STREAM_CODEC.decode(bearingBuffer)) || bearingBuffer.isReadable())
            throw new AssertionError("Per-bearing yaw limits did not round trip");
        ControllerGlobalBearingSettingsPayload global = new ControllerGlobalBearingSettingsPayload(original.pos(),
                original.controllerSubLevelId(), "YAW", 259, 180, 180, 40, false, true, 0, -.5, 0, yawLimits);
        RegistryFriendlyByteBuf globalBuffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        ControllerGlobalBearingSettingsPayload.STREAM_CODEC.encode(globalBuffer, global);
        if (!global.equals(ControllerGlobalBearingSettingsPayload.STREAM_CODEC.decode(globalBuffer)) || globalBuffer.isReadable())
            throw new AssertionError("Global yaw limits and center mask did not round trip");


        testConnectedDisplayConsolePayloads();
        if (buffer.readableBytes() != 0) {
            throw new AssertionError(
                    "OpenControllerPayload decoder left " + buffer.readableBytes() + " unread bytes");
        }

        ControllerSettingsPayload settingsOriginal = new ControllerSettingsPayload(
                new BlockPos(3, 4, 5),
                UUID.fromString("00000000-0000-0000-0000-000000000021"),
                1.0, 2.0, 3.0, 4.0,
                true, false, true, false,
                5.0, 6.0, 7.0,
                8.0, 9.0, 10.0,
                11.0, 12.0, 13.0,
                14.0, 15.0, 16.0,
                6,
                true, true, 87.5, false, false, true,
                17.0, 18.0, 19.0, 20.0,
                21.0, 22.0, 23.0,
                24.0, 25.0, 26.0, 27.0,
                28.0, 29.0, 30.0, 31.0, 32.0,
                33.0, 34.0,
                true,
                true
        );
        RegistryFriendlyByteBuf settingsBuffer =
                new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        ControllerSettingsPayload.STREAM_CODEC.encode(settingsBuffer, settingsOriginal);
        ControllerSettingsPayload settingsDecoded =
                ControllerSettingsPayload.STREAM_CODEC.decode(settingsBuffer);
        if (!settingsOriginal.equals(settingsDecoded) || settingsBuffer.readableBytes() != 0) {
            throw new AssertionError("ControllerSettingsPayload round trip changed data");
        }

        testRadarSessionRoundTrip();

        UUID displaySubLevel = UUID.fromString(
                "00000000-0000-0000-0000-000000000010");
        AutoDefenseSettingsPayload displaySettingsOriginal =
                new AutoDefenseSettingsPayload(
                        new BlockPos(11, 12, 13), displaySubLevel, true, true, false, true, true,
                        2_500.0, 7.5, 4_500.0, 120.0, 90.0, 20.0, 45.0,
                        false, true, 175.0, 15, 30, 256);
        RegistryFriendlyByteBuf displaySettingsBuffer =
                new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        AutoDefenseSettingsPayload.STREAM_CODEC.encode(
                displaySettingsBuffer, displaySettingsOriginal);
        AutoDefenseSettingsPayload displaySettingsDecoded =
                AutoDefenseSettingsPayload.STREAM_CODEC.decode(displaySettingsBuffer);
        if (!displaySettingsOriginal.equals(displaySettingsDecoded)
                || displaySettingsBuffer.readableBytes() != 0) {
            throw new AssertionError(
                    "AutoDefenseSettingsPayload round trip changed data");
        }

        OpenAutoDefenseDisplayPayload displayOriginal =
                new OpenAutoDefenseDisplayPayload(
                         new BlockPos(14, 15, 16), displaySubLevel, false, true, false, true, true,
                        0.0, 6.5, 7_000.0, 180.0, 140.0, 15.0, 50.0,
                        true, false, 160.0, 15, 45, 512,
                        List.of(new OpenAutoDefenseDisplayPayload.Device(
                                "RADAR_BEARING", new BlockPos(17, 18, 19))));
        RegistryFriendlyByteBuf displayBuffer =
                new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        OpenAutoDefenseDisplayPayload.STREAM_CODEC.encode(
                displayBuffer, displayOriginal);
        OpenAutoDefenseDisplayPayload displayDecoded =
                OpenAutoDefenseDisplayPayload.STREAM_CODEC.decode(displayBuffer);
        if (!displayOriginal.equals(displayDecoded)
                || displayBuffer.readableBytes() != 0) {
            throw new AssertionError(
                    "OpenAutoDefenseDisplayPayload round trip changed data");
        }

        List<OpenAutoDefenseDisplayPayload.Device> oversizedDevices =
                IntStream.range(0, 70)
                        .mapToObj(index -> new OpenAutoDefenseDisplayPayload.Device(
                                "DEVICE_" + index, new BlockPos(index, 64, -index)))
                        .toList();
        OpenAutoDefenseDisplayPayload oversizedDisplay =
                new OpenAutoDefenseDisplayPayload(
                        displayOriginal.pos(), displayOriginal.subLevelId(),
                        displayOriginal.automaticAttack(),
                        displayOriginal.scanStructures(),
                        displayOriginal.scanLiving(),
                        displayOriginal.scanMissiles(),
                        displayOriginal.automaticMissileDefense(),
                        displayOriginal.automaticMissileDefenseRange(),
                        displayOriginal.missileProximityRange(), displayOriginal.radarRange(),
                        displayOriginal.yawSpeed(), displayOriginal.pitchSpeed(),
                        displayOriginal.depressionLimit(), displayOriginal.elevationLimit(),
                        displayOriginal.yawReversed(), displayOriginal.pitchReversed(),
                        displayOriginal.cannonFireRange(),
                        displayOriginal.cannonFirePower(),
                        displayOriginal.mapUpdateIntervalSeconds(), displayOriginal.mapResolution(),
                        oversizedDevices);
        RegistryFriendlyByteBuf oversizedBuffer =
                new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        OpenAutoDefenseDisplayPayload.STREAM_CODEC.encode(oversizedBuffer, oversizedDisplay);
        OpenAutoDefenseDisplayPayload truncatedDisplay =
                OpenAutoDefenseDisplayPayload.STREAM_CODEC.decode(oversizedBuffer);
        if (truncatedDisplay.devices().size() != 64 || oversizedBuffer.readableBytes() != 0) {
            throw new AssertionError("OpenAutoDefenseDisplayPayload did not enforce its 64-device limit");
        }

        RegistryFriendlyByteBuf maliciousDisplayBuffer =
                new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        writeAutoDefenseDisplayHeader(maliciousDisplayBuffer, displayOriginal);
        maliciousDisplayBuffer.writeVarInt(65);
        boolean rejected = false;
        try {
            OpenAutoDefenseDisplayPayload.STREAM_CODEC.decode(maliciousDisplayBuffer);
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        if (!rejected) {
            throw new AssertionError("OpenAutoDefenseDisplayPayload accepted 65 devices");
        }

        UUID cannonUuid = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID cannonSubLevel = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID pitchSubLevel = UUID.fromString("00000000-0000-0000-0000-000000000003");
        UUID mountSubLevel = UUID.fromString("00000000-0000-0000-0000-000000000004");
        AimSessionPayload aimOriginal = new AimSessionPayload(
                true, false, true, true, true, false, false, 6, false, true,
                1.0, 2.0, 3.0,
                true, 4.0, 5.0, 6.0,
                7.0, 8.0, 9.0,
                10.0, 11.0, 12.0, true,
                13, cannonUuid, cannonSubLevel, pitchSubLevel,
                new BlockPos(14, 15, 16), mountSubLevel,
                true, false, true,
                17.0, 18.0, 19.0, true,
                20.0, 21.0, 22.0, 23.0, 24.0, 25.0,
                26.0, 27.0, 28.0,
                true, false, true,
                29.0, 30.0, 31.0, 32.0, 33.0, 34.0,
                true, 35.0, 36.0, 37.0,
                true, 38.0, 39.0, 40.0, 41.0, 42.0, 43.0,
                0.6F, 0.03, 0.0,
                List.of(cannonSubLevel, mountSubLevel),
                new TargetLockTelemetry(new net.minecraft.world.phys.Vec3(1.0, 2.0, 3.0), 1400.0, -40.0, 600.0),
                TargetLockTelemetry.UNAVAILABLE,
                List.of(UUID.randomUUID()), List.of(UUID.randomUUID())
        );
        RegistryFriendlyByteBuf aimBuffer =
                new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        AimSessionPayload.STREAM_CODEC.encode(aimBuffer, aimOriginal);
        AimSessionPayload aimDecoded = AimSessionPayload.STREAM_CODEC.decode(aimBuffer);
        if (!aimOriginal.equals(aimDecoded) || aimBuffer.readableBytes() != 0) {
            throw new AssertionError("AimSessionPayload round trip changed data");
        }

        AimGroupSessionPayload groupsOriginal = new AimGroupSessionPayload(List.of(
                new AimGroupSessionPayload.GroupAim(1.0, 2.0, 3.0, 0.0, 0.0, 1.0),
                new AimGroupSessionPayload.GroupAim(-4.0, 5.0, 6.0, 0.5, -0.5, 0.5)));
        RegistryFriendlyByteBuf groupsBuffer =
                new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        AimGroupSessionPayload.STREAM_CODEC.encode(groupsBuffer, groupsOriginal);
        AimGroupSessionPayload groupsDecoded = AimGroupSessionPayload.STREAM_CODEC.decode(groupsBuffer);
        if (!groupsOriginal.equals(groupsDecoded) || groupsBuffer.readableBytes() != 0) {
            throw new AssertionError("AimGroupSessionPayload round trip changed data");
        }

        for (RemoteKeyBindingResultPayload.Result result : RemoteKeyBindingResultPayload.Result.values()) {
            RemoteKeyBindingResultPayload bindingOriginal = new RemoteKeyBindingResultPayload(
                    new BlockPos(-4, 72, 19), 12, true, "axisTriggerRight", result);
            RegistryFriendlyByteBuf bindingBuffer =
                    new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
            RemoteKeyBindingResultPayload.STREAM_CODEC.encode(bindingBuffer, bindingOriginal);
            RemoteKeyBindingResultPayload bindingDecoded =
                    RemoteKeyBindingResultPayload.STREAM_CODEC.decode(bindingBuffer);
            if (!bindingOriginal.equals(bindingDecoded) || bindingBuffer.readableBytes() != 0) {
                throw new AssertionError("RemoteKeyBindingResultPayload round trip changed data");
            }
        }

        RemoteInputStatePayload inputOriginal = new RemoteInputStatePayload(
                true, new BlockPos(8, 70, -12), Set.of("axisTriggerRight"),null,List.of(
                new com.hooya.stabilizedturret.content.remote.ControllerPulseQueue.Pulse(Set.of("buttonA"),3),
                new com.hooya.stabilizedturret.content.remote.ControllerPulseQueue.Pulse(Set.of("buttonB"),2)));
        RegistryFriendlyByteBuf inputBuffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        RemoteInputStatePayload.STREAM_CODEC.encode(inputBuffer, inputOriginal);
        RemoteInputStatePayload inputDecoded = RemoteInputStatePayload.STREAM_CODEC.decode(inputBuffer);
        if (!inputOriginal.equals(inputDecoded) || inputBuffer.readableBytes() != 0) {
            throw new AssertionError("RemoteInputStatePayload round trip changed data");
        }
    }

    private OpenControllerPayloadRoundTrip() {
    }

    private static void testConnectedDisplayConsolePayloads() {
        UUID subLevel = UUID.fromString("00000000-0000-0000-0000-000000000041");
        CompoundTag state = new CompoundTag();
        state.putBoolean("AutomaticAttack", true);
        state.putDouble("RadarRange", 3500.0);
        state.putString("SelectedWeapon", "CRUISE");
        OpenConnectedDisplayConsolePayload original =
                new OpenConnectedDisplayConsolePayload(
                        new BlockPos(3, 70, -8), subLevel, state,
                        1.0, 2.0, 3.0, 4.0, 5.0);
        RegistryFriendlyByteBuf openBuffer = new RegistryFriendlyByteBuf(
                Unpooled.buffer(), RegistryAccess.EMPTY);
        OpenConnectedDisplayConsolePayload.STREAM_CODEC.encode(openBuffer, original);
        OpenConnectedDisplayConsolePayload decoded =
                OpenConnectedDisplayConsolePayload.STREAM_CODEC.decode(openBuffer);
        if (!original.equals(decoded) || openBuffer.readableBytes() != 0) {
            throw new AssertionError(
                    "OpenConnectedDisplayConsolePayload round trip changed data");
        }

        ConnectedDisplayConsoleActionPayload action =
                new ConnectedDisplayConsoleActionPayload(
                        original.consolePos(), subLevel,
                        ConnectedDisplayConsoleActionPayload.Action.MAP_POINT,
                        125.5, -830.25,
                        UUID.fromString("00000000-0000-0000-0000-000000000042"),
                        true);
        RegistryFriendlyByteBuf actionBuffer = new RegistryFriendlyByteBuf(
                Unpooled.buffer(), RegistryAccess.EMPTY);
        ConnectedDisplayConsoleActionPayload.STREAM_CODEC.encode(actionBuffer, action);
        ConnectedDisplayConsoleActionPayload actionDecoded =
                ConnectedDisplayConsoleActionPayload.STREAM_CODEC.decode(actionBuffer);
        if (!action.equals(actionDecoded) || actionBuffer.readableBytes() != 0) {
            throw new AssertionError(
                    "ConnectedDisplayConsoleActionPayload round trip changed data");
        }

        RequestConnectedDisplayConsoleStatePayload request =
                new RequestConnectedDisplayConsoleStatePayload(
                        original.consolePos(), subLevel);
        RegistryFriendlyByteBuf requestBuffer = new RegistryFriendlyByteBuf(
                Unpooled.buffer(), RegistryAccess.EMPTY);
        RequestConnectedDisplayConsoleStatePayload.STREAM_CODEC.encode(
                requestBuffer, request);
        RequestConnectedDisplayConsoleStatePayload requestDecoded =
                RequestConnectedDisplayConsoleStatePayload.STREAM_CODEC.decode(requestBuffer);
        if (!request.equals(requestDecoded) || requestBuffer.readableBytes() != 0) {
            throw new AssertionError(
                    "RequestConnectedDisplayConsoleStatePayload round trip changed data");
        }
    }

    private static void testFrequencyLocalization(OpenControllerPayload decoded) throws IOException {
        Language previous = Language.getInstance();
        Component frequency = Component.translatable("screen.create_fire_control.input.frequency",
                "A", decoded.scopeFirst());
        try {
            for (String locale : List.of("en_us", "zh_cn", "ru_ru")) {
                Map<String, String> translations = new HashMap<>();
                try (var stream = OpenControllerPayloadRoundTrip.class.getResourceAsStream(
                        "/assets/create_fire_control/lang/" + locale + ".json")) {
                    if (stream == null) throw new AssertionError("Missing language file: " + locale);
                    Language.loadFromJson(stream, translations::put);
                }
                String itemName = "client item name: " + locale;
                translations.put("item.minecraft.diamond", itemName);
                Language.inject(testLanguage(translations, previous));
                if (!decoded.scopeFirst().getString().equals(itemName)
                        || !frequency.getString().contains(itemName)
                        || frequency.getString().contains("screen.create_fire_control")
                        || !decoded.scopeSecond().getString().equals("自定义频道 / Custom channel")) {
                    throw new AssertionError("Frequency name did not follow client language: " + locale);
                }
            }
            Language.inject(testLanguage(Map.of(
                    "item.minecraft.diamond", "resource-pack item",
                    "screen.create_fire_control.input.frequency", "%2$s (%1$s)"), previous));
            if (!frequency.getString().equals("resource-pack item (A)")) {
                throw new AssertionError("Resource pack could not override the decoded frequency name");
            }
        } finally {
            Language.inject(previous);
        }
    }

    private static Language testLanguage(Map<String, String> translations, Language fallback) {
        return new Language() {
            @Override public String getOrDefault(String key, String defaultValue) {
                return translations.getOrDefault(key, defaultValue);
            }
            @Override public boolean has(String key) { return translations.containsKey(key); }
            @Override public boolean isDefaultRightToLeft() { return false; }
            @Override public FormattedCharSequence getVisualOrder(FormattedText text) {
                return fallback.getVisualOrder(text);
            }
        };
    }

    private static void testRadarSessionRoundTrip() {
        List<RadarSessionPayload.Threat> threats = List.of(
                new RadarSessionPayload.Threat(
                        UUID.fromString("00000000-0000-0000-0000-000000000031"),
                        RadarSessionPayload.ThreatType.AAA, 101.0, 102.0, 103.0, false),
                new RadarSessionPayload.Threat(
                        UUID.fromString("00000000-0000-0000-0000-000000000032"),
                        RadarSessionPayload.ThreatType.RFL, 201.0, 202.0, 203.0, false),
                new RadarSessionPayload.Threat(
                        UUID.fromString("00000000-0000-0000-0000-000000000033"),
                        RadarSessionPayload.ThreatType.MSL, 301.0, 302.0, 303.0, true));
        List<RadarSessionPayload.Contact> contacts = List.of(
                new RadarSessionPayload.Contact(
                        UUID.fromString("00000000-0000-0000-0000-000000000034"),
                        41.0, 42.0, 43.0,
                        44.0, 45.0, 46.0,
                        47.0, 48.0, 49.0,
                        50.0, 51.0, 52.0, true,
                        new TargetLockTelemetry(new net.minecraft.world.phys.Vec3(44.0, 45.0, 46.0),
                                1400.0, -40.0, 600.0)));
        RadarSessionPayload original = new RadarSessionPayload(
                true,
                1.0, 2.0, 3.0, 4.0, 5.0, 6.0,
                7.0, 8.0, 9.0,
                10.0F,
                UUID.fromString("00000000-0000-0000-0000-000000000035"),
                UUID.fromString("00000000-0000-0000-0000-000000000036"),
                true,
                11.0, 12.0, 13.0,
                14.0, 15.0, 16.0,
                17.0, 18.0, 19.0,
                20.0, 21.0,
                2,
                22, 23, 24, 25, 26, 27, 28, 29, 30, 31,
                29.0, 30.0, 31.0,
                2_000.0, 100.0,
                threats, contacts);

        RegistryFriendlyByteBuf buffer =
                new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        RadarSessionPayload.STREAM_CODEC.encode(buffer, original);
        RadarSessionPayload decoded = RadarSessionPayload.STREAM_CODEC.decode(buffer);
        if (!original.equals(decoded) || buffer.readableBytes() != 0) {
            throw new AssertionError("RadarSessionPayload round trip changed RWR data");
        }
    }

    private static void writeAutoDefenseDisplayHeader(
            RegistryFriendlyByteBuf buffer, OpenAutoDefenseDisplayPayload value) {
        buffer.writeBlockPos(value.pos());
        buffer.writeBoolean(value.subLevelId() != null);
        if (value.subLevelId() != null) buffer.writeUUID(value.subLevelId());
        buffer.writeBoolean(value.automaticAttack());
        buffer.writeBoolean(value.scanStructures());
        buffer.writeBoolean(value.scanLiving());
        buffer.writeBoolean(value.scanMissiles());
        buffer.writeBoolean(value.automaticMissileDefense());
        buffer.writeDouble(value.automaticMissileDefenseRange());
        buffer.writeDouble(value.missileProximityRange());
        buffer.writeDouble(value.radarRange());
        buffer.writeDouble(value.yawSpeed());
        buffer.writeDouble(value.pitchSpeed());
        buffer.writeDouble(value.depressionLimit());
        buffer.writeDouble(value.elevationLimit());
        buffer.writeBoolean(value.yawReversed());
        buffer.writeBoolean(value.pitchReversed());
        buffer.writeDouble(value.cannonFireRange());
        buffer.writeVarInt(value.cannonFirePower());
        buffer.writeVarInt(value.mapUpdateIntervalSeconds());
        buffer.writeVarInt(value.mapResolution());
    }
}
