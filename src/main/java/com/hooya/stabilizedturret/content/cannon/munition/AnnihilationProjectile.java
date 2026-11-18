package com.hooya.stabilizedturret.content.cannon.munition;

import com.hooya.stabilizedturret.registry.ModCbcContent;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.entity.PartEntity;
import rbasamoyai.createbigcannons.munitions.config.components.BallisticPropertiesComponent;
import rbasamoyai.createbigcannons.munitions.config.components.EntityDamagePropertiesComponent;
import riftyboi.cbcmodernwarfare.munitions.medium_cannon.apfsds.APFSDSMediumcannonProjectile;

import java.util.ArrayList;

/** Medium-cannon APFSDS launch and rendering, with an uncapped erasing flight sweep. */
public final class AnnihilationProjectile extends StabilizedApfsdsProjectile {
    public static final float DAMAGE = 2_100_000_000F;
    public static final int LIFETIME_TICKS = 10 * 20;
    private static final String EXPIRY_TAG = "AnnihilationExpiryTick";
    private static final EntityDamagePropertiesComponent ENTITY_DAMAGE =
            new EntityDamagePropertiesComponent(DAMAGE, false, true, true, 0F);
    private static final BallisticPropertiesComponent BALLISTICS =
            new BallisticPropertiesComponent(0, .01, false, 10F,
                    Float.MAX_VALUE, Float.MAX_VALUE, 0);
    private long expiryTick = Long.MIN_VALUE;

    public AnnihilationProjectile(EntityType<? extends APFSDSMediumcannonProjectile> type, Level level) {
        super(type, level);
    }

    @Override
    public ItemStack getItem() {
        // Inventory uses the supplied cartridge model; flight keeps the existing APFSDS dart.
        return ModCbcContent.APFSDS_MEDIUM_CANNON_ROUND.get().getDefaultInstance();
    }

    @Override
    public EntityDamagePropertiesComponent getDamageProperties() {
        return ENTITY_DAMAGE;
    }

    @Override
    protected BallisticPropertiesComponent getBallisticProperties() {
        return BALLISTICS;
    }

    @Override
    protected Vec3 getForces(Vec3 position, Vec3 velocity) {
        // Keep APFSDS drag and speed scaling, without dimension/Sable gravity.
        return velocity.normalize().scale(-getDragForce());
    }

    public long expiryTick() {
        if (expiryTick == Long.MIN_VALUE) expiryTick = level().getGameTime() + LIFETIME_TICKS;
        return expiryTick;
    }

    @Override
    public void tick() {
        if (!level().isClientSide && level().getGameTime() >= expiryTick()) {
            discard();
            return;
        }
        super.tick();
    }

    @Override
    protected void clipAndDamage() {
        if (!(level() instanceof ServerLevel server)) return;
        Vec3 from = position(), velocity = getDeltaMovement();
        Vec3 to = from.add(velocity).add(getForces(from, velocity).scale(.5));
        if (!finite(from) || !finite(to)) {
            discard();
            return;
        }
        eraseSegment(server, from, to);
        // Sable blocks reside in plot coordinates; sweeping the world alone misses them.
        // Snapshot the candidates because deleting the final block can remove a sublevel.
        var bodies = new ArrayList<dev.ryanhcode.sable.sublevel.SubLevel>();
        for (var body : SubLevelContainer.getContainer(server)
                .queryIntersecting(new BoundingBox3d(new AABB(from, to).inflate(.2)))) {
            bodies.add(body);
        }
        for (var body : bodies) {
            if (!body.isRemoved()) {
                eraseSegment(server, body.logicalPose().transformPositionInverse(from),
                        body.logicalPose().transformPositionInverse(to));
            }
        }
    }

    private void eraseSegment(ServerLevel server, Vec3 from, Vec3 to) {
        // DDA visits every crossed voxel, including non-colliding and unbreakable blocks.
        // Returning null deliberately continues to the end, without CBC's 100-hit cap.
        BlockGetter.traverseBlocks(from, to, server, (world, cursor) -> {
            if (!world.isOutsideBuildHeight(cursor) && world.hasChunkAt(cursor)
                    && !world.getBlockState(cursor).isAir()) {
                var pos = cursor.immutable();
                // Remove inventories first: container onRemove callbacks must not drop them.
                world.removeBlockEntity(pos);
                world.setBlock(pos, Blocks.AIR.defaultBlockState(),
                        Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS);
            }
            return (Boolean) null;
        }, world -> null);

        double radius = getBbWidth() * .5;
        AABB sweep = new AABB(from, to).inflate(radius + 1);
        for (Entity candidate : server.getEntities(this, sweep, entity -> !entity.isRemoved())) {
            Entity target = candidate instanceof PartEntity<?> part ? part.getParent() : candidate;
            if (!(target instanceof LivingEntity) || target == getOwner() || target.isRemoved()) continue;
            AABB bounds = candidate.getBoundingBox().inflate(radius);
            if (bounds.contains(from) || bounds.clip(from, to).isPresent()) {
                AnnihilationEntityRemoval.hit(server, target, getEntityDamage(target));
            }
        }
    }

    private static boolean finite(Vec3 vector) {
        return Double.isFinite(vector.x) && Double.isFinite(vector.y) && Double.isFinite(vector.z);
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putLong(EXPIRY_TAG, expiryTick());
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        expiryTick = tag.contains(EXPIRY_TAG) ? tag.getLong(EXPIRY_TAG) : Long.MIN_VALUE;
    }
}
