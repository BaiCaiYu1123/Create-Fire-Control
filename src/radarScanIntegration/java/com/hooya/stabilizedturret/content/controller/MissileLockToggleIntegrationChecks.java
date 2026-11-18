package com.hooya.stabilizedturret.content.controller;

import com.hooya.stabilizedturret.config.FireControlSettings;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** Targeted real-chunk and Sable-body regression for the common radar/IR/AGM obstruction toggle. */
public final class MissileLockToggleIntegrationChecks {
    public static void check(ServerLevel level) throws Exception {
        long time = level.getGameTime();
        boolean original = FireControlSettings.RADAR_RAYCAST.get();
        if (FireControlSettings.RADAR_RAYCAST.getDefault()) throw new AssertionError("Occlusion must still default to off");
        Vec3 origin = new Vec3(320.5, 200.75, 320.5);
        BlockPos wall = BlockPos.containing(origin).east(8);
        SubLevel body = null;
        try {
            FireControlSettings.RADAR_RAYCAST.set(true);
            for (int x = 20; x <= 24; x++) level.getChunk(x, 20);
            level.setBlockAndUpdate(wall, Blocks.AIR.defaultBlockState());
            body = RadarVisibilityIntegrationChecks.body(level, origin.add(48, 0, 0));
            RadarVisibilityIntegrationChecks.checkMissileLocks(level, origin, body, wall);
        } finally {
            FireControlSettings.RADAR_RAYCAST.set(original);
            if (body != null) SubLevelContainer.getContainer(level).removeSubLevel(body, SubLevelRemovalReason.REMOVED);
            level.setBlockAndUpdate(wall, Blocks.AIR.defaultBlockState());
            level.getServer().getWorldData().overworldData().setGameTime(time);
        }
    }
    private MissileLockToggleIntegrationChecks() {}
}
