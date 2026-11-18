package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.network.MathematicalMissilePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.*;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import java.nio.file.*;
import java.util.*;

/** Actual client model/particle factory smoke test and review screenshots. */
@EventBusSubscriber(modid = StabilizedTurret.MOD_ID, value = Dist.CLIENT)
public final class MathematicalMissileClientChecks {
    private static int age, wait, stage;
    private static boolean launched, finished;
    private static net.minecraft.world.entity.Entity camera;
    private static final List<String> TYPES = List.of("farmissile_1tanshe", "farmissile_2tanshe",
            "missile_2tanshe", "medium_aim_missile_2tanshe", "closemissile_1tanshe", "closemissile_2tanshe",
            "mica_em_tanshe", "aim_120_tanshe", "agm_1tanshe", "agm_2tanshe", "agm_3tanshe", "agm_4tanshe",
            "missiletanshe", "antiairmissiletanshe", "cruisemissiletanshe", "cruisemissilelowtanshe",
            "nuclearcruisemissiletanshe", "groundnuclearcruisemissiletanshe", "groundmissiletanshe",
            "groundmissile_2tanshe", "groundmissile_3tanshe", "groundmissile_4tanshe");
    private static final List<UUID> IDS = TYPES.stream().map(path -> UUID.nameUUIDFromBytes(path.getBytes(java.nio.charset.StandardCharsets.UTF_8))).toList();
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void tick(ClientTickEvent.Post event) {
        if (!Boolean.getBoolean("create_fire_control.missileClientChecks") || finished) return;
        Minecraft mc = Minecraft.getInstance();
        try {
            age++;
            if (!launched && age > 80 && mc.screen != null && mc.screen.getClass().getSimpleName().equals("AccessibilityOnboardingScreen")) { mc.setScreen(new TitleScreen()); return; }
            if (!launched && age > 80 && mc.screen instanceof TitleScreen && mc.getOverlay() == null) {
                launched = true;
                mc.createWorldOpenFlows().createFreshLevel("MissileMath-" + System.currentTimeMillis(),
                        new LevelSettings("Missile math QA", GameType.CREATIVE, false, Difficulty.PEACEFUL, true,
                                new GameRules(), WorldDataConfiguration.DEFAULT), new WorldOptions(1234, false, false),
                        WorldPresets::createNormalWorldDimensions, mc.screen);
                return;
            }
            if (mc.level == null || mc.player == null) return;
            if (stage == 0) {
                if (++wait < 60) return;
                mc.setScreen(null); mc.getTutorial().setStep(net.minecraft.client.tutorial.TutorialSteps.NONE);
                mc.getToasts().clear(); mc.options.hideGui = true; mc.options.pauseOnLostFocus = false;
                mc.options.bobView().set(false); mc.options.fov().set(70);
                mc.player.getAbilities().flying = true;
                mc.player.setPos(0, 230, 0); mc.player.setYRot(0); mc.player.setXRot(0);
                mc.getSingleplayerServer().execute(() -> {
                    var player = mc.getSingleplayerServer().getPlayerList().getPlayers().getFirst();
                    player.getAbilities().flying = true; player.teleportTo(0, 230, 0);
                });
                stage = 1; wait = 0;
            }
            mc.player.setDeltaMovement(Vec3.ZERO); mc.player.setYRot(0); mc.player.setXRot(0);
            List<MathematicalMissilePayload.State> states = new ArrayList<>();
            Vec3 frameVelocity = stage == 1 ? new Vec3(.15, 0, .05) : new Vec3(34.03, 0, 0);
            Vec3 translation = frameVelocity.scale(wait);
            if (stage == 2) {
                if (camera == null) camera = net.minecraft.world.entity.EntityType.ARMOR_STAND.create(mc.level);
                camera.setPos(translation.x, 230, translation.z);
                camera.setYRot(0); camera.setXRot(0); camera.setOldPosAndRot();
                mc.setCameraEntity(camera);
            }
            for (int i = 0; i < TYPES.size(); i++) {
                Vec3 position = new Vec3((i % 6 - 2.5) * 4, 236 - i / 6 * 3.5, 23 + i / 6 * 2).add(translation);
                // Stable grid to inspect all native model variants. Stage 2 additionally
                // exercises the full 2-Mach prepared smoke segment away from the camera.
                Vec3 velocity = frameVelocity;
                states.add(new MathematicalMissilePayload.State(IDS.get(i), ResourceLocation.parse("mianbaos_modernwarfare:" + TYPES.get(i)),
                        position.subtract(velocity), position, velocity, 90, 0, 90, 0, 20, 20, 20, 20));
            }
            MathematicalMissileVisuals.accept(new MathematicalMissilePayload(mc.level.dimension().location(), age, states, List.of()));
            require(IDS.stream().allMatch(id -> MathematicalMissileVisuals.find(id) != null), "Missing native model adapters");
            if (++wait == 35) {
                verifyParticles(mc);
                Files.createDirectories(Path.of("missile-math-qa"));
                try (var image = Screenshot.takeScreenshot(mc.getMainRenderTarget())) { image.writeToFile(Path.of("missile-math-qa", "stage-" + stage + ".png")); }
                if (stage == 1) { stage = 2; wait = 0; }
                else {
                    MathematicalMissileVisuals.accept(new MathematicalMissilePayload(mc.level.dimension().location(), age, List.of(), IDS));
                    require(IDS.stream().allMatch(id -> MathematicalMissileVisuals.find(id) == null), "Removal left model adapters alive");
                    finish(mc, null);
                }
            }
            require(age < 5000, "Client check timed out");
        } catch (Throwable failure) { finish(mc, failure); }
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void verifyParticles(Minecraft mc) throws Exception {
        var field = net.minecraft.client.particle.ParticleEngine.class.getDeclaredField("particles");
        field.setAccessible(true);
        var queues = (Map<?, ?>) field.get(mc.particleEngine);
        List<Object> particles = new ArrayList<>();
        for (Object queue : queues.values()) for (Object particle : (Iterable<?>) queue) particles.add(particle);
        var pending = net.minecraft.client.particle.ParticleEngine.class.getDeclaredField("particlesToAdd"); pending.setAccessible(true);
        for (Object particle : (Iterable<?>) pending.get(mc.particleEngine)) particles.add(particle);
        int visible = 0, future = 0;
        var clock = MathematicalMissileVisuals.class.getDeclaredField("animationTick"); clock.setAccessible(true);
        long now = clock.getLong(null);
        for (Object particle : particles) {
            if (!particle.getClass().getName().contains("MathematicalMissileVisuals$ScheduledParticle")) continue;
            var originalField = particle.getClass().getDeclaredField("original"); originalField.setAccessible(true);
            var atField = particle.getClass().getDeclaredField("at"); atField.setAccessible(true);
            var startedField = particle.getClass().getDeclaredField("started"); startedField.setAccessible(true);
            Object original = originalField.get(particle);
            require(original.getClass().getName().startsWith("net.mcreator.myfirstmod.client.particle."), "Particle provider was substituted");
            if (startedField.getBoolean(particle)) visible++;
            if (!startedField.getBoolean(particle) && atField.getDouble(particle) > now + 1) {
                future++;
                require(((com.hooya.stabilizedturret.mixin.client.ParticleAccessor) original).createFireControl$getAge() == 0,
                        "Future native particles aged before their display time");
            }
        }
        require(visible > 0, "No scheduled native particle reached rendering/ticking");
        require(future > 0, "No native particles prepared for the next frame");
        StabilizedTurret.LOGGER.info("MATH MISSILE CLIENT: {} started native particles, {} future particles with age zero", visible, future);
    }
    private static void finish(Minecraft mc, Throwable failure) {
        finished = true;
        if (failure != null) StabilizedTurret.LOGGER.error("MATH MISSILE CLIENT CHECK FAILED", failure);
        try { Files.writeString(Path.of("mathematical-missile-client-result.txt"), failure == null ? "PASS" : "FAIL: " + failure); }
        catch (Exception error) { throw new RuntimeException(error); }
        finally { mc.stop(); }
    }
}
