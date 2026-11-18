package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.content.bearing.YawLimits;
import com.hooya.stabilizedturret.content.controller.DriveByWireCompat;
import com.hooya.stabilizedturret.content.controller.CannonRecoilSettings;
import com.hooya.stabilizedturret.content.controller.FireControlWeaponInputs;
import com.hooya.stabilizedturret.content.controller.RemoteControllerCompat;
import com.hooya.stabilizedturret.content.controller.ScopePreset;
import com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity;
import com.hooya.stabilizedturret.network.AssignBearingRolePayload;
import com.hooya.stabilizedturret.network.ControllerSettingsPayload;
import com.hooya.stabilizedturret.network.ControllerBearingSettingsPayload;
import com.hooya.stabilizedturret.network.ControllerGlobalBearingSettingsPayload;
import com.hooya.stabilizedturret.network.OpenControllerPayload;
import com.hooya.stabilizedturret.network.RemoteKeyBindingResultPayload;
import com.hooya.stabilizedturret.network.SetWirelessFrequencyPayload;
import com.hooya.stabilizedturret.network.SetRemoteKeyBindingPayload;
import com.hooya.stabilizedturret.network.SetCannonMountModePayload;
import com.hooya.stabilizedturret.network.SetPodCannonLimitsPayload;
import com.hooya.stabilizedturret.network.UnbindDevicePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.DoubleConsumer;

public final class StabilizerControllerScreen extends Screen {
    private static final int PANEL_OUTLINE = 0xFF342F28;
    private static final int PANEL_BRASS = 0xFFB8903D;
    private static final int PANEL_INNER_BORDER = 0xFF76664A;
    private static final int PANEL_BACKGROUND = 0xFFE2D2AC;
    private static final int PANEL_HEADER = 0xFF4B4438;
    private static final int LABEL_COLOR = 0xFF3D382F;
    private static final int EDIT_BOX_LABEL_BASELINE_OFFSET = 6;
    private static final int RADAR_FIELDS_TOP = 208;
    private static final int AIRCRAFT_FIELDS_TOP = 232;
    private static final int INPUT_ACTION_COUNT = StabilizerControllerBlockEntity.INPUT_ACTION_COUNT;
    private record InputGroup(String name,int[] actions) {}
    private static final InputGroup[] INPUT_GROUPS = {
        new InputGroup("air",new int[]{6,16,7,8,9,10,11,13,FireControlWeaponInputs.WEAPON_VIEW}),
        new InputGroup("ground",new int[]{0,15,2,3,StabilizerControllerBlockEntity.INPUT_ACTION_GROUND_TARGET_CYCLE,5}),
        new InputGroup("other",new int[]{12,1,14})
    };
    private static final int INPUT_ACTION_FREE_LOOK = 12;
    private static final int INPUT_ACTION_TELEVISION_GUIDANCE = 13;
    private static final int INPUT_ACTION_LASER_RANGE = 15;

    private static final int DEVICES_PER_PAGE = 6;

    private enum Page {
        HOME, FIRE_CONTROL, BEARING_GROUPS, BEARING_GROUP, BEARING_GLOBAL_SELECT, BEARING_GLOBAL,
        PITCH_SELECT, YAW_SELECT,
        PITCH, YAW, VIEW, AIM_POINT, SCOPE_PRESETS, POD_CANNON,
        RADAR, AIRCRAFT, INPUTS, WEAPON_INPUTS, DEVICES
    }

    private final OpenControllerPayload initial;
    private Page page = Page.HOME;

    private double depression;
    private double elevation;
    private double yawSpeed;
    private YawLimits yawLimits;
    private double pitchSpeed;
    private boolean yawReversed;
    private boolean pitchReversed;
    private boolean yawBearingHeadHidden;
    private boolean pitchBearingHeadHidden;
    private double thirdPersonHeight;
    private double viewAimOffsetX;
    private double viewAimOffsetY;
    private double yawOffsetX;
    private double yawOffsetY;
    private double yawOffsetZ;
    private double pitchOffsetX;
    private double pitchOffsetY;
    private double pitchOffsetZ;
    private double scopeOffsetX;
    private double scopeOffsetY;
    private double scopeOffsetZ;
    private boolean virtualScopeEnabled;
    private ScopePreset scopePreset;
    private double radarRange;
    private double radarDepression;
    private double radarElevation;
    private double aaProximityFuseRange;
    private double radarHudScale;
    private double radarHudX;
    private double radarHudY;
    private double aircraftRadarRange;
    private double aircraftRadarHorizontalHalfAngle;
    private double aircraftRadarVerticalAngle;
    private double aircraftProximityFuseRange;
    private double aircraftFuseSafetySeconds;
    private double aircraftRadarMinimumTargetSpeed;
    private double aircraftRadarHudScale;
    private double aircraftRadarHudX;
    private double aircraftRadarHudY;
    private double rwrRange;
    private double rwrScale;
    private boolean aircraftRocketSalvoMode;
    private boolean stabilizerEnabled;
    private boolean stabilizerReactionEnabled;
    private double cannonRecoilMagnitude;
    private boolean mobileShotCompensationEnabled;
    private boolean headAimEnabled;
    private boolean remoteHeadAimEnabled;
    private double podCannonDepression;
    private double podCannonElevation;
    private Component scopeFirst;
    private Component scopeSecond;
    private Component thermalFirst;
    private Component thermalSecond;
    private Component opticalLockFirst;
    private Component opticalLockSecond;
    private Component missileFireFirst;
    private Component missileFireSecond;
    private Component aaMissileFireFirst;
    private Component aaMissileFireSecond;
    private Component radarLockFirst;
    private Component radarLockSecond;
    private Component aircraftMissileFireFirst;
    private Component aircraftMissileFireSecond;
    private Component aircraftWeaponCycleFirst;
    private Component aircraftWeaponCycleSecond;
    private Component aircraftTargetCycleFirst;
    private Component aircraftTargetCycleSecond;
    private Component podViewFirst;
    private Component podViewSecond;
    private Component airToGroundLockFirst;
    private Component airToGroundLockSecond;
    private Component countermeasureFireFirst;
    private Component countermeasureFireSecond;
    private Component freeLookFirst;
    private Component freeLookSecond;
    private Component televisionGuidanceFirst;
    private Component televisionGuidanceSecond;
    private Component laserRangeFirst;
    private Component laserRangeSecond;
    private final List<String> normalRemoteKeys;
    private final List<String> advancedRemoteKeys;
    private final List<String> wirelessRemoteKeys=new ArrayList<>();
    private net.minecraft.nbt.CompoundTag inputState;
    private boolean aircraftWeaponInputs;
    private final net.minecraft.world.item.ItemStack[][] inputFrequencies=new net.minecraft.world.item.ItemStack[INPUT_ACTION_COUNT][2];
    private final List<OpenControllerPayload.BoundDevice> devices;
    private int devicePage;
    private OpenControllerPayload.BoundDevice pendingUnbind;
    private OpenControllerPayload.BoundDevice pendingBearingRole;
    private OpenControllerPayload.BoundDevice pendingCannonMountMode;
    private String selectedBearingRole;
    private BlockPos selectedBearingPos;
    private int selectedBearingGroup;
    private int globalBearingMask;
    private final List<CustomPacketPayload> pendingBearingSettings = new ArrayList<>();
    private RemoteKeyDropdown openRemoteKeyDropdown;
    private RemoteKeyDropdown capturingRemoteKey;
    private final com.hooya.stabilizedturret.client.remote.InputChordCapture wirelessCapture=new com.hooya.stabilizedturret.client.remote.InputChordCapture();
    private final List<AbstractWidget> contentWidgets = new ArrayList<>();
    private final List<AbstractWidget> fixedWidgets = new ArrayList<>();
    private double scrollOffset;
    private PageScrollSlider scrollSlider;
    private Button structurePreviewButton;

    public StabilizerControllerScreen(OpenControllerPayload initial) {
        super(Component.translatable("screen.create_fire_control.controller"));
        this.initial = initial;
        depression = initial.depression();
        elevation = initial.elevation();
        yawSpeed = initial.yawSpeed();
        yawLimits = initial.yawLimits();
        pitchSpeed = initial.pitchSpeed();
        yawReversed = initial.yawReversed();
        pitchReversed = initial.pitchReversed();
        yawBearingHeadHidden = initial.yawBearingHeadHidden();
        pitchBearingHeadHidden = initial.pitchBearingHeadHidden();
        thirdPersonHeight = initial.thirdPersonHeight();
        viewAimOffsetX = initial.viewAimOffsetX();
        viewAimOffsetY = initial.viewAimOffsetY();
        yawOffsetX = initial.yawOffsetX();
        yawOffsetY = initial.yawOffsetY();
        yawOffsetZ = initial.yawOffsetZ();
        pitchOffsetX = initial.pitchOffsetX();
        pitchOffsetY = initial.pitchOffsetY();
        pitchOffsetZ = initial.pitchOffsetZ();
        scopeOffsetX = initial.scopeOffsetX();
        scopeOffsetY = initial.scopeOffsetY();
        scopeOffsetZ = initial.scopeOffsetZ();
        virtualScopeEnabled = initial.virtualScopeEnabled();
        scopePreset = ScopePreset.fromNetworkId(initial.scopePreset());
        radarRange = initial.radarRange();
        radarDepression = initial.radarDepression();
        radarElevation = initial.radarElevation();
        aaProximityFuseRange = initial.aaProximityFuseRange();
        radarHudScale = initial.radarHudScale();
        radarHudX = initial.radarHudX();
        radarHudY = initial.radarHudY();
        aircraftRadarRange = initial.aircraftRadarRange();
        aircraftRadarHorizontalHalfAngle = initial.aircraftRadarHorizontalHalfAngle();
        aircraftRadarVerticalAngle = initial.aircraftRadarVerticalAngle();
        aircraftProximityFuseRange = initial.aircraftProximityFuseRange();
        aircraftFuseSafetySeconds = initial.aircraftFuseSafetySeconds();
        aircraftRadarMinimumTargetSpeed = initial.aircraftRadarMinimumTargetSpeed();
        aircraftRadarHudScale = initial.aircraftRadarHudScale();
        aircraftRadarHudX = initial.aircraftRadarHudX();
        aircraftRadarHudY = initial.aircraftRadarHudY();
        rwrRange = initial.rwrRange();
        rwrScale = initial.rwrScale();
        aircraftRocketSalvoMode = initial.aircraftRocketSalvoMode();
        stabilizerEnabled = initial.stabilizerEnabled();
        stabilizerReactionEnabled = initial.stabilizerReactionEnabled();
        cannonRecoilMagnitude = CannonRecoilSettings.clamp(initial.cannonRecoilMagnitude());
        mobileShotCompensationEnabled = initial.mobileShotCompensationEnabled();
        headAimEnabled = initial.headAimEnabled();
        remoteHeadAimEnabled = initial.remoteHeadAimEnabled();
        podCannonDepression = initial.podCannonDepression();
        podCannonElevation = initial.podCannonElevation();
        scopeFirst = initial.scopeFirst();
        scopeSecond = initial.scopeSecond();
        thermalFirst = initial.thermalFirst();
        thermalSecond = initial.thermalSecond();
        opticalLockFirst = initial.opticalLockFirst();
        opticalLockSecond = initial.opticalLockSecond();
        missileFireFirst = initial.missileFireFirst();
        missileFireSecond = initial.missileFireSecond();
        aaMissileFireFirst = initial.aaMissileFireFirst();
        aaMissileFireSecond = initial.aaMissileFireSecond();
        radarLockFirst = initial.radarLockFirst();
        radarLockSecond = initial.radarLockSecond();
        aircraftMissileFireFirst = initial.aircraftMissileFireFirst();
        aircraftMissileFireSecond = initial.aircraftMissileFireSecond();
        aircraftWeaponCycleFirst = initial.aircraftWeaponCycleFirst();
        aircraftWeaponCycleSecond = initial.aircraftWeaponCycleSecond();
        aircraftTargetCycleFirst = initial.aircraftTargetCycleFirst();
        aircraftTargetCycleSecond = initial.aircraftTargetCycleSecond();
        podViewFirst = initial.podViewFirst();
        podViewSecond = initial.podViewSecond();
        airToGroundLockFirst = initial.airToGroundLockFirst();
        airToGroundLockSecond = initial.airToGroundLockSecond();
        countermeasureFireFirst = initial.countermeasureFireFirst();
        countermeasureFireSecond = initial.countermeasureFireSecond();
        freeLookFirst = initial.freeLookFirst();
        freeLookSecond = initial.freeLookSecond();
        televisionGuidanceFirst = initial.televisionGuidanceFirst();
        televisionGuidanceSecond = initial.televisionGuidanceSecond();
        laserRangeFirst = initial.laserRangeFirst();
        laserRangeSecond = initial.laserRangeSecond();
        normalRemoteKeys = new ArrayList<>(initial.normalRemoteKeys());
        advancedRemoteKeys = new ArrayList<>(initial.advancedRemoteKeys());
        while (normalRemoteKeys.size() < INPUT_ACTION_COUNT) normalRemoteKeys.add("");
        while (advancedRemoteKeys.size() < INPUT_ACTION_COUNT) advancedRemoteKeys.add("");
        loadInputState(initial.inputState());
        devices = new ArrayList<>(initial.devices());
    }

    @Override
    protected void init() {
        scrollOffset = 0.0;
        buildPage();
    }

    private void openPage(Page requested) {
        stageBearingSettings();
        page = requested;
        scrollOffset = 0.0;
        clearWidgets();
        buildPage();
    }

    private void buildPage() {
        structurePreviewButton = null;
        contentWidgets.clear();
        fixedWidgets.clear();
        scrollSlider = null;
        switch (page) {
            case HOME -> buildHomePage();
            case FIRE_CONTROL -> buildFireControlPage();
            case BEARING_GROUPS -> buildBearingGroupsPage();
            case BEARING_GROUP -> buildBearingGroupPage();
            case BEARING_GLOBAL_SELECT -> buildGlobalBearingSelectionPage();
            case BEARING_GLOBAL -> buildGlobalBearingPage();
            case PITCH_SELECT -> buildBearingSelectionPage("PITCH");
            case YAW_SELECT -> buildBearingSelectionPage("YAW");
            case PITCH -> buildPitchPage();
            case YAW -> buildYawPage();
            case VIEW -> buildViewPage();
            case AIM_POINT -> buildAimPointPage();
            case SCOPE_PRESETS -> buildScopePresetsPage();
            case POD_CANNON -> buildPodCannonPage();
            case RADAR -> buildRadarPage();
            case AIRCRAFT -> buildAircraftPage();
            case INPUTS -> buildInputsPage();
            case WEAPON_INPUTS -> buildWeaponInputsPage();
            case DEVICES -> buildDevicesPage();
        }
        for (var child : children()) {
            if (child instanceof AbstractWidget widget && !fixedWidgets.contains(widget)) {
                contentWidgets.add(widget);
            }
        }
        installScrollSlider();
    }

    private void buildHomePage() {
        PanelBounds panel = panelBounds();
        if (initial.controllerSubLevelId() != null) {
            PanelBounds visible = viewportPanelBounds();
            structurePreviewButton = addRenderableWidget(styledButton(StructurePreviewScreen.text("entry"),
                    b -> minecraft.setScreen(new StructurePreviewScreen(this, initial.pos(), initial.controllerSubLevelId(), false,
                            this::acceptStructurePreview)), visible.right - 64, visible.top + 9, 52, 16));
            fixedWidgets.add(structurePreviewButton);
            structurePreviewButton.visible = StructurePreviewScreen.available(initial.pos(), initial.controllerSubLevelId());
        }
        int left = width / 2 - 125;
        int top = panel.top + 42;
        boolean multipleBearings = bearingDevices("YAW").size() > 1
                || bearingDevices("PITCH").size() > 1;
        if (multipleBearings) {
            addRenderableWidget(styledButton(
                    Component.translatable("screen.create_fire_control.bearing_group_details"),
                    button -> openBearingSettingsPage("YAW"), left, top, 120, 20));
            addRenderableWidget(styledButton(
                    Component.translatable("screen.create_fire_control.global_bearing_settings"),
                    button -> {
                        selectedBearingRole = null;
                        selectedBearingPos = null;
                        globalBearingMask = 0;
                        openPage(Page.BEARING_GLOBAL_SELECT);
                    }, left + 130, top, 120, 20));
            top += 30;
        } else {
            addRenderableWidget(styledButton(Component.translatable("screen.create_fire_control.pitch_settings"),
                    button -> openBearingSettingsPage("PITCH"), left, top, 120, 20));
            addRenderableWidget(styledButton(Component.translatable("screen.create_fire_control.yaw_settings"),
                    button -> openBearingSettingsPage("YAW"), left + 130, top, 120, 20));
            top += 30;
        }
        addRenderableWidget(styledButton(Component.translatable("screen.create_fire_control.view_settings"),
                button -> openPage(Page.VIEW), left, top, 120, 20));
        addRenderableWidget(styledButton(Component.translatable("screen.create_fire_control.fire_control_settings"),
                button -> openPage(Page.FIRE_CONTROL), left + 130, top, 120, 20));
        addRenderableWidget(styledButton(Component.translatable("screen.create_fire_control.radar_settings"),
                button -> openPage(Page.RADAR), left, top + 30, 120, 20));
        addRenderableWidget(styledButton(Component.translatable("screen.create_fire_control.devices"),
                button -> openPage(Page.DEVICES), left + 130, top + 30, 120, 20));
        addRenderableWidget(styledButton(Component.translatable("screen.create_fire_control.aircraft_settings"),
                button -> openPage(Page.AIRCRAFT), left, top + 60, 120, 20));
        addRenderableWidget(styledButton(Component.translatable("screen.create_fire_control.input_bindings"),
                button -> openPage(Page.INPUTS), left + 130, top + 60, 120, 20));
        addRenderableWidget(styledButton(Component.translatable("gui.done"), button -> saveAndClose(),
                left, top + 90, 120, 20).setTone(ButtonTone.GREEN));
        addRenderableWidget(styledButton(Component.translatable("gui.cancel"), button -> onClose(),
                left + 130, top + 90, 120, 20).setTone(ButtonTone.RED));
    }

