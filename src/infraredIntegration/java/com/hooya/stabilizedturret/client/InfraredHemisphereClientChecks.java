package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.content.controller.InfraredGeometry;
import com.hooya.stabilizedturret.content.controller.InfraredTargeting;
import com.hooya.stabilizedturret.network.InfraredStatePayload;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Real native render/camera callbacks on a synchronized physical structure and a client seat fixture. */
final class InfraredHemisphereClientChecks {
    private static int stage, frames;
    private static final String[] NAMES = {"hemisphere-front", "hemisphere-look-60", "hemisphere-side-90-limited", "hemisphere-look-120-limited",
            "hemisphere-rear-hidden", "flight-normal-command", "flight-free-camera-path", "flight-creative-free-gui4-fov110",
            "flight-free-initial-direction-path", "flight-behind-marker-no-fallback", "ir-empty-hidden", "ir-reloaded-green",
            "flight-disabled-no-ghost", "flight-reenabled-free", "radar-without-infrared", "radar-with-infrared"};
    private static final List<String> LOG = new ArrayList<>(List.of("stage,expectedX,expectedY,seekerX,seekerY,active"));
    private static final String[] GLOBALS = {"active", "mouseControlEnabled", "viewLockEnabled", "hasCompensatedView", "targetDirection",
            "freeLookDirection", "compensatedThirdPerson", "compensatedCameraType", "compensatedShoulderSurfing", "compensatedShoulderFollowAligned",
            "previousRidingRenderOrientation", "lastCompensatedYaw", "lastCompensatedPitch", "lastCameraFollowNanos", "stabilizedTargetFrame",
            "stabilizedNoseCrosshair", "shoulderFinalWorldOverride", "shoulderRenderOverride"};

    static boolean render(RenderGuiEvent.Post event, Minecraft mc, UUID aircraftId, BlockPos pos) throws Exception {
        var g = event.getGuiGraphics();
        g.fill(0, 0, g.guiWidth(), g.guiHeight(), 0xFF101720);
        if (stage <= 4) {
            double angle = Math.toRadians(new double[]{0, 60, 90, 120, 180}[stage]);
            Vec3 forward = new Vec3(Math.sin(angle), 0, -Math.cos(angle));
            var frame = new InfraredTargeting.Frame(Vec3.ZERO, forward, forward.cross(new Vec3(0, 1, 0)),
                    new Vec3(0, 1, 0), Math.tan(Math.toRadians(35)), 16.0 / 9).withAircraft(Vec3.ZERO, new Vec3(0, 0, -1));
            double sx = stage == 0 ? 1600 : stage == 4 ? 1280 : stage == 3 ? 1800 : 5000;
            Vec3 limited = frame.limitSeeker(sx, 720);
            Vec3 circle = frame.rangeHudCircle();
            if (stage == 0 && (limited == null || limited.x != sx || circle == null || Math.abs(circle.z - InfraredGeometry.RADIUS) > 1e-6))
                throw new AssertionError("Reference-sized finite circular guide or on-disc seeker missing");
            if (stage >= 1 && stage <= 3) {
                if (circle == null || limited == null || Math.hypot(limited.x - circle.x, limited.y - circle.y) > circle.z + 1e-6
                        || circle.z <= InfraredGeometry.RADIUS || circle.z > 4 * InfraredGeometry.RADIUS)
                    throw new AssertionError("Circular guide or constrained centre mismatch at view angle " + angle + ": " + limited + "/" + circle);
            }
            InfraredHudRenderer.renderRings(g, InfraredGeometry.GREEN, sx, 720, frame);
            if (stage == 4 && InfraredHudMesh.lastEmittedSegments() != 0) throw new AssertionError("Rear view drew a false forward lock circle");
        } else if (stage >= 14) radarRender(event, mc);
        else nativeRender(event, mc, aircraftId, pos);
        g.flush();
        if (++frames < 4) return false;
        frames = 0;
        try (var image = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
            image.writeToFile(Path.of("infrared-hud-qa", NAMES[stage] + ".png"));
        }
        stage++;
        if (stage == 7) { mc.options.guiScale().set(4); mc.options.fov().set(110); mc.resizeDisplay(); }
        if (stage == 8) { mc.options.guiScale().set(1); mc.options.fov().set(70); mc.resizeDisplay(); }
        if (stage < NAMES.length) return false;
        Files.write(Path.of("infrared-native-camera-path.csv"), LOG);
        Files.writeString(Path.of("infrared-hemisphere-client-result.txt"), "PASS: independent native/infrared projections, native target-circle pixels unchanged against inactive-IR reference, original command projection in free look, IR mouse-first direction independent, radar world frames with IR selected, view-dependent growing circles, ordinary/creative terminals, GUI1/4 FOV70/110, initial/hidden/off/re-enabled states and empty ammo");
        return true;
    }

