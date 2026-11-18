package com.hooya.stabilizedturret.compat.mianbao;

import com.hooya.stabilizedturret.registry.ModSounds;
import com.hooya.stabilizedturret.registry.ModContraptionTypes;
import com.simibubi.create.api.contraption.ContraptionType;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.AssemblyException;
import com.simibubi.create.content.contraptions.StructureTransform;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Position;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.items.IItemHandler;
import org.apache.commons.lang3.tuple.MutablePair;
import org.jetbrains.annotations.Nullable;
import rbasamoyai.createbigcannons.CBCCompatTransformers;
import rbasamoyai.createbigcannons.cannon_control.ControlPitchContraption;
import rbasamoyai.createbigcannons.cannon_control.cannon_mount.CannonMountBlock;
import rbasamoyai.createbigcannons.cannon_control.cannon_mount.CannonMountBlockEntity;
import rbasamoyai.createbigcannons.cannon_control.cannon_types.CBCCannonContraptionTypes;
import rbasamoyai.createbigcannons.cannon_control.cannon_types.ICannonContraptionType;
import rbasamoyai.createbigcannons.cannon_control.contraption.AbstractMountedCannonContraption;
import rbasamoyai.createbigcannons.cannon_control.contraption.ItemCannon;
import rbasamoyai.createbigcannons.cannon_control.contraption.PitchOrientedContraptionEntity;
import rbasamoyai.createbigcannons.config.CBCConfigs;
import rbasamoyai.createbigcannons.effects.particles.plumes.AutocannonPlumeParticleData;
import rbasamoyai.createbigcannons.index.CBCEntityTypes;
import rbasamoyai.createbigcannons.multiloader.NetworkPlatform;
import rbasamoyai.createbigcannons.munitions.autocannon.AbstractAutocannonProjectile;
import rbasamoyai.createbigcannons.munitions.autocannon.AutocannonAmmoItem;
import rbasamoyai.createbigcannons.munitions.autocannon.ammo_container.AutocannonAmmoContainerItem;
import rbasamoyai.createbigcannons.munitions.autocannon.config.AutocannonProjectilePropertiesComponent;
import rbasamoyai.createbigcannons.network.ClientboundAnimateCannonContraptionPacket;
import rbasamoyai.createbigcannons.remix.GetItemStorage;
import rbasamoyai.createbigcannons.utils.CBCUtils;
import rbasamoyai.ritchiesprojectilelib.RitchiesProjectileLib;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A CBC mounted autocannon contraption backed by Mianbao's original blocks.
 *
 * <p>The Mianbao block entities are retained as virtual block entities so
 * their nine-slot inventories survive assembly and disassembly. CBC rounds
 * are moved into this contraption's five-round input buffer while assembled.
 */