    private void buildFireControlPage() {
        PanelBounds panel = panelBounds();
        int left = formLeft(panel);
        int controlWidth = formWidth(panel);
        int gap = 10;
        int leftButtonWidth = (controlWidth - gap) / 2;
        int rightButtonWidth = controlWidth - gap - leftButtonWidth;
        int top = panel.top + 42;
        addRenderableWidget(styledButton(toggleText("stabilizer", stabilizerEnabled), button -> {
            stabilizerEnabled = !stabilizerEnabled;
            button.setMessage(toggleText("stabilizer", stabilizerEnabled));
            ((BrassButton) button).setTone(stabilizerEnabled ? ButtonTone.GREEN : ButtonTone.NORMAL);
        }, left, top, leftButtonWidth, 20).setTone(stabilizerEnabled ? ButtonTone.GREEN : ButtonTone.NORMAL));
        addRenderableWidget(styledButton(toggleText("head_aim", headAimEnabled), button -> {
            headAimEnabled = !headAimEnabled;
            button.setMessage(toggleText("head_aim", headAimEnabled));
            ((BrassButton) button).setTone(headAimEnabled ? ButtonTone.GREEN : ButtonTone.NORMAL);
        }, left + leftButtonWidth + gap, top, rightButtonWidth, 20).setTone(headAimEnabled ? ButtonTone.GREEN : ButtonTone.NORMAL));
        addRenderableWidget(styledButton(toggleText("remote_head_aim", remoteHeadAimEnabled), button -> {
            if (!initial.remoteTerminalAvailable()) {
                remoteHeadAimEnabled = false;
                button.setMessage(toggleText("remote_head_aim", false));
                ((BrassButton) button).setTone(ButtonTone.RED);
                if (minecraft != null && minecraft.player != null) {
                    minecraft.player.displayClientMessage(
                            Component.translatable("message.create_fire_control.remote_terminal_missing"),
                            true
                    );
                }
                return;
            }
            remoteHeadAimEnabled = !remoteHeadAimEnabled;
            button.setMessage(toggleText("remote_head_aim", remoteHeadAimEnabled));
            ((BrassButton) button).setTone(remoteHeadAimEnabled ? ButtonTone.GREEN : ButtonTone.NORMAL);
        }, left, top + 30, controlWidth, 20).setTone(
                !initial.remoteTerminalAvailable()
                        ? ButtonTone.RED
                        : remoteHeadAimEnabled ? ButtonTone.GREEN : ButtonTone.NORMAL
        ));
        BrassButton implementation=styledButton(stabilizerImplementationText(), button -> {
            stabilizerReactionEnabled=!stabilizerReactionEnabled;
            openPage(Page.FIRE_CONTROL);
        },left,top+60,controlWidth,20);
        implementation.setTooltip(stabilizerImplementationTooltip());
        addRenderableWidget(implementation);
        int nextRow = top + 90;
        BrassButton virtualScope = styledButton(toggleText("virtual_scope", virtualScopeEnabled), button -> {
            virtualScopeEnabled = !virtualScopeEnabled;
            button.setMessage(toggleText("virtual_scope", virtualScopeEnabled));
            ((BrassButton) button).setTone(virtualScopeEnabled ? ButtonTone.GREEN : ButtonTone.NORMAL);
        }, left, nextRow, controlWidth, 20).setTone(virtualScopeEnabled ? ButtonTone.GREEN : ButtonTone.NORMAL);
        virtualScope.active = virtualScopeEnabled || initial.virtualScopeUnavailableReason().isEmpty();
        virtualScope.setTooltip(Tooltip.create(Component.translatable(
                initial.virtualScopeUnavailableReason().isEmpty()
                        ? "screen.create_fire_control.virtual_scope.tooltip"
                        : "message.create_fire_control." + initial.virtualScopeUnavailableReason())));
        addRenderableWidget(virtualScope);
        nextRow += 30;
        if (stabilizerReactionEnabled) {
            BrassButton compensation = styledButton(toggleText("mobile_shot_compensation", mobileShotCompensationEnabled), button -> {
                mobileShotCompensationEnabled = !mobileShotCompensationEnabled;
                button.setMessage(toggleText("mobile_shot_compensation", mobileShotCompensationEnabled));
                ((BrassButton) button).setTone(mobileShotCompensationEnabled ? ButtonTone.GREEN : ButtonTone.NORMAL);
            }, left, nextRow, controlWidth, 20).setTone(mobileShotCompensationEnabled ? ButtonTone.GREEN : ButtonTone.NORMAL);
            compensation.setTooltip(Tooltip.create(Component.translatable(
                    "screen.create_fire_control.mobile_shot_compensation.tooltip")));
            addRenderableWidget(compensation);
            nextRow += 30;
            cannonRecoilControl(left, nextRow);
            nextRow += 36;
        }
        addRenderableWidget(styledButton(scopePresetText(),
                button -> openPage(Page.SCOPE_PRESETS), left, nextRow, controlWidth, 20));
        addRenderableWidget(styledButton(Component.translatable("gui.back"), button -> openPage(Page.HOME),
                left + (controlWidth - 120) / 2, nextRow + 32, 120, 20));
    }

    private void openBearingSettingsPage(String role) {
        List<OpenControllerPayload.BoundDevice> candidates = bearingDevices(role);
        if (candidates.isEmpty() && hasExclusiveHeavyCannonConfiguration()) {
            // In this topology the station itself supplies both axes. The
            // ordinary global speed/elevation fields are its fire-control
            // settings, so no physical bearing needs to be selected.
            selectedBearingRole = role;
            selectedBearingPos = null;
            openPage("PITCH".equals(role) ? Page.PITCH : Page.YAW);
            return;
        }
        boolean multipleBearings = bearingDevices("YAW").size() > 1
                || bearingDevices("PITCH").size() > 1;
        if (!multipleBearings && candidates.size() == 1) {
            selectBearing(candidates.get(0), role);
            openPage("PITCH".equals(role) ? Page.PITCH : Page.YAW);
            return;
        }
        selectedBearingRole = role;
        selectedBearingPos = null;
        selectedBearingGroup = 0;
        openPage(Page.BEARING_GROUPS);
    }

    private boolean hasExclusiveHeavyCannonConfiguration() {
        List<OpenControllerPayload.BoundDevice> stations = devices.stream()
                .filter(device -> "REMOTE_WEAPON_STATION".equals(device.slot()))
                .toList();
        if (stations.size() != 1 || !"block.mianbaos_modernwarfare.heavycannonweaponstation"
                .equals(stations.get(0).translationKey())) return false;
        return devices.stream().noneMatch(device ->
                "YAW".equals(device.slot())
                        || "PITCH".equals(device.slot())
                        || "CANNON_MOUNT".equals(device.slot())
                        || "MISSILE_LAUNCHER".equals(device.slot())
                        || "AA_MISSILE_LAUNCHER".equals(device.slot()));
    }

    private void buildScopePresetsPage() {
        PanelBounds panel = panelBounds();
        int left = width / 2 - 125;
        int top = panel.top + 42;
        ScopePreset[] presets = ScopePreset.values();
        for (int index = 0; index < presets.length; index++) {
            ScopePreset preset = presets[index];
            int x = left + (index % 2) * 130;
            int y = top + (index / 2) * 28;
            addRenderableWidget(styledButton(
                    Component.translatable(preset.translationKey()),
                    button -> {
                        scopePreset = preset;
                        openPage(Page.SCOPE_PRESETS);
                    }, x, y, 120, 20
            ).setTone(scopePreset == preset ? ButtonTone.GREEN : ButtonTone.NORMAL));
        }
        addRenderableWidget(styledButton(Component.translatable("gui.back"),
                button -> openPage(Page.FIRE_CONTROL), left + 65, top + 122, 120, 20));
    }

    private void buildBearingGroupsPage() {
        PanelBounds panel = panelBounds();
        List<OpenControllerPayload.BoundDevice> yaws = bearingDevices("YAW");
        boolean hasGroupMetadata = yaws.stream().anyMatch(device -> device.bearingGroup() >= 0);
        int groupCount = hasGroupMetadata
                ? Math.max(1, yaws.stream().mapToInt(device -> device.bearingGroup() + 1).max().orElse(1))
                : Math.max(1, yaws.size());
        int left = panel.left + 14;
        int width = Math.max(120, contentRight(panel) - left);
        int top = panel.top + 48;
        for (int index = 0; index < groupCount; index++) {
            int group = index;
            List<OpenControllerPayload.BoundDevice> groupYaws = groupDevices("YAW", group);
            Component label = yaws.isEmpty()
                    ? Component.translatable("screen.create_fire_control.bearing_group_default")
                    : Component.translatable("screen.create_fire_control.bearing_group", index + 1)
                    .append(" | ").append(groupYaws.isEmpty()
                            ? Component.translatable("screen.create_fire_control.bearing_group_default")
                            : Component.literal(groupYaws.stream()
                                    .map(this::deviceDisplayName)
                                    .map(Component::getString)
                                    .reduce((leftText, rightText) -> leftText + ", " + rightText)
                                    .orElse("")));
            addRenderableWidget(styledButton(label, button -> {
                selectedBearingGroup = group;
                openPage(Page.BEARING_GROUP);
            }, left, top + index * 28, width, 20));
        }
        addBearingFooter(Page.HOME);
    }

    private void buildBearingGroupPage() {
        PanelBounds panel = panelBounds();
        List<OpenControllerPayload.BoundDevice> yaws = groupDevices("YAW", selectedBearingGroup);
        List<OpenControllerPayload.BoundDevice> allPitches = bearingDevices("PITCH");
        List<OpenControllerPayload.BoundDevice> pitches = allPitches.stream()
                .filter(device -> device.bearingGroup() < 0
                        || device.bearingGroup() == selectedBearingGroup)
                .toList();
        int left = panel.left + 14;
        int width = Math.max(120, contentRight(panel) - left);
        int top = panel.top + 48;
        int row = 0;
        for (OpenControllerPayload.BoundDevice yaw : yaws) {
            addRenderableWidget(styledButton(
                    bearingSelectionLabel(yaw, 0),
                    button -> {
                        selectBearing(yaw, "YAW");
                        openPage(Page.YAW);
                    }, left, top + row++ * 28, width, 20));
        }
        // A pitch can be shared by symmetric yaw bearings. Devices without a
        // server topology group remain visible as a compatibility fallback.
        for (int index = 0; index < pitches.size(); index++) {
            OpenControllerPayload.BoundDevice pitch = pitches.get(index);
            addRenderableWidget(styledButton(
                    bearingSelectionLabel(pitch, index),
                    button -> {
                        selectBearing(pitch, "PITCH");
                        openPage(Page.PITCH);
                    }, left, top + row++ * 28, width, 20));
        }
        if (row == 0) {
            addRenderableWidget(styledButton(
                    Component.translatable("screen.create_fire_control.bearing_selection_empty"),
                    button -> openPage(Page.BEARING_GROUPS), left, top, width, 20));
        }
        addBearingFooter(Page.BEARING_GROUPS);
    }

    private void buildGlobalBearingPage() {
        PanelBounds panel = panelBounds();
        int left = formLeft(panel);
        int top = panel.top;
        boolean pitch = "PITCH".equals(selectedBearingRole);
        // A global page must never carry a previously selected individual
        // bearing into saveAndClose(), where it could overwrite one device.
        selectedBearingPos = null;
        int buttonTop;
        int offsetTop;
        if (pitch) {
            globalNumericBox(left + formWidth(panel) - 60, top + 42, depression,
                    value -> { depression = value; globalBearingMask |= 1; updateLocalGlobalDeviceValues("PITCH", 1); });
            globalNumericBox(left + formWidth(panel) - 60, top + 67, elevation,
                    value -> { elevation = value; globalBearingMask |= 2; updateLocalGlobalDeviceValues("PITCH", 2); });
            globalNumericBox(left + formWidth(panel) - 60, top + 92, pitchSpeed,
                    value -> { pitchSpeed = value; globalBearingMask |= 4; updateLocalGlobalDeviceValues("PITCH", 4); });
            buttonTop = top + 118;
            offsetTop = top + 180;
        } else {
            globalNumericBox(left + formWidth(panel) - 60, top + 42, yawSpeed,
                    value -> { yawSpeed = value; globalBearingMask |= 4; updateLocalGlobalDeviceValues("YAW", 4); });
            yawLimitControls(left, top + 67, true);
            buttonTop = top + 174;
            offsetTop = top + 221;
        }
        addRenderableWidget(styledButton(toggleText("hide_bearing_head",
                pitch ? pitchBearingHeadHidden : yawBearingHeadHidden), button -> {
            if (pitch) pitchBearingHeadHidden = !pitchBearingHeadHidden; else yawBearingHeadHidden = !yawBearingHeadHidden;
            globalBearingMask |= 16;
            updateLocalGlobalDeviceValues(pitch ? "PITCH" : "YAW", 16);
            button.setMessage(toggleText("hide_bearing_head", pitch ? pitchBearingHeadHidden : yawBearingHeadHidden));
        }, left, buttonTop, (formWidth(panel) - 8) / 2, 20));
        addRenderableWidget(styledButton(toggleText(pitch ? "pitch_reversed" : "yaw_reversed",
                pitch ? pitchReversed : yawReversed), button -> {
            if (pitch) pitchReversed = !pitchReversed; else yawReversed = !yawReversed;
            globalBearingMask |= 8;
            updateLocalGlobalDeviceValues(pitch ? "PITCH" : "YAW", 8);
            button.setMessage(toggleText(pitch ? "pitch_reversed" : "yaw_reversed",
                    pitch ? pitchReversed : yawReversed));
        }, left + (formWidth(panel) + 8) / 2, buttonTop, (formWidth(panel) - 8) / 2, 20));
        offsetControlGlobal(left, offsetTop, 0, Component.translatable("screen.create_fire_control.offset_x"),
                pitch ? pitchOffsetX : yawOffsetX, value -> { if (pitch) pitchOffsetX = value; else yawOffsetX = value; globalBearingMask |= 32; updateLocalGlobalDeviceValues(pitch ? "PITCH" : "YAW", 32); });
        offsetControlGlobal(left, offsetTop + 25, 1, Component.translatable("screen.create_fire_control.offset_y"),
                pitch ? pitchOffsetY : yawOffsetY, value -> { if (pitch) pitchOffsetY = value; else yawOffsetY = value; globalBearingMask |= 64; updateLocalGlobalDeviceValues(pitch ? "PITCH" : "YAW", 64); });
        offsetControlGlobal(left, offsetTop + 50, 2, Component.translatable("screen.create_fire_control.offset_z"),
                pitch ? pitchOffsetZ : yawOffsetZ, value -> { if (pitch) pitchOffsetZ = value; else yawOffsetZ = value; globalBearingMask |= 128; updateLocalGlobalDeviceValues(pitch ? "PITCH" : "YAW", 128); });
        addBearingFooter(Page.BEARING_GLOBAL_SELECT);
    }

    private void buildGlobalBearingSelectionPage() {
        PanelBounds panel = panelBounds();
        int left = panel.left + 14;
        int width = Math.max(120, contentRight(panel) - left);
        int top = panel.top + 54;
        addRenderableWidget(styledButton(
                Component.translatable("screen.create_fire_control.yaw_settings"),
                button -> { selectedBearingRole = "YAW"; selectedBearingPos = null; globalBearingMask = 0; openPage(Page.BEARING_GLOBAL); },
                left, top, width, 22));
        addRenderableWidget(styledButton(
                Component.translatable("screen.create_fire_control.pitch_settings"),
                button -> { selectedBearingRole = "PITCH"; selectedBearingPos = null; globalBearingMask = 0; openPage(Page.BEARING_GLOBAL); },
                left, top + 32, width, 22));
        addBearingFooter(Page.HOME);
    }

    private void globalNumericBox(int x, int y, double value, DoubleConsumer consumer) {
        numericBox(x, y, value, consumer, false);
    }

    private void updateLocalGlobalDeviceValues(String role, int mask) {
        boolean pitch = "PITCH".equals(role);
        for (int index = 0; index < devices.size(); index++) {
            OpenControllerPayload.BoundDevice device = devices.get(index);
            if (!role.equals(device.slot())) continue;
            double nextDepression = (mask & 1) != 0 ? depression : device.depression();
            double nextElevation = (mask & 2) != 0 ? elevation : device.elevation();
            double nextSpeed = (mask & 4) != 0 ? (pitch ? pitchSpeed : yawSpeed) : device.speed();
            boolean nextReversed = (mask & 8) != 0
                    ? (pitch ? pitchReversed : yawReversed) : device.reversed();
            boolean nextHeadHidden = (mask & 16) != 0
                    ? (pitch ? pitchBearingHeadHidden : yawBearingHeadHidden) : device.headHidden();
            double nextOffsetX = (mask & 32) != 0 ? (pitch ? pitchOffsetX : yawOffsetX) : device.offsetX();
            double nextOffsetY = (mask & 64) != 0 ? (pitch ? pitchOffsetY : yawOffsetY) : device.offsetY();
            double nextOffsetZ = (mask & 128) != 0 ? (pitch ? pitchOffsetZ : yawOffsetZ) : device.offsetZ();
            devices.set(index, new OpenControllerPayload.BoundDevice(
                    device.slot(), device.index(), device.translationKey(), device.targetPos(),
                    device.subLevel(), device.podMode(), device.podOrder(), device.podReversed(),
                    device.bearingGroup(), nextDepression, nextElevation, nextSpeed,
                    nextReversed, nextHeadHidden, nextOffsetX, nextOffsetY, nextOffsetZ,
                    pitch ? device.yawLimits() : new YawLimits(
                            (mask & 1) != 0 ? yawLimits.left() : device.yawLimits().left(),
                            (mask & 2) != 0 ? yawLimits.right() : device.yawLimits().right(),
                            (mask & 256) != 0 ? yawLimits.centerDirection() : device.yawLimits().centerDirection())));
        }
    }

