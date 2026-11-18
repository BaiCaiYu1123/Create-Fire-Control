package com.hooya.stabilizedturret.client.ponder;

import com.hooya.stabilizedturret.registry.ModBlocks;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllItems;
import net.createmod.catnip.math.Pointing;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.element.TextElementBuilder;
import net.createmod.ponder.api.registration.PonderSceneRegistrationHelper;
import net.createmod.ponder.api.scene.PonderStoryBoard;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * A concise, visual operating manual attached to the Fire-control Computer.
 * Every page deliberately focuses on one job so a new player can understand
 * the installation order without reading the full settings GUI first.
 */
public final class FireControlPonderScenes {
    /* Ponder requires a template even though these scenes build their rig in code. */
    static final ResourceLocation BASE_TEMPLATE = createTemplate("empty_blaze_burner");
    private static final BlockPos COMPUTER = new BlockPos(2, 1, 2);
    private static final BlockPos YAW = new BlockPos(2, 1, 3);
    private static final BlockPos PITCH = new BlockPos(2, 2, 3);
    private static final BlockPos SEAT = new BlockPos(1, 1, 2);
    private static final BlockPos SIGHT = new BlockPos(3, 1, 2);

    public static void register(PonderSceneRegistrationHelper<ResourceLocation> helper) {
        helper.forComponents(ModBlocks.STABILIZER_CONTROLLER.getId())
                .addStoryBoard(BASE_TEMPLATE, FireControlPonderScenes::architecture,
                        FireControlPonderPlugin.FIRE_CONTROL)
                .addStoryBoard(BASE_TEMPLATE, FireControlPonderScenes::binding,
                        FireControlPonderPlugin.FIRE_CONTROL)
                .addStoryBoard(BASE_TEMPLATE, FireControlPonderScenes::configuration,
                        FireControlPonderPlugin.FIRE_CONTROL)
                .addStoryBoard(BASE_TEMPLATE, FireControlPonderScenes::operation,
                        FireControlPonderPlugin.FIRE_CONTROL)
                .addStoryBoard(BASE_TEMPLATE, FireControlPonderScenes::optics,
                        FireControlPonderPlugin.FIRE_CONTROL)
                .addStoryBoard(BASE_TEMPLATE, FireControlPonderScenes::troubleshooting,
                        FireControlPonderPlugin.FIRE_CONTROL);

        MzLoaderPonderScenes.register(helper);
        ConnectedDisplayPonderScenes.register(helper);
    }

    private static void architecture(SceneBuilder scene, SceneBuildingUtil util) {
        begin(scene, util, "fire_control_architecture", "Planning the stabilized structure");
        place(scene, util, YAW, swivelBearing());
        place(scene, util, PITCH, swivelBearing());
        scene.world().setBlocks(util.select().fromTo(1, 2, 4, 3, 2, 4),
                Blocks.IRON_BLOCK.defaultBlockState(), false);
        scene.world().showSection(util.select().fromTo(1, 2, 4, 3, 2, 4), Direction.DOWN);
        scene.idle(10);

        explain(scene, util, YAW, 70, PonderPalette.BLUE,
                "The horizontal physical bearing is the yaw drive: its child structure is the turret.");
        scene.idle(75);
        explain(scene, util, PITCH, 70, PonderPalette.GREEN,
                "Install the pitch bearing on the turret. Its child structure is the cannon or sensor head.");
        scene.idle(75);
        scene.overlay().showText(90)
                .colored(PonderPalette.RED)
                .attachKeyFrame()
                .text("Required hierarchy: hull → yaw bearing → turret → pitch bearing → cannon. Assemble both bearings before testing.");
        scene.idle(95);
    }

