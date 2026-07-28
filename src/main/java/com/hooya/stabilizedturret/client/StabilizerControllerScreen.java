package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.content.controller.DriveByWireCompat;
import com.hooya.stabilizedturret.content.controller.RemoteControllerCompat;
import com.hooya.stabilizedturret.network.AssignBearingRolePayload;
import com.hooya.stabilizedturret.network.ControllerSettingsPayload;
import com.hooya.stabilizedturret.network.OpenControllerPayload;
import com.hooya.stabilizedturret.network.SetWirelessFrequencyPayload;
import com.hooya.stabilizedturret.network.SetRemoteKeyBindingPayload;
import com.hooya.stabilizedturret.network.SetCannonMountModePayload;
import com.hooya.stabilizedturret.network.SetPodCannonLimitsPayload;
import com.hooya.stabilizedturret.network.UnbindDevicePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
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

    private static final int DEVICES_PER_PAGE = 6;

    private enum Page {
        HOME, FIRE_CONTROL, PITCH, YAW, VIEW, AIM_POINT, POD_CANNON,
        RADAR, AIRCRAFT, INPUTS, DEVICES
    }

    private final OpenControllerPayload initial;
    private Page page = Page.HOME;

    private double depression;
    private double elevation;
    private double yawSpeed;
    private double pitchSpeed;
    private boolean yawReversed;
    private boolean pitchReversed;
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
    private boolean stabilizerEnabled;
    private boolean headAimEnabled;
    private boolean remoteHeadAimEnabled;
    private double podCannonDepression;
    private double podCannonElevation;
    private String scopeFirst;
    private String scopeSecond;
    private String thermalFirst;
    private String thermalSecond;
    private String opticalLockFirst;
    private String opticalLockSecond;
    private String missileFireFirst;
    private String missileFireSecond;
    private String aaMissileFireFirst;
    private String aaMissileFireSecond;
    private String radarLockFirst;
    private String radarLockSecond;
    private String aircraftMissileFireFirst;
    private String aircraftMissileFireSecond;
    private String aircraftWeaponCycleFirst;
    private String aircraftWeaponCycleSecond;
    private String aircraftTargetCycleFirst;
    private String aircraftTargetCycleSecond;
    private String podViewFirst;
    private String podViewSecond;
    private String airToGroundLockFirst;
    private String airToGroundLockSecond;
    private final List<String> normalRemoteKeys;
    private final List<String> advancedRemoteKeys;
    private final List<OpenControllerPayload.BoundDevice> devices;
    private int devicePage;
    private OpenControllerPayload.BoundDevice pendingUnbind;
    private OpenControllerPayload.BoundDevice pendingBearingRole;
    private OpenControllerPayload.BoundDevice pendingCannonMountMode;
    private RemoteKeyDropdown openRemoteKeyDropdown;
    private RemoteKeyDropdown capturingRemoteKey;

    public StabilizerControllerScreen(OpenControllerPayload initial) {
        super(Component.translatable("screen.stabilized_turret.controller"));
        this.initial = initial;
        depression = initial.depression();
        elevation = initial.elevation();
        yawSpeed = initial.yawSpeed();
        pitchSpeed = initial.pitchSpeed();
        yawReversed = initial.yawReversed();
        pitchReversed = initial.pitchReversed();
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
        stabilizerEnabled = initial.stabilizerEnabled();
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
        normalRemoteKeys = new ArrayList<>(initial.normalRemoteKeys());
        advancedRemoteKeys = new ArrayList<>(initial.advancedRemoteKeys());
        while (normalRemoteKeys.size() < 11) normalRemoteKeys.add("");
        while (advancedRemoteKeys.size() < 11) advancedRemoteKeys.add("");
        devices = new ArrayList<>(initial.devices());
    }

    @Override
    protected void init() {
        buildPage();
    }

    private void openPage(Page requested) {
        page = requested;
        clearWidgets();
        buildPage();
    }

    private void buildPage() {
        switch (page) {
            case HOME -> buildHomePage();
            case FIRE_CONTROL -> buildFireControlPage();
            case PITCH -> buildPitchPage();
            case YAW -> buildYawPage();
            case VIEW -> buildViewPage();
            case AIM_POINT -> buildAimPointPage();
            case POD_CANNON -> buildPodCannonPage();
            case RADAR -> buildRadarPage();
            case AIRCRAFT -> buildAircraftPage();
            case INPUTS -> buildInputsPage();
            case DEVICES -> buildDevicesPage();
        }
    }

    private void buildHomePage() {
        PanelBounds panel = panelBounds();
        int left = width / 2 - 125;
        int top = panel.top + 42;
        addRenderableWidget(styledButton(Component.translatable("screen.stabilized_turret.pitch_settings"),
                button -> openPage(Page.PITCH), left, top, 120, 20));
        addRenderableWidget(styledButton(Component.translatable("screen.stabilized_turret.yaw_settings"),
                button -> openPage(Page.YAW), left + 130, top, 120, 20));
        addRenderableWidget(styledButton(Component.translatable("screen.stabilized_turret.view_settings"),
                button -> openPage(Page.VIEW), left, top + 30, 120, 20));
        addRenderableWidget(styledButton(Component.translatable("screen.stabilized_turret.fire_control_settings"),
                button -> openPage(Page.FIRE_CONTROL), left + 130, top + 30, 120, 20));
        addRenderableWidget(styledButton(Component.translatable("screen.stabilized_turret.radar_settings"),
                button -> openPage(Page.RADAR), left, top + 60, 120, 20));
        addRenderableWidget(styledButton(Component.translatable("screen.stabilized_turret.devices"),
                button -> openPage(Page.DEVICES), left + 130, top + 60, 120, 20));
        addRenderableWidget(styledButton(Component.translatable("screen.stabilized_turret.aircraft_settings"),
                button -> openPage(Page.AIRCRAFT), left, top + 90, 120, 20));
        addRenderableWidget(styledButton(Component.translatable("screen.stabilized_turret.input_bindings"),
                button -> openPage(Page.INPUTS), left + 130, top + 90, 120, 20));
        addRenderableWidget(styledButton(Component.translatable("gui.done"), button -> saveAndClose(),
                left, top + 120, 120, 20).setTone(ButtonTone.GREEN));
        addRenderableWidget(styledButton(Component.translatable("gui.cancel"), button -> onClose(),
                left + 130, top + 120, 120, 20).setTone(ButtonTone.RED));
    }

    private void buildFireControlPage() {
        PanelBounds panel = panelBounds();
        int left = width / 2 - 125;
        int top = panel.top + 42;
        addRenderableWidget(styledButton(toggleText("stabilizer", stabilizerEnabled), button -> {
            stabilizerEnabled = !stabilizerEnabled;
            button.setMessage(toggleText("stabilizer", stabilizerEnabled));
            ((BrassButton) button).setTone(stabilizerEnabled ? ButtonTone.GREEN : ButtonTone.NORMAL);
        }, left, top, 120, 20).setTone(stabilizerEnabled ? ButtonTone.GREEN : ButtonTone.NORMAL));
        addRenderableWidget(styledButton(toggleText("head_aim", headAimEnabled), button -> {
            headAimEnabled = !headAimEnabled;
            button.setMessage(toggleText("head_aim", headAimEnabled));
            ((BrassButton) button).setTone(headAimEnabled ? ButtonTone.GREEN : ButtonTone.NORMAL);
        }, left + 130, top, 120, 20).setTone(headAimEnabled ? ButtonTone.GREEN : ButtonTone.NORMAL));
        addRenderableWidget(styledButton(toggleText("remote_head_aim", remoteHeadAimEnabled), button -> {
            if (!initial.remoteTerminalAvailable()) {
                remoteHeadAimEnabled = false;
                button.setMessage(toggleText("remote_head_aim", false));
                ((BrassButton) button).setTone(ButtonTone.RED);
                if (minecraft != null && minecraft.player != null) {
                    minecraft.player.displayClientMessage(
                            Component.translatable("message.stabilized_turret.remote_terminal_missing"),
                            true
                    );
                }
                return;
            }
            remoteHeadAimEnabled = !remoteHeadAimEnabled;
            button.setMessage(toggleText("remote_head_aim", remoteHeadAimEnabled));
            ((BrassButton) button).setTone(remoteHeadAimEnabled ? ButtonTone.GREEN : ButtonTone.NORMAL);
        }, left, top + 30, 250, 20).setTone(
                !initial.remoteTerminalAvailable()
                        ? ButtonTone.RED
                        : remoteHeadAimEnabled ? ButtonTone.GREEN : ButtonTone.NORMAL
        ));
        addRenderableWidget(styledButton(Component.translatable("gui.back"), button -> openPage(Page.HOME),
                left + 65, top + 82, 120, 20));
    }

    private void buildPitchPage() {
        PanelBounds panel = panelBounds();
        int left = width / 2 - 150;
        int top = panel.top;
        numericBox(left + 185, top + 39, depression, value -> depression = value, false);
        numericBox(left + 185, top + 64, elevation, value -> elevation = value, false);
        numericBox(left + 185, top + 89, pitchSpeed, value -> pitchSpeed = value, false);
        addRenderableWidget(styledButton(toggleText("pitch_reversed", pitchReversed), button -> {
            pitchReversed = !pitchReversed;
            button.setMessage(toggleText("pitch_reversed", pitchReversed));
            ((BrassButton) button).setTone(pitchReversed ? ButtonTone.RED : ButtonTone.NORMAL);
        }, left + 160, top + 112, 140, 20).setTone(
                pitchReversed ? ButtonTone.RED : ButtonTone.NORMAL));
        offsetControl(left, top + 155, Component.translatable("screen.stabilized_turret.offset_x"), pitchOffsetX, value -> pitchOffsetX = value);
        offsetControl(left, top + 180, Component.translatable("screen.stabilized_turret.offset_y"), pitchOffsetY, value -> pitchOffsetY = value);
        offsetControl(left, top + 205, Component.translatable("screen.stabilized_turret.offset_z"), pitchOffsetZ, value -> pitchOffsetZ = value);
        addRenderableWidget(styledButton(Component.translatable("gui.back"), button -> openPage(Page.HOME),
                left + 90, top + 238, 120, 20));
    }

    private void buildYawPage() {
        PanelBounds panel = panelBounds();
        int left = width / 2 - 150;
        int top = panel.top;
        numericBox(left + 185, top + 43, yawSpeed, value -> yawSpeed = value, false);
        addRenderableWidget(styledButton(toggleText("yaw_reversed", yawReversed), button -> {
            yawReversed = !yawReversed;
            button.setMessage(toggleText("yaw_reversed", yawReversed));
            ((BrassButton) button).setTone(yawReversed ? ButtonTone.RED : ButtonTone.NORMAL);
        }, left + 160, top + 68, 140, 20).setTone(
                yawReversed ? ButtonTone.RED : ButtonTone.NORMAL));
        offsetControl(left, top + 108, Component.translatable("screen.stabilized_turret.offset_x"), yawOffsetX, value -> yawOffsetX = value);
        offsetControl(left, top + 133, Component.translatable("screen.stabilized_turret.offset_y"), yawOffsetY, value -> yawOffsetY = value);
        offsetControl(left, top + 158, Component.translatable("screen.stabilized_turret.offset_z"), yawOffsetZ, value -> yawOffsetZ = value);
        addRenderableWidget(styledButton(Component.translatable("gui.back"), button -> openPage(Page.HOME),
                left + 90, top + 191, 120, 20));
    }

    private void buildViewPage() {
        PanelBounds panel = panelBounds();
        int left = width / 2 - 150;
        int top = panel.top;
        numericBox(left + 240, top + 34, thirdPersonHeight, value -> thirdPersonHeight = value, false);
        offsetControl(left, top + 70, Component.translatable("screen.stabilized_turret.offset_x"), scopeOffsetX, value -> scopeOffsetX = value);
        offsetControl(left, top + 96, Component.translatable("screen.stabilized_turret.offset_y"), scopeOffsetY, value -> scopeOffsetY = value);
        offsetControl(left, top + 122, Component.translatable("screen.stabilized_turret.offset_z"), scopeOffsetZ, value -> scopeOffsetZ = value);
        addRenderableWidget(styledButton(Component.translatable("screen.stabilized_turret.aim_point_settings"),
                button -> openPage(Page.AIM_POINT), left, top + 164, 145, 20));
        addRenderableWidget(styledButton(Component.translatable("screen.stabilized_turret.pod_cannon_settings"),
                button -> openPage(Page.POD_CANNON), left + 155, top + 164, 145, 20));
        addRenderableWidget(styledButton(Component.translatable("gui.back"), button -> openPage(Page.HOME),
                left + 90, top + 196, 120, 20));
    }

    private void buildPodCannonPage() {
        PanelBounds panel = panelBounds();
        int left = width / 2 - 150;
        int top = panel.top;
        numericBox(left + 240, top + 55, podCannonDepression,
                value -> podCannonDepression = Math.max(0.0, Math.min(90.0, value)), false);
        numericBox(left + 240, top + 86, podCannonElevation,
                value -> podCannonElevation = Math.max(0.0, Math.min(90.0, value)), false);
        addRenderableWidget(styledButton(Component.translatable("gui.back"),
                button -> openPage(Page.VIEW), left + 90, top + 132, 120, 20));
    }

    private void buildAimPointPage() {
        PanelBounds panel = panelBounds();
        int left = width / 2 - 150;
        int top = panel.top;
        aimOffsetControl(left, top + 166, Component.translatable("screen.stabilized_turret.aim_offset_x"),
                viewAimOffsetX, value -> viewAimOffsetX = value);
        aimOffsetControl(left, top + 194, Component.translatable("screen.stabilized_turret.aim_offset_y"),
                viewAimOffsetY, value -> viewAimOffsetY = value);
        addRenderableWidget(styledButton(Component.translatable("gui.back"), button -> openPage(Page.VIEW),
                left + 90, top + 226, 120, 20));
    }

    private void buildRadarPage() {
        PanelBounds panel = panelBounds();
        int top = panel.top;
        int boxX = panel.right - 64;
        numericBox(boxX, top + 48, radarRange, value ->
                radarRange = Math.max(200.0, Math.min(20000.0, value)), false);
        numericBox(boxX, top + 74, radarDepression, value ->
                radarDepression = Math.max(0.0, Math.min(90.0, value)), false);
        numericBox(boxX, top + 100, radarElevation, value ->
                radarElevation = Math.max(0.0, Math.min(90.0, value)), false);
        numericBox(boxX, top + 126, radarHudScale, value ->
                radarHudScale = Math.max(40.0, Math.min(150.0, value)), false);
        numericBox(boxX, top + 152, radarHudX, value ->
                radarHudX = Math.max(0.0, Math.min(100.0, value)), false);
        numericBox(boxX, top + 178, radarHudY, value ->
                radarHudY = Math.max(0.0, Math.min(100.0, value)), false);
        numericBox(boxX, top + 204, aaProximityFuseRange, value ->
                aaProximityFuseRange = Math.max(0.0, Math.min(10.0, value)), false);

        addRenderableWidget(styledButton(Component.translatable("gui.back"),
                button -> openPage(Page.HOME), width / 2 - 60, panel.bottom - 31, 120, 20));
    }

    private void buildAircraftPage() {
        PanelBounds panel = panelBounds();
        int boxX = panel.right - 72;
        int top = panel.top;
        numericBox(boxX, top + 43, aircraftRadarRange,
                value -> aircraftRadarRange = Math.max(200.0, Math.min(100000.0, value)), false);
        numericBox(boxX, top + 67, aircraftRadarHorizontalHalfAngle,
                value -> aircraftRadarHorizontalHalfAngle = Math.max(5.0, Math.min(90.0, value)), false);
        numericBox(boxX, top + 91, aircraftRadarVerticalAngle,
                value -> aircraftRadarVerticalAngle = Math.max(2.0, Math.min(90.0, value)), false);
        numericBox(boxX, top + 115, aircraftProximityFuseRange,
                value -> aircraftProximityFuseRange = Math.max(0.0, Math.min(10.0, value)), false);
        numericBox(boxX, top + 139, aircraftFuseSafetySeconds,
                value -> aircraftFuseSafetySeconds = Math.max(0.0, Math.min(10.0, value)), false);
        numericBox(boxX, top + 163, aircraftRadarMinimumTargetSpeed,
                value -> aircraftRadarMinimumTargetSpeed = Math.max(0.0, Math.min(2000.0, value)), false);
        numericBox(boxX, top + 187, aircraftRadarHudScale,
                value -> aircraftRadarHudScale = Math.max(40.0, Math.min(180.0, value)), false);
        numericBox(boxX, top + 211, aircraftRadarHudX,
                value -> aircraftRadarHudX = Math.max(0.0, Math.min(100.0, value)), false);
        numericBox(boxX, top + 235, aircraftRadarHudY,
                value -> aircraftRadarHudY = Math.max(0.0, Math.min(100.0, value)), false);

        addRenderableWidget(styledButton(Component.translatable("gui.back"), button -> openPage(Page.HOME),
                width / 2 - 60, panel.bottom - 29, 120, 20));
    }

    private void buildInputsPage() {
        PanelBounds panel = panelBounds();
        int left = panel.left + 10;
        int top = panel.top + 53;
        int actionWidth = 126;
        int gap = 4;
        int buttonWidth = Math.max(72, (panel.right - panel.left - 20 - actionWidth - gap * 3) / 4);
        int x = left + actionWidth;
        for (int action = 0; action < 11; action++) {
            int y = top + action * 21;
            frequencyButton(x, y, action, true, buttonWidth);
            frequencyButton(x + buttonWidth + gap, y, action, false, buttonWidth);
            remoteKeyButton(RemoteControllerCompat.Type.NORMAL, action,
                    x + (buttonWidth + gap) * 2, y, buttonWidth);
            remoteKeyButton(RemoteControllerCompat.Type.ADVANCED, action,
                    x + (buttonWidth + gap) * 3, y, buttonWidth);
        }
        addRenderableWidget(styledButton(Component.translatable("gui.back"), button -> openPage(Page.HOME),
                width / 2 - 60, panel.bottom - 28, 120, 20));

    }

    private void buildDevicesPage() {
        PanelBounds panel = panelBounds();
        if (pendingUnbind != null) {
            addRenderableWidget(styledButton(
                    Component.translatable("screen.stabilized_turret.unbind_confirm"),
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
                            "screen.stabilized_turret.cannon_mount_mode.default"),
                    button -> setCannonMountMode(false),
                    left, top, 120, 20
            ));
            addRenderableWidget(styledButton(
                    Component.translatable(
                            "screen.stabilized_turret.cannon_mount_mode.pod_control"),
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
                    Component.translatable("screen.stabilized_turret.assign_role"),
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
                    Component.translatable("screen.stabilized_turret.unbind"),
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
                        "screen.stabilized_turret.device_type." + role.toLowerCase(Locale.ROOT)),
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

        OpenControllerPayload.BoundDevice occupied = devices.stream()
                .filter(device -> role.equals(device.slot()))
                .findFirst().orElse(null);
        devices.remove(selected);
        if (occupied != null) {
            int bearingIndex = (int) devices.stream()
                    .filter(device -> "BEARING".equals(device.slot())).count();
            int occupiedIndex = devices.indexOf(occupied);
            devices.set(occupiedIndex, new OpenControllerPayload.BoundDevice(
                    "BEARING", bearingIndex, occupied.translationKey(),
                    occupied.targetPos(), occupied.subLevel()
            ));
        }
        int roleIndex = (int) devices.stream().filter(device -> role.equals(device.slot())).count();
        devices.add(new OpenControllerPayload.BoundDevice(
                role, 0, selected.translationKey(), selected.targetPos(), selected.subLevel()
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

    private void offsetControl(int left, int y, Component axis, double current, DoubleConsumer consumer) {
        final OffsetSlider[] sliderRef = new OffsetSlider[1];
        EditBox box = numericBox(left + 240, y + 3, current, value -> {
            consumer.accept(value);
            if (sliderRef[0] != null) {
                sliderRef[0].setActualValue(value);
            }
        }, true);
        OffsetSlider slider = new OffsetSlider(left, y, 230, 26, axis, current, value -> {
            consumer.accept(value);
            box.setValue(formatOffset(value));
        });
        sliderRef[0] = slider;
        addRenderableWidget(slider);
    }

    private void aimOffsetControl(int left, int y, Component axis, double current, DoubleConsumer consumer) {
        final AimOffsetSlider[] sliderRef = new AimOffsetSlider[1];
        EditBox box = numericBox(left + 240, y + 3, current, value -> {
            double clamped = roundAimOffset(value);
            consumer.accept(clamped);
            if (sliderRef[0] != null) {
                sliderRef[0].setActualValue(clamped);
            }
        }, true);
        AimOffsetSlider slider = new AimOffsetSlider(left, y, 230, 26, axis, current, value -> {
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

    private Button frequencyButton(int x, int y, int channel, boolean first, int buttonWidth) {
        String current = switch (channel) {
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
            default -> first ? scopeFirst : scopeSecond;
        };
        return addRenderableWidget(styledButton(frequencyText(first, current), button -> {
            PacketDistributor.sendToServer(new SetWirelessFrequencyPayload(initial.pos(), channel, first));
            String heldName = minecraft != null && minecraft.player != null
                    && !minecraft.player.getMainHandItem().isEmpty()
                    ? minecraft.player.getMainHandItem().getHoverName().getString()
                    : "-";
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
        BrassButton result = styledButton(remoteKeyText(type, action, selected.get(action), bound), button -> {
            capturingRemoteKey = new RemoteKeyDropdown(type, action, x, y, buttonWidth);
            if (minecraft != null && minecraft.player != null) {
                minecraft.player.displayClientMessage(
                        Component.translatable("message.stabilized_turret.press_binding_key"),
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
                Component.translatable("screen.stabilized_turret.remote_unbind"), button ->
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
        int cellWidth = 84;
        int cellHeight = 16;
        int gap = 2;
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
        List<String> selected = type == RemoteControllerCompat.Type.ADVANCED
                ? advancedRemoteKeys : normalRemoteKeys;
        selected.set(action, key);
        PacketDistributor.sendToServer(new SetRemoteKeyBindingPayload(
                initial.pos(), action, type == RemoteControllerCompat.Type.ADVANCED, key));
        openRemoteKeyDropdown = null;
        capturingRemoteKey = null;
        rebuildPage();
    }

    private boolean hasBoundHub(RemoteControllerCompat.Type type) {
        String slot = type == RemoteControllerCompat.Type.ADVANCED
                ? "TWEAKED_CONTROLLER_HUB" : "REMOTE_CONTROLLER_HUB";
        return devices.stream().anyMatch(device -> slot.equals(device.slot()));
    }

    private Component remoteKeyText(
            RemoteControllerCompat.Type type,
            int action,
            String key,
            boolean bound
    ) {
        if (!bound) return Component.translatable("screen.stabilized_turret.hub_not_bound");
        if (capturingRemoteKey != null
                && capturingRemoteKey.type() == type
                && capturingRemoteKey.action() == action) {
            return Component.translatable("screen.stabilized_turret.press_key");
        }
        if (key == null || key.isEmpty()) return Component.literal("-");
        return Component.empty()
                .append(ControllerKeyMappingCompat.displayKey(type, key))
                .append(" -> ")
                .append(remoteKeyName(type, key));
    }

    private Component remoteKeyName(RemoteControllerCompat.Type type, String key) {
        if (key == null || key.isEmpty()) return Component.literal("-");
        String translationKey = DriveByWireCompat.translationKey(type, key);
        return translationKey.isEmpty() ? Component.literal("-") : Component.translatable(translationKey);
    }

    private Component remoteBindingTooltip(
            RemoteControllerCompat.Type type,
            int action,
            String key,
            boolean terminalBound
    ) {
        Component actionDescription = Component.translatable(
                "screen.stabilized_turret.input.action." + action + ".description");
        Component keyDescription;
        if (!terminalBound) {
            keyDescription = Component.translatable("screen.stabilized_turret.remote_key.terminal_missing");
        } else if (key == null || key.isEmpty()) {
            keyDescription = Component.translatable("screen.stabilized_turret.remote_key.unbound");
        } else {
            keyDescription = Component.translatable(DriveByWireCompat.descriptionKey(type, key));
        }
        return Component.translatable(
                "screen.stabilized_turret.input.binding_tooltip",
                actionDescription,
                keyDescription
        );
    }

    private BrassButton styledButton(Component message, Button.OnPress onPress, int x, int y, int width, int height) {
        return new BrassButton(x, y, width, height, message, onPress);
    }

    private Component frequencyText(boolean first, String name) {
        return Component.literal((first ? "A: " : "B: ") + name);
    }

    private Component toggleText(String key, boolean enabled) {
        return Component.translatable(
                "screen.stabilized_turret." + key,
                Component.translatable(enabled ? "options.on" : "options.off")
        );
    }

    private void saveAndClose() {
        PacketDistributor.sendToServer(new ControllerSettingsPayload(
                initial.pos(),
                depression,
                elevation,
                yawSpeed,
                pitchSpeed,
                yawReversed,
                pitchReversed,
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
                stabilizerEnabled,
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
                aircraftRadarHudY
        ));
        PacketDistributor.sendToServer(new SetPodCannonLimitsPayload(
                initial.pos(),
                Math.max(0.0, Math.min(90.0, podCannonDepression)),
                Math.max(0.0, Math.min(90.0, podCannonElevation))
        ));
        onClose();
    }

    private static double roundOffset(double value) {
        return Math.max(-20.0, Math.min(20.0, Math.round(value * 10.0) / 10.0));
    }

    private static String formatOffset(double value) {
        return String.format(Locale.ROOT, "%.1f", roundOffset(value));
    }

    private static double roundAimOffset(double value) {
        return Math.max(-45.0, Math.min(45.0, Math.round(value * 10.0) / 10.0));
    }

    private static String formatAimOffset(double value) {
        return String.format(Locale.ROOT, "%.1f", roundAimOffset(value));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0x68000000);
        renderPanel(graphics);
        if (page == Page.AIM_POINT) {
            renderAimPointPreview(graphics);
        } else if (page == Page.RADAR) {
            renderRadarPreview(graphics);
        } else if (page == Page.AIRCRAFT) {
            PanelBounds panel = panelBounds();
            int areaLeft = panel.left + 14, areaTop = panel.top + 43, areaWidth = 214, areaHeight = 200;
            graphics.fill(areaLeft, areaTop, areaLeft + areaWidth, areaTop + areaHeight, 0xFF4A504D);
            graphics.fill(areaLeft + 1, areaTop + 1, areaLeft + areaWidth - 1, areaTop + areaHeight - 1, 0xFF76817B);
            int previewWidth = Math.max(72, Math.min(202, (int)Math.round(116.0 * aircraftRadarHudScale / 100.0)));
            int previewHeight = Math.max(68, (int)Math.round(previewWidth * 450.0 / 474.0));
            int px = areaLeft + 4 + (int)Math.round(Math.max(0, areaWidth - previewWidth - 8) * aircraftRadarHudX / 100.0);
            int py = areaTop + 4 + (int)Math.round(Math.max(0, areaHeight - previewHeight - 8) * aircraftRadarHudY / 100.0);
            RadarHudRenderer.renderAircraftPreview(graphics, px, py, previewWidth, previewHeight,
                    aircraftRadarRange, aircraftRadarHorizontalHalfAngle, aircraftRadarVerticalAngle);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
        renderLabels(graphics);
        graphics.flush();
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
        PanelBounds panel = panelBounds();
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
        return switch (page) {
            case HOME -> centeredPanel(278, 214);
            case FIRE_CONTROL -> centeredPanel(278, 226);
            case RADAR -> centeredPanel(430, 350);
            case AIRCRAFT -> centeredPanel(500, 374);
            case INPUTS -> centeredPanel(690, 326);
            case PITCH -> centeredPanel(328, 278);
            case YAW -> centeredPanel(328, 231);
            case VIEW -> centeredPanel(328, 264);
            case AIM_POINT -> centeredPanel(328, 260);
            case POD_CANNON -> centeredPanel(328, 200);
            case DEVICES -> centeredPanel(360, 270);
        };
    }

    private PanelBounds centeredPanel(int desiredWidth, int desiredHeight) {
        int panelWidth = Math.min(desiredWidth, Math.max(8, width - 8));
        int panelHeight = Math.min(desiredHeight, Math.max(8, height - 8));
        int left = (width - panelWidth) / 2;
        int top = (height - panelHeight) / 2;
        return new PanelBounds(left, top, left + panelWidth, top + panelHeight);
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
                        Component.translatable("screen.stabilized_turret.fire_control_settings"),
                        width / 2, panel.top + 12, 0xFFFFFF);

            }
            case PITCH -> {
                PanelBounds panel = panelBounds();
                int left = width / 2 - 150;
                int top = panel.top;
                drawCenteredNoShadow(graphics, Component.translatable("screen.stabilized_turret.pitch_settings"), width / 2, top + 12, 0xFFFFFF);
                graphics.drawString(font, Component.translatable("screen.stabilized_turret.depression"), left, top + 45, color, false);
                graphics.drawString(font, Component.translatable("screen.stabilized_turret.elevation"), left, top + 70, color, false);
                graphics.drawString(font, Component.translatable("screen.stabilized_turret.pitch_speed"), left, top + 95, color, false);
                graphics.drawString(font, Component.translatable("screen.stabilized_turret.pitch_offset"), left, top + 143, color, false);
            }
            case YAW -> {
                PanelBounds panel = panelBounds();
                int left = width / 2 - 150;
                int top = panel.top;
                drawCenteredNoShadow(graphics, Component.translatable("screen.stabilized_turret.yaw_settings"), width / 2, top + 12, 0xFFFFFF);
                graphics.drawString(font, Component.translatable("screen.stabilized_turret.yaw_speed"), left, top + 49, color, false);
                graphics.drawString(font, Component.translatable("screen.stabilized_turret.yaw_offset"), left, top + 96, color, false);
            }
            case VIEW -> {
                PanelBounds panel = panelBounds();
                int left = width / 2 - 150;
                int top = panel.top;
                drawCenteredNoShadow(graphics, Component.translatable("screen.stabilized_turret.view_settings"), width / 2, top + 12, 0xFFFFFF);
                graphics.drawString(font, Component.translatable("screen.stabilized_turret.third_person_height"), left, top + 40, color, false);
                graphics.drawString(font, Component.translatable("screen.stabilized_turret.scope_offset"), left, top + 59, color, false);

            }
            case POD_CANNON -> {
                PanelBounds panel = panelBounds();
                int left = width / 2 - 150;
                int top = panel.top;
                drawCenteredNoShadow(graphics,
                        Component.translatable("screen.stabilized_turret.pod_cannon_settings"),
                        width / 2, top + 12, 0xFFFFFF);
                graphics.drawString(font,
                        Component.translatable("screen.stabilized_turret.pod_cannon_depression"),
                        left, top + 61, color, false);
                graphics.drawString(font,
                        Component.translatable("screen.stabilized_turret.pod_cannon_elevation"),
                        left, top + 92, color, false);
            }
            case RADAR -> {
                PanelBounds panel = panelBounds();
                int labelX = panel.left + 215;
                drawCenteredNoShadow(graphics,
                        Component.translatable("screen.stabilized_turret.radar_settings"),
                        width / 2, panel.top + 12, 0xFFFFFF);
                graphics.drawString(font, Component.translatable("screen.stabilized_turret.radar_preview"),
                        panel.left + 14, panel.top + 35, LABEL_COLOR, false);
                graphics.drawString(font, Component.translatable("screen.stabilized_turret.radar_range"),
                        labelX, panel.top + 54, LABEL_COLOR, false);
                graphics.drawString(font, Component.translatable("screen.stabilized_turret.radar_depression"),
                        labelX, panel.top + 80, LABEL_COLOR, false);
                graphics.drawString(font, Component.translatable("screen.stabilized_turret.radar_elevation"),
                        labelX, panel.top + 106, LABEL_COLOR, false);
                graphics.drawString(font, Component.translatable("screen.stabilized_turret.radar_hud_scale"),
                        labelX, panel.top + 132, LABEL_COLOR, false);
                graphics.drawString(font, Component.translatable("screen.stabilized_turret.radar_hud_x"),
                        labelX, panel.top + 158, LABEL_COLOR, false);
                graphics.drawString(font, Component.translatable("screen.stabilized_turret.radar_hud_y"),
                        labelX, panel.top + 184, LABEL_COLOR, false);
                graphics.drawString(font, Component.translatable("screen.stabilized_turret.aa_proximity_fuse_range"),
                        labelX, panel.top + 210, LABEL_COLOR, false);

            }
            case AIRCRAFT -> {
                PanelBounds panel = panelBounds();
                int labelX = panel.left + 240;
                drawCenteredNoShadow(graphics, Component.translatable(
                        "screen.stabilized_turret.aircraft_systems_title"),
                        width / 2, panel.top + 12, 0xFFFFFF);
                graphics.drawString(font, Component.translatable("screen.stabilized_turret.aircraft_preview"), panel.left + 18, panel.top + 33, LABEL_COLOR, false);
                graphics.drawString(font, Component.translatable("screen.stabilized_turret.aircraft_radar_range"), labelX, panel.top + 49, LABEL_COLOR, false);
                graphics.drawString(font, Component.translatable("screen.stabilized_turret.aircraft_radar_horizontal"), labelX, panel.top + 73, LABEL_COLOR, false);
                graphics.drawString(font, Component.translatable("screen.stabilized_turret.aircraft_radar_vertical"), labelX, panel.top + 97, LABEL_COLOR, false);
                graphics.drawString(font, Component.translatable("screen.stabilized_turret.aircraft_fuse_range"), labelX, panel.top + 121, LABEL_COLOR, false);
                graphics.drawString(font, Component.translatable("screen.stabilized_turret.aircraft_fuse_safety"), labelX, panel.top + 145, LABEL_COLOR, false);
                graphics.drawString(font, Component.translatable("screen.stabilized_turret.aircraft_min_target_speed"), labelX, panel.top + 169, LABEL_COLOR, false);
                graphics.drawString(font, Component.translatable("screen.stabilized_turret.aircraft_hud_scale"), labelX, panel.top + 193, LABEL_COLOR, false);
                graphics.drawString(font, Component.translatable("screen.stabilized_turret.aircraft_hud_x"), labelX, panel.top + 217, LABEL_COLOR, false);
                graphics.drawString(font, Component.translatable("screen.stabilized_turret.aircraft_hud_y"), labelX, panel.top + 241, LABEL_COLOR, false);
            }
            case INPUTS -> {
                PanelBounds panel = panelBounds();
                int left = panel.left + 10;
                int top = panel.top + 12;
                int actionWidth = 126;
                int gap = 4;
                int buttonWidth = Math.max(72, (panel.right - panel.left - 20 - actionWidth - gap * 3) / 4);
                int x = left + actionWidth;
                drawCenteredNoShadow(graphics, Component.translatable("screen.stabilized_turret.input_bindings"),
                        width / 2, top, 0xFFFFFF);
                graphics.drawString(font, Component.translatable("screen.stabilized_turret.input_wireless_a"), x + 2, panel.top + 36, color, false);
                graphics.drawString(font, Component.translatable("screen.stabilized_turret.input_wireless_b"), x + buttonWidth + gap + 2, panel.top + 36, color, false);
                graphics.drawString(font, Component.translatable("screen.stabilized_turret.input_normal_remote"), x + (buttonWidth + gap) * 2 + 2, panel.top + 36, color, false);
                graphics.drawString(font, Component.translatable("screen.stabilized_turret.input_advanced_remote"), x + (buttonWidth + gap) * 3 + 2, panel.top + 36, color, false);
                for (int action = 0; action < 11; action++) {
                    String label = font.plainSubstrByWidth(inputActionLabel(action).getString(), actionWidth - 4);
                    graphics.drawString(font, label, left, panel.top + 59 + action * 21, color, false);
                }
            }
            case AIM_POINT -> {
                PanelBounds panel = panelBounds();
                int left = width / 2 - 150;
                int top = panel.top;
                drawCenteredNoShadow(graphics, Component.translatable("screen.stabilized_turret.aim_point_settings"), width / 2, top + 12, 0xFFFFFF);
                graphics.drawString(font, Component.translatable("screen.stabilized_turret.aim_point_preview"), left, top + 37, color, false);
                graphics.drawString(font, Component.translatable("screen.stabilized_turret.aim_point_help"), left, top + 153, 0xFF625A4C, false);
            }
            case DEVICES -> renderDeviceLabels(graphics);
        }
    }

    private void renderDeviceLabels(GuiGraphics graphics) {
        PanelBounds panel = panelBounds();
        drawCenteredNoShadow(graphics,
                Component.translatable("screen.stabilized_turret.devices"),
                width / 2, panel.top + 12, 0xFFFFFF);
        if (pendingUnbind != null) {
            drawCenteredNoShadow(graphics,
                    Component.translatable("screen.stabilized_turret.unbind_confirm_title"),
                    width / 2, panel.top + 60, LABEL_COLOR);
            drawCenteredNoShadow(graphics, deviceDisplayName(pendingUnbind),
                    width / 2, panel.top + 86, LABEL_COLOR);
            drawCenteredNoShadow(graphics,
                    Component.translatable("screen.stabilized_turret.unbind_bearing_warning"),
                    width / 2, panel.top + 112, 0xFF7A342C);
            return;
        }
        if (pendingBearingRole != null) {
            drawCenteredNoShadow(graphics,
                    Component.translatable("screen.stabilized_turret.choose_bearing_role"),
                    width / 2, panel.top + 46, LABEL_COLOR);
            drawCenteredNoShadow(graphics, deviceDisplayName(pendingBearingRole),
                    width / 2, panel.top + 60, LABEL_COLOR);
            return;
        }
        if (pendingCannonMountMode != null) {
            drawCenteredNoShadow(graphics,
                    Component.translatable(
                            "screen.stabilized_turret.choose_cannon_mount_mode"),
                    width / 2, panel.top + 46, LABEL_COLOR);
            drawCenteredNoShadow(graphics, deviceDisplayName(pendingCannonMountMode),
                    width / 2, panel.top + 60, LABEL_COLOR);
            return;
        }
        if (devices.isEmpty()) {
            drawCenteredNoShadow(graphics,
                    Component.translatable("screen.stabilized_turret.devices_empty"),
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
                                "screen.stabilized_turret.device_physical").getString()
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
                Component.translatable("screen.stabilized_turret.device_page",
                        devicePage + 1, devicePageCount()),
                width / 2, panel.bottom - 49, 0xFF625A4C);
    }

    private Component deviceDisplayName(OpenControllerPayload.BoundDevice device) {
        Component type = Component.translatable(
                "screen.stabilized_turret.device_type."
                        + device.slot().toLowerCase(Locale.ROOT));
        var result = Component.empty().append(type);
        if (isCannonMountDevice(device.slot())) {
            String mode = "POD_CONTROL".equals(device.podMode())
                    ? "pod_control" : "default";
            result.append(" (").append(Component.translatable(
                    "screen.stabilized_turret.cannon_mount_mode." + mode)).append(")");
        }
        return result.append(" | ").append(Component.translatable(device.translationKey()));
    }

    private Component inputActionLabel(int action) {
        return Component.translatable("screen.stabilized_turret.input.action." + action);
    }

    private void drawCenteredNoShadow(GuiGraphics graphics, Component text, int centerX, int y, int color) {
        graphics.drawString(font, text, centerX - font.width(text) / 2, y, color, false);
    }

    private void renderAimPointPreview(GuiGraphics graphics) {
        PanelBounds panel = panelBounds();
        int left = width / 2 - 145;
        int top = panel.top + 50;
        int right = width / 2 + 145;
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
        int previewWidth = 186;
        int previewHeight = 142;
        graphics.fill(left, top, left + previewWidth, top + previewHeight, 0xFF393A36);
        graphics.fill(left + 2, top + 2, left + previewWidth - 2, top + previewHeight - 2, 0xFF6B746A);
        int size = Math.max(32, Math.min(126, (int) Math.round(84.0 * radarHudScale / 100.0)));
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
                                ? "message.stabilized_turret.advanced_key_not_bound"
                                : "message.stabilized_turret.normal_key_not_bound"),
                        true
                );
            }
            return true;
        }
        selectRemoteKey(capture.type(), capture.action(), signal);
        return true;
    }
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (capturingRemoteKey != null) {
            RemoteKeyDropdown capture = capturingRemoteKey;
            String signal = ControllerKeyMappingCompat.signalForMouseButton(
                    capture.type(), button);
            if (signal == null) {
                if (minecraft != null && minecraft.player != null) {
                    minecraft.player.displayClientMessage(
                            Component.translatable(capture.type() == RemoteControllerCompat.Type.ADVANCED
                                    ? "message.stabilized_turret.advanced_key_not_bound"
                                    : "message.stabilized_turret.normal_key_not_bound"),
                            true
                    );
                }
            } else {
                selectRemoteKey(capture.type(), capture.action(), signal);
            }
            return true;
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
    public boolean isPauseScreen() {
        return false;
    }

    private static final class OffsetSlider extends AbstractSliderButton {
        private final Component axis;
        private final DoubleConsumer consumer;

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
            graphics.fill(left + 1, top + 1, right - 1, bottom - 1, isHovered() ? 0xFFEAD9AE : 0xFFD8C59B);
            int trackY = top + 19;
            graphics.fill(left + 7, trackY - 1, right - 7, trackY + 2, 0xFF5B554A);
            int handleX = left + 7 + (int) Math.round(value * (width - 14));
            graphics.fill(handleX - 3, trackY - 5, handleX + 4, trackY + 6, 0xFF493F31);
            graphics.fill(handleX - 2, trackY - 4, handleX + 3, trackY + 5, 0xFFC9A34A);
            net.minecraft.client.gui.Font font = Minecraft.getInstance().font;
            graphics.drawString(font, getMessage(), left + (width - font.width(getMessage())) / 2,
                    top + 3, 0xFF2F2A23, false);
        }

        private static double toSlider(double actual) {
            return (roundOffset(actual) + 20.0) / 40.0;
        }

        private static double toActual(double slider) {
            return slider * 40.0 - 20.0;
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

    private enum ButtonTone { NORMAL, GREEN, RED }

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
            int background = switch (tone) {
                case GREEN -> isHovered() ? 0xFF64815A : 0xFF506C49;
                case RED -> isHovered() ? 0xFF8A6258 : 0xFF704E47;
                case NORMAL -> isHovered() ? 0xFF777064 : 0xFF5F5A50;
            };
            if (!active) {
                background = 0xFF4A4842;
            }
            graphics.fill(getX(), getY(), getX() + width, getY() + height, 0xFF302C26);
            graphics.fill(getX() + 1, getY() + 1, getX() + width - 1, getY() + height - 1, PANEL_BRASS);
            graphics.fill(getX() + 2, getY() + 2, getX() + width - 2, getY() + height - 2, background);
            graphics.fill(getX() + 3, getY() + 3, getX() + width - 3, getY() + 4, 0x35FFFFFF);
            graphics.enableScissor(getX() + 4, getY() + 2, getX() + width - 4, getY() + height - 2);
            net.minecraft.client.gui.Font font = Minecraft.getInstance().font;
            graphics.drawString(
                    font,
                    getMessage(),
                    getX() + (width - font.width(getMessage())) / 2,
                    getY() + (height - 8) / 2,
                    active ? 0xFFF4EBD5 : 0xFF9A958A,
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
    }
}
