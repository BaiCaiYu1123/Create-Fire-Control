package com.hooya.stabilizedturret.content.display;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.controller.GuidedMissileMode;
import com.hooya.stabilizedturret.content.controller.MathematicalMissileManager;
import com.hooya.stabilizedturret.registry.ModBlocks;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.simulated_team.simulated.util.SimAssemblyHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Chicken;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import org.joml.Vector3d;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

/** Runs the production scan with real entity storage, Sable plots and missile adapters. */
@EventBusSubscriber(modid = StabilizedTurret.MOD_ID)
public final class RadarScanIntegration {
    @SubscribeEvent
    public static void start(ServerStartedEvent event) {
        Throwable failure = null;
        try {
            if (Boolean.getBoolean("create_fire_control.radarMemoryChecks"))
                com.hooya.stabilizedturret.content.controller.RadarMemoryIntegrationChecks.check(event.getServer().overworld());
            else if (Boolean.getBoolean("create_fire_control.missileLockToggleChecks"))
                com.hooya.stabilizedturret.content.controller.MissileLockToggleIntegrationChecks.check(event.getServer().overworld());
            else if (Boolean.getBoolean("create_fire_control.radarColdAcquisitionOnly"))
                com.hooya.stabilizedturret.content.controller.RadarColdAcquisitionChecks.check(event.getServer().overworld());
            else if (Boolean.getBoolean("create_fire_control.radarOcclusionChecks"))
                com.hooya.stabilizedturret.content.controller.RadarVisibilityIntegrationChecks.check(event.getServer().overworld());
            else check(event.getServer().overworld());
        } catch (Throwable error) {
            failure = error;
            StabilizedTurret.LOGGER.error("DISPLAY RADAR SCAN CHECK FAILED", error);
        } finally {
            try {
                String result = failure == null
                        ? Boolean.getBoolean("create_fire_control.radarMemoryChecks")
                        ? "PASS: radar 3/2-second memory, reacquisition, expiry, manual cancel, power off, real-contact/guidance/RWR separation"
                        : Boolean.getBoolean("create_fire_control.missileLockToggleChecks")
                        ? "PASS: shared radar/IR/AGM occlusion switch; default off; initial acquisition, sustained locks, same-tick launch readiness; repeated toggles behind one unchanged wall; both AGM types; fluids; no chunk loads"
                        : Boolean.getBoolean("create_fire_control.radarColdAcquisitionOnly")
                        ? Boolean.getBoolean("create_fire_control.radarColdCrowdOnly")
                        ? "PASS: four radars with 20,000 cold targets each, bounded shared work/IO/queues/caches, zero chunk loads"
                        : "PASS: cold 4km aircraft/ground relative-motion matrix, live-shaped high-altitude discovery, 19km saved real terrain, continuous tracks, bounded reads and zero chunk loads"
                        : Boolean.getBoolean("create_fire_control.radarOcclusionChecks")
                        ? "PASS: radar terrain/fluids/physical plots, all scan entry points, RWR illumination/stale contacts/no extra rays, IR/air-to-ground lock and launch occlusion, saved terrain, load limits and mass-target budget"
                        : "PASS: original SectionPos overflow reproduced; root/plot living and missiles, "
                        + "mathematical missiles, world projection, range, deduplication, own missiles and remote plot"
                        : "FAIL: " + failure;
                Files.writeString(Path.of("radar-scan-result.txt"), result);
                System.out.println(result);
            } catch (Exception error) {
                throw new RuntimeException(error);
            } finally {
                event.getServer().halt(false);
            }
        }
    }

