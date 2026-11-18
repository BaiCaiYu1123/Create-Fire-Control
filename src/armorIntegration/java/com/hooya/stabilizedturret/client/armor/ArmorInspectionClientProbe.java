package com.hooya.stabilizedturret.client.armor;

import com.caca.copypoly.Copypoly;
import com.caca.copypoly.PolyBlockEntity;
import com.cbc_terminal_ballistics.client.ClientInspectionSnapshots;
import com.cbc_terminal_ballistics.registry.ModItems;
import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.config.FireControlSettings;
import com.hooya.stabilizedturret.registry.ModCbcContent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import rbasamoyai.createbigcannons.block_armor_properties.BlockArmorInspectionToolItem;
import rbasamoyai.createbigcannons.index.CBCItems;

import java.nio.file.Path;
import java.util.ArrayList;

/** Exercises the actual CBC overlay and CTB's periodic client/server snapshot. */
@EventBusSubscriber(modid = StabilizedTurret.MOD_ID, value = Dist.CLIENT)
public final class ArmorInspectionClientProbe {
    private static final BlockPos FRONT = new BlockPos(8, 102, 8);
    private static final String THICKNESS = "tooltip.create_fire_control.armor.equivalent_thickness";
    private static final String DETAILS = "tooltip.create_fire_control.armor.inspection_details";
    private static int stage, elapsed, waitTicks;

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        if (!Boolean.getBoolean("create_fire_control.armorInspectionSmoke") || stage == 4) return;
        var mc = Minecraft.getInstance();
        try {
            if (++elapsed > 2400) throw new IllegalStateException("Inspection test timed out at stage " + stage);
            advance(mc);
        } catch (Throwable failure) {
            StabilizedTurret.LOGGER.error("ARMOR_INSPECTION_CLIENT_FAIL", failure);
            stage = 4;
            mc.stop();
        }
    }

    private static void advance(Minecraft mc) throws Exception {
        if (stage == 0 && (mc.screen instanceof TitleScreen || mc.screen instanceof AccessibilityOnboardingScreen)
                && mc.getOverlay() == null) {
            mc.options.renderDistance().set(3);
            mc.options.simulationDistance().set(5);
            mc.options.guiScale().set(2);
            mc.options.pauseOnLostFocus = false;
            var settings = new LevelSettings("Armor inspection regression", GameType.CREATIVE, false,
                    Difficulty.PEACEFUL, true, new GameRules(), WorldDataConfiguration.DEFAULT);
            mc.createWorldOpenFlows().createFreshLevel("armor-inspection-" + System.currentTimeMillis(),
                    settings, new WorldOptions(1234, false, false),
                    registries -> registries.registryOrThrow(Registries.WORLD_PRESET)
                            .getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions(), mc.screen);
            stage = 1;
            return;
        }
        if (mc.level == null || mc.player == null || mc.screen != null || mc.getSingleplayerServer() == null) return;

        if (stage == 1) {
            var server = mc.getSingleplayerServer();
            server.execute(() -> {
                FireControlSettings.EQUIVALENT_ARMOR.set(true);
                var level = server.overworld();
                level.setBlockAndUpdate(FRONT, Copypoly.POLY.get().defaultBlockState());
                ((PolyBlockEntity) level.getBlockEntity(FRONT)).setMaterial(Blocks.OBSIDIAN.defaultBlockState());
                level.setBlockAndUpdate(FRONT.south(2), Blocks.IRON_BLOCK.defaultBlockState());
                var player = server.getPlayerList().getPlayers().getFirst();
                player.getAbilities().flying = true;
                player.onUpdateAbilities();
                player.connection.teleport(8.55, 100.9, 5.5, 0, 0);
                player.setItemInHand(InteractionHand.MAIN_HAND, CBCItems.BLOCK_ARMOR_INSPECTION_TOOL.asStack());

                var shot = ModCbcContent.APFSDS_MEDIUM_CANNON_PROJECTILE.get().create(level);
                shot.setPos(8.25, 102.5, 6);
                shot.setNoGravity(true);
                shot.setDeltaMovement(0, 0, 12);
                shot.setProjectileMass(.0001F);
                level.addFreshEntity(shot);
            });
            stage = 2;
            return;
        }
        if (!(mc.hitResult instanceof BlockHitResult hit) || !hit.getBlockPos().equals(FRONT) || ++waitTicks < 25) return;
        var rows = new ArrayList<Component>();
        BlockArmorInspectionToolItem.addBlockArmorInfo(rows, mc.level, FRONT, mc.level.getBlockState(FRONT));
        var thickness = rows.stream().filter(row -> key(row, THICKNESS)).findFirst().orElseThrow();
        Object[] args = ((TranslatableContents) thickness.getContents()).getArgs();
        int expectedBlocks = stage == 2 ? 1 : 2;
        require(((Number) args[0]).longValue() == expectedBlocks * 1000L
                        && ((Number) args[1]).intValue() == expectedBlocks,
                "Expected " + expectedBlocks + " connected layers, got " + thickness.getString());

        if (stage == 2) {
            capture(mc, "inspection-cbc.png");
            var server = mc.getSingleplayerServer();
            server.execute(() -> {
                server.overworld().setBlockAndUpdate(FRONT.south(2), Blocks.AIR.defaultBlockState());
                server.overworld().setBlockAndUpdate(FRONT.south(), Blocks.IRON_BLOCK.defaultBlockState());
                server.getPlayerList().getPlayers().getFirst().setItemInHand(
                        InteractionHand.MAIN_HAND, new ItemStack(ModItems.ADVANCED_BLOCK_ARMOR_INSPECTION_TOOL.get()));
            });
            waitTicks = 0;
            stage = 3;
            return;
        }
        var snapshot = ClientInspectionSnapshots.get(FRONT);
        if (snapshot == null || snapshot.lastOutcome().isEmpty()
                || !mc.player.getMainHandItem().is(ModItems.ADVANCED_BLOCK_ARMOR_INSPECTION_TOOL.get())) return;
        require(rows.stream().anyMatch(row -> key(row, DETAILS)), "Advanced tool did not show the compact details hint");
        require(rows.stream().noneMatch(row -> key(row, "tooltip.cbc_terminal_ballistics.advanced_inspector.last_impact")),
                "The long CTB impact line is still in the default view");
        require(rows.stream().mapToInt(mc.font::width).max().orElseThrow() < 210,
                "The default overlay is wider than expected");
        capture(mc, "inspection-terminal-compact.png");
        StabilizedTurret.LOGGER.info("ARMOR_INSPECTION_CLIENT_PASS: separate blocks read 1000 mm, "
                + "touching blocks read 2000 mm and the CTB overlay stays compact after a hit");
        stage = 4;
        mc.stop();
    }

    private static boolean key(Component line, String key) {
        return line.getContents() instanceof TranslatableContents contents && contents.getKey().equals(key);
    }

    private static void capture(Minecraft mc, String name) throws Exception {
        try (var screenshot = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
            screenshot.writeToFile(Path.of(name));
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private ArmorInspectionClientProbe() {}
}
