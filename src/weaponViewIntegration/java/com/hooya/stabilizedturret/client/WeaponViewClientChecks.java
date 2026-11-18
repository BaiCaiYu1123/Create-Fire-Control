package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.network.WeaponViewPayload;
import com.hooya.stabilizedturret.network.MathematicalMissilePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.resources.sounds.TickableSoundInstance;
import net.minecraft.sounds.SoundSource;
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
import net.neoforged.neoforge.client.event.sound.PlaySoundSourceEvent;
import java.nio.file.*;
import java.util.*;

/** Real transformed Camera.setup, native models, mouse input and optional flight-control mixin QA. */
@EventBusSubscriber(modid = StabilizedTurret.MOD_ID, value = Dist.CLIENT)
public final class WeaponViewClientChecks {
    private static int age, wait, stage;
    private static boolean launched, finished;
    private static boolean draining;
    private record DelayedView(int delivery, WeaponViewPayload payload) {}
    private static final List<DelayedView> pendingViews = new ArrayList<>();
    private static Vec3 lastRendered;
    private static final Vec3 TARGET = new Vec3(10000, 230, 10000);
    private static final List<String> TYPES = List.of("farmissile_1tanshe", "agm_1tanshe", "bombtanshe", "heavyrocketexplosiontanshe");
    private static final UUID ID = new UUID(2, 3);
    private static final java.util.concurrent.atomic.AtomicInteger audioSources = new java.util.concurrent.atomic.AtomicInteger();
    private static volatile String audioFailure;