    private static void check(ServerLevel level) throws Exception {
        BlockPos displayPos = new BlockPos(0, 240, 0);
        level.setChunkForced(0, 0, true);
        level.getChunk(displayPos);
        level.setBlockAndUpdate(displayPos, ModBlocks.CONNECTED_DISPLAY.get().defaultBlockState());
        var display = (ConnectedDisplayBlockEntity) level.getBlockEntity(displayPos);
        require(display != null, "display block entity exists");
        Vec3 origin = Vec3.atCenterOf(displayPos);

        Chicken rootLiving = chicken(level, origin.add(30, 0, 0));
        Chicken distantLiving = chicken(level, origin.add(3000, 0, 0));
        Entity rootMissile = missile(level, origin.add(60, 0, 0), false);
        Entity mathMissile = missile(level, origin.add(90, 0, 0), true);
        Entity ownMissile = missile(level, origin.add(120, 0, 0), false);
        Method ownerToken = ConnectedDisplayBlockEntity.class.getDeclaredMethod("cruiseOwnershipToken");
        ownerToken.setAccessible(true);
        ownMissile.getPersistentData().putString("CreateFireControlCruiseOwner", (String) ownerToken.invoke(display));

        BlockPos anchor = new BlockPos(160, 240, 0);
        level.getChunk(anchor);
        level.setBlockAndUpdate(anchor, Blocks.IRON_BLOCK.defaultBlockState());
        var assembly = SimAssemblyHelper.assembleFromSingleBlock(level, anchor.below(), anchor, false, false);
        require(assembly != null, "Sable plot assembled");
        var body = (ServerSubLevel) assembly.subLevel();
        require(body.getLevel() == level, "Sable plots share root entity storage");
        BlockPos localAnchor = anchor.offset(assembly.offset());
        Chicken plotLiving = chicken(level, Vec3.atCenterOf(localAnchor).add(0, 2, 0));
        Entity plotMissile = missile(level, Vec3.atCenterOf(localAnchor).add(0, 4, 0), false);
        require(Sable.HELPER.getContaining(plotLiving) == body, "living entity is in plot coordinates");
        Vec3 expectedLiving = Sable.HELPER.projectOutOfSubLevel(level, plotLiving.getBoundingBox().getCenter());
        Vec3 expectedMissile = Sable.HELPER.projectOutOfSubLevel(level, plotMissile.getBoundingBox().getCenter());

        for (int x = 0; x <= 10; x++) level.getChunk(x, 0);

        scan(display, 10);
        contact(display, rootLiving, rootLiving.getBoundingBox().getCenter());
        contact(display, rootMissile, rootMissile.getBoundingBox().getCenter());
        contact(display, mathMissile, mathMissile.getBoundingBox().getCenter());
        contact(display, plotLiving, expectedLiving);
        contact(display, plotMissile, expectedMissile);
        absent(display, distantLiving, "outside radar range");
        absent(display, ownMissile, "own cruise missile");

        // Recreate the old scan's inverse query for a far-away loaded plot.
        // A SectionPos x of 2^21-1 makes the exclusive upper key wrap negative.
        Vector3d localOrigin = new Vector3d(origin.x, origin.y, origin.z);
        body.logicalPose().transformPositionInverse(localOrigin);
        body.logicalPose().position().add(localOrigin.x - 33_554_431.0, 0, 0);
        body.updateLastPose();
        body.updateBoundingBox();
        body.forceUpdateGlobalBounds();
        localOrigin.set(origin.x, origin.y, origin.z);
        body.logicalPose().transformPositionInverse(localOrigin);
        require(Math.abs(localOrigin.x - 33_554_431.0) < .01, "old query crosses signed section boundary");
        AABB oldQuery = new AABB(localOrigin.x, localOrigin.y, localOrigin.z,
                localOrigin.x, localOrigin.y, localOrigin.z).inflate(2000);
        boolean reproduced = false;
        try {
            level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class, oldQuery);
        } catch (IllegalArgumentException expected) {
            reproduced = expected.getMessage().contains("Start element")
                    && expected.getMessage().contains("end element");
            System.out.println("Reproduced original crash: " + expected);
        }
        require(reproduced, "original fastutil section-index crash reproduced");