    private static void binding(SceneBuilder scene, SceneBuildingUtil util) {
        begin(scene, util, "fire_control_binding", "Binding devices with the Create Wrench");
        place(scene, util, YAW, swivelBearing());
        place(scene, util, PITCH, swivelBearing());
        place(scene, util, SEAT, AllBlocks.SEATS.get(net.minecraft.world.item.DyeColor.GRAY).getDefaultState());
        place(scene, util, SIGHT, Blocks.OBSERVER.defaultBlockState());
        scene.idle(10);

        Vec3 computerTop = util.vector().topOf(COMPUTER);
        scene.overlay().showControls(computerTop, Pointing.DOWN, 45)
                .rightClick()
                .withItem(AllItems.WRENCH.asStack());
        scene.overlay().showText(70)
                .pointAt(computerTop)
                .placeNearTarget()
                .attachKeyFrame()
                .text("First, right-click the Fire-control Computer with a Create Wrench to enter binding mode.");
        scene.idle(75);

        scene.overlay().showOutline(PonderPalette.BLUE, "bindable",
                util.select().fromTo(SEAT, SIGHT), 75);
        scene.overlay().showText(75)
                .colored(PonderPalette.BLUE)
                .pointAt(util.vector().centerOf(SEAT))
                .placeNearTarget()
                .text("Blue outlines are compatible, unbound modules. Keep holding the wrench and right-click the module you need.");
        scene.idle(80);

        scene.overlay().showOutline(PonderPalette.RED, "bound",
                util.select().fromTo(YAW, PITCH), 75);
        scene.overlay().showText(75)
                .colored(PonderPalette.RED)
                .pointAt(util.vector().centerOf(YAW))
                .placeNearTarget()
                .attachKeyFrame()
                .text("Red outlines are already bound to this computer. Bearings are auto-classified by placement and can be reassigned in Bound Devices.");
        scene.idle(80);

        scene.overlay().showText(80)
                .attachKeyFrame()
                .text("Bind at least: yaw bearing, pitch bearing, operator seat, and an assembled cannon mount or supported ground-vehicle missile launcher.");
        scene.idle(85);
    }

    private static void configuration(SceneBuilder scene, SceneBuildingUtil util) {
        begin(scene, util, "fire_control_configuration", "Configuring limits, speeds and direction");
        place(scene, util, YAW, swivelBearing());
        place(scene, util, PITCH, swivelBearing());
        scene.idle(10);

        scene.overlay().showControls(util.vector().topOf(COMPUTER), Pointing.DOWN, 40)
                .rightClick();

        explain(scene, util, COMPUTER, 80, PonderPalette.WHITE,
                "Open the computer GUI. Use Yaw Settings for traverse speed/offset and Pitch Settings for depression, elevation, speed and offset.");
        scene.idle(85);
        explain(scene, util, PITCH, 80, PonderPalette.RED,
                "Depression and elevation are rigid limits relative to the turret. They also apply when stress power tries to drive past them.");
        scene.idle(85);
        explain(scene, util, YAW, 80, PonderPalette.BLUE,
                "Offsets move the controlled child structure around its bearing anchor. Adjust in 0.1-block steps and test at low speed.");
        scene.idle(85);
        scene.overlay().showText(90)
                .colored(PonderPalette.GREEN)
                .attachKeyFrame()
                .text("If an axis turns away from the reticle, enable only that axis' Reverse option. Manual reverse overrides automatic direction detection.");
        scene.idle(95);
    }

    private static void operation(SceneBuilder scene, SceneBuildingUtil util) {
        begin(scene, util, "fire_control_operation", "Stabilizer and head-aim operation");
        place(scene, util, YAW, swivelBearing());
        place(scene, util, PITCH, swivelBearing());
        place(scene, util, SEAT, AllBlocks.SEATS.get(net.minecraft.world.item.DyeColor.GRAY).getDefaultState());
        scene.idle(10);

        explain(scene, util, COMPUTER, 75, PonderPalette.GREEN,
                "Enable Stabilizer to isolate each controlled rotation axis from its parent structure.");
        scene.idle(80);
        explain(scene, util, SEAT, 80, PonderPalette.BLUE,
                "Sit in the bound operator seat. Head Aim then drives the cannon aim point toward your reticle while respecting speed and pitch limits.");
        scene.idle(85);
        scene.overlay().showLine(PonderPalette.GREEN,
                util.vector().centerOf(PITCH), util.vector().of(2.5, 3.0, 6.0), 70);
        scene.overlay().showText(70)
                .pointAt(util.vector().of(2.5, 3.0, 6.0))
                .placeNearTarget()
                .attachKeyFrame()
                .text("The cannon ray, not the player's body rotation, is the final aiming reference. First-person and unlocked Sable view remain stabilized.");
        scene.idle(75);
        scene.overlay().showText(85)
                .colored(PonderPalette.RED)
                .attachKeyFrame()
                .text("Remote authorization only replaces who activates Head Aim. Seat optics, radar, locking and other seat functions remain available.");
        scene.idle(90);
    }

