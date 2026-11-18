package com.hooya.stabilizedturret.client.ponder;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.registry.ModBlocks;
import com.simibubi.create.AllItems;
import net.createmod.catnip.math.Pointing;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.registration.PonderSceneRegistrationHelper;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** Create Ponder pages for filling, binding and operating the MZ autoloader. */
public final class MzLoaderPonderScenes {
    private static final BlockPos LOADER = new BlockPos(2, 1, 2);
    private static final BlockPos MOUNT = new BlockPos(2, 1, 4);
    private static final BlockPos LEVER = new BlockPos(1, 1, 2);

    public static void register(PonderSceneRegistrationHelper<ResourceLocation> helper) {
        helper.forComponents(ModBlocks.MZ_LOADER.getId())
                .addStoryBoard(FireControlPonderScenes.BASE_TEMPLATE,
                        MzLoaderPonderScenes::ammunition, FireControlPonderPlugin.FIRE_CONTROL)
                .addStoryBoard(FireControlPonderScenes.BASE_TEMPLATE,
                        MzLoaderPonderScenes::binding, FireControlPonderPlugin.FIRE_CONTROL)
                .addStoryBoard(FireControlPonderScenes.BASE_TEMPLATE,
                        MzLoaderPonderScenes::cycle, FireControlPonderPlugin.FIRE_CONTROL);
    }

    private static void ammunition(SceneBuilder scene, SceneBuildingUtil util) {
        begin(scene, util, "mz_loader_ammunition", "Filling the MZ ammunition carousel");
        Vec3 loaderTop = util.vector().topOf(LOADER);
        scene.overlay().showControls(loaderTop, Pointing.DOWN, 55)
                .rightClick()
                .withItem(cartridgeStack());
        scene.overlay().showText(75)
                .pointAt(loaderTop)
                .placeNearTarget()
                .attachKeyFrame()
                .text("Use an APFSDS medium-cannon cartridge on the loader to fill one carousel slot. It stores up to 24 rounds.");
        scene.idle(80);

        scene.overlay().showText(80)
                .colored(PonderPalette.RED)
                .pointAt(util.vector().centerOf(LOADER))
                .placeNearTarget()
                .attachKeyFrame()
                .text("Once the first loading cycle starts, the carousel is locked against topping up until all loaded rounds have been used.");
        scene.idle(85);
    }

    private static void binding(SceneBuilder scene, SceneBuildingUtil util) {
        begin(scene, util, "mz_loader_binding", "Binding the loader to a cannon mount");
        scene.world().setBlock(MOUNT, cannonMount(), false);
        scene.world().showSection(util.select().position(MOUNT), Direction.DOWN);
        scene.idle(10);

        scene.overlay().showControls(util.vector().topOf(LOADER), Pointing.DOWN, 45)
                .rightClick()
                .withItem(AllItems.WRENCH.asStack());
        scene.overlay().showText(70)
                .pointAt(util.vector().topOf(LOADER))
                .placeNearTarget()
                .attachKeyFrame()
                .text("Right-click the MZ loader with a Create Wrench to begin binding.");
        scene.idle(75);

        scene.overlay().showOutline(PonderPalette.BLUE, "mz_mount",
                util.select().position(MOUNT), 65);
        scene.overlay().showControls(util.vector().topOf(MOUNT), Pointing.DOWN, 45)
                .rightClick()
                .withItem(AllItems.WRENCH.asStack());
        scene.overlay().showText(75)
                .colored(PonderPalette.BLUE)
                .pointAt(util.vector().centerOf(MOUNT))
                .placeNearTarget()
                .attachKeyFrame()
                .text("Then wrench the cannon mount to bind it. Standard, fixed and compact cannon mounts are supported.");
        scene.idle(80);
    }

    private static void cycle(SceneBuilder scene, SceneBuildingUtil util) {
        begin(scene, util, "mz_loader_cycle", "Automatic and redstone loading cycles");
        scene.world().setBlock(MOUNT, cannonMount(), false);
        scene.world().setBlock(LEVER, Blocks.LEVER.defaultBlockState(), false);
        scene.world().showSection(util.select().position(MOUNT), Direction.DOWN);
        scene.world().showSection(util.select().position(LEVER), Direction.EAST);
        scene.idle(10);

        scene.overlay().showLine(PonderPalette.GREEN,
                util.vector().centerOf(LOADER), util.vector().centerOf(MOUNT), 80);
        scene.overlay().showText(85)
                .colored(PonderPalette.GREEN)
                .pointAt(util.vector().centerOf(MOUNT))
                .placeNearTarget()
                .attachKeyFrame()
                .text("After a bound cannon fires, the loader opens the breech, inserts one round, waits one second, and closes the breech automatically.");
        scene.idle(90);

        scene.overlay().showControls(util.vector().topOf(LEVER), Pointing.DOWN, 45)
                .rightClick();
        scene.effects().indicateRedstone(LEVER);
        scene.overlay().showText(80)
                .pointAt(util.vector().centerOf(LEVER))
                .placeNearTarget()
                .attachKeyFrame()
                .text("Without a bound mount, a redstone rising edge can still run one animation and consume one stored round. Signals are ignored while busy.");
        scene.idle(85);
    }

    private static void begin(SceneBuilder scene, SceneBuildingUtil util, String id, String title) {
        scene.title(id, title);
        scene.configureBasePlate(0, 0, 6);
        scene.scaleSceneView(0.95F);
        scene.world().setBlocks(util.select().fromTo(0, 1, 0, 7, 7, 7),
                Blocks.AIR.defaultBlockState(), false);
        scene.showBasePlate();
        scene.world().setBlock(LOADER, ModBlocks.MZ_LOADER.get().defaultBlockState(), false);
        scene.world().showSection(util.select().position(LOADER), Direction.DOWN);
        scene.idle(10);
    }

    private static ItemStack cartridgeStack() {
        Item cartridge = BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath(
                StabilizedTurret.MOD_ID, "apfsds_medium_cannon_cartridge"));
        return cartridge == Items.AIR
                ? Items.IRON_NUGGET.getDefaultInstance()
                : cartridge.getDefaultInstance();
    }

    private static BlockState cannonMount() {
        return BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath(
                "createbigcannons", "cannon_mount")).defaultBlockState();
    }

    private MzLoaderPonderScenes() {
    }
}
