package com.hooya.stabilizedturret.content.loader;

import com.hooya.stabilizedturret.content.controller.BindingRef;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.Contraption;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import rbasamoyai.createbigcannons.multiloader.NetworkPlatform;
import rbasamoyai.createbigcannons.network.ClientboundUpdateContraptionPacket;

import java.util.UUID;
import java.lang.reflect.Method;

/** Loaded reflectively only when Create Big Cannons is present. */
public final class MzLoaderCbcSyncCompat {
    public static void sync(
            CannonAutoLoader loader,
            Entity cannonEntity,
            BlockEntity breech
    ) {
        if (loader == null || loader.getLevel() == null || cannonEntity == null || breech == null) return;
        BindingRef binding = loader.getCannonMountBinding();
        UUID subLevelId = binding == null ? null : binding.subLevelId();
        sync(loader.getLevel(), subLevelId, cannonEntity, breech);
    }

    /** Shared Sable tracking bridge for non-block autoloaders such as a maid task. */
    public static void sync(
            Level level,
            UUID subLevelId,
            Entity cannonEntity,
            BlockEntity breech
    ) {
        if (level == null || subLevelId == null || cannonEntity == null || breech == null) return;
        if (!(SubLevelContainer.getContainer(level).getSubLevel(subLevelId)
                instanceof ServerSubLevel subLevel)) return;
        if (!(cannonEntity instanceof AbstractContraptionEntity contraptionEntity)
                || !(contraptionEntity.getContraption() instanceof Contraption contraption)) return;
        Object rawStart;
        try {
            Method method = contraption.getClass().getMethod("getStartPos");
            rawStart = method.invoke(contraption);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return;
        }
        if (!(rawStart instanceof BlockPos start)) return;
        StructureTemplate.StructureBlockInfo oldInfo = contraption.getBlocks().get(start);
        if (oldInfo == null) return;

        CompoundTag data = breech.saveWithFullMetadata(cannonEntity.level().registryAccess());
        data.remove("x");
        data.remove("y");
        data.remove("z");
        StructureTemplate.StructureBlockInfo updated = new StructureTemplate.StructureBlockInfo(
                oldInfo.pos(), oldInfo.state(), data);
        contraption.getBlocks().put(start, updated);
        ClientboundUpdateContraptionPacket packet = ClientboundUpdateContraptionPacket.entity(
                contraptionEntity, start, updated);
        for (UUID playerId : subLevel.getTrackingPlayers()) {
            if (subLevel.getLevel().getPlayerByUUID(playerId) instanceof ServerPlayer player) {
                NetworkPlatform.sendToClientPlayer(packet, player);
            }
        }
    }

    private MzLoaderCbcSyncCompat() {
    }
}