    private void offsetControlGlobal(int left, int y, int axisIndex, Component axis,
                                     double value, DoubleConsumer consumer) {
        OffsetSlider slider = offsetControl(left, y, axis, value, consumer);
        slider.setMarkers(globalOffsetMarkers("PITCH".equals(selectedBearingRole), axisIndex));
    }

    private List<Double> globalOffsetMarkers(boolean pitch, int axisIndex) {
        String role = pitch ? "PITCH" : "YAW";
        return devices.stream()
                .filter(device -> role.equals(device.slot()))
                .map(device -> switch (axisIndex) {
                    case 0 -> device.offsetX();
                    case 1 -> device.offsetY();
                    default -> device.offsetZ();
                })
                .filter(Double::isFinite)
                .toList();
    }

    private void buildBearingSelectionPage(String role) {
        PanelBounds panel = panelBounds();
        List<OpenControllerPayload.BoundDevice> candidates = bearingDevices(role);
        int left = panel.left + 14;
        int width = Math.max(120, contentRight(panel) - left);
        int top = panel.top + 48;
        for (int index = 0; index < candidates.size(); index++) {
            OpenControllerPayload.BoundDevice device = candidates.get(index);
            int y = top + index * 28;
            addRenderableWidget(styledButton(
                    bearingSelectionLabel(device, index),
                    button -> {
                        selectBearing(device, role);
                        openPage("PITCH".equals(role) ? Page.PITCH : Page.YAW);
                    },
                    left, y, width, 20
            ));
        }
        if (candidates.isEmpty()) {
            addRenderableWidget(styledButton(
                    Component.translatable("screen.create_fire_control.bearing_selection_empty"),
                    button -> openPage(Page.HOME), left, top, width, 20
            ));
        }
        addBearingFooter(Page.HOME);
    }

    private void addBearingFooter(Page backPage) {
        PanelBounds viewport = viewportPanelBounds();
        int footerWidth = 250;
        int left = viewport.left + Math.max(0, (viewport.right - viewport.left - footerWidth) / 2);
        int footerY = viewport.bottom - 32;
        addFixedWidget(styledButton(Component.translatable("gui.back"),
                button -> openPage(backPage), left, footerY, 120, 20));
        addFixedWidget(styledButton(Component.translatable("gui.done"),
                button -> saveAndClose(), left + 130, footerY, 120, 20)
                .setTone(ButtonTone.GREEN));
    }

    private <T extends AbstractWidget> T addFixedWidget(T widget) {
        addRenderableWidget(widget);
        fixedWidgets.add(widget);
        return widget;
    }

    private List<OpenControllerPayload.BoundDevice> bearingDevices(String role) {
        return devices.stream().filter(device -> role.equals(device.slot())).toList();
    }

    private List<OpenControllerPayload.BoundDevice> groupDevices(String role, int group) {
        List<OpenControllerPayload.BoundDevice> candidates = bearingDevices(role);
        boolean hasGroupMetadata = candidates.stream().anyMatch(device -> device.bearingGroup() >= 0);
        return candidates.stream()
                .filter(device -> !hasGroupMetadata || device.bearingGroup() < 0
                        || device.bearingGroup() == group)
                .toList();
    }

    private void selectBearing(OpenControllerPayload.BoundDevice device, String role) {
        selectedBearingRole = role;
        selectedBearingPos = device.targetPos();
        if ("PITCH".equals(role) && Double.isFinite(device.depression())) {
            depression = device.depression();
            elevation = device.elevation();
            pitchSpeed = device.speed();
            pitchReversed = device.reversed();
            pitchBearingHeadHidden = device.headHidden();
            pitchOffsetX = device.offsetX(); pitchOffsetY = device.offsetY(); pitchOffsetZ = device.offsetZ();
        } else if ("YAW".equals(role) && Double.isFinite(device.speed())) {
            yawSpeed = device.speed();
            yawLimits = device.yawLimits();
            yawReversed = device.reversed();
            yawBearingHeadHidden = device.headHidden();
            yawOffsetX = device.offsetX(); yawOffsetY = device.offsetY(); yawOffsetZ = device.offsetZ();
        }
    }

    private Component bearingSelectionLabel(
            OpenControllerPayload.BoundDevice device,
            int displayIndex
    ) {
        return Component.literal((displayIndex + 1) + ". ")
                .append(deviceDisplayName(device))
                .append(Component.literal(" [" + device.targetPos().getX() + ", "
                        + device.targetPos().getY() + ", " + device.targetPos().getZ() + "]"));
    }

    private void drawSelectedBearingLabel(
            GuiGraphics graphics,
            PanelBounds panel,
            String role
    ) {
        OpenControllerPayload.BoundDevice selected = devices.stream()
                .filter(device -> role.equals(device.slot()))
                .filter(device -> selectedBearingPos == null
                        || selectedBearingPos.equals(device.targetPos()))
                .findFirst().orElse(null);
        if (selected == null) return;
        String text = font.plainSubstrByWidth(
                deviceDisplayName(selected).getString(), panel.right - panel.left - 28);
        drawCenteredNoShadow(graphics, Component.literal(text),
                (panel.left + panel.right) / 2, panel.top + 29, 0xFF625A4C);
    }

    private void buildPitchPage() {
        PanelBounds panel = panelBounds();
        int left = formLeft(panel);
        int formWidth = formWidth(panel);
        int top = panel.top;
        int boxX = left + formWidth - 60;
        numericBox(boxX, top + 39, depression, value -> depression = value, false);
        numericBox(boxX, top + 64, elevation,
                value -> elevation = Math.max(0.0, Math.min(89.0, value)), false);
        numericBox(boxX, top + 89, pitchSpeed, value -> pitchSpeed = value, false);
        int gap = 8;
        int leftButtonWidth = (formWidth - gap) / 2;
        int rightButtonWidth = formWidth - gap - leftButtonWidth;
        addRenderableWidget(styledButton(toggleText("pitch_reversed", pitchReversed), button -> {
            pitchReversed = !pitchReversed;
            button.setMessage(toggleText("pitch_reversed", pitchReversed));
            ((BrassButton) button).setTone(pitchReversed ? ButtonTone.RED : ButtonTone.NORMAL);
        }, left + leftButtonWidth + gap, top + 112, rightButtonWidth, 20).setTone(
                pitchReversed ? ButtonTone.RED : ButtonTone.NORMAL));
        addRenderableWidget(styledButton(toggleText("hide_bearing_head", pitchBearingHeadHidden), button -> {
            pitchBearingHeadHidden = !pitchBearingHeadHidden;
            button.setMessage(toggleText("hide_bearing_head", pitchBearingHeadHidden));
            ((BrassButton) button).setTone(
                    pitchBearingHeadHidden ? ButtonTone.GREEN : ButtonTone.NORMAL);
        }, left, top + 112, leftButtonWidth, 20).setTone(
                pitchBearingHeadHidden ? ButtonTone.GREEN : ButtonTone.NORMAL));
        offsetControl(left, top + 155, Component.translatable("screen.create_fire_control.offset_x"), pitchOffsetX, value -> pitchOffsetX = value);
        offsetControl(left, top + 180, Component.translatable("screen.create_fire_control.offset_y"), pitchOffsetY, value -> pitchOffsetY = value);
        offsetControl(left, top + 205, Component.translatable("screen.create_fire_control.offset_z"), pitchOffsetZ, value -> pitchOffsetZ = value);
        addRenderableWidget(styledButton(Component.translatable("gui.back"), button -> backFromBearingSettings(),
                left + 90, top + 238, 120, 20));
    }

    private void buildYawPage() {
        PanelBounds panel = panelBounds();
        int left = formLeft(panel);
        int formWidth = formWidth(panel);
        int top = panel.top;
        numericBox(left + formWidth - 60, top + 43, yawSpeed, value -> yawSpeed = value, false);
        yawLimitControls(left, top + 68, false);
        int gap = 8;
        int leftButtonWidth = (formWidth - gap) / 2;
        int rightButtonWidth = formWidth - gap - leftButtonWidth;
        addRenderableWidget(styledButton(toggleText("yaw_reversed", yawReversed), button -> {
            yawReversed = !yawReversed;
            button.setMessage(toggleText("yaw_reversed", yawReversed));
            ((BrassButton) button).setTone(yawReversed ? ButtonTone.RED : ButtonTone.NORMAL);
        }, left + leftButtonWidth + gap, top + 174, rightButtonWidth, 20).setTone(
                yawReversed ? ButtonTone.RED : ButtonTone.NORMAL));
        addRenderableWidget(styledButton(toggleText("hide_bearing_head", yawBearingHeadHidden), button -> {
            yawBearingHeadHidden = !yawBearingHeadHidden;
            button.setMessage(toggleText("hide_bearing_head", yawBearingHeadHidden));
            ((BrassButton) button).setTone(
                    yawBearingHeadHidden ? ButtonTone.GREEN : ButtonTone.NORMAL);
        }, left, top + 174, leftButtonWidth, 20).setTone(
                yawBearingHeadHidden ? ButtonTone.GREEN : ButtonTone.NORMAL));
        offsetControl(left, top + 214, Component.translatable("screen.create_fire_control.offset_x"), yawOffsetX, value -> yawOffsetX = value);
        offsetControl(left, top + 239, Component.translatable("screen.create_fire_control.offset_y"), yawOffsetY, value -> yawOffsetY = value);
        offsetControl(left, top + 264, Component.translatable("screen.create_fire_control.offset_z"), yawOffsetZ, value -> yawOffsetZ = value);
        addRenderableWidget(styledButton(Component.translatable("gui.back"), button -> backFromBearingSettings(),
                left + 90, top + 297, 120, 20));
    }

    private void backFromBearingSettings() {
        boolean multipleBearings = bearingDevices("YAW").size() > 1
                || bearingDevices("PITCH").size() > 1;
        openPage(multipleBearings ? Page.BEARING_GROUP : Page.HOME);
    }

    private void buildViewPage() {
        PanelBounds panel = panelBounds();
        int left = formLeft(panel);
        int formWidth = formWidth(panel);
        int top = panel.top;
        numericBox(left + formWidth - 60, top + 34, thirdPersonHeight,
                value -> thirdPersonHeight = value, false);
        int offsetWidgetsStart = children().size();
        offsetControl(left, top + 70, Component.translatable("screen.create_fire_control.offset_x"), scopeOffsetX, value -> scopeOffsetX = value);
        offsetControl(left, top + 96, Component.translatable("screen.create_fire_control.offset_y"), scopeOffsetY, value -> scopeOffsetY = value);
        offsetControl(left, top + 122, Component.translatable("screen.create_fire_control.offset_z"), scopeOffsetZ, value -> scopeOffsetZ = value);
        if (virtualScopeEnabled) {
            for (int index = offsetWidgetsStart; index < children().size(); index++) {
                if (children().get(index) instanceof AbstractWidget widget) {
                    widget.active = false;
                    widget.setTooltip(Tooltip.create(Component.translatable(
                            "screen.create_fire_control.virtual_scope.offset_locked")));
                    if (widget instanceof EditBox box) {
                        box.setEditable(false);
                        box.setTextColor(0x808080);
                        box.setTextColorUneditable(0x808080);
                    }
                }
            }
        }
        int gap = 8;
        int leftButtonWidth = (formWidth - gap) / 2;
        int rightButtonWidth = formWidth - gap - leftButtonWidth;
        addRenderableWidget(styledButton(Component.translatable("screen.create_fire_control.aim_point_settings"),
                button -> openPage(Page.AIM_POINT), left, top + 151, leftButtonWidth, 20));
        addRenderableWidget(styledButton(Component.translatable("screen.create_fire_control.pod_cannon_settings"),
                button -> openPage(Page.POD_CANNON), left + leftButtonWidth + gap,
                top + 151, rightButtonWidth, 20));
        addRenderableWidget(styledButton(Component.translatable("gui.back"), button -> openPage(Page.HOME),
                left + 90, top + 183, 120, 20));
    }

    private void buildPodCannonPage() {
        PanelBounds panel = panelBounds();
        int left = formLeft(panel);
        int formWidth = formWidth(panel);
        int top = panel.top;
        numericBox(left + formWidth - 60, top + 55, podCannonDepression,
                value -> podCannonDepression = Math.max(0.0, Math.min(90.0, value)), false);
        numericBox(left + formWidth - 60, top + 86, podCannonElevation,
                value -> podCannonElevation = Math.max(0.0, Math.min(90.0, value)), false);
        addRenderableWidget(styledButton(Component.translatable("gui.back"),
                button -> openPage(Page.VIEW), left + 90, top + 132, 120, 20));
    }

    private void buildAimPointPage() {
        PanelBounds panel = panelBounds();
        int left = formLeft(panel);
        int top = panel.top;
        aimOffsetControl(left, top + 166, Component.translatable("screen.create_fire_control.aim_offset_x"),
                viewAimOffsetX, value -> viewAimOffsetX = value);
        aimOffsetControl(left, top + 194, Component.translatable("screen.create_fire_control.aim_offset_y"),
                viewAimOffsetY, value -> viewAimOffsetY = value);
        addRenderableWidget(styledButton(Component.translatable("gui.back"), button -> openPage(Page.VIEW),
                left + 90, top + 226, 120, 20));
    }

    private void buildRadarPage() {
        PanelBounds panel = panelBounds();
        int top = panel.top;
        int boxX = contentRight(panel) - 60;
        int fieldsTop = top + RADAR_FIELDS_TOP;
        numericBox(boxX, fieldsTop, radarRange, value ->
                radarRange = Math.max(200.0, Math.min(20000.0, value)), false);
        numericBox(boxX, fieldsTop + 26, radarDepression, value ->
                radarDepression = Math.max(0.0, Math.min(90.0, value)), false);
        numericBox(boxX, fieldsTop + 52, radarElevation, value ->
                radarElevation = Math.max(0.0, Math.min(90.0, value)), false);
        numericBox(boxX, fieldsTop + 78, radarHudScale, value ->
                radarHudScale = Math.max(40.0, Math.min(150.0, value)), false);
        numericBox(boxX, fieldsTop + 104, radarHudX, value ->
                radarHudX = Math.max(0.0, Math.min(100.0, value)), false);
        numericBox(boxX, fieldsTop + 130, radarHudY, value ->
                radarHudY = Math.max(0.0, Math.min(100.0, value)), false);
        numericBox(boxX, fieldsTop + 156, aaProximityFuseRange, value ->
                aaProximityFuseRange = Math.max(0.0, Math.min(10.0, value)), false);

        addRenderableWidget(styledButton(Component.translatable("gui.back"),
                button -> openPage(Page.HOME), width / 2 - 60, top + 396, 120, 20));
    }

    private void buildAircraftPage() {
        PanelBounds panel = panelBounds();
        int boxX = contentRight(panel) - 60;
        int top = panel.top;
        int fieldsTop = top + AIRCRAFT_FIELDS_TOP;
        int toggleX = formLeft(panel);
        addRenderableWidget(styledButton(
                toggleText("aircraft_rocket_salvo_mode", aircraftRocketSalvoMode), button -> {
                    aircraftRocketSalvoMode = !aircraftRocketSalvoMode;
                    button.setMessage(toggleText(
                            "aircraft_rocket_salvo_mode", aircraftRocketSalvoMode));
                    ((BrassButton) button).setTone(aircraftRocketSalvoMode
                            ? ButtonTone.GREEN : ButtonTone.NORMAL);
                }, toggleX, top + 200, formWidth(panel), 20).setTone(
                        aircraftRocketSalvoMode ? ButtonTone.GREEN : ButtonTone.NORMAL));
        numericBox(boxX, fieldsTop, aircraftRadarRange,
                value -> aircraftRadarRange = Math.max(200.0, Math.min(100000.0, value)), false);
        numericBox(boxX, fieldsTop + 24, aircraftRadarHorizontalHalfAngle,
                value -> aircraftRadarHorizontalHalfAngle = Math.max(5.0, Math.min(90.0, value)), false);
        numericBox(boxX, fieldsTop + 48, aircraftRadarVerticalAngle,
                value -> aircraftRadarVerticalAngle = Math.max(2.0, Math.min(90.0, value)), false);
        numericBox(boxX, fieldsTop + 72, aircraftProximityFuseRange,
                value -> aircraftProximityFuseRange = Math.max(0.0, Math.min(10.0, value)), false);
        numericBox(boxX, fieldsTop + 96, aircraftFuseSafetySeconds,
                value -> aircraftFuseSafetySeconds = Math.max(0.0, Math.min(10.0, value)), false);
        numericBox(boxX, fieldsTop + 120, aircraftRadarMinimumTargetSpeed,
                value -> aircraftRadarMinimumTargetSpeed = Math.max(0.0, Math.min(2000.0, value)), false);
        numericBox(boxX, fieldsTop + 144, aircraftRadarHudScale,
                value -> aircraftRadarHudScale = Math.max(40.0, Math.min(180.0, value)), false);
        numericBox(boxX, fieldsTop + 168, aircraftRadarHudX,
                value -> aircraftRadarHudX = Math.max(0.0, Math.min(100.0, value)), false);
        numericBox(boxX, fieldsTop + 192, aircraftRadarHudY,
                value -> aircraftRadarHudY = Math.max(0.0, Math.min(100.0, value)), false);
        numericBox(boxX, fieldsTop + 216, rwrRange,
                value -> rwrRange = Math.max(200.0, Math.min(100000.0, value)), false);
        numericBox(boxX, fieldsTop + 240, rwrScale,
                value -> rwrScale = Math.max(40.0, Math.min(180.0, value)), false);

        addRenderableWidget(styledButton(Component.translatable("gui.back"), button -> openPage(Page.HOME),
                width / 2 - 60, top + 516, 120, 20));
    }

