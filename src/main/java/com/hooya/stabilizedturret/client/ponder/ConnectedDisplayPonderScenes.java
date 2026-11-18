package com.hooya.stabilizedturret.client.ponder;

import com.hooya.stabilizedturret.content.display.ConnectedDisplayBlock;
import com.hooya.stabilizedturret.content.display.DisplayConnection;
import com.hooya.stabilizedturret.registry.ModBlocks;
import com.simibubi.create.AllBlocks;
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
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Create Ponder pages for connected display walls and their weapon-control UI. */
public final class ConnectedDisplayPonderScenes {
    /*
     * The old empty_blaze_burner template is only 5x2x5.  Its y=2 lies
     * outside the Ponder world, so the upper display row was never created.
     * depot provides a 6x4x6 world; begin() clears its contents before the
     * display wall is placed.
     */
    private static final ResourceLocation DISPLAY_TEMPLATE =
            ResourceLocation.fromNamespaceAndPath("create", "depot");
    private static final BlockPos BOTTOM_LEFT = new BlockPos(2, 1, 2);
    private static final BlockPos BOTTOM_MIDDLE = new BlockPos(3, 1, 2);
    private static final BlockPos BOTTOM_RIGHT = new BlockPos(4, 1, 2);
    private static final BlockPos TOP_LEFT = new BlockPos(2, 2, 2);
    private static final BlockPos TOP_MIDDLE = new BlockPos(3, 2, 2);
    private static final BlockPos TOP_RIGHT = new BlockPos(4, 2, 2);
    private static final BlockPos SENSOR = new BlockPos(1, 1, 4);
    private static final BlockPos WEAPON = new BlockPos(4, 1, 4);

    public static void register(PonderSceneRegistrationHelper<ResourceLocation> helper) {
        helper.forComponents(ModBlocks.CONNECTED_DISPLAY.getId())
                .addStoryBoard(DISPLAY_TEMPLATE,
                        ConnectedDisplayPonderScenes::wall, FireControlPonderPlugin.FIRE_CONTROL)
                .addStoryBoard(DISPLAY_TEMPLATE,
                        ConnectedDisplayPonderScenes::binding, FireControlPonderPlugin.FIRE_CONTROL)
                .addStoryBoard(DISPLAY_TEMPLATE,
                        ConnectedDisplayPonderScenes::operation, FireControlPonderPlugin.FIRE_CONTROL);
    }

    private static void wall(SceneBuilder scene, SceneBuildingUtil util) {
        begin(scene, util, "connected_display_wall", "Building a connected display wall");
        // A multi-block BER must live in one Ponder world section. Revealing each
        // panel separately makes its render owner see only one row of the wall.
        showWall(scene, util);
        scene.idle(20);

        scene.overlay().showOutline(PonderPalette.BLUE, "display_wall",
                util.select().fromTo(BOTTOM_LEFT, TOP_RIGHT), 90);
        scene.overlay().showText(90)
                .colored(PonderPalette.BLUE)
                .pointAt(util.vector().centerOf(TOP_LEFT))
                .placeNearTarget()
                .attachKeyFrame()
                .text("Place six matching displays facing the same direction in a 3-wide, 2-high rectangle. They connect into one seamless screen.");
        scene.idle(95);

        scene.overlay().showText(80)
                .pointAt(util.vector().centerOf(BOTTOM_LEFT))
                .placeNearTarget()
                .attachKeyFrame()
                .text("All panels share one controller. Existing bindings and settings are retained when the wall grows or its panels are used.");
        scene.idle(85);
    }

