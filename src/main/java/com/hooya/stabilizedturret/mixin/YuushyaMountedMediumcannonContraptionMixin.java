package com.hooya.stabilizedturret.mixin;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.compat.yuushya.YuushyaCannonCompat;
import com.hooya.stabilizedturret.compat.yuushya.YuushyaCannonRecoilAnimation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import riftyboi.cbcmodernwarfare.cannon_control.contraption.MountedMediumcannonContraption;
import riftyboi.cbcmodernwarfare.cannons.medium_cannon.MediumcannonBlock;
import riftyboi.cbcmodernwarfare.cannons.medium_cannon.material.MediumcannonMaterial;
import rbasamoyai.createbigcannons.cannon_control.contraption.AbstractMountedCannonContraption;

import java.util.Map;

@Mixin(MountedMediumcannonContraption.class)
public abstract class YuushyaMountedMediumcannonContraptionMixin {
    @Inject(method = "collectCannonBlocks", at = @At("RETURN"))
    private void stabilizedTurret$includeXMuzzleEndpoint(
            Level level,
            BlockPos assemblyOrigin,
            CallbackInfoReturnable<Boolean> cir
    ) {
        if (!cir.getReturnValueZ()) {
            return;
        }

        MountedMediumcannonContraption cannon =
                (MountedMediumcannonContraption) (Object) this;
        BlockState originState = level.getBlockState(assemblyOrigin);
        if (!(originState.getBlock() instanceof MediumcannonBlock originBlock)
                || originBlock.getFacing(originState).getAxis() != Direction.Axis.X) {
            return;
        }

        MediumcannonMaterial material =
                originBlock.getMediumcannonMaterialInLevel(level, originState, assemblyOrigin);
        Map<BlockPos, StructureTemplate.StructureBlockInfo> blocks = cannon.getBlocks();
        int added = 0;
        for (Direction direction : new Direction[]{Direction.EAST, Direction.WEST}) {
            for (int distance = 1;
                 distance <= AbstractMountedCannonContraption.getMaxCannonLength();
                 distance++) {
                BlockPos worldPos = assemblyOrigin.relative(direction, distance);
                BlockPos localPos = worldPos.subtract(assemblyOrigin);
                BlockState state = level.getBlockState(worldPos);
                if (!(state.getBlock() instanceof MediumcannonBlock block)
                        || block.getFacing(state).getAxis() != Direction.Axis.X
                        || block.getMediumcannonMaterialInLevel(level, state, worldPos) != material) {
                    break;
                }
                if (blocks.containsKey(localPos)) {
                    continue;
                }
                if (!YuushyaCannonCompat.isBarrel(state)) {
                    break;
                }

                CompoundTag nbt = null;
                BlockEntity sourceEntity = level.getBlockEntity(worldPos);
                if (sourceEntity != null) {
                    nbt = sourceEntity.saveWithFullMetadata(level.registryAccess());
                    nbt.remove("x");
                    nbt.remove("y");
                    nbt.remove("z");
                }
                StructureTemplate.StructureBlockInfo info =
                        new StructureTemplate.StructureBlockInfo(localPos, state, nbt);
                blocks.put(localPos, info);
                if (nbt != null) {
                    BlockEntity copied = BlockEntity.loadStatic(
                            localPos, state, nbt, level.registryAccess()
                    );
                    if (copied != null) {
                        cannon.presentBlockEntities.put(localPos, copied);
                    }
                }
                added++;
            }
        }
        if (added > 0) {
            StabilizedTurret.LOGGER.info(
                    "Included {} X-axis Yuushya cannon endpoint block(s) omitted by CBC-MW",
                    added
            );
        }
    }

    @Inject(method = "assemble", at = @At("RETURN"))
    private void stabilizedTurret$fitBoundsToYuushyaEndpoints(
            Level level,
            BlockPos assemblyOrigin,
            CallbackInfoReturnable<Boolean> cir
    ) {
        if (!cir.getReturnValueZ()) {
            return;
        }
        MountedMediumcannonContraption cannon =
                (MountedMediumcannonContraption) (Object) this;
        if (cannon.getBlocks().isEmpty()) {
            return;
        }

        AABB completeBounds = null;
        for (BlockPos pos : cannon.getBlocks().keySet()) {
            AABB blockBounds = new AABB(
                    pos.getX(), pos.getY(), pos.getZ(),
                    pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1
            );
            completeBounds = completeBounds == null
                    ? blockBounds
                    : completeBounds.minmax(blockBounds);
        }
        cannon.bounds = completeBounds;
    }

    @Inject(method = "isConnectedToCannon", at = @At("HEAD"), cancellable = true)
    private void stabilizedTurret$connectYuushyaEndpoint(
            LevelAccessor level,
            BlockState candidateState,
            BlockPos candidatePos,
            Direction traversalDirection,
            MediumcannonMaterial material,
            CallbackInfoReturnable<Boolean> cir
    ) {
        BlockPos previousPos = candidatePos.relative(traversalDirection.getOpposite());
        BlockState previousState = level.getBlockState(previousPos);
        boolean involvesYuushyaBarrel = YuushyaCannonCompat.isBarrel(candidateState)
                || YuushyaCannonCompat.isBarrel(previousState);
        if (!involvesYuushyaBarrel
                || !(candidateState.getBlock() instanceof MediumcannonBlock candidate)
                || !(previousState.getBlock() instanceof MediumcannonBlock previous)
                || candidate.getFacing(candidateState).getAxis() != traversalDirection.getAxis()
                || previous.getFacing(previousState).getAxis() != traversalDirection.getAxis()
                || candidate.getMediumcannonMaterialInLevel(
                        level, candidateState, candidatePos
                ) != material
                || previous.getMediumcannonMaterialInLevel(
                        level, previousState, previousPos
                ) != material
                || !candidate.canConnectToSide(
                        candidateState, traversalDirection.getOpposite()
                )
                || !previous.canConnectToSide(previousState, traversalDirection)) {
            return;
        }

        // Yuushya modelling blocks are intended to behave as a continuous
        // barrel once this cannon type is selected. Do not let a stale saved
        // ItemCannonBehavior face drop the muzzle-end block from assembly.
        cir.setReturnValue(true);
    }

    @Inject(method = "animate", at = @At("TAIL"))
    private void stabilizedTurret$startYuushyaBarrelRecoil(CallbackInfo ci) {
        MountedMediumcannonContraption cannon = (MountedMediumcannonContraption) (Object) this;
        Direction muzzleDirection = cannon.initialOrientation();
        for (var entry : cannon.presentBlockEntities.entrySet()) {
            startRecoil(entry.getValue(), muzzleDirection);
            BlockEntity renderedEntity = cannon.getBlockEntityClientSide(entry.getKey());
            if (renderedEntity != entry.getValue()) {
                startRecoil(renderedEntity, muzzleDirection);
            }
        }
    }

    private static void startRecoil(BlockEntity blockEntity, Direction muzzleDirection) {
        if (blockEntity instanceof YuushyaCannonRecoilAnimation animation) {
            animation.stabilizedTurret$startRecoil(muzzleDirection);
        }
    }
}