    private int inputRowHeight() { return inputTerminalTypes().size()>1?42:22; }
    private List<RemoteControllerCompat.Type> inputTerminalTypes() {
        return java.util.Arrays.stream(RemoteControllerCompat.Type.values()).filter(this::hasBoundHub).toList();
    }
    private void loadInputState(net.minecraft.nbt.CompoundTag state) {
        inputState=state.copy();wirelessRemoteKeys.clear();
        var frequencies=state.getList("Frequencies",net.minecraft.nbt.Tag.TAG_COMPOUND);
        for(int action=0;action<INPUT_ACTION_COUNT;action++) {
            wirelessRemoteKeys.add(state.getCompound("WirelessRemoteKeys").getString(Integer.toString(action)));
            for(int slot=0;slot<2;slot++)inputFrequencies[action][slot]=net.minecraft.world.item.ItemStack.parseOptional(
                    Minecraft.getInstance().level.registryAccess(),frequencies.getCompound(action).getCompound(slot==0?"First":"Second"));
        }
    }
    public void acceptInputState(com.hooya.stabilizedturret.network.FireControlInputsStatePayload payload) {
        if(!initial.pos().equals(payload.pos()))return;
        loadInputState(payload.state());
        for(int action=0;action<INPUT_ACTION_COUNT;action++) {
            normalRemoteKeys.set(action,payload.state().getCompound("NormalRemoteKeys").getString(Integer.toString(action)));
            advancedRemoteKeys.set(action,payload.state().getCompound("AdvancedRemoteKeys").getString(Integer.toString(action)));
        }
        if(!payload.accepted())minecraft.player.displayClientMessage(Component.translatable("message.create_fire_control.remote_key_binding.invalid_key"),true);
        if(page==Page.INPUTS || page==Page.WEAPON_INPUTS)rebuildPage();
    }
    private void buildInputsPage() {
        PanelBounds panel=panelBounds();int left=panel.left+14,right=contentRight(panel),y=panel.top+34;
        List<RemoteControllerCompat.Type> terminals=inputTerminalTypes();
        for(InputGroup group:INPUT_GROUPS) {
            y+=20;
            if (!group.name().equals("other")) {
                boolean aircraft = group.name().equals("air");
                addRenderableWidget(styledButton(Component.translatable("screen.create_fire_control.weapon_switch_options"),
                        button -> { aircraftWeaponInputs = aircraft; openPage(Page.WEAPON_INPUTS); },
                        left, y, right-left, 18));
                y += 22;
            }
            for(int action:group.actions()) {
                buildInputRow(action, left, right, y, terminals);
                y+=inputRowHeight();
            }
        }
        addRenderableWidget(styledButton(Component.translatable("gui.back"),button->openPage(Page.HOME),right-56,y+2,56,18));
    }

    private void buildInputRow(int action, int left, int right, int y, List<RemoteControllerCompat.Type> terminals) {
        int frequencyX=right-44-(terminals.size()==1?122:0);
        for(int slot=0;slot<2;slot++) {
            addRenderableWidget(styledButton(Component.empty(),button->minecraft.setScreen(
                    new com.hooya.stabilizedturret.client.remote.FireControlFrequencyScreen(this,initial.pos(),action,
                            inputFrequencies[action][0],inputFrequencies[action][1])),frequencyX+slot*22,y,20,18));
        }
        for(int index=0;index<terminals.size();index++) {
            var type=terminals.get(index);
            int bw=terminals.size()==1?118:(right-left-(terminals.size()-1)*4)/terminals.size();
            int bx=terminals.size()==1?right-bw:left+index*(bw+4),by=terminals.size()==1?y:y+20;
            if(type==RemoteControllerCompat.Type.WIRELESS) {
                String key=wirelessRemoteKeys.get(action);
                Component value=capturingRemoteKey!=null&&capturingRemoteKey.type()==type&&capturingRemoteKey.action()==action
                        ?wirelessCapture.preview(Component.translatable("screen.create_fire_control.press_key"))
                        :key.isEmpty()?Component.literal("-"):ControllerKeyMappingCompat.displayKey(type,key);
                addRenderableWidget(styledButton(Component.translatable("screen.create_fire_control.input.terminal_wireless").append(value),button->{
                    wirelessCapture.clear();capturingRemoteKey=new RemoteKeyDropdown(type,action,bx,by,bw);rebuildPage();
                },bx,by,bw,18));
            } else {
                Button button=remoteKeyButton(type,action,bx,by,bw);button.setHeight(18);
            }
        }
    }

    private int[] weaponInputActions() {
        if (inputState == null) return new int[0];
        return java.util.Arrays.stream(inputState.getIntArray(aircraftWeaponInputs ? "AircraftWeaponActions" : "GroundWeaponActions"))
                .filter(action -> aircraftWeaponInputs ? FireControlWeaponInputs.aircraftWeapon(action) != null
                        : FireControlWeaponInputs.groundWeapon(action) != null).distinct().toArray();
    }

    private void buildWeaponInputsPage() {
        PanelBounds panel=panelBounds();int left=panel.left+14,right=contentRight(panel),y=panel.top+56;
        var terminals = inputTerminalTypes();
        for (int action : weaponInputActions()) {
            buildInputRow(action, left, right, y, terminals);
            y += inputRowHeight();
        }
        if (weaponInputActions().length == 0) y += 22;
        addRenderableWidget(styledButton(Component.translatable("gui.back"), button -> openPage(Page.INPUTS), right-56,y+6,56,18));
    }

    private void renderInputRow(GuiGraphics graphics, int action, int left, int frequencyX, int y) {
        graphics.drawString(font,font.plainSubstrByWidth(inputActionLabel(action).getString(),frequencyX-left-6),left,y+5,LABEL_COLOR,false);
        for(int slot=0;slot<2;slot++)graphics.renderItem(inputFrequencies[action][slot],frequencyX+slot*22+2,y+1);
    }

    private void renderWeaponInputLabels(GuiGraphics graphics) {
        PanelBounds panel=panelBounds();int left=panel.left+14,right=contentRight(panel),y=panel.top+56;
        int frequencyX=right-44-(inputTerminalTypes().size()==1?122:0);
        graphics.drawString(font, Component.translatable("screen.create_fire_control.input.category." + (aircraftWeaponInputs ? "air" : "ground")),
                left,panel.top+35,LABEL_COLOR,false);
        int[] actions = weaponInputActions();
        if (actions.length == 0) graphics.drawString(font, Component.translatable("screen.create_fire_control.weapon_switch_empty"),left,y+5,LABEL_COLOR,false);
        for (int action : actions) {
            renderInputRow(graphics,action,left,frequencyX,y);
            y += inputRowHeight();
        }
    }
    private void renderInputLabels(GuiGraphics graphics) {
        PanelBounds panel=panelBounds();int left=panel.left+14,right=contentRight(panel),y=panel.top+34;
        int frequencyX=right-44-(inputTerminalTypes().size()==1?122:0);
        for(InputGroup group:INPUT_GROUPS) {
            Component category=Component.translatable("screen.create_fire_control.input.category."+group.name());
            graphics.fill(left,y+14,right,y+15,0xFFB39C70);
            graphics.drawString(font,category,left,y+3,0xFF76603A,false);y+=20;
            if (!group.name().equals("other")) y += 22;
            for(int action:group.actions()) {
                renderInputRow(graphics,action,left,frequencyX,y);
                y+=inputRowHeight();
            }
        }
    }

    private void buildDevicesPage() {
        PanelBounds panel = panelBounds();
        if (pendingUnbind != null) {
            addRenderableWidget(styledButton(
                    Component.translatable("screen.create_fire_control.unbind_confirm"),
                    button -> {
                        sendUnbind(pendingUnbind, true);
                        removeLocalDevice(pendingUnbind);
                        pendingUnbind = null;
                        rebuildPage();
                    },
                    width / 2 - 125, panel.bottom - 62, 120, 20
            ).setTone(ButtonTone.RED));
            addRenderableWidget(styledButton(
                    Component.translatable("gui.cancel"),
                    button -> {
                        pendingUnbind = null;
                        rebuildPage();
                    },
                    width / 2 + 5, panel.bottom - 62, 120, 20
            ));
            return;
        }
        if (pendingBearingRole != null) {
            int left = width / 2 - 125;
            int top = panel.top + 72;
            addBearingRoleButton("YAW", left, top);
            addBearingRoleButton("PITCH", left + 130, top);
            addRenderableWidget(styledButton(
                    Component.translatable("gui.cancel"),
                    button -> {
                        pendingBearingRole = null;
                        rebuildPage();
                    },
                    width / 2 - 60, panel.bottom - 46, 120, 20
            ));
            return;
        }
        if (pendingCannonMountMode != null) {
            int left = width / 2 - 125;
            int top = panel.top + 72;
            addRenderableWidget(styledButton(
                    Component.translatable(
                            "screen.create_fire_control.cannon_mount_mode.default"),
                    button -> setCannonMountMode(false),
                    left, top, 120, 20
            ));
            addRenderableWidget(styledButton(
                    Component.translatable(
                            "screen.create_fire_control.cannon_mount_mode.pod_control"),
                    button -> setCannonMountMode(true),
                    left + 130, top, 120, 20
            ));
            addRenderableWidget(styledButton(
                    Component.translatable("gui.cancel"),
                    button -> {
                        pendingCannonMountMode = null;
                        rebuildPage();
                    },
                    width / 2 - 60, panel.bottom - 46, 120, 20
            ));
            return;
        }

        int pageCount = devicePageCount();
        devicePage = Math.max(0, Math.min(devicePage, pageCount - 1));
        int start = devicePage * DEVICES_PER_PAGE;
        int end = Math.min(devices.size(), start + DEVICES_PER_PAGE);
        for (int index = start; index < end; index++) {
            OpenControllerPayload.BoundDevice device = devices.get(index);
            int row = index - start;
            if (isBearingDevice(device.slot()) || isCannonMountDevice(device.slot())) {
                addRenderableWidget(styledButton(
                    Component.translatable("screen.create_fire_control.assign_role"),
                    button -> {
                            if (isCannonMountDevice(device.slot())) {
                                pendingCannonMountMode = device;
                            } else {
                                pendingBearingRole = device;
                            }
                            rebuildPage();
                        },
                        panel.right - 128, panel.top + 39 + row * 25, 56, 20
                ));
            }
            addRenderableWidget(styledButton(
                    Component.translatable("screen.create_fire_control.unbind"),
                    button -> requestUnbind(device),
                    panel.right - 68, panel.top + 39 + row * 25, 54, 20
            ).setTone(ButtonTone.RED));
        }
        BrassButton previous = styledButton(
                Component.translatable("gui.previous"),
                button -> {
                    devicePage--;
                    rebuildPage();
                },
                panel.left + 14, panel.bottom - 55, 80, 20
        );
        previous.active = devicePage > 0;
        addRenderableWidget(previous);
        BrassButton next = styledButton(
                Component.translatable("gui.next"),
                button -> {
                    devicePage++;
                    rebuildPage();
                },
                panel.right - 94, panel.bottom - 55, 80, 20
        );
        next.active = devicePage + 1 < pageCount;
        addRenderableWidget(next);
        addRenderableWidget(styledButton(
                Component.translatable("gui.back"),
                button -> openPage(Page.HOME),
                width / 2 - 60, panel.bottom - 29, 120, 20
        ));
    }

    private void addBearingRoleButton(String role, int x, int y) {
        addRenderableWidget(styledButton(
                Component.translatable(
                        "screen.create_fire_control.device_type." + role.toLowerCase(Locale.ROOT)),
                button -> assignBearingRole(role),
                x, y, 120, 20
        ));
    }

    private void setCannonMountMode(boolean podControl) {
        OpenControllerPayload.BoundDevice selected = pendingCannonMountMode;
        if (selected == null) return;
        PacketDistributor.sendToServer(new SetCannonMountModePayload(
                initial.pos(), selected.targetPos(), podControl));
        int selectedIndex = devices.indexOf(selected);
        if (selectedIndex >= 0) {
            devices.set(selectedIndex, new OpenControllerPayload.BoundDevice(
                    selected.slot(),
                    selected.index(),
                    selected.translationKey(),
                    selected.targetPos(),
                    selected.subLevel(),
                    podControl ? "POD_CONTROL" : "DEFAULT",
                    selected.podOrder(),
                    selected.podReversed()
            ));
        }
        pendingCannonMountMode = null;
        rebuildPage();
    }

    private void assignBearingRole(String role) {
        OpenControllerPayload.BoundDevice selected = pendingBearingRole;
        if (selected == null) return;
        if (role.equals(selected.slot())) {
            pendingBearingRole = null;
            rebuildPage();
            return;
        }
        PacketDistributor.sendToServer(new AssignBearingRolePayload(
                initial.pos(), selected.slot(), selected.index(), selected.targetPos(), role
        ));

        devices.remove(selected);
        int roleIndex = (int) devices.stream().filter(device -> role.equals(device.slot())).count();
        devices.add(new OpenControllerPayload.BoundDevice(
                role, roleIndex, selected.translationKey(), selected.targetPos(), selected.subLevel()
        ));
        renumberUnassignedBearings();
        pendingBearingRole = null;
        rebuildPage();
    }

    private void renumberUnassignedBearings() {
        int bearingIndex = 0;
        for (int index = 0; index < devices.size(); index++) {
            OpenControllerPayload.BoundDevice device = devices.get(index);
            if ("BEARING".equals(device.slot())) {
                devices.set(index, new OpenControllerPayload.BoundDevice(
                        device.slot(), bearingIndex++, device.translationKey(),
                        device.targetPos(), device.subLevel()
                ));
            }
        }
    }

    private static boolean isBearingDevice(String slot) {
        return "BEARING".equals(slot) || "YAW".equals(slot) || "PITCH".equals(slot);
    }

    private static boolean isCannonMountDevice(String slot) {
        return "CANNON_MOUNT".equals(slot);
    }

    private void requestUnbind(OpenControllerPayload.BoundDevice device) {
        if ("YAW".equals(device.slot()) || "PITCH".equals(device.slot())) {
            pendingUnbind = device;
            rebuildPage();
            return;
        }
        sendUnbind(device, false);
        removeLocalDevice(device);
        rebuildPage();
    }

    private void sendUnbind(OpenControllerPayload.BoundDevice device, boolean confirmed) {
        PacketDistributor.sendToServer(new UnbindDevicePayload(
                initial.pos(), device.slot(), device.index(), device.targetPos(), confirmed));
    }

    private void removeLocalDevice(OpenControllerPayload.BoundDevice removed) {
        devices.removeIf(device -> device.slot().equals(removed.slot())
                && device.index() == removed.index()
                && device.targetPos().equals(removed.targetPos()));
        int missileIndex = 0;
        int aaMissileIndex = 0;
        int aircraftRackIndex = 0;
        int aircraftRocketPodIndex = 0;
        int cannonTurretPodIndex = 0;
        int countermeasureLauncherIndex = 0;
        int remoteWeaponStationIndex = 0;
        for (int index = 0; index < devices.size(); index++) {
            OpenControllerPayload.BoundDevice device = devices.get(index);
            if ("MISSILE_LAUNCHER".equals(device.slot())) {
                devices.set(index, new OpenControllerPayload.BoundDevice(
                        device.slot(), missileIndex++, device.translationKey(),
                        device.targetPos(), device.subLevel()));
            } else if ("AA_MISSILE_LAUNCHER".equals(device.slot())) {
                devices.set(index, new OpenControllerPayload.BoundDevice(
                        device.slot(), aaMissileIndex++, device.translationKey(),
                        device.targetPos(), device.subLevel()));
            } else if ("AIRCRAFT_MISSILE_RACK".equals(device.slot())) {
                devices.set(index, new OpenControllerPayload.BoundDevice(
                        device.slot(), aircraftRackIndex++, device.translationKey(),
                        device.targetPos(), device.subLevel()));
            } else if ("AIRCRAFT_ROCKET_POD".equals(device.slot())) {
                devices.set(index, new OpenControllerPayload.BoundDevice(
                        device.slot(), aircraftRocketPodIndex++, device.translationKey(),
                        device.targetPos(), device.subLevel()));
            } else if ("CANNON_TURRET_POD".equals(device.slot())) {
                devices.set(index, new OpenControllerPayload.BoundDevice(
                        device.slot(), cannonTurretPodIndex++, device.translationKey(),
                        device.targetPos(), device.subLevel()));
            } else if ("COUNTERMEASURE_LAUNCHER".equals(device.slot())) {
                devices.set(index, new OpenControllerPayload.BoundDevice(
                        device.slot(), countermeasureLauncherIndex++, device.translationKey(),
                        device.targetPos(), device.subLevel()));
            } else if ("REMOTE_WEAPON_STATION".equals(device.slot())) {
                devices.set(index, new OpenControllerPayload.BoundDevice(
                        device.slot(), remoteWeaponStationIndex++, device.translationKey(),
                        device.targetPos(), device.subLevel()));
            }
        }
        devicePage = Math.min(devicePage, devicePageCount() - 1);
    }

    private int devicePageCount() {
        return Math.max(1, (devices.size() + DEVICES_PER_PAGE - 1) / DEVICES_PER_PAGE);
    }

    private void rebuildPage() {
        clearWidgets();
        buildPage();
    }

    private OffsetSlider offsetControl(int left, int y, Component axis, double current, DoubleConsumer consumer) {
        int controlWidth = formWidth(panelBounds());
        int boxX = left + controlWidth - 60;
        int sliderWidth = Math.max(50, controlWidth - 70);
        final OffsetSlider[] sliderRef = new OffsetSlider[1];
        EditBox box = numericBox(boxX, y + 3, current, value -> {
            consumer.accept(value);
            if (sliderRef[0] != null) {
                sliderRef[0].setActualValue(value);
            }
        }, true);
        OffsetSlider slider = new OffsetSlider(left, y, sliderWidth, 26, axis, current, value -> {
            consumer.accept(value);
            box.setValue(formatOffset(value));
        });
        sliderRef[0] = slider;
        addRenderableWidget(slider);
        return slider;
    }