    private static void binding(SceneBuilder scene, SceneBuildingUtil util) {
        begin(scene, util, "connected_display_binding", "Connecting sensors and weapons");
        showWall(scene, util);
        scene.world().setBlock(SENSOR, AllBlocks.MECHANICAL_BEARING.getDefaultState(), false);
        scene.world().setBlock(WEAPON, cannonMount(), false);
        scene.world().showSection(util.select().position(SENSOR), Direction.DOWN);
        scene.world().showSection(util.select().position(WEAPON), Direction.DOWN);
        scene.idle(10);

        scene.overlay().showControls(util.vector().topOf(BOTTOM_LEFT), Pointing.DOWN, 45)
                .rightClick()
                .withItem(AllItems.WRENCH.asStack());
        scene.overlay().showText(70)
                .pointAt(util.vector().topOf(BOTTOM_LEFT))
                .placeNearTarget()
                .attachKeyFrame()
                .text("Wrench any panel to enter display binding mode. Compatible devices are highlighted across normal and Sable sub-levels.");
        scene.idle(75);

        scene.overlay().showOutline(PonderPalette.BLUE, "display_devices",
                util.select().fromTo(SENSOR, WEAPON), 80);
        scene.overlay().showText(85)
                .colored(PonderPalette.BLUE)
                .pointAt(util.vector().centerOf(SENSOR))
                .placeNearTarget()
                .attachKeyFrame()
                .text("Bind a radar bearing, yaw and pitch bearings, cannon mounts, air-defense launchers, or cruise-missile launchers with the wrench.");
        scene.idle(90);
    }

    private static void operation(SceneBuilder scene, SceneBuildingUtil util) {
        begin(scene, util, "connected_display_operation", "Configuring and operating the display");
        showWall(scene, util);
        scene.idle(10);

        scene.overlay().showControls(util.vector().topOf(BOTTOM_LEFT), Pointing.DOWN, 45)
                .rightClick()
                .whileSneaking();
        scene.overlay().showText(80)
                .pointAt(util.vector().centerOf(BOTTOM_LEFT))
                .placeNearTarget()
                .attachKeyFrame()
                .text("Sneak-right-click a panel to open settings for radar range, map detail, bearing limits, weapon ranges, and automatic modes.");
        scene.idle(85);

        scene.overlay().showControls(util.vector().centerOf(TOP_RIGHT), Pointing.LEFT, 45)
                .rightClick();
        scene.overlay().showText(85)
                .colored(PonderPalette.GREEN)
                .pointAt(util.vector().centerOf(TOP_RIGHT))
                .placeNearTarget()
                .attachKeyFrame()
                .text("Use the screen directly to select contacts, choose the cannon or missile category, set a manual aim point, and fire.");
        scene.idle(90);

        scene.overlay().showText(90)
                .colored(PonderPalette.RED)
                .attachKeyFrame()
                .text("Automatic attack and missile defense require valid bound sensors and weapons. Configure safe firing ranges before enabling either mode.");
        scene.idle(95);
    }

    private static void begin(SceneBuilder scene, SceneBuildingUtil util, String id, String title) {
        scene.title(id, title);
        scene.configureBasePlate(0, 0, 6);
        scene.scaleSceneView(0.95F);
        scene.world().setBlocks(util.select().fromTo(0, 1, 0, 7, 7, 7),
                Blocks.AIR.defaultBlockState(), false);
        scene.showBasePlate();
        placeDisplayWall(scene);
    }

    private static void showWall(SceneBuilder scene, SceneBuildingUtil util) {
        scene.world().showSection(util.select().fromTo(BOTTOM_LEFT, TOP_RIGHT), Direction.DOWN);
    }

    private static void placeDisplay(SceneBuilder scene, BlockPos pos, DisplayConnection connection) {
        BlockState state = ModBlocks.CONNECTED_DISPLAY.get().defaultBlockState()
                .setValue(ConnectedDisplayBlock.FACING, Direction.NORTH)
                .setValue(ConnectedDisplayBlock.CONNECTION, connection);
        scene.world().setBlock(pos, state, false);
    }

    private static void placeDisplayWall(SceneBuilder scene) {
        placeDisplay(scene, BOTTOM_LEFT, DisplayConnection.BOTTOM_LEFT);
        placeDisplay(scene, BOTTOM_MIDDLE, DisplayConnection.BOTTOM);
        placeDisplay(scene, BOTTOM_RIGHT, DisplayConnection.BOTTOM_RIGHT);
        placeDisplay(scene, TOP_LEFT, DisplayConnection.TOP_LEFT);
        placeDisplay(scene, TOP_MIDDLE, DisplayConnection.TOP);
        placeDisplay(scene, TOP_RIGHT, DisplayConnection.TOP_RIGHT);
    }

    private static BlockState cannonMount() {
        return BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath(
                "createbigcannons", "cannon_mount")).defaultBlockState();
    }

    private ConnectedDisplayPonderScenes() {
    }
}