    private static void nativeRender(RenderGuiEvent.Post event, Minecraft mc, UUID aircraftId, BlockPos pos) throws Exception {
        Class<?> hud = Class.forName("ace.flight.neoforge.client.MouseFlightControllerClient");
        Class<?> terminalType = Class.forName("ace.flight.block.MouseFlightControllerBlockEntity");
        boolean creative = stage == 7;
        Class<?> type = creative ? Class.forName("ace.flight.block.CreativeFlightControlComputerBlockEntity") : terminalType;
        String id = creative ? "creative_flight_control_computer" : "mouse_flight_controller";
        var state = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("create_flight_control:" + id)).defaultBlockState();
        var entity = (BlockEntity) type.getConstructor(BlockPos.class, net.minecraft.world.level.block.state.BlockState.class).newInstance(pos, state);
        entity.setLevel(mc.level);
        Object terminal = creative ? type.getMethod("getFlightTerminal").invoke(entity) : entity;
        ((BlockEntity) terminal).setLevel(mc.level);
        var facing = terminalType.getField("bodyForwardFacing");
        facing.set(terminal, facing.getType().getMethod("fromDirection", Direction.class).invoke(null, Direction.NORTH));
        terminalType.getField("noseCrosshairDistance").setDouble(terminal, stage == 8 || stage == 9 ? 0 : 500);
        terminalType.getField("mouseTargetYOffset").setDouble(terminal, .13);
        var seatPos = pos.south();
        mc.level.setBlock(seatPos, BuiltInRegistries.BLOCK.get(ResourceLocation.parse("create:red_seat")).defaultBlockState(), 3);
        if (!Boolean.TRUE.equals(terminalType.getMethod("setLinkedMouseSeat", BlockPos.class).invoke(terminal, seatPos)))
            throw new AssertionError("Real flight terminal refused its same-structure seat fixture");
        var seat = new com.simibubi.create.content.contraptions.actors.seat.SeatEntity(mc.level);
        seat.setPos(Vec3.atCenterOf(seatPos));
        var globals = new LinkedHashMap<Field, Object>();
        for (String name : GLOBALS) {
            try { var field = field(hud, name); globals.put(field, field.get(null)); }
            catch (NoSuchFieldException missing) { if (!name.startsWith("stabilized")) throw missing; }
        }
        var camera = mc.gameRenderer.getMainCamera();
        Vec3 oldCamera = camera.getPosition(), oldPlayer = mc.player.position();
        float oldYaw = camera.getYRot(), oldPitch = camera.getXRot(), oldRoll = camera.getRoll();
        float playerYaw = mc.player.getYRot(), playerPitch = mc.player.getXRot();
        CameraType oldType = mc.options.getCameraType();
        var cameraRotation = Camera.class.getDeclaredMethod("setRotation", float.class, float.class, float.class); cameraRotation.setAccessible(true);
        var cameraPosition = Camera.class.getDeclaredMethod("setPosition", Vec3.class); cameraPosition.setAccessible(true);
        var body = SubLevelContainer.getContainer(mc.level).getSubLevel(aircraftId);
        var pose = body instanceof dev.ryanhcode.sable.sublevel.ClientSubLevel clientBody ? clientBody.renderPose() : body.logicalPose();
        var point = pose.transformPosition(new Vector3d(pos.getX()+.5, pos.getY()+.5, pos.getZ()+.5));
        Vec3 origin = new Vec3(point.x, point.y, point.z);
        boolean free = stage != 5 && stage != 9;
        try {
            if (!mc.player.startRiding(seat, true)) throw new AssertionError("Client seat fixture rejected the rider");
            boolean flightEnabled = stage != 12;
            set(hud, "active", terminal); set(hud, "mouseControlEnabled", flightEnabled); set(hud, "viewLockEnabled", !free);
            set(hud, "hasCompensatedView", stage != 8); set(hud, "compensatedThirdPerson", true);
            mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
            set(hud, "compensatedCameraType", CameraType.THIRD_PERSON_BACK);
            set(hud, "compensatedShoulderSurfing", false); set(hud, "compensatedShoulderFollowAligned", false);
            set(hud, "previousRidingRenderOrientation", new Quaterniond(pose.orientation()));
            set(hud, "lastCompensatedYaw", 180F); set(hud, "lastCompensatedPitch", 0F);
            set(hud, "lastCameraFollowNanos", System.nanoTime()-16_000_000L);
            set(hud, "freeLookDirection", stage == 8 ? null : new Vector3d(0, 0, -1));
            for (String name : new String[]{"stabilizedTargetFrame", "stabilizedNoseCrosshair"})
                try { set(hud, name, null); } catch (NoSuchFieldException ignored) { }
            cameraRotation.invoke(camera, 180F, 0F, 0F);
            cameraPosition.invoke(camera, origin.add(8, 3, 18));
            Vector3d command = stage == 9 ? new Vector3d(0, 0, 1) : new Vector3d(.25, 0, -1).normalize();
            Vector3d commandBefore = new Vector3d(command);
            set(hud, "targetDirection", command);
            if (Boolean.TRUE.equals(hud.getMethod("isActive").invoke(null)) != flightEnabled)
                throw new AssertionError("Native active/seat authority failed: seat=" + seat.blockPosition() + " link="
                        + terminalType.getMethod("getLinkedMouseSeatPos").invoke(terminal) + " body=" + dev.ryanhcode.sable.Sable.HELPER.getContaining(seat));
            InfraredHudRenderer.accept(new InfraredStatePayload(true, stage + 100, stage == 10 ? 0 : 2,
                    false, Long.MIN_VALUE, null, Vec3.ZERO, origin, new Vec3(0, 0, -1)));
            if (stage == 10) {
                if (InfraredHudRenderer.selected()) throw new AssertionError("Zero-ammo payload kept IR HUD selected");
                InfraredHudRenderer.render(event);
                return;
            }
            if (!flightEnabled) {
                invoke(hud, "render", new Class[]{RenderGuiEvent.Post.class}, event);
                if (MouseFlightSeekerCompat.hasFlightAim(event) || MouseFlightSeekerCompat.hasTarget(event))
                    throw new AssertionError("Disabled mouse flight retained its native marker");
                InfraredHudRenderer.render(event);
                return;
            }
            if (free && stage != 8) {
                mc.player.setYRot(202); mc.player.setXRot(7);
                var angles = new ViewportEvent.ComputeCameraAngles(camera, 1, 180, 0, 0);
                invoke(hud, "cameraAngles", new Class[]{ViewportEvent.ComputeCameraAngles.class}, angles);
                cameraRotation.invoke(camera, angles.getYaw(), angles.getPitch(), angles.getRoll());
            }
            Vector3d mouseDirection = (Vector3d) field(hud, "freeLookDirection").get(null);
            Vector3d expected = !free ? command : mouseDirection == null
                    ? (Vector3d) invoke(hud, "targetFromCurrentView", new Class[]{Minecraft.class}, mc)
                    : (Vector3d) invoke(hud, "targetForObservedCamera", new Class[]{Minecraft.class, Vector3d.class}, mc, mouseDirection);
            if (free && stage != 8) {
                Vector3d followed = (Vector3d) invoke(hud, "targetFromCurrentView", new Class[]{Minecraft.class}, mc);
                if (mouseDirection == null || mouseDirection.angle(new Vector3d(0, 0, -1)) < .1
                        || expected.angle(followed) < .001)
                    throw new AssertionError("Seeker is following the camera instead of the native mouse target ahead of it");
            }
            double distance = terminalType.getField("noseCrosshairDistance").getDouble(terminal);
            Object projected = distance > 0 ? invoke(hud, "projectWorldPoint", new Class[]{Minecraft.class, Camera.class, Vector3d.class},
                    mc, camera, new Vector3d(point).fma(distance, expected))
                    : invoke(hud, "projectDirection", new Class[]{Minecraft.class, Camera.class, Vector3d.class}, mc, camera, expected);
            int expectedX = ((Number) record(projected, "x")).intValue(), expectedY = ((Number) record(projected, "y")).intValue();
            boolean visible = Boolean.TRUE.equals(record(projected, "visible"));
            Object nativePoint = distance > 0 ? invoke(hud, "projectWorldPoint", new Class[]{Minecraft.class, Camera.class, Vector3d.class},
                    mc, camera, new Vector3d(point).fma(distance, command))
                    : invoke(hud, "projectDirection", new Class[]{Minecraft.class, Camera.class, Vector3d.class}, mc, camera, command);
            int nativeX = ((Number) record(nativePoint, "x")).intValue(), nativeY = ((Number) record(nativePoint, "y")).intValue();
            boolean nativeVisible = Boolean.TRUE.equals(record(nativePoint, "visible"));
            if (distance <= 0 && stage == 8) {
                nativeX = event.getGuiGraphics().guiWidth()/2;
                nativeY = event.getGuiGraphics().guiHeight()/2 + (int)Math.round(.13*event.getGuiGraphics().guiHeight());
                nativeVisible = true;
            }
            com.mojang.blaze3d.platform.NativeImage reference = null;
            if (frames == 0 && nativeVisible) {
                InfraredHudRenderer.accept(InfraredStatePayload.inactive());
                invoke(hud, "drawTargetCircle", new Class[]{net.minecraft.client.gui.GuiGraphics.class, int.class, int.class}, event.getGuiGraphics(), nativeX, nativeY);
                event.getGuiGraphics().flush();
                reference = Screenshot.takeScreenshot(mc.getMainRenderTarget());
                reference.writeToFile(Path.of("infrared-hud-qa", "native-reference-"+NAMES[stage]+".png"));
                event.getGuiGraphics().fill(0, 0, event.getGuiGraphics().guiWidth(), event.getGuiGraphics().guiHeight(), 0xFF101720);
                InfraredHudRenderer.accept(new InfraredStatePayload(true, stage+100, 2, false, Long.MIN_VALUE, null, Vec3.ZERO, origin, new Vec3(0,0,-1)));
            }
            invoke(hud, "render", new Class[]{RenderGuiEvent.Post.class}, event);
            if (MouseFlightSeekerCompat.hasTarget(event) != nativeVisible)
                throw new AssertionError("IR changed native marker visibility");
            if (nativeVisible) {
                double capturedX = field(MouseFlightSeekerCompat.class,"targetX").getDouble(null);
                double capturedY = field(MouseFlightSeekerCompat.class,"targetY").getDouble(null);
                double scale = event.getGuiGraphics().guiHeight()/1440.0;
                if (Math.abs((capturedX-1280)*scale+event.getGuiGraphics().guiWidth()/2.0-nativeX)>1e-6
                        || Math.abs(capturedY*scale-nativeY)>1e-6)
                    throw new AssertionError("Native target circle followed IR free-look instead of its original command");
            }
            if (reference != null) {
                event.getGuiGraphics().flush();
                try (var actual = Screenshot.takeScreenshot(mc.getMainRenderTarget()); var expectedImage = reference) {
                    actual.writeToFile(Path.of("infrared-hud-qa", "native-only-"+NAMES[stage]+".png"));
                    double guiScale = mc.getWindow().getGuiScale();
                    int cx = (int)Math.round(nativeX*guiScale), cy = (int)Math.round(nativeY*guiScale);
                    for(int py=Math.max(0,cy-36);py<Math.min(actual.getHeight(),cy+36);py++)
                        for(int px=Math.max(0,cx-36);px<Math.min(actual.getWidth(),cx+36);px++)
                            if(actual.getPixelRGBA(px,py)!=expectedImage.getPixelRGBA(px,py))
                                throw new AssertionError("IR recolored or modified the native target circle at "+px+","+py);
                }
            }
            var cameraFrame = (InfraredTargeting.Frame) invoke(InfraredHudRenderer.class, "cameraFrame", new Class[]{Minecraft.class, float.class}, mc, 1F);
            var captured = MouseFlightSeekerCompat.frame(event, cameraFrame);
            if (captured.seekerActive() != visible || !MouseFlightSeekerCompat.hasFlightAim(event))
                throw new AssertionError("Native marker visibility was replaced with an active fixed centre");
            double x = event.getGuiGraphics().guiWidth()/2.0 + (captured.seekerX()-1280)*event.getGuiGraphics().guiHeight()/1440;
            double y = captured.seekerY()*event.getGuiGraphics().guiHeight()/1440;
            if (visible && (Math.abs(x-expectedX)>1e-6 || Math.abs(y-expectedY)>1e-6))
                throw new AssertionError("IR did not follow the native free-look path: " + x + "," + y + " vs " + expectedX + "," + expectedY);
            if (free && visible && Math.hypot(x-event.getGuiGraphics().guiWidth()/2.0, y-event.getGuiGraphics().guiHeight()/2.0)<3)
                throw new AssertionError("Free-look collapsed to a fixed screen-centre marker");
            if (((Vector3d) field(hud, "targetDirection").get(null)).distance(commandBefore) > 1e-12)
                throw new AssertionError("IR free-look rewrote the flight steering command");
            InfraredHudRenderer.renderRings(event.getGuiGraphics(), InfraredGeometry.GREEN,
                    visible ? captured.seekerX() : Double.NaN, visible ? captured.seekerY() : Double.NaN, captured);
            if (frames == 0) LOG.add(NAMES[stage]+","+expectedX+","+expectedY+","+captured.seekerX()+","+captured.seekerY()+","+captured.seekerActive());
        } finally {
            InfraredHudRenderer.accept(InfraredStatePayload.inactive()); mc.player.stopRiding(); mc.player.setPos(oldPlayer);
            mc.player.setYRot(playerYaw); mc.player.setXRot(playerPitch); mc.options.setCameraType(oldType);
            cameraRotation.invoke(camera, oldYaw, oldPitch, oldRoll); cameraPosition.invoke(camera, oldCamera);
            for (var saved : globals.entrySet()) saved.getKey().set(null, saved.getValue());
        }
    }
    private static void radarRender(RenderGuiEvent.Post event, Minecraft mc) throws Exception {
        var saved = new LinkedHashMap<Field,Object>();
        for(String name:new String[]{"active","aircraftMode","origin","forward","up","contacts","selectedTarget","lockedTarget","selectedAircraftWeapon","closeAmmo"}) {
            var f=field(ClientRadarState.class,name);saved.put(f,f.get(null));
        }
        var camera=mc.gameRenderer.getMainCamera();
        Vec3 oldPosition=camera.getPosition();float yaw=camera.getYRot(),pitch=camera.getXRot(),roll=camera.getRoll();
        var rotation=Camera.class.getDeclaredMethod("setRotation",float.class,float.class,float.class);rotation.setAccessible(true);
        var position=Camera.class.getDeclaredMethod("setPosition",Vec3.class);position.setAccessible(true);
        var seat=new com.simibubi.create.content.contraptions.actors.seat.SeatEntity(mc.level);seat.setPos(mc.player.position());
        try {
            if(!mc.player.startRiding(seat,true)) throw new AssertionError("Radar fixture passenger setup failed");
            position.invoke(camera,Vec3.ZERO);rotation.invoke(camera,180F,0F,0F);
            UUID id=UUID.fromString("91a7c324-6656-43fa-84c7-ec1720720192");
            Vec3 target=new Vec3(120,90,-1000);
            var contact=new ClientRadarState.Contact(id,target,Vec3.ZERO,new net.minecraft.world.phys.AABB(target,target).inflate(3),false,
                    com.hooya.stabilizedturret.network.TargetLockTelemetry.UNAVAILABLE);
            set(ClientRadarState.class,"active",true);set(ClientRadarState.class,"aircraftMode",true);
            set(ClientRadarState.class,"origin",Vec3.ZERO);set(ClientRadarState.class,"forward",new Vec3(0,0,-1));set(ClientRadarState.class,"up",new Vec3(0,1,0));
            set(ClientRadarState.class,"contacts",List.of(contact));set(ClientRadarState.class,"selectedTarget",id);set(ClientRadarState.class,"lockedTarget",null);
            set(ClientRadarState.class,"selectedAircraftWeapon",com.hooya.stabilizedturret.content.controller.MianbaoAircraftCompat.WeaponType.CLOSE.ordinal());
            set(ClientRadarState.class,"closeAmmo",2);
            InfraredHudRenderer.accept(stage==14?InfraredStatePayload.inactive():new InfraredStatePayload(true,300,2,false,Long.MIN_VALUE,null,Vec3.ZERO,Vec3.ZERO,new Vec3(0,0,-1)));
            if(stage==15&&!InfraredHudRenderer.selected()) throw new AssertionError("Radar coexistence did not select IR");
            RadarHudRenderer.render(event);
        } finally {
            InfraredHudRenderer.accept(InfraredStatePayload.inactive());mc.player.stopRiding();
            rotation.invoke(camera,yaw,pitch,roll);position.invoke(camera,oldPosition);
            for(var f:saved.entrySet())f.getKey().set(null,f.getValue());
        }
    }
    private static Field field(Class<?> type, String name) throws Exception { var field = type.getDeclaredField(name); field.setAccessible(true); return field; }
    private static void set(Class<?> type, String name, Object value) throws Exception { field(type, name).set(null, value); }
    private static Object record(Object object, String name) throws Exception { return invoke(object.getClass(), name, new Class[0], object); }
    private static Object invoke(Class<?> type, String name, Class<?>[] types, Object... args) throws Exception {
        var method = type.getDeclaredMethod(name, types); method.setAccessible(true);
        if (types.length == 0 && args.length == 1 && type.isInstance(args[0])) return method.invoke(args[0]);
        return method.invoke(null, args);
    }
    private InfraredHemisphereClientChecks() {}
}