    private void yawLimitControls(int left, int y, boolean global) {
        yawLimitControl(left, y, Component.translatable("screen.create_fire_control.yaw_left_limit"),
                yawLimits.left(), value -> {
                    yawLimits = new YawLimits(value, yawLimits.right(), yawLimits.centerDirection());
                    if (global) { globalBearingMask |= 1; updateLocalGlobalDeviceValues("YAW", 1); }
                });
        yawLimitControl(left, y + 30, Component.translatable("screen.create_fire_control.yaw_right_limit"),
                yawLimits.right(), value -> {
                    yawLimits = new YawLimits(yawLimits.left(), value, yawLimits.centerDirection());
                    if (global) { globalBearingMask |= 2; updateLocalGlobalDeviceValues("YAW", 2); }
                });
        List<BrassButton> buttons = new ArrayList<>();
        String[] directions = {"east", "south", "west", "north"};
        int totalWidth = formWidth(panelBounds());
        for (int index = 0; index < 4; index++) {
            int direction = index;
            int x = left + index * (totalWidth + 6) / 4;
            int buttonWidth = (totalWidth + 6) / 4 - 6;
            BrassButton button = styledButton(Component.translatable("screen.create_fire_control.yaw_center_" + directions[index]),
                    clicked -> {
                        yawLimits = new YawLimits(yawLimits.left(), yawLimits.right(), direction);
                        if (global) { globalBearingMask |= 256; updateLocalGlobalDeviceValues("YAW", 256); }
                        for (int i = 0; i < buttons.size(); i++)
                            buttons.get(i).setTone(i == direction ? ButtonTone.GREEN : ButtonTone.NORMAL);
                    }, x, y + 76, buttonWidth, 20).setTone(
                    index == yawLimits.centerDirection() ? ButtonTone.GREEN : ButtonTone.NORMAL);
            button.setTooltip(Tooltip.create(Component.translatable("screen.create_fire_control.yaw_center_hint")));
            buttons.add(addRenderableWidget(button));
        }
    }

    private void yawLimitControl(int left, int y, Component label, double current, DoubleConsumer consumer) {
        int controlWidth = formWidth(panelBounds());
        final YawLimitSlider[] sliderRef = new YawLimitSlider[1];
        final EditBox[] boxRef = new EditBox[1];
        EditBox box = numericBox(left + controlWidth - 60, y + 3, current, value -> {
            if (!Double.isFinite(value)) return;
            double clamped = YawLimits.clamp(value);
            consumer.accept(clamped);
            if (sliderRef[0] != null) sliderRef[0].setActualValue(clamped);
            if (boxRef[0] != null && clamped != value) boxRef[0].setValue(String.format(Locale.ROOT, "%.2f", clamped));
        }, false);
        boxRef[0] = box;
        box.setTooltip(Tooltip.create(Component.translatable("screen.create_fire_control.yaw_limit_hint")));
        YawLimitSlider slider = new YawLimitSlider(left, y, Math.max(50, controlWidth - 70), 26, label, current, value -> {
            consumer.accept(value);
            box.setValue(String.format(Locale.ROOT, "%.2f", value));
        });
        slider.setTooltip(Tooltip.create(Component.translatable("screen.create_fire_control.yaw_limit_hint")));
        sliderRef[0] = slider;
        addRenderableWidget(slider);
    }

    private void aimOffsetControl(int left, int y, Component axis, double current, DoubleConsumer consumer) {
        int controlWidth = formWidth(panelBounds());
        int boxX = left + controlWidth - 60;
        int sliderWidth = Math.max(50, controlWidth - 70);
        final AimOffsetSlider[] sliderRef = new AimOffsetSlider[1];
        EditBox box = numericBox(boxX, y + 3, current, value -> {
            double clamped = roundAimOffset(value);
            consumer.accept(clamped);
            if (sliderRef[0] != null) {
                sliderRef[0].setActualValue(clamped);
            }
        }, true);
        AimOffsetSlider slider = new AimOffsetSlider(left, y, sliderWidth, 26, axis, current, value -> {
            consumer.accept(value);
            box.setValue(formatAimOffset(value));
        });
        sliderRef[0] = slider;
        addRenderableWidget(slider);
    }

    private EditBox numericBox(
            int x,
            int y,
            double value,
            DoubleConsumer consumer,
            boolean signed
    ) {
        EditBox box = new EditBox(font, x, y, 60, 20, Component.empty());
        box.setTextShadow(false);
        box.setTextColor(0xFFF4EACF);
        box.setTextColorUneditable(0xFF9D9585);
        box.setValue(signed ? formatOffset(value) : String.format(Locale.ROOT, "%.2f", value));
        box.setFilter(text -> text.isEmpty() || text.matches(signed
                ? "-?[0-9]*\\.?[0-9]*"
                : "[0-9]*\\.?[0-9]*"));
        box.setResponder(text -> {
            try {
                consumer.accept(Double.parseDouble(text));
            } catch (NumberFormatException ignored) {
            }
        });
        addRenderableWidget(box);
        return box;
    }

    private void cannonRecoilControl(int left, int y) {
        int controlWidth = formWidth(panelBounds());
        final CannonRecoilSlider[] sliderRef = new CannonRecoilSlider[1];
        EditBox box = numericBox(left + controlWidth - 60, y + 3, cannonRecoilMagnitude, value -> {
            cannonRecoilMagnitude = CannonRecoilSettings.clamp(value);
            if (sliderRef[0] != null) sliderRef[0].setActualValue(cannonRecoilMagnitude);
        }, false);
        Component label = Component.translatable("screen.create_fire_control.cannon_recoil_magnitude");
        Tooltip tooltip = Tooltip.create(Component.translatable(
                "screen.create_fire_control.cannon_recoil_magnitude.tooltip"));
        box.setMessage(label);
        box.setMaxLength(6);
        box.setFilter(text -> {
            if (text.isEmpty() || text.equals(".")) return true;
            if (!text.matches("[0-9]*\\.?[0-9]{0,2}")) return false;
            try {
                return Double.parseDouble(text) <= CannonRecoilSettings.MAX;
            } catch (NumberFormatException ignored) {
                return false;
            }
        });
        box.setValue(formatRecoil(cannonRecoilMagnitude));
        box.setTooltip(tooltip);
        CannonRecoilSlider slider = new CannonRecoilSlider(left, y, Math.max(50, controlWidth - 70), 26,
                label, cannonRecoilMagnitude, value -> {
                    cannonRecoilMagnitude = value;
                    box.setValue(formatRecoil(value));
                });
        slider.setTooltip(tooltip);
        sliderRef[0] = slider;
        addRenderableWidget(slider);
    }

    private static String formatRecoil(double value) {
        return String.format(Locale.ROOT, "%.2f", CannonRecoilSettings.clamp(value))
                .replaceAll("\\.?0+$", "");
    }

    private Button frequencyButton(int x, int y, int channel, boolean first, int buttonWidth) {
        Component current = switch (channel) {
            case 1 -> first ? thermalFirst : thermalSecond;
            case 2 -> first ? opticalLockFirst : opticalLockSecond;
            case 3 -> first ? missileFireFirst : missileFireSecond;
            case 4 -> first ? aaMissileFireFirst : aaMissileFireSecond;
            case 5 -> first ? radarLockFirst : radarLockSecond;
            case 6 -> first ? aircraftMissileFireFirst : aircraftMissileFireSecond;
            case 7 -> first ? aircraftWeaponCycleFirst : aircraftWeaponCycleSecond;
            case 8 -> first ? aircraftTargetCycleFirst : aircraftTargetCycleSecond;
            case 9 -> first ? podViewFirst : podViewSecond;
            case 10 -> first ? airToGroundLockFirst : airToGroundLockSecond;
            case 11 -> first ? countermeasureFireFirst : countermeasureFireSecond;
            case INPUT_ACTION_FREE_LOOK -> first ? freeLookFirst : freeLookSecond;
            case INPUT_ACTION_TELEVISION_GUIDANCE -> first
                    ? televisionGuidanceFirst : televisionGuidanceSecond;
            case INPUT_ACTION_LASER_RANGE -> first ? laserRangeFirst : laserRangeSecond;
            default -> first ? scopeFirst : scopeSecond;
        };
        return addRenderableWidget(styledButton(frequencyText(first, current), button -> {
            PacketDistributor.sendToServer(new SetWirelessFrequencyPayload(initial.pos(), channel, first));
            Component heldName = minecraft != null && minecraft.player != null
                    && !minecraft.player.getMainHandItem().isEmpty()
                    ? minecraft.player.getMainHandItem().getHoverName().copy()
                    : Component.literal("-");
            switch (channel) {
                case 1 -> { if (first) thermalFirst = heldName; else thermalSecond = heldName; }
                case 2 -> { if (first) opticalLockFirst = heldName; else opticalLockSecond = heldName; }
                case 3 -> { if (first) missileFireFirst = heldName; else missileFireSecond = heldName; }
                case 4 -> { if (first) aaMissileFireFirst = heldName; else aaMissileFireSecond = heldName; }
                case 5 -> { if (first) radarLockFirst = heldName; else radarLockSecond = heldName; }
                case 6 -> { if (first) aircraftMissileFireFirst = heldName; else aircraftMissileFireSecond = heldName; }
                case 7 -> { if (first) aircraftWeaponCycleFirst = heldName; else aircraftWeaponCycleSecond = heldName; }
                case 8 -> { if (first) aircraftTargetCycleFirst = heldName; else aircraftTargetCycleSecond = heldName; }
                case 9 -> { if (first) podViewFirst = heldName; else podViewSecond = heldName; }
                case 10 -> {
                    if (first) airToGroundLockFirst = heldName;
                    else airToGroundLockSecond = heldName;
                }
                case 11 -> {
                    if (first) countermeasureFireFirst = heldName;
                    else countermeasureFireSecond = heldName;
                }
                case INPUT_ACTION_FREE_LOOK -> {
                    if (first) freeLookFirst = heldName;
                    else freeLookSecond = heldName;
                }
                case INPUT_ACTION_TELEVISION_GUIDANCE -> {
                    if (first) televisionGuidanceFirst = heldName;
                    else televisionGuidanceSecond = heldName;
                }
                case INPUT_ACTION_LASER_RANGE -> {
                    if (first) laserRangeFirst = heldName;
                    else laserRangeSecond = heldName;
                }
                default -> { if (first) scopeFirst = heldName; else scopeSecond = heldName; }
            }
            button.setMessage(frequencyText(first, heldName));
        }, x, y, buttonWidth, 20));
    }

    private Button remoteKeyButton(
            RemoteControllerCompat.Type type, int action, int x, int y, int buttonWidth
    ) {
        boolean bound = hasBoundHub(type);
        List<String> selected = type == RemoteControllerCompat.Type.ADVANCED
                ? advancedRemoteKeys : normalRemoteKeys;
        Component typePrefix = Component.translatable(
                type == RemoteControllerCompat.Type.ADVANCED ? "screen.create_fire_control.input.terminal_advanced" : "screen.create_fire_control.input.terminal_normal");
        BrassButton result = styledButton(
                Component.empty().append(typePrefix).append(
                        remoteKeyText(type, action, selected.get(action), bound)), button -> {
            capturingRemoteKey = new RemoteKeyDropdown(type, action, x, y, buttonWidth);
            if (minecraft != null && minecraft.player != null) {
                minecraft.player.displayClientMessage(
                        Component.translatable("message.create_fire_control.press_binding_key"),
                        true
                );
            }
            rebuildPage();
        }, x, y, buttonWidth, 20);
        result.setTooltip(Tooltip.create(
                remoteBindingTooltip(type, action, selected.get(action), bound)));
        result.active = bound;
        return addRenderableWidget(result);
    }

    private void buildRemoteKeyDropdown(RemoteKeyDropdown dropdown) {
        RemoteKeyDropdownLayout layout = remoteKeyDropdownLayout(dropdown);
        String[] keys = DriveByWireCompat.keys(dropdown.type());
        addRenderableWidget(styledButton(Component.empty(), button -> { },
                layout.x() - 4, layout.y() - 4, layout.width() + 8, layout.height() + 8));
        BrassButton unbind = styledButton(
                Component.translatable("screen.create_fire_control.remote_unbind"), button ->
                        selectRemoteKey(dropdown.type(), dropdown.action(), ""),
                layout.x(), layout.y(), layout.cellWidth(), layout.cellHeight());
        unbind.setTone(ButtonTone.RED);
        unbind.setTooltip(Tooltip.create(remoteBindingTooltip(
                dropdown.type(), dropdown.action(), "", true)));
        addRenderableWidget(unbind);
        for (int index = 0; index < keys.length; index++) {
            int entry = index + 1;
            int column = entry % layout.columns();
            int row = entry / layout.columns();
            String key = keys[index];
            BrassButton option = styledButton(remoteKeyName(dropdown.type(), key), button ->
                            selectRemoteKey(dropdown.type(), dropdown.action(), key),
                    layout.x() + column * (layout.cellWidth() + layout.gap()),
                    layout.y() + row * (layout.cellHeight() + layout.gap()),
                    layout.cellWidth(), layout.cellHeight());
            option.setTooltip(Tooltip.create(remoteBindingTooltip(
                    dropdown.type(), dropdown.action(), key, true)));
            addRenderableWidget(option);
        }
    }

    private RemoteKeyDropdownLayout remoteKeyDropdownLayout(RemoteKeyDropdown dropdown) {
        PanelBounds panel = panelBounds();
        int columns = dropdown.type() == RemoteControllerCompat.Type.ADVANCED ? 4 : 2;
        int availableWidth = Math.max(120, panel.right - panel.left - 20);
        while (columns > 2 && columns * 54 + (columns - 1) * 2 > availableWidth) columns--;
        int cellHeight = 16;
        int gap = 2;
        int cellWidth = Math.max(54, (availableWidth - (columns - 1) * gap) / columns);
        int entries = DriveByWireCompat.keys(dropdown.type()).length + 1;
        int rows = (entries + columns - 1) / columns;
        int menuWidth = columns * cellWidth + (columns - 1) * gap;
        int menuHeight = rows * cellHeight + (rows - 1) * gap;
        int x = Math.max(panel.left + 8, Math.min(
                dropdown.x() + dropdown.buttonWidth() - menuWidth, panel.right - 8 - menuWidth));
        int y = dropdown.y() + 20;
        if (y + menuHeight > panel.bottom - 34) y = dropdown.y() - menuHeight;
        y = Math.max(panel.top + 30, y);
        return new RemoteKeyDropdownLayout(x, y, menuWidth, menuHeight, columns, cellWidth, cellHeight, gap);
    }
    private void selectRemoteKey(RemoteControllerCompat.Type type, int action, String key) {
        if(type==RemoteControllerCompat.Type.WIRELESS)PacketDistributor.sendToServer(new com.hooya.stabilizedturret.network.SetFireControlInputPayload(
                initial.pos(),action,type.ordinal(),key,net.minecraft.world.item.ItemStack.EMPTY,net.minecraft.world.item.ItemStack.EMPTY));
        else PacketDistributor.sendToServer(new SetRemoteKeyBindingPayload(
                initial.pos(), action, type == RemoteControllerCompat.Type.ADVANCED, key));
        openRemoteKeyDropdown = null;
        capturingRemoteKey = null;
        rebuildPage();
    }

    /** Applies only edits the server accepted, avoiding an optimistic false binding in the UI. */
    public void acceptRemoteKeyBindingResult(RemoteKeyBindingResultPayload payload) {
        if (!initial.pos().equals(payload.pos())
                || payload.action() < 0 || payload.action() >= normalRemoteKeys.size()) {
            return;
        }
        if (payload.result() == RemoteKeyBindingResultPayload.Result.SAVED) {
            List<String> selected = payload.advanced() ? advancedRemoteKeys : normalRemoteKeys;
            selected.set(payload.action(), payload.key());
        }
        if (minecraft != null && minecraft.player != null) {
            minecraft.player.displayClientMessage(
                    Component.translatable(payload.result().translationKey()), true);
        }
        if (page == Page.INPUTS || page == Page.WEAPON_INPUTS) rebuildPage();
    }

    private boolean hasBoundHub(RemoteControllerCompat.Type type) {
        return inputState != null && inputState.getBoolean(type.name()+"Available");
    }

    private Component remoteKeyText(
            RemoteControllerCompat.Type type,
            int action,
            String key,
            boolean bound
    ) {
        if (!bound) return Component.translatable("screen.create_fire_control.hub_not_bound");
        if (capturingRemoteKey != null
                && capturingRemoteKey.type() == type
                && capturingRemoteKey.action() == action) {
            return Component.translatable("screen.create_fire_control.press_key");
        }
        if (key == null || key.isEmpty()) return Component.literal("-");
        return Component.empty()
                .append(ControllerKeyMappingCompat.displayKey(type, key))
                .append(" -> ")
                .append(remoteKeyName(type, key));
    }

    private Component remoteKeyName(RemoteControllerCompat.Type type, String key) {
        if (key == null || key.isEmpty()) return Component.literal("-");
        String translationKey = DriveByWireCompat.displayTranslationKey(type, key);
        return translationKey.isEmpty() ? Component.literal("-") : Component.translatable(translationKey);
    }

    private Component remoteBindingTooltip(
            RemoteControllerCompat.Type type,
            int action,
            String key,
            boolean terminalBound
    ) {
        Component actionDescription = FireControlWeaponInputs.isSelection(action)
                ? Component.translatable("screen.create_fire_control.weapon_switch_description", inputActionLabel(action))
                : Component.translatable("screen.create_fire_control.input.action." + action + ".description");
        Component keyDescription;
        if (!terminalBound) {
            keyDescription = Component.translatable("screen.create_fire_control.remote_key.terminal_missing");
        } else if (key == null || key.isEmpty()) {
            keyDescription = Component.translatable("screen.create_fire_control.remote_key.unbound");
        } else {
            keyDescription = Component.translatable(DriveByWireCompat.descriptionKey(type, key));
        }
        return Component.translatable(
                "screen.create_fire_control.input.binding_tooltip",
                actionDescription,
                keyDescription.copy().append("\n").append(Component.translatable(
                        "screen.create_fire_control.input.remote_requirements"))
        );
    }