    private static void optics(SceneBuilder scene, SceneBuildingUtil util) {
        begin(scene, util, "fire_control_optics", "Scope, optical pod and thermal imaging");
        place(scene, util, SIGHT, Blocks.OBSERVER.defaultBlockState());
        scene.idle(10);

        explain(scene, util, SIGHT, 80, PonderPalette.BLUE,
                "Bind an assembled cannon mount for the gun sight, or bind an electro-optical pod for an independently aimed sensor view.");
        scene.idle(85);
        scene.overlay().showCenteredScrollInput(SIGHT, Direction.UP, 55);
        scene.overlay().showText(75)
                .pointAt(util.vector().topOf(SIGHT))
                .placeNearTarget()
                .attachKeyFrame()
                .text("While either optical view is open, use the mouse wheel to change magnification. Zoom eases smoothly and is remembered separately.");
        scene.idle(80);
        scene.effects().indicateRedstone(COMPUTER);
        explain(scene, util, COMPUTER, 85, PonderPalette.GREEN,
                "Bind input actions in Input Bindings: one action toggles the selected optical view, and Thermal toggles white-hot imaging. Pod thermal starts off.");
        scene.idle(90);
        scene.overlay().showText(80)
                .colored(PonderPalette.RED)
                .attachKeyFrame()
                .text("Only one player can use a particular scope or pod at a time. Leaving the seat, dying, or losing the device safely closes the view.");
        scene.idle(85);
    }

    private static void troubleshooting(SceneBuilder scene, SceneBuildingUtil util) {
        begin(scene, util, "fire_control_troubleshooting", "Quick fault-finding checklist");
        place(scene, util, YAW, swivelBearing());
        place(scene, util, PITCH, swivelBearing());
        place(scene, util, SIGHT, Blocks.OBSERVER.defaultBlockState());
        scene.idle(10);

        explain(scene, util, YAW, 70, PonderPalette.RED,
                "Axis does not move: confirm the physical bearing is assembled, still bound, has a child structure, and its maximum speed is above zero.");
        scene.idle(75);
        explain(scene, util, PITCH, 70, PonderPalette.RED,
                "Pitch runs away or reverses: verify the hierarchy, then toggle Pitch Reverse. Do not compensate by swapping depression/elevation signs.");
        scene.idle(75);
        explain(scene, util, SIGHT, 75, PonderPalette.BLUE,
                "Head Aim has no direction: bind an assembled cannon mount or supported ground-vehicle missile launcher and verify the operator is in the bound seat.");
        scene.idle(80);
        scene.overlay().showText(90)
                .colored(PonderPalette.GREEN)
                .attachKeyFrame()
                .text("Recommended first test: low traverse speed, ±10° pitch limits, stabilizer on, head aim on. Expand limits only after both axes follow correctly.");
        scene.idle(95);
    }

    private static void begin(
            SceneBuilder scene,
            SceneBuildingUtil util,
            String id,
            String title
    ) {
        scene.title(id, title);
        scene.configureBasePlate(0, 0, 6);
        scene.scaleSceneView(0.9F);
        scene.world().setBlocks(util.select().fromTo(0, 1, 0, 7, 7, 7),
                Blocks.AIR.defaultBlockState(), false);
        scene.showBasePlate();
        scene.world().setBlock(COMPUTER,
                ModBlocks.STABILIZER_CONTROLLER.get().defaultBlockState(), false);
        scene.world().showSection(util.select().position(COMPUTER), Direction.DOWN);
        scene.idle(10);
    }

    private static void place(
            SceneBuilder scene,
            SceneBuildingUtil util,
            BlockPos pos,
            BlockState state
    ) {
        scene.world().setBlock(pos, state, false);
        scene.world().showSection(util.select().position(pos), Direction.DOWN);
    }

    private static void explain(
            SceneBuilder scene,
            SceneBuildingUtil util,
            BlockPos pos,
            int duration,
            PonderPalette color,
            String text
    ) {
        TextElementBuilder builder = scene.overlay()
                .showOutlineWithText(util.select().position(pos), duration)
                .colored(color)
                .pointAt(util.vector().centerOf(pos))
                .placeNearTarget()
                .attachKeyFrame();
        builder.text(text);
    }

    private static BlockState swivelBearing() {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath("simulated", "swivel_bearing");
        Block block = BuiltInRegistries.BLOCK.get(id);
        return block.defaultBlockState();
    }

    private static ResourceLocation createTemplate(String path) {
        return ResourceLocation.fromNamespaceAndPath("create", path);
    }

    private FireControlPonderScenes() {
    }
}
