package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity;
import com.hooya.stabilizedturret.registry.ModBlocks;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.physics.constraint.FixedConstraintConfiguration;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.ticket.SubLevelLoadingTicketType;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Unit;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import java.util.ArrayList;

/** Dense native leaf models exceed both former block and vertex limits without complex physics shapes. */
final class PreviewLargeStructureFixture {
    static final int LENGTH=256,HEIGHT=48,WIDTH=16,BLOCKS=LENGTH*HEIGHT*WIDTH;
    static StabilizerControllerBlockEntity create(ServerLevel level) {
        var player=level.getServer().getPlayerList().getPlayers().getFirst();
        player.getAbilities().flying=true;player.getAbilities().mayfly=true;player.onUpdateAbilities();
        BlockPos base=new BlockPos(256,128,0);
        var state=Blocks.OAK_LEAVES.defaultBlockState().setValue(BlockStateProperties.PERSISTENT,true);
        var blocks=new ArrayList<BlockPos>(BLOCKS);
        for(int x=0;x<LENGTH;x++)for(int y=0;y<HEIGHT;y++)for(int z=0;z<WIDTH;z++) {
            BlockPos pos=base.offset(x,y,z);level.setBlock(pos,state,2|16);blocks.add(pos);
        }
        BlockPos controllerPos=base.offset(LENGTH/2,HEIGHT-1,WIDTH/2);
        level.setBlock(controllerPos,ModBlocks.STABILIZER_CONTROLLER.get().defaultBlockState(),2|16);
        var body=SubLevelAssemblyHelper.assembleBlocks(level,controllerPos,blocks,BoundingBox3i.from(blocks));
        if(body==null)throw new AssertionError("large native structure failed to assemble");
        ((ServerSubLevelContainer)SubLevelContainer.getContainer(level)).addForceLoadTicket(body,
                SubLevelLoadingTicketType.COMMAND_FORCED,Unit.INSTANCE);
        BlockPos origin=body.getPlot().getCenterBlock();
        var local=new Vector3d(origin.getX(),origin.getY(),origin.getZ());
        var world=body.logicalPose().transformPosition(new Vector3d(local));
        SubLevelPhysicsSystem.require(level).getPipeline().addConstraint(null,body,
                new FixedConstraintConfiguration(world,local,new Quaterniond(body.logicalPose().orientation())));
        return (StabilizerControllerBlockEntity)level.getBlockEntity(origin);
    }
    private PreviewLargeStructureFixture() {}
}