    @SubscribeEvent public static void soundStarted(PlaySoundSourceEvent event) {
        if (!Boolean.getBoolean("create_fire_control.weaponViewClientChecks")) return;
        SoundInstance sound = event.getSound();
        while (sound instanceof dev.ryanhcode.sable.sound.MovingSoundInstanceDelegate delegate) sound = delegate.instance;
        if (sound.getClass().getEnclosingClass() != WeaponViewSound.class) return;
        try {
            int source = ((dev.ryanhcode.sable.mixin.sublevel_sounds.ChannelAccessor) event.getChannel()).getSource();
            require(org.lwjgl.openal.AL10.alGetSourcei(source, org.lwjgl.openal.AL10.AL_SOURCE_STATE)
                    == org.lwjgl.openal.AL10.AL_PLAYING, "Native engine buffer did not start playing");
            require(org.lwjgl.openal.AL10.alGetSourcei(source, org.lwjgl.openal.AL10.AL_SOURCE_RELATIVE) != 0,
                    "Engine channel was left behind in world space");
            require(org.lwjgl.openal.AL10.alGetSourcef(source, org.lwjgl.openal.AL10.AL_GAIN) > 0,
                    "Engine channel had zero output gain");
            audioSources.incrementAndGet();
        } catch (Throwable failure) { audioFailure = failure.toString(); }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void tick(ClientTickEvent.Post event) {
        if (!Boolean.getBoolean("create_fire_control.weaponViewClientChecks") || finished) return;
        Minecraft mc = Minecraft.getInstance();
        try {
            if (++age > 1200) throw new AssertionError("Client check timed out");
            if (!launched && age > 80 && mc.screen != null
                    && mc.screen.getClass().getSimpleName().equals("AccessibilityOnboardingScreen")) {
                mc.setScreen(new TitleScreen()); return;
            }
            if (!launched && age > 80 && mc.screen instanceof TitleScreen && mc.getOverlay() == null) {
                launched = true;
                mc.createWorldOpenFlows().createFreshLevel("WeaponView-" + System.currentTimeMillis(),
                        new LevelSettings("Weapon view QA", GameType.CREATIVE, false, Difficulty.PEACEFUL,
                                true, new GameRules(), WorldDataConfiguration.DEFAULT),
                        new WorldOptions(1234, false, false), WorldPresets::createNormalWorldDimensions, mc.screen);
                return;
            }
            if (mc.level == null || mc.player == null) return;
            if (draining) {
                require(!ClientWeaponViewState.isActive(), "Tail lifetime check re-entered the camera");
                if (++wait >= 130) {
                    require(particles("ACTIVE").isEmpty() && particles("SMOKE").isEmpty(),
                            "Detached native particles stopped aging after leaving the camera");
                    require(ClientWeaponViewState.requiredTrailFarDistance() == 0,
                            "Expired trails retained a distant projection range");
                    finish(mc, null);
                }
                return;
            }
            if (stage == 0) {
                if (++wait < 60) return;
                mc.setScreen(null); mc.getTutorial().setStep(net.minecraft.client.tutorial.TutorialSteps.NONE);
                mc.getToasts().clear(); mc.options.hideGui = true; mc.options.pauseOnLostFocus = false;
                mc.options.bobView().set(false); mc.options.fov().set(70);
                mc.options.getSoundSourceOptionInstance(SoundSource.MASTER).set(1.0);
                mc.options.getSoundSourceOptionInstance(SoundSource.NEUTRAL).set(1.0);
                mc.player.getAbilities().flying = true;
                mc.player.setPos(0, 230, 0);
                mc.getSingleplayerServer().execute(() -> {
                    var player = mc.getSingleplayerServer().getPlayerList().getPlayers().getFirst();
                    player.getAbilities().flying = true; player.teleportTo(0, 230, 0);
                });
                stage = 1; wait = 0;
            }
            mc.player.setDeltaMovement(Vec3.ZERO);
            String type = TYPES.get(stage - 1);
            Vec3 velocity = stage == 2 ? new Vec3(26, 12, 18)
                    : stage == 4 ? new Vec3(4, 32, 8) : new Vec3(34.03, 0, 0);
            Vec3 current = TARGET.add(0, 0, stage * 100).add(velocity.scale(wait));
            var typeId = ResourceLocation.parse("mianbaos_modernwarfare:" + type);
            if (wait == 0) { pendingViews.clear(); lastRendered = null; audioSources.set(0); }
            boolean math = stage <= 2;
            if (math) MathematicalMissileVisuals.accept(new MathematicalMissilePayload(mc.level.dimension().location(),
                    age, List.of(new MathematicalMissilePayload.State(ID, typeId,
                    current.subtract(velocity), current, velocity, 90, 0, 90, 0, 20, 20, 20, 20)), List.of()));
            int delay = switch (wait % 10) { case 3 -> 4; case 4 -> 2; default -> 0; };
            pendingViews.add(new DelayedView(age + delay, new WeaponViewPayload(mc.level.dimension().location(), ID,
                    typeId, current, velocity, age)));
            for (var iterator = pendingViews.iterator(); iterator.hasNext();) {
                DelayedView delivery = iterator.next();
                if (delivery.delivery() <= age) { ClientWeaponViewState.accept(delivery.payload()); iterator.remove(); }
            }
            require(ClientWeaponViewState.isActive(), "Native model unavailable: " + type);
            var camera = mc.gameRenderer.getMainCamera();
            var entity = mc.getCameraEntity();
            Vec3 playerPosition = mc.player.position();
            float playerYaw = mc.player.getYRot(), playerPitch = mc.player.getXRot();
            var perspective = mc.options.getCameraType();
            float before = ClientWeaponViewState.yaw();
            // Turn side-on to inspect both the attached flame and continuous smoke at Mach 2.
            mc.player.turn(wait == 0 ? 600 : 3, 1);
            require(ClientWeaponViewState.yaw() != before, "Mouse did not orbit the weapon");
            require(mc.player.getYRot() == playerYaw && mc.player.getXRot() == playerPitch,
                    "Weapon orbit rotated the player/aircraft input");
            camera.setup(mc.level, entity, false, false, 0.5F);
            Vec3 rendered = ClientWeaponViewState.weaponPosition(0.5F);
            if (lastRendered != null) require(rendered.subtract(lastRendered).dot(velocity) >= -1.0e-5,
                    "The rendered missile flashed backward when delayed poses arrived");
            lastRendered = rendered;
            if (wait == 10) {
                ClientWeaponViewState.accept(new WeaponViewPayload(mc.level.dimension().location(), ID,
                        typeId, current.add(velocity), velocity, age + 1));
                ClientWeaponViewState.accept(new WeaponViewPayload(mc.level.dimension().location(), ID,
                        typeId, current.subtract(velocity.scale(8)), velocity, age - 1));
                if (math) MathematicalMissileVisuals.accept(new MathematicalMissilePayload(mc.level.dimension().location(),
                        age + 1, List.of(new MathematicalMissilePayload.State(ID, typeId,
                        current.subtract(velocity.scale(9)), current.subtract(velocity.scale(8)), velocity,
                        90, 0, 90, 0, 20, 20, 20, 20)), List.of()));
                require(ClientWeaponViewState.weaponPosition(0.95F).equals(rendered),
                        "An arriving packet changed the body pose after this frame's camera setup");
                camera.setup(mc.level, entity, false, false, 0.95F);
                require(camera.getPosition().equals(ClientWeaponViewState.cameraPosition(0.5F)),
                        "Camera and model used different partial ticks within one frame");
            }
            Vec3 offset = rendered.subtract(camera.getPosition());
            require(offset.length() >= 10.4 && offset.length() < 60,
                    "Camera did not orbit the remote weapon: camera=" + camera.getPosition() + " distance=" + offset.length());
            require(offset.normalize().dot(new Vec3(camera.getLookVector())) > 0.9999,
                    "Camera orientation was overwritten by another camera mixin");
            require(camera.isDetached(), "Weapon view must render as third person");
            require(mc.getCameraEntity() == entity && mc.player.position().equals(playerPosition)
                    && mc.options.getCameraType() == perspective, "Observation changed player/camera ownership");
            // ClientLevel.hasChunk always returns true, including missing chunks.
            require(mc.level.getChunkSource().getChunk(625, 625,
                    net.minecraft.world.level.chunk.status.ChunkStatus.FULL, false) == null,
                    "Observation loaded the weapon's client chunk");
            if (++wait == 65) {
                verifyExhaust(mc, stage != 3);
                verifyAudio(mc, typeId, current, velocity, stage != 3);
                if (net.neoforged.fml.ModList.get().isLoaded("create_flight_control")) {
                    Class<?> flight = Class.forName("ace.flight.neoforge.client.MouseFlightControllerClient");
                    require(Arrays.stream(flight.getDeclaredMethods()).anyMatch(m -> m.getName().contains("observerOwnsView")),
                            "Optional flight-control observer mixin did not apply");
                    require(!(boolean) flight.getMethod("isActive").invoke(null), "Mouse flight must yield to observation");
                }
                Files.createDirectories(Path.of("weapon-view-qa"));
                try (var image = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
                    image.writeToFile(Path.of("weapon-view-qa", "stage-" + stage + ".png"));
                }
                verifyExitAndSwitch(mc, typeId, current, velocity, stage != 3);
                MathematicalMissileVisuals.accept(new MathematicalMissilePayload(mc.level.dimension().location(),
                        age, List.of(), List.of(ID)));
                camera.setup(mc.level, entity, false, false, 0.5F);
                require(camera.getPosition().distanceTo(mc.player.position()) < 10, "Release did not restore player view");
                if (++stage > TYPES.size()) { draining = true; wait = 0; return; }
                wait = 0;
            }
        } catch (Throwable failure) { finish(mc, failure); }
    }
    private static void verifyExhaust(Minecraft mc, boolean powered) throws Exception {
        var flameField = WeaponViewExhaust.class.getDeclaredField("flame"); flameField.setAccessible(true);
        var smokeField = WeaponViewExhaust.class.getDeclaredField("SMOKE"); smokeField.setAccessible(true);
        var flame = (net.minecraft.client.particle.Particle) flameField.get(null);
        var smoke = (Collection<?>) smokeField.get(null);
        if (!powered) {
            require(flame == null, "An unpowered bomb acquired missile exhaust");
            for (Object particle : smoke) {
                var detached = particle.getClass().getDeclaredField("detached"); detached.setAccessible(true);
                require(detached.getBoolean(particle), "A bomb generated new smoke instead of preserving old trails");
            }
            return;
        }
        require(flame != null && flame.isAlive(), "Missing attached flame in remote observation");
        float partial = mc.getTimer().getGameTimeDeltaPartialTick(true);
        Vec3 nozzle = WeaponViewExhaust.nozzle(ClientWeaponViewState.weaponPosition(partial));
        var modelField = ClientWeaponViewState.class.getDeclaredField("visual"); modelField.setAccessible(true);
        var model = (net.minecraft.world.entity.Entity) modelField.get(null);
        Vec3 actualModelTail = ClientWeaponViewState.weaponPosition(partial).add(WeaponViewExhaust.sampleNozzle(model));
        require(nozzle.distanceTo(actualModelTail) < 0.01,
                "Exhaust missed the native renderer's transformed rear section");
        if (stage == 1) require(ClientWeaponViewState.weaponPosition(partial).distanceTo(nozzle) > 1.4,
                "Long-range missile exhaust was attached inside the body instead of at its model tail");
        require(flame.getBoundingBox().getCenter().distanceTo(nozzle) < 1.0e-6,
                "Flame lags behind the rendered nozzle at Mach 2");
        require(!smoke.isEmpty() && smoke.size() <= 8192, "Native smoke missing or unbounded");
        var originalField = flame.getClass().getDeclaredField("original"); originalField.setAccessible(true);
        require(originalField.get(flame).getClass().getName().startsWith("net.mcreator.myfirstmod.client.particle."),
                "Exhaust no longer uses the installed native particle provider");
        var nativeFlame = (net.minecraft.client.particle.Particle) originalField.get(flame);
        require(flame.getRenderType() == nativeFlame.getRenderType(), "Native flame shader was changed");
        require(((com.hooya.stabilizedturret.mixin.client.ParticleAccessor) nativeFlame).createFireControl$getAlpha() == 1,
                "Native flame alpha was changed");
        var nativeSmoke = (net.minecraft.client.particle.Particle) originalField.get(smoke.iterator().next());
        require(((com.hooya.stabilizedturret.mixin.client.ParticleAccessor) nativeSmoke).createFireControl$getLifetime() == 124,
                "Native smoke lifetime was shortened");
        require(((com.hooya.stabilizedturret.mixin.client.SingleQuadParticleAccessor) nativeSmoke).createFireControl$getQuadSize() >= 1,
                "Native smoke was rescaled");
    }
    private static Collection<?> particles(String fieldName) throws Exception {
        var field = WeaponViewExhaust.class.getDeclaredField(fieldName); field.setAccessible(true);
        return (Collection<?>) field.get(null);
    }

    private static List<TickableSoundInstance> flightSounds() throws Exception {
        var field = WeaponViewSound.class.getDeclaredField("ACTIVE"); field.setAccessible(true);
        return new ArrayList<>((Collection<TickableSoundInstance>) field.get(null));
    }

    private static void tickFlightSound() throws Exception {
        var field = ClientWeaponViewState.class.getDeclaredField("visual"); field.setAccessible(true);
        WeaponViewSound.tick((net.minecraft.world.entity.Entity) field.get(null));
    }

    private static void verifyAudio(Minecraft mc, ResourceLocation type, Vec3 position, Vec3 velocity,
                                    boolean powered) throws Exception {
        require(audioFailure == null, "OpenAL audio check failed: " + audioFailure);
        var sounds = flightSounds();
        if (!powered) {
            require(sounds.isEmpty() && audioSources.get() == 0, "An unpowered bomb acquired engine audio");
            return;
        }
        // The installed native sample lasts 2.71 s before the native 1..1.5 pitch is applied.
        require(!sounds.isEmpty() && sounds.size() <= 60, "Missing or accumulating observer engine samples");
        require(audioSources.get() > 5, "No audible native engine channels at the remote missile camera");
        for (var sound : sounds) {
            require(sound.getLocation().toString().equals("mianbaos_modernwarfare:missile_engine_far"),
                    "Observation replaced the native engine sound");
            require(sound.isRelative() && sound.getAttenuation() == SoundInstance.Attenuation.NONE,
                    "The moving camera can outrun an engine sample");
            require(sound.getSource() == SoundSource.NEUTRAL && sound.getPitch() >= 1 && sound.getPitch() <= 1.5,
                    "Native sound category or pitch range was changed");
        }
        if (stage <= 2) {
            // Public phase metadata must still stop engine audio while the private camera stays active.
            int engineAge = stage == 1 ? -1 : 51;
            MathematicalMissileVisuals.accept(new MathematicalMissilePayload(mc.level.dimension().location(),
                    age + 2, List.of(new MathematicalMissilePayload.State(ID, type,
                    position.subtract(velocity), position, velocity, 90, 0, 90, 0,
                    60, engineAge, engineAge, engineAge)), List.of()));
            tickFlightSound();
            require(ClientWeaponViewState.isActive() && flightSounds().isEmpty()
                    && sounds.stream().allMatch(TickableSoundInstance::isStopped),
                    "Unignited or burned-out missile kept playing its engine");
            MathematicalMissileVisuals.accept(new MathematicalMissilePayload(mc.level.dimension().location(),
                    age + 3, List.of(new MathematicalMissilePayload.State(ID, type,
                    position.subtract(velocity), position, velocity, 90, 0, 90, 0, 20, 20, 20, 20)), List.of()));
            tickFlightSound();
            require(!flightSounds().isEmpty(), "Engine audio did not resume after ignition");
        }
    }

    private static void verifyExitAndSwitch(Minecraft mc, ResourceLocation type, Vec3 position, Vec3 velocity,
                                            boolean powered) throws Exception {
        var existing = new ArrayList<>(particles("ACTIVE"));
        var sounds = flightSounds();
        ClientWeaponViewState.clear();
        require(flightSounds().isEmpty() && sounds.stream().allMatch(TickableSoundInstance::isStopped),
                "Leaving the camera retained observer audio");
        require(particles("ACTIVE").containsAll(existing), "Leaving the camera erased an entire trail segment");
        var frozen = new IdentityHashMap<Object, Vec3>();
        for (Object particle : existing) {
            var original = particle.getClass().getDeclaredField("original"); original.setAccessible(true);
            var detached = particle.getClass().getDeclaredField("detached"); detached.setAccessible(true);
            require(detached.getBoolean(particle), "Released trail still follows a camera session");
            frozen.put(particle, ((net.minecraft.client.particle.Particle) original.get(particle)).getPos());
        }
        if (powered) {
            // Another weapon, and then the same UUID again, in the same tick.
            // Neither transition may drag the old emission cohort to the new camera.
            for (UUID id : List.of(new UUID(2, 99), ID)) {
                ClientWeaponViewState.accept(new WeaponViewPayload(mc.level.dimension().location(), id, type,
                        position.add(1000, 50, 1000), velocity, mc.level.getGameTime()));
                ClientWeaponViewState.prepareExhaust(null);
                tickFlightSound();
                require(!flightSounds().isEmpty(), "New observation failed to start its own engine samples");
                for (var entry : frozen.entrySet()) {
                    var particle = (net.minecraft.client.particle.Particle) entry.getKey();
                    var original = particle.getClass().getDeclaredField("original"); original.setAccessible(true);
                    require(((net.minecraft.client.particle.Particle) original.get(particle)).getPos().equals(entry.getValue()),
                            "Old trails moved when entering another missile or repressing in the same tick");
                    require(particle.getBoundingBox().getCenter().distanceTo(entry.getValue()) < 0.2,
                            "A detached particle reattached to a later observation session");
                }
                var switchedSounds = flightSounds();
                ClientWeaponViewState.clear();
                require(switchedSounds.stream().allMatch(TickableSoundInstance::isStopped),
                        "Switching missiles retained the previous observer's sound");
            }
        }
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static void finish(Minecraft mc, Throwable failure) {
        finished = true;
        if (failure != null) StabilizedTurret.LOGGER.error("WEAPON VIEW CLIENT CHECK FAILED", failure);
        try { Files.writeString(Path.of("weapon-view-client-result.txt"), failure == null ? "PASS" : "FAIL: " + failure); }
        catch (Exception error) { throw new RuntimeException(error); }
        finally { mc.stop(); }
    }
}
