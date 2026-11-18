package com.hooya.stabilizedturret.content.cannon.munition;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.mixin.AnnihilationLivingEntityAccessor;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Persistent UUID tombstones prevent revival/reinsertion of erased boss entities. */
public final class AnnihilationEntityRemoval extends SavedData {
    private static final Factory<AnnihilationEntityRemoval> FACTORY =
            new Factory<>(AnnihilationEntityRemoval::new, AnnihilationEntityRemoval::load, null);
    private final Set<UUID> erased = new HashSet<>();

    public static AnnihilationEntityRemoval get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(FACTORY,
                "create_fire_control_annihilated_entities");
    }

    public boolean contains(UUID uuid) { return erased.contains(uuid); }

    public static void hit(ServerLevel level, Entity target, DamageSource damage) {
        if (hasDied(target)) return;
        // Players use the normal death/respawn protocol rather than having their session erased.
        if (target instanceof ServerPlayer) {
            target.hurt(damage, AnnihilationProjectile.DAMAGE);
            return;
        }
        try {
            target.hurt(damage, AnnihilationProjectile.DAMAGE);
        } catch (RuntimeException error) {
            StabilizedTurret.LOGGER.debug("Damage failed for {}; applying forced removal", target.getType(), error);
        }
        // Successful one-hit kills retain normal death events, loot and corpse animation.
        if (hasDied(target)) return;
        AnnihilationEntityRemoval data = get(level);
        data.erased.add(target.getUUID());
        data.setDirty();
        ((AnnihilationErasure) target).stabilizedTurret$erase();
    }

    private static boolean hasDied(Entity target) {
        return target.isRemoved() || target instanceof LivingEntity living
                && living.getHealth() <= 0F
                && (((AnnihilationLivingEntityAccessor) living).stabilizedTurret$hasDied() || living.deathTime > 0);
    }

    private static AnnihilationEntityRemoval load(CompoundTag tag, HolderLookup.Provider registries) {
        AnnihilationEntityRemoval data = new AnnihilationEntityRemoval();
        for (Tag uuid : tag.getList("Erased", Tag.TAG_INT_ARRAY)) data.erased.add(NbtUtils.loadUUID(uuid));
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (UUID uuid : erased) list.add(NbtUtils.createUUID(uuid));
        tag.put("Erased", list);
        return tag;
    }
}
