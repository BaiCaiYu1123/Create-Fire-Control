package com.hooya.stabilizedturret.mixin;

import com.hooya.stabilizedturret.compat.mianbao.MianbaoCannonCompat;
import com.hooya.stabilizedturret.compat.mianbao.MianbaoMountedAutocannonContraption;
import net.minecraft.core.Direction;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import com.simibubi.create.content.contraptions.Contraption;
import rbasamoyai.createbigcannons.cannon_control.contraption.AbstractMountedCannonContraption;
import rbasamoyai.createbigcannons.cannon_control.contraption.PitchOrientedContraptionEntity;
import rbasamoyai.createbigcannons.cannons.CannonContraptionProviderBlock;
import rbasamoyai.createbigcannons.cannons.InteractableCannonBlock;
import rbasamoyai.createbigcannons.crafting.casting.CannonCastShape;

/** Makes the eight selected Mianbao blocks visible to CBC cannon mounts. */
@Pseudo
@Mixin(
        targets = {
                "net.mcreator.myfirstmod.block.AutocannonbreechBlock",
                "net.mcreator.myfirstmod.block.AutocannonwidebarrelBlock",
                "net.mcreator.myfirstmod.block.AutocannonmidbarrelBlock",
                "net.mcreator.myfirstmod.block.AutocannonfinebarrelBlock",
                "net.mcreator.myfirstmod.block.AutocannonmuzzlebrakeBlock",
                "net.mcreator.myfirstmod.block.RotarycannonbreechBlock",
                "net.mcreator.myfirstmod.block.RotarycannonbarrelBlock",
                "net.mcreator.myfirstmod.block.RotarygunbarrelBlock"
        },
        remap = false
)
public abstract class MianbaoCannonBlockMixin
        implements CannonContraptionProviderBlock, InteractableCannonBlock {
    /**
     * Mianbao's barrel blocks opt out of physical collision even though they
     * already expose an accurate model-sized outline shape. Create contraption
     * collision uses the physical shape, so expose that outline as collision
     * for both placed and assembled cannon parts.
     */
    public VoxelShape getCollisionShape(
            BlockState state,
            BlockGetter level,
            BlockPos pos,
            CollisionContext context
    ) {
        return state.getShape(level, pos, context);
    }

    @Override
    public AbstractMountedCannonContraption getCannonContraption() {
        return new MianbaoMountedAutocannonContraption();
    }

    @Override
    public Direction getFacing(BlockState state) {
        return MianbaoCannonCompat.facing(state);
    }

    @Override
    public CannonCastShape getCannonShape() {
        Block self = (Block) (Object) this;
        return MianbaoCannonCompat.id(self).getPath().endsWith("breech")
                ? CannonCastShape.AUTOCANNON_BREECH
                : CannonCastShape.AUTOCANNON_BARREL;
    }

    @Override
    public boolean isComplete(BlockState state) {
        return true;
    }

    @Override
    public boolean onInteractWhileAssembled(
            Player player,
            net.minecraft.core.BlockPos localPos,
            Direction side,
            InteractionHand hand,
            Level level,
            Contraption contraption,
            BlockEntity blockEntity,
            StructureTemplate.StructureBlockInfo info,
            PitchOrientedContraptionEntity entity
    ) {
        Block self = (Block) (Object) this;
        if (!MianbaoCannonCompat.id(self).getPath().endsWith("breech")
                || !(contraption instanceof MianbaoMountedAutocannonContraption cannon)) {
            return false;
        }
        return cannon.interactWithMagazine(player, hand, level, entity);
    }
}