    private BrassButton styledButton(Component message, Button.OnPress onPress, int x, int y, int width, int height) {
        return new BrassButton(x, y, width, height, message, onPress);
    }

    public static Button createBrassButton(Component message, Button.OnPress onPress,
                                    int x, int y, int width, int height, boolean accept) {
        return new BrassButton(x, y, width, height, message, onPress)
                .setTone(accept ? ButtonTone.GREEN : ButtonTone.NORMAL);
    }

    private Component frequencyText(boolean first, Component name) {
        return Component.translatable("screen.create_fire_control.input.frequency", first ? "A" : "B", name);
    }

    private Component toggleText(String key, boolean enabled) {
        return Component.translatable(
                "screen.create_fire_control." + key,
                Component.translatable(enabled ? "options.on" : "options.off")
        );
    }

    private Component scopePresetText() {
        return Component.translatable(
                "screen.create_fire_control.scope_preset",
                Component.translatable(scopePreset.translationKey())
        );
    }

    private Component stabilizerImplementationText() {
        return toggleText("stabilizer_implementation", stabilizerReactionEnabled);
    }

    private Tooltip stabilizerImplementationTooltip() {
        return Tooltip.create(Component.translatable(stabilizerReactionEnabled
                ? "screen.create_fire_control.stabilizer_implementation.reaction.tooltip"
                : "screen.create_fire_control.stabilizer_implementation.no_reaction.tooltip"));
    }

    private void saveAndClose() {
        elevation = Math.max(0.0, Math.min(89.0, elevation));
        PacketDistributor.sendToServer(new ControllerSettingsPayload(
                initial.pos(),
                initial.controllerSubLevelId(),
                depression,
                elevation,
                yawSpeed,
                pitchSpeed,
                yawReversed,
                pitchReversed,
                yawBearingHeadHidden,
                pitchBearingHeadHidden,
                thirdPersonHeight,
                roundAimOffset(viewAimOffsetX),
                roundAimOffset(viewAimOffsetY),
                roundOffset(yawOffsetX),
                roundOffset(yawOffsetY),
                roundOffset(yawOffsetZ),
                roundOffset(pitchOffsetX),
                roundOffset(pitchOffsetY),
                roundOffset(pitchOffsetZ),
                roundOffset(scopeOffsetX),
                roundOffset(scopeOffsetY),
                roundOffset(scopeOffsetZ),
                scopePreset.ordinal(),
                stabilizerEnabled,
                stabilizerReactionEnabled,
                cannonRecoilMagnitude,
                mobileShotCompensationEnabled,
                headAimEnabled,
                remoteHeadAimEnabled,
                radarRange,
                radarDepression,
                radarElevation,
                aaProximityFuseRange,
                radarHudScale,
                radarHudX,
                radarHudY,
                aircraftRadarRange,
                aircraftRadarHorizontalHalfAngle,
                aircraftRadarVerticalAngle,
                aircraftProximityFuseRange,
                aircraftFuseSafetySeconds,
                aircraftRadarMinimumTargetSpeed,
                aircraftRadarHudScale,
                aircraftRadarHudX,
                aircraftRadarHudY,
                rwrRange,
                rwrScale,
                aircraftRocketSalvoMode,
                virtualScopeEnabled
        ));
        PacketDistributor.sendToServer(new SetPodCannonLimitsPayload(
                initial.pos(),
                Math.max(0.0, Math.min(90.0, podCannonDepression)),
                Math.max(0.0, Math.min(90.0, podCannonElevation))
        ));
        stageBearingSettings();
        for (CustomPacketPayload payload : pendingBearingSettings) PacketDistributor.sendToServer(payload);
        onClose();
    }

    private void stageBearingSettings() {
        boolean pitch = "PITCH".equals(selectedBearingRole);
        boolean yaw = "YAW".equals(selectedBearingRole);
        if ((!pitch && !yaw) || page != Page.PITCH && page != Page.YAW && page != Page.BEARING_GLOBAL) return;
        double d = pitch ? depression : 180.0;
        double e = pitch ? elevation : 180.0;
        double speed = pitch ? pitchSpeed : yawSpeed;
        boolean reversed = pitch ? pitchReversed : yawReversed;
        boolean hidden = pitch ? pitchBearingHeadHidden : yawBearingHeadHidden;
        double x = roundOffset(pitch ? pitchOffsetX : yawOffsetX);
        double y = roundOffset(pitch ? pitchOffsetY : yawOffsetY);
        double z = roundOffset(pitch ? pitchOffsetZ : yawOffsetZ);
        if (page == Page.BEARING_GLOBAL) {
            if (globalBearingMask != 0) pendingBearingSettings.add(new ControllerGlobalBearingSettingsPayload(
                    initial.pos(), initial.controllerSubLevelId(), selectedBearingRole, globalBearingMask,
                    d, e, speed, reversed, hidden, x, y, z, yawLimits));
            globalBearingMask = 0;
        } else if (selectedBearingPos != null) {
            pendingBearingSettings.add(new ControllerBearingSettingsPayload(initial.pos(), initial.controllerSubLevelId(),
                    selectedBearingRole, selectedBearingPos, d, e, speed, reversed, hidden, x, y, z, yawLimits));
            for (int index = 0; index < devices.size(); index++) {
                OpenControllerPayload.BoundDevice device = devices.get(index);
                if (!selectedBearingRole.equals(device.slot()) || !selectedBearingPos.equals(device.targetPos())) continue;
                devices.set(index, new OpenControllerPayload.BoundDevice(device.slot(), device.index(), device.translationKey(),
                        device.targetPos(), device.subLevel(), device.podMode(), device.podOrder(), device.podReversed(),
                        device.bearingGroup(), d, e, speed, reversed, hidden, x, y, z, pitch ? device.yawLimits() : yawLimits));
            }
        }
    }

    private static double roundOffset(double value) {
        return Math.max(-20.0, Math.min(20.0, Math.round(value * 1000.0) / 1000.0));
    }

    private static String formatOffset(double value) {
        return String.format(Locale.ROOT, "%.3f", roundOffset(value));
    }

    private static double roundAimOffset(double value) {
        return Math.max(-45.0, Math.min(45.0, Math.round(value * 1000.0) / 1000.0));
    }

    private static String formatAimOffset(double value) {
        return String.format(Locale.ROOT, "%.3f", roundAimOffset(value));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0x68000000);
        renderPanel(graphics);
        PanelBounds viewport = viewportPanelBounds();
        graphics.enableScissor(
                viewport.left + 7,
                viewport.top + 29,
                viewport.right - (scrollSlider == null ? 7 : 17),
                viewport.bottom - 7
        );
        if (page == Page.AIM_POINT) {
            renderAimPointPreview(graphics);
        } else if (page == Page.RADAR) {
            renderRadarPreview(graphics);
        } else if (page == Page.AIRCRAFT) {
            PanelBounds panel = panelBounds();
            int areaLeft = panel.left + 14;
            int areaTop = panel.top + 42;
            int areaWidth = Math.max(80, contentRight(panel) - areaLeft);
            int areaHeight = 148;
            graphics.fill(areaLeft, areaTop, areaLeft + areaWidth, areaTop + areaHeight, 0xFF4A504D);
            graphics.fill(areaLeft + 1, areaTop + 1, areaLeft + areaWidth - 1, areaTop + areaHeight - 1, 0xFF76817B);
            int previewWidth = Math.max(72, Math.min(areaWidth - 8,
                    (int)Math.round(116.0 * aircraftRadarHudScale / 100.0)));
            int previewHeight = Math.max(68, (int)Math.round(previewWidth * 450.0 / 474.0));
            previewHeight = Math.min(areaHeight - 8, previewHeight);
            int px = areaLeft + 4 + (int)Math.round(Math.max(0, areaWidth - previewWidth - 8) * aircraftRadarHudX / 100.0);
            int py = areaTop + 4 + (int)Math.round(Math.max(0, areaHeight - previewHeight - 8) * aircraftRadarHudY / 100.0);
            RadarHudRenderer.renderAircraftPreview(graphics, px, py, previewWidth, previewHeight,
                    aircraftRadarRange, aircraftRadarHorizontalHalfAngle, aircraftRadarVerticalAngle);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
        renderLabels(graphics);
        graphics.disableScissor();
        if (scrollSlider != null) {
            scrollSlider.render(graphics, mouseX, mouseY, partialTick);
        }
        renderHeaderOverlay(graphics);
        if (structurePreviewButton != null) {
            structurePreviewButton.visible = StructurePreviewScreen.available(initial.pos(), initial.controllerSubLevelId());
            structurePreviewButton.render(graphics, mouseX, mouseY, partialTick);
        }
        graphics.flush();
    }

    private void acceptStructurePreview(com.hooya.stabilizedturret.network.StructurePreviewStatePayload.Device updated) {
        var s = updated.settings();
        if (s == null) return;
        pendingBearingSettings.removeIf(p -> p instanceof ControllerBearingSettingsPayload old
                && old.role().equals(updated.role()) && old.bearingPos().equals(updated.pos()));
        if (pendingBearingSettings.stream().anyMatch(p -> p instanceof ControllerGlobalBearingSettingsPayload old
                && old.role().equals(updated.role()))) {
            pendingBearingSettings.add(new ControllerBearingSettingsPayload(initial.pos(), initial.controllerSubLevelId(),
                    updated.role(), updated.pos(), s.depression(), s.elevation(), s.speed(), s.reversed(), s.headHidden(),
                    s.offset().x, s.offset().y, s.offset().z, s.limits()));
        }
        for (int i = 0; i < devices.size(); i++) {
            var d = devices.get(i);
            if (!d.slot().equals(updated.role()) || !d.targetPos().equals(updated.pos())) continue;
            devices.set(i, new OpenControllerPayload.BoundDevice(d.slot(), d.index(), d.translationKey(), d.targetPos(),
                    d.subLevel(), d.podMode(), d.podOrder(), d.podReversed(), d.bearingGroup(), s.depression(),
                    s.elevation(), s.speed(), s.reversed(), s.headHidden(), s.offset().x, s.offset().y, s.offset().z, s.limits()));
        }
    }