public final class MianbaoMountedAutocannonContraption extends AbstractMountedCannonContraption
        implements ItemCannon, GetItemStorage {
    private static final int INPUT_LIMIT = 5;
    private static final int ROTARY_COAST_TICKS = 20;
    private static final float ROTARY_FULL_SPEED_DEGREES = 60.0F;
    private static final float ROTARY_ALIGNMENT_DEGREES = 90.0F;
    private static final float ROTARY_COAST_NOMINAL_TRAVEL =
            ROTARY_FULL_SPEED_DEGREES * (ROTARY_COAST_TICKS + 1) * 0.5F;
    private static final float AUTOCANNON_RECOIL_DISTANCE = 0.2F;
    private static final int AUTOCANNON_RECOIL_RETURN_TICKS = 3;

    /*
     * Local copy/adaptation of the requested nethersteel rifled-barrel
     * physics. There is deliberately no advanced-tech class, registry or
     * runtime dependency here. The rifled barrel transforms a propellant context,
     * so the context is seeded from CBC's built-in steel-autocannon values.
     */
    private static final float NETHERSTEEL_BASE_SPEED = 3.0F;
    private static final float NETHERSTEEL_INITIAL_GAS = 1.5F;
    private static final float NETHERSTEEL_BASE_SPREAD = 3.0F;
    private static final float NETHERSTEEL_BASE_RECOIL = 3.0F;
    private static final float NETHERSTEEL_GAS_LOSS_PER_BARREL = 0.35F;
    private static final float NETHERSTEEL_DRAG_FACTOR = 0.08F;
    private static final float NETHERSTEEL_SPREAD_MULTIPLIER = 0.45F;
    private static final float NETHERSTEEL_RECOIL_PER_REMAINING_GAS = 0.75F;
    private static final float NETHERSTEEL_WEIGHT_PER_BLOCK = 6.0F;

    /*
     * Local copy of CBC Modern Warfare 0.0.6v's nethersteel rotary-cannon
     * launch values and its MountedRotarycannonContraption speed formula.
     * Keeping primitive constants here avoids any runtime or build dependency
     * on cbcmodernwarfare.
     */
    private static final float ROTARY_NETHERSTEEL_BASE_SPEED = 3.0F;
    private static final float ROTARY_NETHERSTEEL_SPEED_PER_BARREL = 1.5F;
    private static final int ROTARY_NETHERSTEEL_MAX_SPEED_INCREASES = 4;
    private static final int ROTARY_NETHERSTEEL_PROJECTILE_LIFETIME = 85;

    private final Deque<ItemStack> inputBuffer = new ArrayDeque<>();
    private final Set<BlockPos> barrelPositions = new HashSet<>();
    private final Set<BlockPos> rotaryBarrelPositions = new HashSet<>();
    private final IItemHandler itemStorage = new BreechItemHandler();

    private MianbaoCannonCompat.Family family;
    private ResourceLocation barrelId;
    private int barrelCount;
    private int firingCooldown;
    private int controllerFirePower;
    private ItemStack magazine = ItemStack.EMPTY;

    private float rotaryAngle;
    private float previousRotaryAngle;
    private int rotaryCoastTicks;
    private float rotaryCoastStartAngle;
    private float rotaryCoastTravel;
    private float barrelRecoil;
    private float previousBarrelRecoil;

    @Override
    public boolean assemble(Level level, BlockPos pos) throws AssemblyException {
        BlockState startState = level.getBlockState(pos);
        MianbaoCannonCompat.Family startFamily = MianbaoCannonCompat.family(startState);
        if (startFamily == null) {
            return false;
        }

        Direction.Axis axis = MianbaoCannonCompat.facing(startState).getAxis();
        Direction positive = Direction.fromAxisAndDirection(axis, Direction.AxisDirection.POSITIVE);
        List<BlockPos> cannonPositions = collectLine(level, pos, positive);
        if (cannonPositions.size() > getMaxCannonLength()) {
            throw cannonTooLarge();
        }

        BlockPos breechWorldPos = null;
        BlockState breechState = null;
        ResourceLocation foundBarrelId = null;
        int foundBarrels = 0;

        for (BlockPos cannonPos : cannonPositions) {
            BlockState state = level.getBlockState(cannonPos);
            MianbaoCannonCompat.Family stateFamily = MianbaoCannonCompat.family(state);
            if (stateFamily != startFamily) {
                throw assemblyError("mixed_family");
            }
            if (MianbaoCannonCompat.isBreech(state, startFamily)) {
                if (breechWorldPos != null) {
                    throw assemblyError("multiple_breeches");
                }
                breechWorldPos = cannonPos;
                breechState = state;
                continue;
            }
            if (!MianbaoCannonCompat.isAllowedBarrel(state, startFamily)) {
                throw assemblyError("invalid_block");
            }
            ResourceLocation stateId = MianbaoCannonCompat.id(state.getBlock());
            foundBarrelId = stateId;
            ++foundBarrels;
        }

        if (breechWorldPos == null || breechState == null) {
            throw assemblyError("no_breech");
        }
        if (foundBarrels == 0 || foundBarrelId == null) {
            throw assemblyError("no_barrel");
        }

        Direction breechFacing = MianbaoCannonCompat.facing(breechState);
        if (breechFacing.getAxis() != axis) {
            throw assemblyError("incorrect_direction");
        }
        Set<BlockPos> cannonSet = Set.copyOf(cannonPositions);
        for (int i = 0; i <= foundBarrels; ++i) {
            BlockPos expectedPos = breechWorldPos.relative(breechFacing, i);
            if (!cannonSet.contains(expectedPos)) {
                throw assemblyError("incorrect_direction");
            }
            BlockState expectedState = level.getBlockState(expectedPos);
            if (MianbaoCannonCompat.facing(expectedState) != breechFacing) {
                throw assemblyError("incorrect_direction");
            }
            if (i == 0) {
                if (!MianbaoCannonCompat.isBreech(expectedState, startFamily)) {
                    throw assemblyError("incorrect_direction");
                }
            } else if (!MianbaoCannonCompat.isAllowedBarrel(expectedState, startFamily)) {
                throw assemblyError("invalid_block");
            }
        }

        this.family = startFamily;
        BlockPos muzzleBarrelPos = breechWorldPos.relative(breechFacing, foundBarrels);
        this.barrelId = MianbaoCannonCompat.id(level.getBlockState(muzzleBarrelPos).getBlock());
        this.barrelCount = foundBarrels;
        this.initialOrientation = breechFacing;
        this.anchor = pos;
        this.startPos = breechWorldPos.subtract(pos);
        this.blocks.clear();
        this.presentBlockEntities.clear();
        this.actors.clear();
        this.barrelPositions.clear();
        this.rotaryBarrelPositions.clear();

        int minimumAxisCoordinate = 0;
        int maximumAxisCoordinate = 0;
        for (BlockPos worldPos : cannonPositions) {
            BlockState state = level.getBlockState(worldPos);
            CompoundTag nbt = getBlockEntityNBT(level, worldPos);
            BlockPos localPos = worldPos.subtract(pos);
            StructureTemplate.StructureBlockInfo info =
                    new StructureTemplate.StructureBlockInfo(localPos, state, nbt);
            this.blocks.put(localPos, info);

            int axisCoordinate = axisCoordinate(localPos, axis);
            minimumAxisCoordinate = Math.min(minimumAxisCoordinate, axisCoordinate);
            maximumAxisCoordinate = Math.max(maximumAxisCoordinate, axisCoordinate);

            if (nbt != null) {
                BlockEntity blockEntity = BlockEntity.loadStatic(localPos, state, nbt, level.registryAccess());
                if (blockEntity != null) {
                    blockEntity.setLevel(level);
                    this.presentBlockEntities.put(localPos, blockEntity);
                }
            }

            if (MianbaoCannonCompat.isAnyBarrel(state)) {
                BlockPos immutablePos = localPos.immutable();
                this.barrelPositions.add(immutablePos);
                if (MianbaoCannonCompat.isRotaryBarrel(state)) {
                    this.rotaryBarrelPositions.add(immutablePos);
                }
                MovementContext context = new MovementContext(level, info, this);
                this.actors.add(MutablePair.of(info, context));
            }
        }

        this.frontExtensionLength = maximumAxisCoordinate;
        this.backExtensionLength = -minimumAxisCoordinate;
        this.bounds = createBoundsFromExtensionLengths();
        takeInitialAmmunitionFromMianbaoInventory();
        return !this.blocks.isEmpty();
    }

    private static List<BlockPos> collectLine(Level level, BlockPos origin, Direction positive) {
        List<BlockPos> positions = new ArrayList<>();
        positions.add(origin.immutable());
        collectDirection(level, origin, positive, positions);
        collectDirection(level, origin, positive.getOpposite(), positions);
        return positions;
    }

    private static void collectDirection(Level level, BlockPos origin, Direction direction, List<BlockPos> output) {
        BlockPos cursor = origin.relative(direction);
        int remaining = getMaxCannonLength();
        while (remaining-- > 0 && MianbaoCannonCompat.family(level.getBlockState(cursor)) != null) {
            output.add(cursor.immutable());
            cursor = cursor.relative(direction);
        }
    }

    private static int axisCoordinate(BlockPos pos, Direction.Axis axis) {
        return switch (axis) {
            case X -> pos.getX();
            case Y -> pos.getY();
            case Z -> pos.getZ();
        };
    }

    private static AssemblyException assemblyError(String reason) {
        return new AssemblyException(Component.translatable("exception.create_fire_control.mianbao_cannon." + reason));
    }

    private void takeInitialAmmunitionFromMianbaoInventory() {
        Object breech = this.presentBlockEntities.get(this.startPos);
        if (!(breech instanceof Container container)) {
            return;
        }
        for (int slot = 0; slot < container.getContainerSize(); ++slot) {
            ItemStack remainder = container.getItem(slot).copy();
            if (remainder.isEmpty()) {
                continue;
            }
            if (this.magazine.isEmpty() && remainder.getItem() instanceof AutocannonAmmoContainerItem) {
                this.magazine = remainder.copyWithCount(1);
                remainder.shrink(1);
            }
            while (this.inputBuffer.size() < INPUT_LIMIT
                    && isOriginalCbcAutocannonRound(remainder)
                    && !remainder.isEmpty()) {
                this.inputBuffer.addLast(remainder.copyWithCount(1));
                remainder.shrink(1);
            }
            container.setItem(slot, remainder);
        }
        container.setChanged();
    }

    @Override
    public void tick(Level level, PitchOrientedContraptionEntity entity) {
        super.tick(level, entity);
        tickRotaryAnimation();
        tickBarrelRecoil();
        if (this.firingCooldown > 0) {
            --this.firingCooldown;
        }
        ControlPitchContraption controller = entity.getController();
        refreshCannonMountFirePower(level, controller);
        if (level instanceof ServerLevel serverLevel && canBeFiredOnController(controller)) {
            fireShot(serverLevel, entity);
        }
    }

    /**
     * Read the same firing-face signal used by CBC's cannon mount. This keeps
     * firing reliable after assembly and reload even when no new neighbour
     * update is emitted after the contraption entity is attached.
     */
    private void refreshCannonMountFirePower(Level level, ControlPitchContraption controller) {
        if (!(controller instanceof CannonMountBlockEntity mount)) {
            return;
        }
        BlockState mountState = mount.getControllerState();
        if (!mountState.hasProperty(CannonMountBlock.HORIZONTAL_FACING)) {
            return;
        }
        Direction firingFace = mountState.getValue(CannonMountBlock.HORIZONTAL_FACING);
        this.controllerFirePower = level.getSignal(mount.getBlockPos().relative(firingFace), firingFace);
    }

    private void tickRotaryAnimation() {
        this.previousRotaryAngle = this.rotaryAngle;
        if (this.family != MianbaoCannonCompat.Family.ROTARY || this.rotaryCoastTicks <= 0) {
            return;
        }
        int elapsedTicks = ROTARY_COAST_TICKS - this.rotaryCoastTicks + 1;
        float progress = (float) elapsedTicks / ROTARY_COAST_TICKS;
        float easedProgress = 1.0F - (1.0F - progress) * (1.0F - progress);
        this.rotaryAngle = Mth.wrapDegrees(
                this.rotaryCoastStartAngle + this.rotaryCoastTravel * easedProgress);
        --this.rotaryCoastTicks;
    }

    private void tickBarrelRecoil() {
        this.previousBarrelRecoil = this.barrelRecoil;
        if (this.family != MianbaoCannonCompat.Family.AUTOCANNON || this.barrelRecoil <= 0.0F) {
            return;
        }
        this.barrelRecoil = Math.max(
                0.0F,
                this.barrelRecoil - AUTOCANNON_RECOIL_DISTANCE / AUTOCANNON_RECOIL_RETURN_TICKS);
    }

    @Override
    public void animate() {
        super.animate();
        markShotAnimation();
    }

    private void markShotAnimation() {
        if (this.family == MianbaoCannonCompat.Family.ROTARY) {
            this.rotaryCoastTicks = ROTARY_COAST_TICKS;
            this.rotaryCoastStartAngle = this.rotaryAngle;
            float nominalTarget = this.rotaryAngle + ROTARY_COAST_NOMINAL_TRAVEL;
            float alignedTarget = Math.round(nominalTarget / ROTARY_ALIGNMENT_DEGREES)
                    * ROTARY_ALIGNMENT_DEGREES;
            this.rotaryCoastTravel = alignedTarget - this.rotaryAngle;
        } else if (this.family == MianbaoCannonCompat.Family.AUTOCANNON) {
            this.barrelRecoil = AUTOCANNON_RECOIL_DISTANCE;
        }
    }

    public float getRotaryAngle(float partialTicks) {
        return Mth.rotLerp(partialTicks, this.previousRotaryAngle, this.rotaryAngle);
    }

    public float getBarrelRecoil(float partialTicks) {
        if (this.family != MianbaoCannonCompat.Family.AUTOCANNON) {
            return 0.0F;
        }
        return Mth.lerp(partialTicks, this.previousBarrelRecoil, this.barrelRecoil);
    }

    public boolean isBarrelPosition(BlockPos localPos) {
        return this.barrelPositions.contains(localPos);
    }

    public boolean isRotaryBarrelPosition(BlockPos localPos) {
        return this.rotaryBarrelPositions.contains(localPos);
    }

    public boolean hasRotaryBarrels() {
        return !this.rotaryBarrelPositions.isEmpty();
    }

    public boolean hasAnimatedBarrels() {
        return !this.barrelPositions.isEmpty();
    }

    @Override
    public void fireShot(ServerLevel level, PitchOrientedContraptionEntity entity) {
        if (this.family == null || this.barrelId == null || this.initialOrientation == null || this.startPos == null) {
            return;
        }
        if (canBeFiredOnController(entity.getController()) && this.controllerFirePower <= 0) {
            return;
        }
        if (this.firingCooldown > 0) {
            return;
        }

        ItemStack foundProjectile = extractNextInput();
        if (!(foundProjectile.getItem() instanceof AutocannonAmmoItem round)
                || !isOriginalCbcAutocannonRound(foundProjectile)) {
            return;
        }

        this.firingCooldown = this.family.shotIntervalTicks();
        markShotAnimation();

        Vec3 breechCenter = entity.toGlobalVector(Vec3.atCenterOf(this.startPos), 0.0F);
        Vec3 forwardPoint = entity.toGlobalVector(
                Vec3.atCenterOf(this.startPos.relative(this.initialOrientation)), 0.0F);
        Vec3 forward = forwardPoint.subtract(breechCenter).normalize();
        Vec3 structureCenter = entity.toGlobalVector(Vec3.atCenterOf(BlockPos.ZERO), 0.0F);
        Vec3 outsideMuzzleCenter = entity.toGlobalVector(
                Vec3.atCenterOf(this.startPos.relative(this.initialOrientation, this.barrelCount + 1)), 0.0F);
        Vec3 spawnPos = outsideMuzzleCenter.subtract(forward.scale(0.5D));

        ejectSpentItem(level, entity, round.getSpentItem(foundProjectile), structureCenter);

        NethersteelShot ballistics = calculateNethersteelShot(this.barrelCount);
        float projectileVelocity = calculateRotaryNethersteelVelocity(this.barrelCount);
        AutocannonProjectilePropertiesComponent roundProperties = round.getAutocannonProperties(foundProjectile);
        boolean tracer = CBCConfigs.server().munitions.allAutocannonProjectilesAreTracers.get()
                || round.isTracer(foundProjectile);
        AbstractAutocannonProjectile projectile = round.getAutocannonProjectile(foundProjectile, level);
        if (projectile != null) {
            projectile.setPos(spawnPos);
            projectile.setChargePower(this.barrelCount);
            projectile.setTracer(tracer);
            projectile.setLifetime(ROTARY_NETHERSTEEL_PROJECTILE_LIFETIME);
            projectile.shoot(forward.x, forward.y, forward.z, projectileVelocity, ballistics.spread());
            projectile.addUntouchableEntity(entity, 1);
            Entity vehicle = entity.getVehicle();
            if (vehicle != null && CBCEntityTypes.CANNON_CARRIAGE.is(vehicle)) {
                projectile.addUntouchableEntity(vehicle, 1);
            }
            level.addFreshEntity(projectile);
            projectile.xRotO = projectile.getXRot();
            projectile.yRotO = projectile.getYRot();
        }

        float recoilMagnitude = ballistics.recoil();
        if (roundProperties != null) {
            recoilMagnitude += roundProperties.addedRecoil();
        }
        recoilMagnitude *= CBCConfigs.server().cannons.autocannonRecoilScale.getF();
        ControlPitchContraption controller = entity.getController();
        if (controller != null) {
            controller.onRecoil(forward.scale(-recoilMagnitude), structureCenter, entity);
        }

        NetworkPlatform.sendToClientTracking(
                ClientboundAnimateCannonContraptionPacket.entity((AbstractContraptionEntity) entity), entity);
        sendMuzzleParticles(level, entity, spawnPos, forward);
        playMianbaoFiringSounds(level, spawnPos);
        queueProjectileChunkload(level, projectile);
    }

    private ItemStack extractNextInput() {
        if (!this.inputBuffer.isEmpty()) {
            return this.inputBuffer.removeFirst();
        }
        if (this.magazine.isEmpty()
                || AutocannonAmmoContainerItem.getTotalAmmoCount(this.magazine) <= 0) {
            return ItemStack.EMPTY;
        }
        ItemStack mainAmmo = AutocannonAmmoContainerItem.getMainAmmoStack(this.magazine);
        ItemStack tracerAmmo = AutocannonAmmoContainerItem.getTracerAmmoStack(this.magazine);
        if ((!mainAmmo.isEmpty() && !isOriginalCbcAutocannonRound(mainAmmo))
                || (!tracerAmmo.isEmpty() && !isOriginalCbcAutocannonRound(tracerAmmo))) {
            return ItemStack.EMPTY;
        }
        return AutocannonAmmoContainerItem.pollItemFromContainer(this.magazine);
    }

    private static boolean isOriginalCbcAutocannonRound(ItemStack stack) {
        return stack.getItem() instanceof AutocannonAmmoItem
                && "createbigcannons".equals(
                BuiltInRegistries.ITEM.getKey(stack.getItem()).getNamespace());
    }

    private static boolean isOriginalCbcAmmoContainer(ItemStack stack) {
        if (!(stack.getItem() instanceof AutocannonAmmoContainerItem)) {
            return false;
        }
        ItemStack mainAmmo = AutocannonAmmoContainerItem.getMainAmmoStack(stack);
        ItemStack tracerAmmo = AutocannonAmmoContainerItem.getTracerAmmoStack(stack);
        return (mainAmmo.isEmpty() || isOriginalCbcAutocannonRound(mainAmmo))
                && (tracerAmmo.isEmpty() || isOriginalCbcAutocannonRound(tracerAmmo));
    }

    private void ejectSpentItem(
            ServerLevel level,
            PitchOrientedContraptionEntity entity,
            ItemStack spent,
            Vec3 structureCenter
    ) {
        if (spent.isEmpty()) {
            return;
        }
        Vec3 ejectPos = entity.toGlobalVector(
                Vec3.atCenterOf(this.startPos.relative(this.initialOrientation.getOpposite())), 0.0F);
        ItemEntity itemEntity = new ItemEntity(level, ejectPos.x, ejectPos.y, ejectPos.z, spent);
        itemEntity.setDeltaMovement(ejectPos.subtract(structureCenter).normalize().scale(0.5D));
        itemEntity.setPickUpDelay(20);
        level.addFreshEntity(itemEntity);
    }

    private void sendMuzzleParticles(
            ServerLevel level,
            PitchOrientedContraptionEntity entity,
            Vec3 spawnPos,
            Vec3 forward
    ) {
        Vec3 particlePos = CBCCompatTransformers.transformVec3(level, spawnPos, this.entity.position());
        Vec3 rawParticleVelocity = forward.scale(1.25D);
        for (ServerPlayer player : level.players()) {
            if (entity.getControllingPassenger() == player) {
                continue;
            }
            Vec3 particleVelocity = CBCCompatTransformers.transformLocationNormal(
                    level, this.entity.blockPosition(), rawParticleVelocity);
            level.sendParticles(
                    player,
                    new AutocannonPlumeParticleData(1.0F),
                    true,
                    particlePos.x,
                    particlePos.y,
                    particlePos.z,
                    0,
                    particleVelocity.x,
                    particleVelocity.y,
                    particleVelocity.z,
                    1.0D
            );
        }
    }

    private void playMianbaoFiringSounds(ServerLevel level, Vec3 position) {
        boolean machineGun = MianbaoCannonCompat.ROTARY_GUN_BARREL.equals(this.barrelId);
        SoundEvent mainSound = machineGun
                ? ModSounds.MIANBAO_MACHINE_GUN_FIRE_HALF.get()
                : ModSounds.MIANBAO_AUTOCANNON_FIRE_HALF.get();
        SoundEvent farSound = machineGun
                ? ModSounds.MIANBAO_MACHINE_GUN_FIRE_FAR_HALF.get()
                : ModSounds.MIANBAO_AUTOCANNON_FIRE_FAR_HALF.get();
        BlockPos soundPos = BlockPos.containing(position);
        playSound(level, soundPos, mainSound);
        playSound(level, soundPos, farSound);
    }

    private static void playSound(ServerLevel level, BlockPos pos, SoundEvent sound) {
        float pitch = 0.7F + level.getRandom().nextFloat() * 0.3F;
        level.playSound(null, pos, sound, SoundSource.NEUTRAL, 1.0F, pitch);
    }

    private static void queueProjectileChunkload(
            ServerLevel level,
            @Nullable AbstractAutocannonProjectile projectile
    ) {
        if (projectile == null || !CBCConfigs.server().munitions.projectilesCanChunkload.get()) {
            return;
        }
        BlockPos transformed = CBCCompatTransformers.transformBlockPos(
                level, BlockPos.containing((Position) projectile.position()));
        ChunkPos chunkPos = new ChunkPos(transformed);
        RitchiesProjectileLib.queueForceLoad(level, chunkPos.x, chunkPos.z);
    }

    private static NethersteelShot calculateNethersteelShot(int barrels) {
        float gas = NETHERSTEEL_INITIAL_GAS;
        float drag = 0.0F;
        float accumulatedVelocity = 0.0F;
        float spread = NETHERSTEEL_BASE_SPREAD;
        float recoil = NETHERSTEEL_BASE_RECOIL;
        for (int i = 0; i < barrels; ++i) {
            float currentVelocity = Math.max(0.0F, accumulatedVelocity + NETHERSTEEL_BASE_SPEED - drag);
            gas -= NETHERSTEEL_GAS_LOSS_PER_BARREL;
            drag += NETHERSTEEL_DRAG_FACTOR * currentVelocity * currentVelocity;
            spread *= NETHERSTEEL_SPREAD_MULTIPLIER;
            recoil += Math.max(0.0F, gas) * NETHERSTEEL_RECOIL_PER_REMAINING_GAS;
            accumulatedVelocity += Math.max(0.0F, gas);
        }
        float velocity = Math.max(0.0F, accumulatedVelocity + NETHERSTEEL_BASE_SPEED - drag);
        return new NethersteelShot(velocity, spread, recoil);
    }

    private static float calculateRotaryNethersteelVelocity(int barrels) {
        int speedIncreases = Mth.clamp(barrels, 0, ROTARY_NETHERSTEEL_MAX_SPEED_INCREASES);
        return ROTARY_NETHERSTEEL_BASE_SPEED
                + ROTARY_NETHERSTEEL_SPEED_PER_BARREL * speedIncreases;
    }

    @Override
    public void onRedstoneUpdate(
            ServerLevel level,
            PitchOrientedContraptionEntity entity,
            boolean togglePower,
            int firePower,
            ControlPitchContraption controller
    ) {
        this.controllerFirePower = Mth.clamp(firePower, 0, 15);
        if (togglePower && this.controllerFirePower > 0) {
            this.firingCooldown = 0;
        }
    }

    @Override
    public boolean canBeFiredOnController(ControlPitchContraption control) {
        return this.entity == null || this.entity.getVehicle() != control;
    }

    @Override
    public float getWeightForStress() {
        return this.blocks.size() * NETHERSTEEL_WEIGHT_PER_BLOCK;
    }

    @Override
    public Vec3 getInteractionVec(PitchOrientedContraptionEntity entity) {
        return entity.toGlobalVector(Vec3.atCenterOf(this.startPos), 0.0F);
    }

    @Override
    public ICannonContraptionType getCannonType() {
        return CBCCannonContraptionTypes.AUTOCANNON;
    }

    @Override
    public ContraptionType getType() {
        return ModContraptionTypes.MIANBAO_AUTOCANNON.get();
    }

    @Override
    public ItemStack insertItemIntoCannon(ItemStack stack, boolean simulate) {
        return this.itemStorage.insertItem(1, stack, simulate);
    }

    @Override
    public ItemStack extractItemFromCannon(boolean simulate) {
        return this.itemStorage.extractItem(0, 1, simulate);
    }

    @Override
    public IItemHandler getItemStorage() {
        return this.itemStorage;
    }

    /**
     * Read-only bridge used by this mod's optional CBC fire-control analyzer.
     * Probing a copied magazine preserves tracer sequencing and never consumes
     * ammunition from the live cannon.
     */
    public ItemStack stabilizedTurret$getBallisticAmmunition() {
        if (!this.inputBuffer.isEmpty()) {
            return this.inputBuffer.peekFirst().copyWithCount(1);
        }
        if (this.magazine.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ItemStack probe = this.magazine.copy();
        ItemStack next = AutocannonAmmoContainerItem.pollItemFromContainer(probe);
        return isOriginalCbcAutocannonRound(next) ? next : ItemStack.EMPTY;
    }

    public float stabilizedTurret$getBallisticBaseSpeed() {
        return ROTARY_NETHERSTEEL_BASE_SPEED;
    }

    public float stabilizedTurret$getBallisticSpeedIncreasePerBarrel() {
        return ROTARY_NETHERSTEEL_SPEED_PER_BARREL;
    }

    public int stabilizedTurret$getBallisticMaxSpeedIncreases() {
        return ROTARY_NETHERSTEEL_MAX_SPEED_INCREASES;
    }

    public int stabilizedTurret$getBallisticProjectileLifetime() {
        return ROTARY_NETHERSTEEL_PROJECTILE_LIFETIME;
    }

    public float stabilizedTurret$getBallisticBaseRecoil() {
        return calculateNethersteelShot(this.barrelCount).recoil();
    }

    public String stabilizedTurret$getBallisticMaterialName() {
        return "create_fire_control:mianbao_nethersteel_autocannon";
    }

    public boolean interactWithMagazine(
            Player player,
            InteractionHand hand,
            Level level,
            PitchOrientedContraptionEntity entity
    ) {
        ItemStack held = player.getItemInHand(hand);
        boolean inserting = isOriginalCbcAmmoContainer(held);
        if (level.isClientSide) {
            // The magazine itself is server-authoritative contraption state.
            // Accept an empty-hand click client-side even if that client has
            // not received the latest magazine state, so the removal request
            // always reaches the server (notably for creative containers).
            return inserting || held.isEmpty();
        }
        boolean removing = held.isEmpty() && !this.magazine.isEmpty();
        if (!inserting && !removing) {
            return false;
        }

        ItemStack oldMagazine = this.magazine;
        if (!oldMagazine.isEmpty()) {
            this.magazine = ItemStack.EMPTY;
        }
        if (inserting && this.magazine.isEmpty()) {
            this.magazine = held.copyWithCount(1);
            player.setItemInHand(hand, ItemStack.EMPTY);
        }
        if (removing) {
            player.setItemInHand(hand, oldMagazine);
        } else if (!oldMagazine.isEmpty() && !player.addItem(oldMagazine)) {
            Vec3 dropPos = entity.toGlobalVector(Vec3.atCenterOf(this.startPos), 0.0F);
            level.addFreshEntity(new ItemEntity(level, dropPos.x, dropPos.y, dropPos.z, oldMagazine));
        }
        return true;
    }

    @Override
    public void addBlocksToWorld(Level level, StructureTransform transform) {
        if (this.disassembled) {
            return;
        }
        List<ItemStack> overflow = returnAmmunitionToMianbaoInventory();
        syncBreechBlockEntityNbt(level.registryAccess());
        BlockPos dropPos = transform.apply(this.startPos);
        super.addBlocksToWorld(level, transform);
        for (ItemStack stack : overflow) {
            if (!stack.isEmpty()) {
                level.addFreshEntity(new ItemEntity(
                        level,
                        dropPos.getX() + 0.5D,
                        dropPos.getY() + 0.5D,
                        dropPos.getZ() + 0.5D,
                        stack
                ));
            }
        }
    }

    private List<ItemStack> returnAmmunitionToMianbaoInventory() {
        List<ItemStack> overflow = new ArrayList<>();
        Object breech = this.presentBlockEntities.get(this.startPos);
        if (!(breech instanceof Container container)) {
            overflow.addAll(this.inputBuffer);
            this.inputBuffer.clear();
            if (!this.magazine.isEmpty()) {
                overflow.add(this.magazine);
                this.magazine = ItemStack.EMPTY;
            }
            return overflow;
        }

        while (!this.inputBuffer.isEmpty()) {
            ItemStack remainder = insertIntoContainer(container, this.inputBuffer.removeFirst());
            if (!remainder.isEmpty()) {
                overflow.add(remainder);
            }
        }
        if (!this.magazine.isEmpty()) {
            ItemStack remainder = insertIntoContainer(container, this.magazine);
            if (!remainder.isEmpty()) {
                overflow.add(remainder);
            }
            this.magazine = ItemStack.EMPTY;
        }
        container.setChanged();
        return overflow;
    }

    private static ItemStack insertIntoContainer(Container container, ItemStack input) {
        ItemStack remainder = input.copy();
        for (int slot = 0; slot < container.getContainerSize() && !remainder.isEmpty(); ++slot) {
            ItemStack existing = container.getItem(slot);
            if (existing.isEmpty()) {
                int moved = Math.min(remainder.getCount(), Math.min(container.getMaxStackSize(), remainder.getMaxStackSize()));
                container.setItem(slot, remainder.copyWithCount(moved));
                remainder.shrink(moved);
                continue;
            }
            if (!ItemStack.isSameItemSameComponents(existing, remainder)) {
                continue;
            }
            int room = Math.min(container.getMaxStackSize(), existing.getMaxStackSize()) - existing.getCount();
            if (room <= 0) {
                continue;
            }
            int moved = Math.min(room, remainder.getCount());
            existing.grow(moved);
            container.setItem(slot, existing);
            remainder.shrink(moved);
        }
        return remainder;
    }

    private void syncBreechBlockEntityNbt(HolderLookup.Provider registries) {
        BlockEntity breech = this.presentBlockEntities.get(this.startPos);
        StructureTemplate.StructureBlockInfo oldInfo = this.blocks.get(this.startPos);
        if (breech == null || oldInfo == null) {
            return;
        }
        CompoundTag nbt = breech.saveWithFullMetadata(registries);
        nbt.remove("x");
        nbt.remove("y");
        nbt.remove("z");
        this.blocks.put(this.startPos,
                new StructureTemplate.StructureBlockInfo(this.startPos, oldInfo.state(), nbt));
    }

    @Override
    public CompoundTag writeNBT(HolderLookup.Provider registries, boolean spawnPacket) {
        CompoundTag tag = super.writeNBT(registries, spawnPacket);
        if (this.family != null) {
            tag.putString("MianbaoFamily", this.family.name());
        }
        if (this.barrelId != null) {
            tag.putString("MianbaoBarrel", this.barrelId.toString());
        }
        tag.putInt("MianbaoBarrelCount", this.barrelCount);
        tag.putInt("MianbaoCooldown", this.firingCooldown);
        tag.putInt("MianbaoControllerPower", this.controllerFirePower);
        tag.putFloat("MianbaoRotaryAngle", this.rotaryAngle);
        tag.putFloat("MianbaoPreviousRotaryAngle", this.previousRotaryAngle);
        tag.putInt("MianbaoRotaryCoast", this.rotaryCoastTicks);
        tag.putFloat("MianbaoRotaryCoastStart", this.rotaryCoastStartAngle);
        tag.putFloat("MianbaoRotaryCoastTravel", this.rotaryCoastTravel);
        tag.putFloat("MianbaoBarrelRecoil", this.barrelRecoil);
        tag.putFloat("MianbaoPreviousBarrelRecoil", this.previousBarrelRecoil);

        ListTag input = new ListTag();
        for (ItemStack stack : this.inputBuffer) {
            input.add(stack.saveOptional(registries));
        }
        tag.put("MianbaoInput", input);
        if (!this.magazine.isEmpty()) {
            tag.put("MianbaoMagazine", this.magazine.save(registries));
        }
        return tag;
    }

    @Override
    public void readNBT(Level level, CompoundTag tag, boolean clientData) {
        super.readNBT(level, tag, clientData);
        try {
            this.family = tag.contains("MianbaoFamily")
                    ? MianbaoCannonCompat.Family.valueOf(tag.getString("MianbaoFamily"))
                    : null;
        } catch (IllegalArgumentException ignored) {
            this.family = null;
        }
        this.barrelId = tag.contains("MianbaoBarrel")
                ? ResourceLocation.tryParse(tag.getString("MianbaoBarrel"))
                : null;
        this.barrelCount = tag.getInt("MianbaoBarrelCount");
        this.firingCooldown = tag.getInt("MianbaoCooldown");
        this.controllerFirePower = tag.getInt("MianbaoControllerPower");
        this.rotaryAngle = tag.getFloat("MianbaoRotaryAngle");
        this.previousRotaryAngle = tag.getFloat("MianbaoPreviousRotaryAngle");
        this.rotaryCoastTicks = tag.getInt("MianbaoRotaryCoast");
        this.rotaryCoastStartAngle = tag.getFloat("MianbaoRotaryCoastStart");
        this.rotaryCoastTravel = tag.getFloat("MianbaoRotaryCoastTravel");
        this.barrelRecoil = tag.getFloat("MianbaoBarrelRecoil");
        this.previousBarrelRecoil = tag.getFloat("MianbaoPreviousBarrelRecoil");

        this.inputBuffer.clear();
        ListTag input = tag.getList("MianbaoInput", Tag.TAG_COMPOUND);
        for (int i = 0; i < input.size(); ++i) {
            ItemStack stack = ItemStack.parseOptional(level.registryAccess(), input.getCompound(i));
            if (!stack.isEmpty()) {
                this.inputBuffer.addLast(stack);
            }
        }
        this.magazine = tag.contains("MianbaoMagazine")
                ? ItemStack.parseOptional(level.registryAccess(), tag.getCompound("MianbaoMagazine"))
                : ItemStack.EMPTY;
        rebuildBarrelPositions(level);
    }

    private void rebuildBarrelPositions(Level level) {
        this.barrelPositions.clear();
        this.rotaryBarrelPositions.clear();
        Set<BlockPos> actorPositions = new HashSet<>();
        for (MutablePair<StructureTemplate.StructureBlockInfo, MovementContext> actor : this.actors) {
            actorPositions.add(actor.getLeft().pos());
        }
        for (StructureTemplate.StructureBlockInfo info : this.blocks.values()) {
            if (MianbaoCannonCompat.isAnyBarrel(info.state())) {
                BlockPos barrelPos = info.pos().immutable();
                this.barrelPositions.add(barrelPos);
                if (actorPositions.add(barrelPos)) {
                    this.actors.add(MutablePair.of(info, new MovementContext(level, info, this)));
                }
            }
            if (MianbaoCannonCompat.isRotaryBarrel(info.state())) {
                this.rotaryBarrelPositions.add(info.pos().immutable());
            }
        }
    }

    private record NethersteelShot(float velocity, float spread, float recoil) {
    }

    private final class BreechItemHandler implements IItemHandler {
        @Override
        public int getSlots() {
            return 2;
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            if (slot == 1 && inputBuffer.size() >= INPUT_LIMIT && !inputBuffer.isEmpty()) {
                return inputBuffer.peekLast();
            }
            return ItemStack.EMPTY;
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            if (slot != 1
                    || stack.isEmpty()
                    || !isOriginalCbcAutocannonRound(stack)
                    || inputBuffer.size() >= INPUT_LIMIT) {
                return stack;
            }
            int accepted = Math.min(INPUT_LIMIT - inputBuffer.size(), stack.getCount());
            if (!simulate) {
                for (int i = 0; i < accepted; ++i) {
                    inputBuffer.addLast(stack.copyWithCount(1));
                }
            }
            return accepted == stack.getCount()
                    ? ItemStack.EMPTY
                    : stack.copyWithCount(stack.getCount() - accepted);
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            return ItemStack.EMPTY;
        }

        @Override
        public int getSlotLimit(int slot) {
            return 1;
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return slot == 1 && isOriginalCbcAutocannonRound(stack);
        }
    }
}