        // Advance beyond the contact hold window: remote plot entities must
        // disappear, while normal and unmanaged mathematical contacts survive.
        scan(display, 100);
        contact(display, rootLiving, rootLiving.getBoundingBox().getCenter());
        contact(display, rootMissile, rootMissile.getBoundingBox().getCenter());
        contact(display, mathMissile, mathMissile.getBoundingBox().getCenter());
        absent(display, plotLiving, "far-away plot living entity");
        absent(display, plotMissile, "far-away plot missile");

        body.logicalPose().orientation().rotateY(Math.PI / 3);
        body.forceUpdateGlobalBounds();
        scan(display, 150);
        contact(display, rootLiving, rootLiving.getBoundingBox().getCenter());
        contact(display, mathMissile, mathMissile.getBoundingBox().getCenter());
    }

    public static Chicken chicken(ServerLevel level, Vec3 position) throws Exception {
        Chicken entity = EntityType.CHICKEN.create(level);
        require(entity != null, "chicken created");
        entity.setNoAi(true);
        entity.setPos(position);
        makeAccessible(level, entity);
        require(level.addFreshEntity(entity), "living entity added");
        // Sable projects newly joined plot entities out during its spawn hook.
        // Restore raw plot coordinates to exercise the scan's projection path.
        entity.setPos(position);
        return entity;
    }

    private static Entity missile(ServerLevel level, Vec3 position, boolean mathematical) throws Exception {
        var id = ResourceLocation.fromNamespaceAndPath("mianbaos_modernwarfare", "cruisemissiletanshe");
        require(BuiltInRegistries.ENTITY_TYPE.containsKey(id), "native cruise missile type registered");
        Entity entity = BuiltInRegistries.ENTITY_TYPE.get(id).create(level);
        require(entity != null, "missile created");
        entity.setPos(position);
        entity.setDeltaMovement(.5, 0, 0);
        makeAccessible(level, entity);
        entity.getPersistentData().putBoolean("CreateFireControlCruiseGuided", true);
        entity.getPersistentData().putString(GuidedMissileMode.ROUTE, mathematical ? "math" : "entity");
        entity.getPersistentData().putInt("CreateFireControlMissileFlightRoutePolicy", 1);
        level.addFreshEntity(entity);
        if (!mathematical) entity.setPos(position);
        require(mathematical ? MathematicalMissileManager.contains(entity)
                        && level.getEntity(entity.getUUID()) == null
                        : level.getEntity(entity.getUUID()) == entity,
                "missile uses requested entity/math route");
        return entity;
    }

    private static void makeAccessible(ServerLevel level, Entity entity) throws Exception {
        level.getChunk(entity.blockPosition());
        // These synchronous checks run before the first chunk-status tick.
        // Publish the fixture chunks to the real entity storage immediately.
        var field = ServerLevel.class.getDeclaredField("entityManager");
        field.setAccessible(true);
        var manager = (PersistentEntitySectionManager<?>) field.get(level);
        manager.updateChunkStatus(entity.chunkPosition(), FullChunkStatus.ENTITY_TICKING);
    }

    private static void scan(ConnectedDisplayBlockEntity display, long now) throws Exception {
        Method scan = ConnectedDisplayBlockEntity.class.getDeclaredMethod("scan", long.class);
        scan.setAccessible(true);
        scan.invoke(display, now);
    }

    private static void contact(ConnectedDisplayBlockEntity display, Entity entity, Vec3 expected) {
        var hits = display.getContacts().stream().filter(c -> c.key().id().equals(entity.getUUID())).toList();
        require(hits.size() == 1, "one contact for " + entity.getType() + " at " + entity.position());
        require(hits.getFirst().center().distanceTo(expected) < 1e-6, "contact uses world coordinates");
    }

    private static void absent(ConnectedDisplayBlockEntity display, Entity entity, String message) {
        require(display.getContacts().stream().noneMatch(c -> c.key().id().equals(entity.getUUID())), message);
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
