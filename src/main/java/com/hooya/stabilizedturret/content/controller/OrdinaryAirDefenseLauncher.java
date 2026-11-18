package com.hooya.stabilizedturret.content.controller;

import com.hooya.stabilizedturret.StabilizedTurret;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/** Adds ordinary rounds to both generations of AA launchers without linking to Mianbao. */
@EventBusSubscriber(modid = StabilizedTurret.MOD_ID)
public final class OrdinaryAirDefenseLauncher {
    public static boolean isRound(ItemStack stack) {
        var id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return !stack.isEmpty() && id.getNamespace().equals(MianbaoMissileCompat.MOD_ID)
                && id.getPath().equals("portableantiairmissile");
    }

    public static boolean hasRound(BlockEntity launcher) {
        return launcher instanceof Container container && container.getContainerSize() > 0
                && isRound(container.getItem(0));
    }

    @SubscribeEvent
    public static void rightClick(PlayerInteractEvent.RightClickBlock event) {
        if (!isRound(event.getItemStack()) || !MianbaoAirDefenseCompat.isLauncher(
                event.getLevel().getBlockState(event.getPos()))) return;
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        if (!event.getLevel().isClientSide()) {
            load(event.getLevel(), event.getPos(), event.getEntity(), event.getItemStack());
        }
    }

    static boolean load(Level level, BlockPos pos, Player player, ItemStack held) {
        if (level.isClientSide() || !isRound(held) || !MianbaoAirDefenseCompat.isLauncher(level.getBlockState(pos))
                || !(level.getBlockEntity(pos) instanceof Container container) || container.getContainerSize() < 1) return false;
        ItemStack current = container.getItem(0);
        int maximum = Math.min(4, container.getMaxStackSize());
        if (!current.isEmpty() && !isRound(current) || current.getCount() >= maximum) return false;
        int count = player.getAbilities().instabuild ? maximum : current.getCount() + 1;
        container.setItem(0, held.copyWithCount(count));
        if (!player.getAbilities().instabuild) held.shrink(1);
        container.setChanged();
        level.sendBlockUpdated(pos, level.getBlockState(pos), level.getBlockState(pos), 3);
        level.playSound(null, pos, SoundEvents.CANDLE_PLACE, SoundSource.BLOCKS, 1, 1);
        player.displayClientMessage(Component.translatable("message.create_fire_control.aa_missile.ordinary_loaded", count, maximum), true);
        return true;
    }

    static boolean fire(Level level, BindingRef ref, BlockEntity launcher,
                        StabilizerControllerBlockEntity computer) {
        if (computer == null || computer.isRemoved() || !computer.getAaMissileLaunchers().contains(ref)
                || !computer.isOrdinaryAaGuidanceReady() || !hasRound(launcher)) return false;
        return launch(level, ref, launcher, computer);
    }

    /** Native redstone pulses need no computer or beam lock; keep the round's native flight. */
    public static boolean fireFromRedstone(Level level, BlockPos pos) {
        if (level.isClientSide() || !MianbaoAirDefenseCompat.isLauncher(level.getBlockState(pos))) return false;
        BlockEntity launcher = level.getBlockEntity(pos);
        if (!hasRound(launcher)) return false;
        return launch(level, BindingRef.capture(level, pos), launcher, null);
    }

    private static boolean launch(Level level, BindingRef ref, BlockEntity launcher,
                                  StabilizerControllerBlockEntity computer) {
        var server = level.getServer();
        var root = server == null ? null : server.getLevel(level.dimension());
        Vec3 muzzle = MianbaoAirDefenseCompat.getLauncherMuzzlePosition(level, ref);
        Vec3 direction = MianbaoAirDefenseCompat.getLauncherDirection(level, ref);
        if (root == null || muzzle == null || direction == null) return false;
        var type = BuiltInRegistries.ENTITY_TYPE.getOptional(ResourceLocation.fromNamespaceAndPath(
                MianbaoMissileCompat.MOD_ID, "portableantiairmissiletanshe")).orElse(null);
        Entity missile = type == null ? null : type.create(root);
        if (missile == null) return false;
        missile.setPos(muzzle.x, muzzle.y, muzzle.z);
        Vec3 localMuzzle = Vec3.atCenterOf(launcher.getBlockPos()).add(
                MianbaoAirDefenseCompat.localDirection(launcher.getBlockState()).scale(2.6));
        Vec3 velocity = direction.scale(OrdinaryAirDefenseGuidance.LAUNCH_SPEED).add(
                MianbaoAircraftCompat.carrierVelocityAtPoint(level, ref, localMuzzle));
        if (velocity.length() > OrdinaryAirDefenseGuidance.MAX_SPEED) {
            velocity = velocity.normalize().scale(OrdinaryAirDefenseGuidance.MAX_SPEED);
        }
        MianbaoMissileCompat.setWorldVelocity(missile, velocity);
        missile.getPersistentData().putBoolean("MianbaoCarrierMotionApplied", true);
        missile.setSilent(true);
        missile.setNoGravity(true);
        if (missile instanceof AbstractArrow arrow) {
            arrow.setBaseDamage(15.0);
            arrow.pickup = AbstractArrow.Pickup.DISALLOWED;
        }
        if (computer != null) OrdinaryAirDefenseGuidance.adopt(missile, computer);
        if (!root.addFreshEntity(missile)) {
            if (computer != null) OrdinaryAirDefenseGuidance.cancelLaunch(missile);
            else missile.discard();
            return false;
        }
        ((Container) launcher).removeItem(0, 1);
        launcher.setChanged();
        level.sendBlockUpdated(launcher.getBlockPos(), launcher.getBlockState(), launcher.getBlockState(), 3);
        root.playSound(null, muzzle.x, muzzle.y, muzzle.z, BuiltInRegistries.SOUND_EVENT.get(
                ResourceLocation.fromNamespaceAndPath(MianbaoMissileCompat.MOD_ID, "missile_launcher")), SoundSource.NEUTRAL, 5, .8F);
        MianbaoMissileRemoteSync.broadcast(missile);
        return true;
    }

    private OrdinaryAirDefenseLauncher() {}
}