    /**
     * Screen.render() unconditionally invokes this method before rendering
     * widgets.  Leaving the inherited implementation active applies the
     * vanilla post-process after our panel has already been drawn, blurring
     * the panel and all labels along with the world.
     */
    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // The dim layer is rendered explicitly at the start of render().
    }

    private void renderPanel(GuiGraphics graphics) {
        PanelBounds panel = viewportPanelBounds();
        renderBrassFrame(graphics,panel.left,panel.top,panel.right,panel.bottom);
    }

    public static void renderBrassFrame(GuiGraphics graphics,int left,int top,int right,int bottom) {
        PanelBounds panel=new PanelBounds(left,top,right,bottom);
        graphics.fill(panel.left + 4, panel.top + 5, panel.right + 5, panel.bottom + 6, 0x90000000);
        graphics.fill(panel.left, panel.top, panel.right, panel.bottom, PANEL_OUTLINE);
        graphics.fill(panel.left + 2, panel.top + 2, panel.right - 2, panel.bottom - 2, PANEL_BRASS);
        graphics.fill(panel.left + 5, panel.top + 5, panel.right - 5, panel.bottom - 5, PANEL_INNER_BORDER);
        graphics.fill(panel.left + 7, panel.top + 28, panel.right - 7, panel.bottom - 7, PANEL_BACKGROUND);
        graphics.fill(panel.left + 7, panel.top + 7, panel.right - 7, panel.top + 27, PANEL_HEADER);
        graphics.fill(panel.left + 8, panel.top + 27, panel.right - 8, panel.top + 29, 0xFF947436);
        drawBolt(graphics, panel.left + 4, panel.top + 4);
        drawBolt(graphics, panel.right - 6, panel.top + 4);
        drawBolt(graphics, panel.left + 4, panel.bottom - 6);
        drawBolt(graphics, panel.right - 6, panel.bottom - 6);
    }

    private PanelBounds panelBounds() {
        PanelBounds viewport = viewportPanelBounds();
        int desiredHeight = desiredPanelSize().height;
        int top = viewport.top - (int) Math.round(scrollOffset);
        return new PanelBounds(viewport.left, top, viewport.right, top + desiredHeight);
    }

    private PanelBounds viewportPanelBounds() {
        PanelSize desired = desiredPanelSize();
        return centeredPanel(desired.width, desired.height);
    }

    private PanelSize desiredPanelSize() {
        return switch (page) {
            case HOME -> new PanelSize(278, 214);
            case FIRE_CONTROL -> new PanelSize(278, stabilizerReactionEnabled ? 352 : 286);
            case SCOPE_PRESETS -> new PanelSize(278, 226);
            case BEARING_GROUPS, BEARING_GROUP, BEARING_GLOBAL_SELECT, PITCH_SELECT, YAW_SELECT -> new PanelSize(328, 300);
            case BEARING_GLOBAL -> new PanelSize(328, "PITCH".equals(selectedBearingRole) ? 360 : 376);
            case RADAR -> new PanelSize(328, 430);
            case AIRCRAFT -> new PanelSize(328, 550);
            case INPUTS -> new PanelSize(370, 62 + INPUT_GROUPS.length*20 + 44
                    + java.util.Arrays.stream(INPUT_GROUPS).mapToInt(group -> group.actions().length).sum()*inputRowHeight());
            case WEAPON_INPUTS -> new PanelSize(370, 90 + Math.max(1,weaponInputActions().length)*inputRowHeight());
            case PITCH -> new PanelSize(328, 278);
            case YAW -> new PanelSize(328, 337);
            case VIEW -> new PanelSize(328, 236);
            case AIM_POINT -> new PanelSize(328, 260);
            case POD_CANNON -> new PanelSize(328, 200);
            case DEVICES -> new PanelSize(328, 270);
        };
    }

    private PanelBounds centeredPanel(int desiredWidth, int desiredHeight) {
        int horizontalMargin = Math.max(12, Math.min(28, width / 24));
        int verticalMargin = Math.max(10, Math.min(24, height / 28));
        int panelWidth = Math.min(desiredWidth,
                Math.max(8, width - horizontalMargin * 2));
        int panelHeight = Math.min(desiredHeight,
                Math.max(8, height - verticalMargin * 2));
        int left = (width - panelWidth) / 2;
        int top = (height - panelHeight) / 2;
        return new PanelBounds(left, top, left + panelWidth, top + panelHeight);
    }

    private int formLeft(PanelBounds panel) {
        return panel.left + 14;
    }

    private int formWidth(PanelBounds panel) {
        return Math.max(120, Math.min(300, contentRight(panel) - formLeft(panel)));
    }

    private int contentRight(PanelBounds panel) {
        return panel.right - 14 - (maximumScrollOffset() > 0.0 ? 10 : 0);
    }

    private void installScrollSlider() {
        double maximum = maximumScrollOffset();
        if (maximum <= 0.0) {
            scrollOffset = 0.0;
            return;
        }
        scrollOffset = Math.max(0.0, Math.min(maximum, scrollOffset));
        PanelBounds viewport = viewportPanelBounds();
        scrollSlider = new PageScrollSlider(
                viewport.right - 14,
                viewport.top + 33,
                8,
                Math.max(32, viewport.bottom - viewport.top - 42),
                scrollOffset / maximum
        );
        addRenderableWidget(scrollSlider);
    }

    private double maximumScrollOffset() {
        PanelSize desired = desiredPanelSize();
        PanelBounds viewport = viewportPanelBounds();
        int viewportHeight = viewport.bottom - viewport.top;
        return desired.height > viewportHeight
                ? desired.height - viewportHeight + 22.0
                : 0.0;
    }

    private void setScrollOffset(double requested, boolean updateSlider) {
        double maximum = maximumScrollOffset();
        double next = Math.max(0.0, Math.min(maximum, requested));
        int delta = (int) Math.round(scrollOffset) - (int) Math.round(next);
        scrollOffset = next;
        if (delta != 0) {
            for (AbstractWidget widget : contentWidgets) {
                widget.setY(widget.getY() + delta);
            }
        }
        if (updateSlider && scrollSlider != null) {
            scrollSlider.setScrollFraction(maximum <= 0.0 ? 0.0 : next / maximum);
        }
    }

    private void renderHeaderOverlay(GuiGraphics graphics) {
        PanelBounds panel = viewportPanelBounds();
        graphics.fill(panel.left + 7, panel.top + 7, panel.right - 7, panel.top + 27, PANEL_HEADER);
        graphics.fill(panel.left + 8, panel.top + 27, panel.right - 8, panel.top + 29, 0xFF947436);
        drawCenteredNoShadow(graphics, pageTitle(), width / 2, panel.top + 12, 0xFFFFFF);
    }

    private Component pageTitle() {
        return switch (page) {
            case HOME -> title;
            case FIRE_CONTROL -> Component.translatable("screen.create_fire_control.fire_control_settings");
            case BEARING_GROUPS -> Component.translatable("screen.create_fire_control.bearing_groups");
            case BEARING_GROUP -> Component.translatable("screen.create_fire_control.bearing_group_details");
            case BEARING_GLOBAL_SELECT -> Component.translatable("screen.create_fire_control.global_bearing_settings");
            case BEARING_GLOBAL -> Component.translatable(
                    "PITCH".equals(selectedBearingRole)
                            ? "screen.create_fire_control.pitch_settings"
                            : "screen.create_fire_control.yaw_settings");
            case PITCH_SELECT -> Component.translatable("screen.create_fire_control.pitch_select");
            case YAW_SELECT -> Component.translatable("screen.create_fire_control.yaw_select");
            case PITCH -> Component.translatable("screen.create_fire_control.pitch_settings");
            case YAW -> Component.translatable("screen.create_fire_control.yaw_settings");
            case VIEW -> Component.translatable("screen.create_fire_control.view_settings");
            case AIM_POINT -> Component.translatable("screen.create_fire_control.aim_point_settings");
            case SCOPE_PRESETS -> Component.translatable("screen.create_fire_control.scope_preset_title");
            case POD_CANNON -> Component.translatable("screen.create_fire_control.pod_cannon_settings");
            case RADAR -> Component.translatable("screen.create_fire_control.radar_settings");
            case AIRCRAFT -> Component.translatable("screen.create_fire_control.aircraft_systems_title");
            case INPUTS -> Component.translatable("screen.create_fire_control.input_bindings");
            case WEAPON_INPUTS -> Component.translatable("screen.create_fire_control.weapon_switch_options");
            case DEVICES -> Component.translatable("screen.create_fire_control.devices");
        };
    }

    private static void drawBolt(GuiGraphics graphics, int x, int y) {
        graphics.fill(x, y, x + 2, y + 2, 0xFFDEC36C);
        graphics.fill(x + 1, y + 1, x + 2, y + 2, 0xFF5A4828);
    }

    private void renderLabels(GuiGraphics graphics) {
        int color = LABEL_COLOR;
        switch (page) {
            case HOME -> drawCenteredNoShadow(graphics, title, width / 2, panelBounds().top + 12, 0xFFFFFF);
            case FIRE_CONTROL -> {
                PanelBounds panel = panelBounds();
                int left = width / 2 - 125;
                drawCenteredNoShadow(graphics,
                        Component.translatable("screen.create_fire_control.fire_control_settings"),
                        width / 2, panel.top + 12, 0xFFFFFF);

            }
            case BEARING_GROUPS, BEARING_GROUP, BEARING_GLOBAL_SELECT, PITCH_SELECT, YAW_SELECT,
                 SCOPE_PRESETS ->
                    drawCenteredNoShadow(graphics, pageTitle(), width / 2,
                            panelBounds().top + 12, 0xFFFFFF);
            case BEARING_GLOBAL -> {
                PanelBounds panel = panelBounds();
                int left = formLeft(panel);
                int top = panel.top;
                boolean pitch = "PITCH".equals(selectedBearingRole);
                drawCenteredNoShadow(graphics, pageTitle(), width / 2, top + 12, 0xFFFFFF);
                if (pitch) {
                    graphics.drawString(font, Component.translatable("screen.create_fire_control.depression"),
                            left, top + 45, color, false);
                    graphics.drawString(font, Component.translatable("screen.create_fire_control.elevation"),
                            left, top + 70, color, false);
                    graphics.drawString(font, Component.translatable("screen.create_fire_control.pitch_speed"),
                            left, top + 95, color, false);
                    graphics.drawString(font, Component.translatable("screen.create_fire_control.pitch_offset"),
                            left, top + 150, color, false);
                } else {
                    graphics.drawString(font, Component.translatable("screen.create_fire_control.yaw_speed"),
                            left, top + 45, color, false);
                    graphics.drawString(font, Component.translatable("screen.create_fire_control.yaw_limit_center"),
                            left, top + 132, color, false);
                    graphics.drawString(font, Component.translatable("screen.create_fire_control.yaw_offset"),
                            left, top + 203, color, false);
                }
            }
            case PITCH -> {
                PanelBounds panel = panelBounds();
                int left = formLeft(panel);
                int top = panel.top;
                drawCenteredNoShadow(graphics, Component.translatable("screen.create_fire_control.pitch_settings"), width / 2, top + 12, 0xFFFFFF);
                drawSelectedBearingLabel(graphics, panel, "PITCH");
                graphics.drawString(font, Component.translatable("screen.create_fire_control.depression"), left, top + 45, color, false);
                graphics.drawString(font, Component.translatable("screen.create_fire_control.elevation"), left, top + 70, color, false);
                graphics.drawString(font, Component.translatable("screen.create_fire_control.pitch_speed"), left, top + 95, color, false);
                graphics.drawString(font, Component.translatable("screen.create_fire_control.pitch_offset"), left, top + 128, color, false);
            }
            case YAW -> {
                PanelBounds panel = panelBounds();
                int left = formLeft(panel);
                int top = panel.top;
                drawCenteredNoShadow(graphics, Component.translatable("screen.create_fire_control.yaw_settings"), width / 2, top + 12, 0xFFFFFF);
                drawSelectedBearingLabel(graphics, panel, "YAW");
                graphics.drawString(font, Component.translatable("screen.create_fire_control.yaw_speed"), left, top + 49, color, false);
                graphics.drawString(font, Component.translatable("screen.create_fire_control.yaw_limit_center"), left, top + 133, color, false);
                graphics.drawString(font, Component.translatable("screen.create_fire_control.yaw_offset"), left, top + 202, color, false);
            }
            case VIEW -> {
                PanelBounds panel = panelBounds();
                int left = formLeft(panel);
                int top = panel.top;
                drawCenteredNoShadow(graphics, Component.translatable("screen.create_fire_control.view_settings"), width / 2, top + 12, 0xFFFFFF);
                graphics.drawString(font, Component.translatable("screen.create_fire_control.third_person_height"), left, top + 40, color, false);
                graphics.drawString(font, Component.translatable("screen.create_fire_control.scope_offset"), left, top + 59,
                        virtualScopeEnabled ? 0x808080 : color, false);

            }
            case POD_CANNON -> {
                PanelBounds panel = panelBounds();
                int left = formLeft(panel);
                int top = panel.top;
                drawCenteredNoShadow(graphics,
                        Component.translatable("screen.create_fire_control.pod_cannon_settings"),
                        width / 2, top + 12, 0xFFFFFF);
                graphics.drawString(font,
                        Component.translatable("screen.create_fire_control.pod_cannon_depression"),
                        left, top + 61, color, false);
                graphics.drawString(font,
                        Component.translatable("screen.create_fire_control.pod_cannon_elevation"),
                        left, top + 92, color, false);
            }
            case RADAR -> {
                PanelBounds panel = panelBounds();
                int labelX = panel.left + 14;
                int fieldsTop = panel.top + RADAR_FIELDS_TOP
                        + EDIT_BOX_LABEL_BASELINE_OFFSET;
                drawCenteredNoShadow(graphics,
                        Component.translatable("screen.create_fire_control.radar_settings"),
                        width / 2, panel.top + 12, 0xFFFFFF);
                graphics.drawString(font, Component.translatable("screen.create_fire_control.radar_preview"),
                        panel.left + 14, panel.top + 35, LABEL_COLOR, false);
                graphics.drawString(font, Component.translatable("screen.create_fire_control.radar_range"),
                        labelX, fieldsTop, LABEL_COLOR, false);
                graphics.drawString(font, Component.translatable("screen.create_fire_control.radar_depression"),
                        labelX, fieldsTop + 26, LABEL_COLOR, false);
                graphics.drawString(font, Component.translatable("screen.create_fire_control.radar_elevation"),
                        labelX, fieldsTop + 52, LABEL_COLOR, false);
                graphics.drawString(font, Component.translatable("screen.create_fire_control.radar_hud_scale"),
                        labelX, fieldsTop + 78, LABEL_COLOR, false);
                graphics.drawString(font, Component.translatable("screen.create_fire_control.radar_hud_x"),
                        labelX, fieldsTop + 104, LABEL_COLOR, false);
                graphics.drawString(font, Component.translatable("screen.create_fire_control.radar_hud_y"),
                        labelX, fieldsTop + 130, LABEL_COLOR, false);
                graphics.drawString(font, Component.translatable("screen.create_fire_control.aa_proximity_fuse_range"),
                        labelX, fieldsTop + 156, LABEL_COLOR, false);

            }
            case AIRCRAFT -> {
                PanelBounds panel = panelBounds();
                int labelX = panel.left + 14;
                int fieldsTop = panel.top + AIRCRAFT_FIELDS_TOP
                        + EDIT_BOX_LABEL_BASELINE_OFFSET;
                drawCenteredNoShadow(graphics, Component.translatable(
                        "screen.create_fire_control.aircraft_systems_title"),
                        width / 2, panel.top + 12, 0xFFFFFF);
                graphics.drawString(font, Component.translatable("screen.create_fire_control.aircraft_preview"), panel.left + 14, panel.top + 33, LABEL_COLOR, false);
                graphics.drawString(font, Component.translatable("screen.create_fire_control.aircraft_radar_range"), labelX, fieldsTop, LABEL_COLOR, false);
                graphics.drawString(font, Component.translatable("screen.create_fire_control.aircraft_radar_horizontal"), labelX, fieldsTop + 24, LABEL_COLOR, false);
                graphics.drawString(font, Component.translatable("screen.create_fire_control.aircraft_radar_vertical"), labelX, fieldsTop + 48, LABEL_COLOR, false);
                graphics.drawString(font, Component.translatable("screen.create_fire_control.aircraft_fuse_range"), labelX, fieldsTop + 72, LABEL_COLOR, false);
                graphics.drawString(font, Component.translatable("screen.create_fire_control.aircraft_fuse_safety"), labelX, fieldsTop + 96, LABEL_COLOR, false);
                graphics.drawString(font, Component.translatable("screen.create_fire_control.aircraft_min_target_speed"), labelX, fieldsTop + 120, LABEL_COLOR, false);
                graphics.drawString(font, Component.translatable("screen.create_fire_control.aircraft_hud_scale"), labelX, fieldsTop + 144, LABEL_COLOR, false);
                graphics.drawString(font, Component.translatable("screen.create_fire_control.aircraft_hud_x"), labelX, fieldsTop + 168, LABEL_COLOR, false);
                graphics.drawString(font, Component.translatable("screen.create_fire_control.aircraft_hud_y"), labelX, fieldsTop + 192, LABEL_COLOR, false);
                graphics.drawString(font, Component.translatable("screen.create_fire_control.rwr_range"), labelX, fieldsTop + 216, LABEL_COLOR, false);
                graphics.drawString(font, Component.translatable("screen.create_fire_control.rwr_scale"), labelX, fieldsTop + 240, LABEL_COLOR, false);
            }
            case INPUTS -> renderInputLabels(graphics);
            case WEAPON_INPUTS -> renderWeaponInputLabels(graphics);
            case AIM_POINT -> {
                PanelBounds panel = panelBounds();
                int left = formLeft(panel);
                int top = panel.top;
                drawCenteredNoShadow(graphics, Component.translatable("screen.create_fire_control.aim_point_settings"), width / 2, top + 12, 0xFFFFFF);
                graphics.drawString(font, Component.translatable("screen.create_fire_control.aim_point_preview"), left, top + 37, color, false);
                graphics.drawString(font, Component.translatable("screen.create_fire_control.aim_point_help"), left, top + 153, 0xFF625A4C, false);
            }
            case DEVICES -> renderDeviceLabels(graphics);
        }
    }

    private void renderDeviceLabels(GuiGraphics graphics) {
        PanelBounds panel = panelBounds();
        drawCenteredNoShadow(graphics,
                Component.translatable("screen.create_fire_control.devices"),
                width / 2, panel.top + 12, 0xFFFFFF);
        if (pendingUnbind != null) {
            drawCenteredNoShadow(graphics,
                    Component.translatable("screen.create_fire_control.unbind_confirm_title"),
                    width / 2, panel.top + 60, LABEL_COLOR);
            drawCenteredNoShadow(graphics, deviceDisplayName(pendingUnbind),
                    width / 2, panel.top + 86, LABEL_COLOR);
            drawCenteredNoShadow(graphics,
                    Component.translatable("screen.create_fire_control.unbind_bearing_warning"),
                    width / 2, panel.top + 112, 0xFF7A342C);
            return;
        }
        if (pendingBearingRole != null) {
            drawCenteredNoShadow(graphics,
                    Component.translatable("screen.create_fire_control.choose_bearing_role"),
                    width / 2, panel.top + 46, LABEL_COLOR);
            drawCenteredNoShadow(graphics, deviceDisplayName(pendingBearingRole),
                    width / 2, panel.top + 60, LABEL_COLOR);
            return;
        }
        if (pendingCannonMountMode != null) {
            drawCenteredNoShadow(graphics,
                    Component.translatable(
                            "screen.create_fire_control.choose_cannon_mount_mode"),
                    width / 2, panel.top + 46, LABEL_COLOR);
            drawCenteredNoShadow(graphics, deviceDisplayName(pendingCannonMountMode),
                    width / 2, panel.top + 60, LABEL_COLOR);
            return;
        }
        if (devices.isEmpty()) {
            drawCenteredNoShadow(graphics,
                    Component.translatable("screen.create_fire_control.devices_empty"),
                    width / 2, panel.top + 105, LABEL_COLOR);
        } else {
            int start = devicePage * DEVICES_PER_PAGE;
            int end = Math.min(devices.size(), start + DEVICES_PER_PAGE);
            for (int index = start; index < end; index++) {
                OpenControllerPayload.BoundDevice device = devices.get(index);
                String position = " [" + device.targetPos().getX() + ", "
                        + device.targetPos().getY() + ", " + device.targetPos().getZ() + "]";
                String physical = device.subLevel()
                        ? " ? " + Component.translatable(
                                "screen.create_fire_control.device_physical").getString()
                        : "";
                String label = deviceDisplayName(device).getString() + position + physical;
                int controlsLeft = (isBearingDevice(device.slot())
                        || isCannonMountDevice(device.slot()))
                        ? panel.right - 128 : panel.right - 68;
                int labelWidth = Math.max(24, controlsLeft - (panel.left + 14) - 8);
                String clipped = font.plainSubstrByWidth(label, labelWidth);
                graphics.drawString(font, clipped, panel.left + 14,
                        panel.top + 45 + (index - start) * 25, LABEL_COLOR, false);
            }
        }
        drawCenteredNoShadow(graphics,
                Component.translatable("screen.create_fire_control.device_page",
                        devicePage + 1, devicePageCount()),
                width / 2, panel.bottom - 49, 0xFF625A4C);
    }

    private Component deviceDisplayName(OpenControllerPayload.BoundDevice device) {
        Component type = Component.translatable(
                "screen.create_fire_control.device_type."
                        + device.slot().toLowerCase(Locale.ROOT));
        var result = Component.empty().append(type);
        if (isCannonMountDevice(device.slot())) {
            String mode = "POD_CONTROL".equals(device.podMode())
                    ? "pod_control" : "default";
            result.append(" (").append(Component.translatable(
                    "screen.create_fire_control.cannon_mount_mode." + mode)).append(")");
        }
        return result.append(" | ").append(Component.translatable(device.translationKey()));
    }

    private Component inputActionLabel(int action) {
        return FireControlWeaponInputs.label(action);
    }

    private void drawCenteredNoShadow(GuiGraphics graphics, Component text, int centerX, int y, int color) {
        graphics.drawString(font, text, centerX - font.width(text) / 2, y, color, false);
    }

    private void renderAimPointPreview(GuiGraphics graphics) {
        PanelBounds panel = panelBounds();
        int left = panel.left + 14;
        int top = panel.top + 50;
        int right = contentRight(panel);
        int bottom = top + 96;
        graphics.fill(left, top, right, bottom, 0xFF393A36);
        graphics.fill(left + 2, top + 2, right - 2, bottom - 2, 0xFF71806A);
        for (int x = left + 22; x < right; x += 22) {
            graphics.fill(x, top + 2, x + 1, bottom - 2, 0x286F796B);
        }
        for (int y = top + 18; y < bottom; y += 18) {
            graphics.fill(left + 2, y, right - 2, y + 1, 0x286F796B);
        }

        int centerX = (left + right) / 2;
        int centerY = (top + bottom) / 2;
        int desiredX = centerX + (int) Math.round((right - left) * roundAimOffset(viewAimOffsetX) / 100.0);
        int desiredY = centerY + (int) Math.round((bottom - top) * roundAimOffset(viewAimOffsetY) / 100.0);
        ThirdPersonAimHudRenderer.drawViewReticle(graphics, desiredX, desiredY);
        ThirdPersonAimHudRenderer.drawCannonReticle(graphics, centerX, centerY);
    }

    private void renderRadarPreview(GuiGraphics graphics) {
        PanelBounds panel = panelBounds();
        int left = panel.left + 14;
        int top = panel.top + 48;
        int previewWidth = Math.max(80, contentRight(panel) - left);
        int previewHeight = 148;
        graphics.fill(left, top, left + previewWidth, top + previewHeight, 0xFF393A36);
        graphics.fill(left + 2, top + 2, left + previewWidth - 2, top + previewHeight - 2, 0xFF6B746A);
        int size = Math.max(32, Math.min(Math.min(previewWidth, previewHeight) - 8,
                (int) Math.round(84.0 * radarHudScale / 100.0)));
        int availableX = previewWidth - size - 8;
        int availableY = previewHeight - size - 8;
        int x = left + 4 + (int) Math.round(availableX * radarHudX / 100.0);
        int y = top + 4 + (int) Math.round(availableY * radarHudY / 100.0);
        RadarHudRenderer.renderPreview(graphics, x, y, size);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (capturingRemoteKey == null) {
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
        RemoteKeyDropdown capture = capturingRemoteKey;
        if (keyCode == 256) {
            selectRemoteKey(capture.type(), capture.action(), "");
            return true;
        }
        if(capture.type()==RemoteControllerCompat.Type.WIRELESS) {
            if(wirelessCapture.press(com.hooya.stabilizedturret.content.remote.ControllerInput.channel(false,keyCode)))rebuildPage();
            return true;
        }
        if (keyCode == 259 || keyCode == 261) {
            selectRemoteKey(capture.type(), capture.action(), "");
            return true;
        }

        String signal = ControllerKeyMappingCompat.signalForKeyboardKey(
                capture.type(), keyCode, scanCode);
        if (signal == null) {
            if (minecraft != null && minecraft.player != null) {
                minecraft.player.displayClientMessage(
                        Component.translatable(capture.type() == RemoteControllerCompat.Type.ADVANCED
                                ? "message.create_fire_control.advanced_key_not_bound"
                                : "message.create_fire_control.normal_key_not_bound"),
                        true
                );
            }
            return true;
        }
        selectRemoteKey(capture.type(), capture.action(), signal);
        return true;
    }
    @Override public boolean keyReleased(int key,int scan,int modifiers) {
        if(capturingRemoteKey!=null&&capturingRemoteKey.type()==RemoteControllerCompat.Type.WIRELESS) {
            finishWirelessCapture(com.hooya.stabilizedturret.content.remote.ControllerInput.channel(false,key));return true;
        }
        return super.keyReleased(key,scan,modifiers);
    }
    @Override public boolean mouseReleased(double x,double y,int button) {
        if(capturingRemoteKey!=null&&capturingRemoteKey.type()==RemoteControllerCompat.Type.WIRELESS){finishWirelessCapture(com.hooya.stabilizedturret.content.remote.ControllerInput.channel(true,button));return true;}
        return super.mouseReleased(x,y,button);
    }
    private void finishWirelessCapture(String channel) {
        var keys=wirelessCapture.release(channel);
        if(!keys.isEmpty()){var capture=capturingRemoteKey;selectRemoteKey(capture.type(),capture.action(),String.join("+",keys));}
    }
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (capturingRemoteKey != null) {
            RemoteKeyDropdown capture = capturingRemoteKey;
            if(capture.type()==RemoteControllerCompat.Type.WIRELESS){
                if(wirelessCapture.press(com.hooya.stabilizedturret.content.remote.ControllerInput.channel(true,button)))rebuildPage();
                return true;
            }
            String signal = ControllerKeyMappingCompat.signalForMouseButton(
                    capture.type(), button);
            if (signal == null) {
                if (minecraft != null && minecraft.player != null) {
                    minecraft.player.displayClientMessage(
                            Component.translatable(capture.type() == RemoteControllerCompat.Type.ADVANCED
                                    ? "message.create_fire_control.advanced_key_not_bound"
                                    : "message.create_fire_control.normal_key_not_bound"),
                            true
                    );
                }
            } else {
                selectRemoteKey(capture.type(), capture.action(), signal);
            }
            return true;
        }
        if (scrollSlider != null && scrollSlider.isMouseOver(mouseX, mouseY)) {
            if (scrollSlider.mouseClicked(mouseX, mouseY, button)) {
                setFocused(scrollSlider);
                setDragging(true);
                return true;
            }
            return false;
        }
        // The preview entry is fixed in the title bar, outside the scrolling viewport.
        if (openRemoteKeyDropdown == null && structurePreviewButton != null
                && structurePreviewButton.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (openRemoteKeyDropdown == null && !isInsideContentViewport(mouseX, mouseY)) {
            return false;
        }
        if (openRemoteKeyDropdown == null) {
            return super.mouseClicked(mouseX, mouseY, button);
        }
        RemoteKeyDropdown dropdown = openRemoteKeyDropdown;
        RemoteKeyDropdownLayout layout = remoteKeyDropdownLayout(dropdown);
        boolean inside = mouseX >= layout.x() - 4 && mouseX < layout.x() + layout.width() + 4
                && mouseY >= layout.y() - 4 && mouseY < layout.y() + layout.height() + 4;
        if (!inside) {
            openRemoteKeyDropdown = null;
            rebuildPage();
            return true;
        }
        if (button != 0) return true;

        int localX = (int) mouseX - layout.x();
        int localY = (int) mouseY - layout.y();
        int column = localX / (layout.cellWidth() + layout.gap());
        int row = localY / (layout.cellHeight() + layout.gap());
        boolean inCell = localX >= 0 && localY >= 0
                && column >= 0 && column < layout.columns()
                && row >= 0
                && localX % (layout.cellWidth() + layout.gap()) < layout.cellWidth()
                && localY % (layout.cellHeight() + layout.gap()) < layout.cellHeight();
        if (!inCell) return true;

        int entry = row * layout.columns() + column;
        String[] keys = DriveByWireCompat.keys(dropdown.type());
        if (entry == 0) {
            selectRemoteKey(dropdown.type(), dropdown.action(), "");
        } else if (entry <= keys.length) {
            selectRemoteKey(dropdown.type(), dropdown.action(), keys[entry - 1]);
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(
            double mouseX, double mouseY, double scrollX, double scrollY
    ) {
        if (capturingRemoteKey != null) {
            RemoteKeyDropdown capture = capturingRemoteKey;
            if(capture.type()==RemoteControllerCompat.Type.WIRELESS) {
                String wheel=com.hooya.stabilizedturret.content.remote.ControllerInput.scrollChannel(scrollY);
                if(!wheel.isEmpty()&&wirelessCapture.press(wheel))finishWirelessCapture(wheel);
                return true;
            }
            String signal = ControllerKeyMappingCompat.signalForMouseScroll(
                    capture.type(), scrollY);
            if (signal != null) {
                selectRemoteKey(capture.type(), capture.action(), signal);
            }
            return true;
        }
        if (maximumScrollOffset() > 0.0
                && viewportPanelBounds().contains(mouseX, mouseY)) {
            setScrollOffset(scrollOffset - scrollY * 28.0, true);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private boolean isInsideContentViewport(double mouseX, double mouseY) {
        PanelBounds viewport = viewportPanelBounds();
        int right = viewport.right - (scrollSlider == null ? 7 : 17);
        return mouseX >= viewport.left + 7 && mouseX < right
                && mouseY >= viewport.top + 29 && mouseY < viewport.bottom - 7;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static final class OffsetSlider extends AbstractSliderButton {
        private final Component axis;
        private final DoubleConsumer consumer;
        private List<Double> markers = List.of();

        private OffsetSlider(int x, int y, int width, int height, Component axis, double actual, DoubleConsumer consumer) {
            super(x, y, width, height, Component.empty(), toSlider(actual));
            this.axis = axis;
            this.consumer = consumer;
            updateMessage();
        }

        private void setActualValue(double actual) {
            value = toSlider(actual);
            updateMessage();
        }

        private void setMarkers(List<Double> values) {
            markers = values == null ? List.of() : values.stream()
                    .filter(Double::isFinite)
                    .map(OffsetSlider::toSlider)
                    .toList();
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.empty().append(axis).append(": " + formatOffset(toActual(value))));
        }

        @Override
        protected void applyValue() {
            double actual = roundOffset(toActual(value));
            value = toSlider(actual);
            consumer.accept(actual);
            updateMessage();
        }

        @Override
        public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            int left = getX();
            int top = getY();
            int right = left + width;
            int bottom = top + height;
            graphics.fill(left, top, right, bottom, 0xFF514C42);
            graphics.fill(left + 1, top + 1, right - 1, bottom - 1,
                    !active ? 0xFFAAAAAA : isHovered() ? 0xFFEAD9AE : 0xFFD8C59B);
            int trackY = top + 19;
            graphics.fill(left + 7, trackY - 1, right - 7, trackY + 2, 0xFF5B554A);
            for (double marker : markers) {
                int markerX = left + 7 + (int) Math.round(marker * (width - 14));
                graphics.fill(markerX - 2, trackY - 4, markerX + 3, trackY + 5, 0xFF3F7A9E);
            }
            int handleX = left + 7 + (int) Math.round(value * (width - 14));
            graphics.fill(handleX - 3, trackY - 5, handleX + 4, trackY + 6, 0xFF493F31);
            graphics.fill(handleX - 2, trackY - 4, handleX + 3, trackY + 5, active ? 0xFFC9A34A : 0xFF888888);
            net.minecraft.client.gui.Font font = Minecraft.getInstance().font;
            graphics.drawString(font, getMessage(), left + (width - font.width(getMessage())) / 2,
                    top + 3, active ? 0xFF2F2A23 : 0xFF666666, false);
        }

        private static double toSlider(double actual) {
            return (roundOffset(actual) + 20.0) / 40.0;
        }

        private static double toActual(double slider) {
            return slider * 40.0 - 20.0;
        }
    }

    private static final class YawLimitSlider extends AbstractSliderButton {
        private final Component axis;
        private final DoubleConsumer consumer;
        private List<Double> markers = List.of();

        private YawLimitSlider(int x, int y, int width, int height, Component axis, double actual, DoubleConsumer consumer) {
            super(x, y, width, height, Component.empty(), toSlider(actual));
            this.axis = axis;
            this.consumer = consumer;
            updateMessage();
        }

        private void setActualValue(double actual) {
            value = toSlider(actual);
            updateMessage();
        }

        private void setMarkers(List<Double> values) {
            markers = values == null ? List.of() : values.stream()
                    .filter(Double::isFinite)
                    .map(YawLimitSlider::toSlider)
                    .toList();
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.empty().append(axis).append(": " + (toActual(value) >= 180.0 ? Component.translatable("screen.create_fire_control.yaw_unlimited").getString() : String.format(Locale.ROOT, "%.2f°", toActual(value)))));
        }

        @Override
        protected void applyValue() {
            double actual = Math.round(YawLimits.clamp(toActual(value)) * 100.0) / 100.0;
            value = toSlider(actual);
            consumer.accept(actual);
            updateMessage();
        }

        @Override
        public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            int left = getX();
            int top = getY();
            int right = left + width;
            int bottom = top + height;
            graphics.fill(left, top, right, bottom, 0xFF514C42);
            graphics.fill(left + 1, top + 1, right - 1, bottom - 1, isHovered() ? 0xFFEAD9AE : 0xFFD8C59B);
            int trackY = top + 19;
            graphics.fill(left + 7, trackY - 1, right - 7, trackY + 2, 0xFF5B554A);
            for (double marker : markers) {
                int markerX = left + 7 + (int) Math.round(marker * (width - 14));
                graphics.fill(markerX - 2, trackY - 4, markerX + 3, trackY + 5, 0xFF3F7A9E);
            }
            int handleX = left + 7 + (int) Math.round(value * (width - 14));
            graphics.fill(handleX - 3, trackY - 5, handleX + 4, trackY + 6, 0xFF493F31);
            graphics.fill(handleX - 2, trackY - 4, handleX + 3, trackY + 5, 0xFFC9A34A);
            net.minecraft.client.gui.Font font = Minecraft.getInstance().font;
            graphics.drawString(font, getMessage(), left + (width - font.width(getMessage())) / 2,
                    top + 3, 0xFF2F2A23, false);
        }

        private static double toSlider(double actual) {
            return YawLimits.clamp(actual) / 180.0;
        }

        private static double toActual(double slider) {
            return slider * 180.0;
        }
    }

    private static final class CannonRecoilSlider extends AbstractSliderButton {
        private final Component label;
        private final DoubleConsumer consumer;

        private CannonRecoilSlider(int x, int y, int width, int height,
                                  Component label, double actual, DoubleConsumer consumer) {
            super(x, y, width, height, Component.empty(),
                    CannonRecoilSettings.clamp(actual) / CannonRecoilSettings.MAX);
            this.label = label;
            this.consumer = consumer;
            updateMessage();
        }

        private void setActualValue(double actual) {
            value = CannonRecoilSettings.clamp(actual) / CannonRecoilSettings.MAX;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.empty().append(label)
                    .append(": " + formatRecoil(value * CannonRecoilSettings.MAX)));
        }

        @Override
        protected void applyValue() {
            double actual = Math.round(value * CannonRecoilSettings.MAX * 100.0) / 100.0;
            value = actual / CannonRecoilSettings.MAX;
            consumer.accept(actual);
            updateMessage();
        }

        @Override
        public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            int left = getX();
            int top = getY();
            int right = left + width;
            graphics.fill(left, top, right, top + height, 0xFF514C42);
            graphics.fill(left + 1, top + 1, right - 1, top + height - 1,
                    isHovered() ? 0xFFEAD9AE : 0xFFD8C59B);
            int trackY = top + 19;
            graphics.fill(left + 7, trackY - 1, right - 7, trackY + 2, 0xFF5B554A);
            int handleX = left + 7 + (int) Math.round(value * (width - 14));
            graphics.fill(handleX - 3, trackY - 5, handleX + 4, trackY + 6, 0xFF493F31);
            graphics.fill(handleX - 2, trackY - 4, handleX + 3, trackY + 5, 0xFFC9A34A);
            net.minecraft.client.gui.Font font = Minecraft.getInstance().font;
            graphics.drawString(font, getMessage(), left + (width - font.width(getMessage())) / 2,
                    top + 3, 0xFF2F2A23, false);
        }
    }

    private static final class AimOffsetSlider extends AbstractSliderButton {
        private final Component axis;
        private final DoubleConsumer consumer;

        private AimOffsetSlider(int x, int y, int width, int height, Component axis, double actual, DoubleConsumer consumer) {
            super(x, y, width, height, Component.empty(), toSlider(actual));
            this.axis = axis;
            this.consumer = consumer;
            updateMessage();
        }

        private void setActualValue(double actual) {
            value = toSlider(actual);
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.empty().append(axis).append(": " + formatAimOffset(toActual(value)) + "%"));
        }

        @Override
        protected void applyValue() {
            double actual = roundAimOffset(toActual(value));
            value = toSlider(actual);
            consumer.accept(actual);
            updateMessage();
        }

        @Override
        public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            int left = getX();
            int top = getY();
            int right = left + width;
            int bottom = top + height;
            graphics.fill(left, top, right, bottom, 0xFF514C42);
            graphics.fill(left + 1, top + 1, right - 1, bottom - 1, isHovered() ? 0xFFEAD9AE : 0xFFD8C59B);
            int trackY = top + 19;
            graphics.fill(left + 7, trackY - 1, right - 7, trackY + 2, 0xFF5B554A);
            int handleX = left + 7 + (int) Math.round(value * (width - 14));
            graphics.fill(handleX - 3, trackY - 5, handleX + 4, trackY + 6, 0xFF493F31);
            graphics.fill(handleX - 2, trackY - 4, handleX + 3, trackY + 5, 0xFF73C6D0);
            net.minecraft.client.gui.Font font = Minecraft.getInstance().font;
            graphics.drawString(font, getMessage(), left + (width - font.width(getMessage())) / 2,
                    top + 3, 0xFF2F2A23, false);
        }

        private static double toSlider(double actual) {
            return (roundAimOffset(actual) + 45.0) / 90.0;
        }

        private static double toActual(double slider) {
            return slider * 90.0 - 45.0;
        }
    }

    private final class PageScrollSlider extends AbstractSliderButton {
        private PageScrollSlider(int x, int y, int width, int height, double value) {
            super(x, y, width, height, Component.empty(), value);
            updateMessage();
        }

        private void setScrollFraction(double fraction) {
            value = Math.max(0.0, Math.min(1.0, fraction));
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.empty());
        }

        @Override
        protected void applyValue() {
            setScrollOffset(value * maximumScrollOffset(), false);
        }

        @Override
        public void onClick(double mouseX, double mouseY) {
            setValueFromMouse(mouseY);
        }

        @Override
        protected void onDrag(double mouseX, double mouseY, double dragX, double dragY) {
            setValueFromMouse(mouseY);
        }

        private void setValueFromMouse(double mouseY) {
            value = Math.max(0.0, Math.min(1.0,
                    (mouseY - getY() - 5.0) / Math.max(1.0, height - 10.0)));
            applyValue();
        }

        @Override
        public void renderWidget(
                GuiGraphics graphics, int mouseX, int mouseY, float partialTick
        ) {
            int left = getX();
            int top = getY();
            int right = left + width;
            int bottom = top + height;
            graphics.fill(left, top, right, bottom, 0xFF302C26);
            graphics.fill(left + 1, top + 1, right - 1, bottom - 1, PANEL_BRASS);
            int trackX = left + width / 2;
            graphics.fill(trackX - 1, top + 3, trackX + 2, bottom - 3, 0xFF514C42);
            int handleY = top + 5 + (int) Math.round(value * (height - 10));
            graphics.fill(left + 2, handleY - 4, right - 2, handleY + 5,
                    isHovered() ? 0xFFEAD9AE : 0xFFD8C59B);
        }
    }

    private enum ButtonTone { NORMAL, GREEN, RED }

    /** Paints an existing editor button with the computer's controls, keeping its original action. */
    public static void renderBrassButton(GuiGraphics graphics, Button button, boolean accept) {
        BrassButton.draw(graphics, button, accept ? ButtonTone.GREEN : ButtonTone.NORMAL);
    }

    private static final class BrassButton extends Button {
        private ButtonTone tone = ButtonTone.NORMAL;

        private BrassButton(int x, int y, int width, int height, Component message, OnPress onPress) {
            super(x, y, width, height, message, onPress, DEFAULT_NARRATION);
        }

        private BrassButton setTone(ButtonTone tone) {
            this.tone = tone;
            return this;
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            draw(graphics, this, tone);
        }

        private static void draw(GuiGraphics graphics, Button button, ButtonTone tone) {
            int background = switch (tone) {
                case GREEN -> button.isHovered() ? 0xFF64815A : 0xFF506C49;
                case RED -> button.isHovered() ? 0xFF8A6258 : 0xFF704E47;
                case NORMAL -> button.isHovered() ? 0xFF777064 : 0xFF5F5A50;
            };
            if (!button.active) {
                background = 0xFF4A4842;
            }
            int x=button.getX(), y=button.getY(), width=button.getWidth(), height=button.getHeight();
            graphics.fill(x, y, x + width, y + height, 0xFF302C26);
            graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, PANEL_BRASS);
            graphics.fill(x + 2, y + 2, x + width - 2, y + height - 2, background);
            graphics.fill(x + 3, y + 3, x + width - 3, y + 4, 0x35FFFFFF);
            graphics.enableScissor(x + 4, y + 2, x + width - 4, y + height - 2);
            net.minecraft.client.gui.Font font = Minecraft.getInstance().font;
            graphics.drawString(
                    font,
                    button.getMessage(),
                    x + (width - font.width(button.getMessage())) / 2,
                    y + (height - 8) / 2,
                    button.active ? 0xFFF4EBD5 : 0xFF9A958A,
                    false
            );
            graphics.disableScissor();
        }
    }

    private record RemoteKeyDropdown(RemoteControllerCompat.Type type, int action, int x, int y, int buttonWidth) {
    }

    private record RemoteKeyDropdownLayout(
            int x, int y, int width, int height, int columns, int cellWidth, int cellHeight, int gap
    ) {
    }

    private record PanelBounds(int left, int top, int right, int bottom) {
        private boolean contains(double x, double y) {
            return x >= left && x < right && y >= top && y < bottom;
        }
    }

    private record PanelSize(int width, int height) {
    }
}
