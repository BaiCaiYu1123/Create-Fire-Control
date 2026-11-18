package com.hooya.stabilizedturret.content.armor;

import com.caca.copypoly.Copypoly;
import com.caca.copypoly.PolyBlockEntity;
import com.hooya.stabilizedturret.registry.ModItems;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllItems;
import com.simibubi.create.AllRecipeTypes;
import com.simibubi.create.content.kinetics.crafter.MechanicalCraftingInput;
import com.simibubi.create.content.kinetics.crafter.RecipeGridHandler;
import com.simibubi.create.content.kinetics.mixer.MechanicalMixerBlockEntity;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import com.simibubi.create.content.kinetics.press.MechanicalPressBlockEntity;
import com.simibubi.create.content.processing.basin.BasinBlockEntity;
import com.simibubi.create.content.processing.burner.BlazeBurnerBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.SmithingMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import rbasamoyai.createbigcannons.block_armor_properties.BlockArmorPropertiesHandler;

import java.util.List;

@PrefixGameTestTemplate(false)
public final class CompositeArmorGameTests {
    private static final BlockPos POS = new BlockPos(2, 2, 2);

    @GameTest(template = "empty", templateNamespace = "copypoly")
    public static void smithingConsumesOnlyPlatesUsed(GameTestHelper helper) {
        helper.setBlock(POS, Blocks.SMITHING_TABLE);
        var player = helper.makeMockPlayer(GameType.SURVIVAL);
        var menu = new SmithingMenu(0, player.getInventory(),
                ContainerLevelAccess.create(helper.getLevel(), helper.absolutePos(POS)));
        ItemStack armor = namedArmor(1);
        armor.setCount(2);
        menu.getSlot(SmithingMenu.BASE_SLOT).set(armor);
        ItemStack plates = new ItemStack(ModItems.COMPOSITE_ARMOR_PLATE.get(), 12);
        menu.getSlot(SmithingMenu.ADDITIONAL_SLOT).set(plates);
        assertOutput(helper, menu.getSlot(SmithingMenu.RESULT_SLOT).getItem(), 13);
        menu.clicked(SmithingMenu.RESULT_SLOT, 0, ClickType.PICKUP, player);
        assertOutput(helper, menu.getCarried(), 13);
        helper.assertTrue(menu.getSlot(SmithingMenu.ADDITIONAL_SLOT).getItem().isEmpty(), "Smithing did not empty the addition slot");
        helper.assertTrue(menu.getSlot(SmithingMenu.BASE_SLOT).getItem().getCount() == 1, "Smithing took multiple armor blocks");

        menu.setCarried(ItemStack.EMPTY);
        menu.getSlot(SmithingMenu.BASE_SLOT).set(namedArmor(98).copyWithCount(2));
        menu.getSlot(SmithingMenu.ADDITIONAL_SLOT).set(new ItemStack(ModItems.COMPOSITE_ARMOR_PLATE.get(), 9));
        assertOutput(helper, menu.getSlot(SmithingMenu.RESULT_SLOT).getItem(), 100);
        menu.clicked(SmithingMenu.RESULT_SLOT, 0, ClickType.QUICK_MOVE, player);
        helper.assertTrue(player.getInventory().items.stream().anyMatch(stack -> ArmorUpgrade.level(stack) == 100),
                "Shift-click did not deliver level 100");
        helper.assertTrue(menu.getSlot(SmithingMenu.ADDITIONAL_SLOT).getItem().getCount() == 7,
                "Shift-click did not preserve the seven excess plates");
        helper.assertTrue(menu.getSlot(SmithingMenu.BASE_SLOT).getItem().getCount() == 1,
                "Shift-click processed the second armor in the base slot");

        menu.getSlot(SmithingMenu.BASE_SLOT).set(namedArmor(100));
        menu.getSlot(SmithingMenu.ADDITIONAL_SLOT).set(new ItemStack(ModItems.COMPOSITE_ARMOR_PLATE.get(), 4));
        helper.assertTrue(!menu.getSlot(SmithingMenu.RESULT_SLOT).hasItem(), "Max-level armor can be upgraded again");
        helper.assertTrue(menu.getSlot(SmithingMenu.ADDITIONAL_SLOT).getItem().getCount() == 4, "Max-level armor consumed plates");

        menu.getSlot(SmithingMenu.TEMPLATE_SLOT).set(new ItemStack(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE, 2));
        menu.getSlot(SmithingMenu.BASE_SLOT).set(new ItemStack(Items.DIAMOND_AXE));
        menu.getSlot(SmithingMenu.ADDITIONAL_SLOT).set(new ItemStack(Items.NETHERITE_INGOT, 5));
        menu.clicked(SmithingMenu.RESULT_SLOT, 0, ClickType.PICKUP, player);
        helper.assertTrue(menu.getCarried().is(Items.NETHERITE_AXE), "Vanilla smithing no longer works");
        helper.assertTrue(menu.getSlot(SmithingMenu.ADDITIONAL_SLOT).getItem().getCount() == 4, "Vanilla smithing consumed extra ingots");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "copypoly", timeoutTicks = 240)
    public static void poweredMixerRequiresHeat(GameTestHelper helper) {
        BasinBlockEntity basin = prepareBasin(helper);
        BlockPos mixerPos = POS.above(2);
        helper.setBlock(mixerPos, AllBlocks.MECHANICAL_MIXER.get());
        helper.setBlock(mixerPos.east(), AllBlocks.COGWHEEL.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.Y));
        helper.setBlock(mixerPos.east().above(),
                AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(CreativeMotorBlock.FACING, Direction.DOWN));
        CreativeMotorBlockEntity motor = helper.getBlockEntity(mixerPos.east().above());
        motor.generatedSpeed.setValue(64);
        MechanicalMixerBlockEntity mixer = helper.getBlockEntity(mixerPos);
        helper.runAfterDelay(65, () -> {
            helper.assertTrue(Math.abs(mixer.getSpeed()) >= 32, "Fixture did not power the mixer");
            helper.assertTrue(basin.getOutputInventory().getStackInSlot(0).isEmpty(), "Cold mixer produced an upgrade");
            helper.assertTrue(basin.getInputInventory().getStackInSlot(1).getCount() == 3, "Cold mixer consumed plates");
            helper.setBlock(POS.below(), AllBlocks.BLAZE_BURNER.getDefaultState()
                    .setValue(BlazeBurnerBlock.HEAT_LEVEL, BlazeBurnerBlock.HeatLevel.SMOULDERING));
            var burnerPos = helper.absolutePos(POS.below());
            var level = helper.getLevel();
            var fuel = BlazeBurnerBlock.tryInsert(level.getBlockState(burnerPos), level, burnerPos,
                    new ItemStack(Items.COAL), false, true, false);
            helper.assertTrue(fuel.getResult().consumesAction(), "Fixture could not fuel the blaze burner");
        });
        helper.succeedWhen(() -> {
            assertOutput(helper, basin.getOutputInventory().getStackInSlot(0), 8);
            helper.assertTrue(basin.getInputInventory().getStackInSlot(1).getCount() == 2, "Mixer did not consume one plate");
        });
    }

    @GameTest(template = "empty", templateNamespace = "copypoly", timeoutTicks = 220)
    public static void poweredPressUpgradesArmorAndLeavesBlockedInputs(GameTestHelper helper) {
        BasinBlockEntity basin = prepareBasin(helper);
        var output = basin.getOutputInventory();
        for (int slot = 0; slot < output.getSlots(); slot++) output.setStackInSlot(slot, new ItemStack(Items.DIRT, 64));
        var press = placePoweredPress(helper);
        helper.runAfterDelay(70, () -> {
            helper.assertTrue(press.getSpeed() != 0, "Fixture did not power the press");
            helper.assertTrue(basin.getInputInventory().getStackInSlot(1).getCount() == 3,
                    "Blocked press consumed a plate");
            for (int slot = 0; slot < output.getSlots(); slot++) output.setStackInSlot(slot, ItemStack.EMPTY);
            basin.notifyChangeOfContents();
        });
        helper.succeedWhen(() -> {
            assertOutput(helper, output.getStackInSlot(0), 8);
            helper.assertTrue(basin.getInputInventory().getStackInSlot(1).getCount() == 2,
                    "Press did not consume one plate for one level");
        });
    }

    @GameTest(template = "empty", templateNamespace = "copypoly", timeoutTicks = 220)
    public static void poweredPressMakesOnePlateFromTwoIronAndOneCopper(GameTestHelper helper) {
        helper.setBlock(POS, AllBlocks.BASIN.get());
        BasinBlockEntity basin = helper.getBlockEntity(POS);
        var input = basin.getInputInventory();
        var output = basin.getOutputInventory();
        input.setStackInSlot(0, AllItems.IRON_SHEET.asStack());
        input.setStackInSlot(1, AllItems.COPPER_SHEET.asStack(2));
        var press = placePoweredPress(helper);
        helper.runAfterDelay(70, () -> {
            helper.assertTrue(press.getSpeed() != 0, "Fixture did not power the press");
            helper.assertTrue(output.getStackInSlot(0).isEmpty() && input.getStackInSlot(0).getCount() == 1
                    && input.getStackInSlot(1).getCount() == 2, "One iron sheet matched or consumed ingredients");
            input.setStackInSlot(0, AllItems.IRON_SHEET.asStack(3));
            basin.notifyChangeOfContents();
        });
        helper.succeedWhen(() -> {
            var plate = output.getStackInSlot(0);
            helper.assertTrue(plate.is(ModItems.COMPOSITE_ARMOR_PLATE.get()) && plate.getCount() == 1,
                    "Press must produce exactly one composite plate");
            helper.assertTrue(input.getStackInSlot(0).getCount() == 1 && input.getStackInSlot(1).getCount() == 1,
                    "Press did not consume two iron sheets and one copper sheet");
        });
    }

    @GameTest(template = "empty", templateNamespace = "copypoly")
    public static void armorCraftsWithZincXInBothCraftingTypes(GameTestHelper helper) {
        var level = helper.getLevel();
        var recipes = level.getRecipeManager();
        ItemStack zinc = AllItems.ZINC_INGOT.asStack();
        ItemStack plate = new ItemStack(ModItems.COMPOSITE_ARMOR_PLATE.get());
        var xPattern = List.of(zinc, plate, zinc, plate, zinc, plate, zinc, plate, zinc);
        var invalid = List.of(plate, zinc, plate, zinc, zinc, zinc, plate, zinc, plate);
        RecipeType<CraftingRecipe> mechanical = AllRecipeTypes.MECHANICAL_CRAFTING.getType();
        for (var type : List.of(RecipeType.CRAFTING, mechanical)) {
            var registries = level.registryAccess();
            var pattern = type == mechanical ? mechanicalGrid(xPattern, registries) : CraftingInput.of(3, 3, xPattern);
            var wrong = type == mechanical ? mechanicalGrid(invalid, registries) : CraftingInput.of(3, 3, invalid);
            var recipe = recipes.getRecipeFor(type, pattern, level)
                    .orElseThrow(() -> new IllegalStateException("Missing X-shaped armor recipe for " + type));
            var armor = recipe.value().assemble(pattern, registries);
            helper.assertTrue(armor.is(Copypoly.ITEM.get()) && armor.getCount() == 1 && ArmorUpgrade.level(armor) == 1,
                    "X pattern did not yield one level-one armor in " + type);
            helper.assertTrue(!recipe.value().matches(wrong, level), "Incorrect zinc layout matched " + type);
        }
        helper.assertTrue(recipes.byKey(ResourceLocation.fromNamespaceAndPath("create_fire_control", "composite_armor_plate")).isEmpty(),
                "Old workbench composite plate recipe still exists");
        helper.assertTrue(recipes.getRecipesFor(RecipeType.STONECUTTING, new SingleRecipeInput(zinc), level).stream()
                .noneMatch(recipe -> recipe.value().getResultItem(level.registryAccess()).is(Copypoly.ITEM.get())),
                "Old zinc stonecutting recipe still creates armor");
        helper.succeed();
    }

    private static CraftingInput mechanicalGrid(List<ItemStack> stacks, HolderLookup.Provider registries) {
        var cells = new ListTag();
        for (int slot = 0; slot < stacks.size(); slot++) {
            var cell = new CompoundTag();
            cell.putInt("x", slot % 3);
            cell.putInt("y", slot / 3);
            cell.put("item", stacks.get(slot).saveOptional(registries));
            cells.add(cell);
        }
        var data = new CompoundTag();
        data.put("Grid", cells);
        var grid = RecipeGridHandler.GroupedItems.read(data, registries);
        grid.calcStats();
        return MechanicalCraftingInput.of(grid);
    }

    @GameTest(template = "empty", templateNamespace = "copypoly")
    public static void armorLevelSurvivesPlacementAndScalesStats(GameTestHelper helper) {
        var player = helper.makeMockPlayer(GameType.SURVIVAL);
        helper.setBlock(POS.below(), Blocks.STONE);
        var pos = helper.absolutePos(POS);
        player.setPos(Vec3.atBottomCenterOf(pos.east()));
        ItemStack stack = namedArmor(100);
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        var hit = new BlockHitResult(Vec3.atBottomCenterOf(pos), Direction.UP, pos.below(), false);
        helper.assertTrue(stack.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, hit)).consumesAction(),
                "Armor item placement did not succeed");
        PolyBlockEntity armor = helper.getBlockEntity(POS);
        var access = (PolyArmorAccess) armor;
        helper.assertTrue(access.fireControl$armorLevel() == 100, "Placed armor lost its level");
        armor.setMaterial(Blocks.ANCIENT_DEBRIS.defaultBlockState());
        var level = helper.getLevel();
        var state = armor.getBlockState();
        var properties = BlockArmorPropertiesHandler.getProperties(state);
        double highHardness = properties.hardness(level, state, pos, true);
        double highToughness = properties.toughness(level, state, pos, true);
        var saved = armor.saveWithoutMetadata(level.registryAccess());
        var dropped = Block.getDrops(state, level, pos, armor).stream()
                .filter(drop -> drop.is(Copypoly.ITEM.get())).findFirst().orElseThrow();
        helper.assertTrue(ArmorUpgrade.level(dropped) == 100, "Armor drop lost its level");
        access.fireControl$setArmorLevel(1);
        close(helper, 100 * properties.hardness(level, state, pos, true), highHardness, "CBC hardness");
        close(helper, 100 * properties.toughness(level, state, pos, true), highToughness, "CBC toughness");
        if (ModList.get().isLoaded("cbc_terminal_ballistics")) TerminalChecks.verify(helper, access);
        armor.loadWithComponents(saved, level.registryAccess());
        helper.assertTrue(access.fireControl$armorLevel() == 100, "Armor block entity reload lost its level");
        helper.succeed();
    }

    private static BasinBlockEntity prepareBasin(GameTestHelper helper) {
        helper.setBlock(POS, AllBlocks.BASIN.get());
        BasinBlockEntity basin = helper.getBlockEntity(POS);
        basin.getInputInventory().setStackInSlot(0, namedArmor(7));
        basin.getInputInventory().setStackInSlot(1, new ItemStack(ModItems.COMPOSITE_ARMOR_PLATE.get(), 3));
        return basin;
    }

    private static MechanicalPressBlockEntity placePoweredPress(GameTestHelper helper) {
        BlockPos pressPos = POS.above(2);
        helper.setBlock(pressPos, AllBlocks.MECHANICAL_PRESS.getDefaultState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST));
        helper.setBlock(pressPos.east(), AllBlocks.CREATIVE_MOTOR.getDefaultState()
                .setValue(CreativeMotorBlock.FACING, Direction.WEST));
        CreativeMotorBlockEntity motor = helper.getBlockEntity(pressPos.east());
        motor.generatedSpeed.setValue(64);
        return helper.getBlockEntity(pressPos);
    }

    private static ItemStack namedArmor(int level) {
        ItemStack armor = Copypoly.ITEM.get().getDefaultInstance();
        ArmorUpgrade.writeLevel(armor, level);
        armor.set(DataComponents.CUSTOM_NAME, Component.literal("Test armor"));
        return armor;
    }

    private static void assertOutput(GameTestHelper helper, ItemStack stack, int level) {
        helper.assertTrue(stack.is(Copypoly.ITEM.get()) && ArmorUpgrade.level(stack) == level,
                "Expected an armor item at level " + level + ": " + stack);
        helper.assertTrue(stack.getHoverName().getString().equals("Test armor"), "Upgrade dropped item components");
    }

    private static void close(GameTestHelper helper, double expected, double actual, String stat) {
        helper.assertTrue(Math.abs(actual - expected) < 1e-6, stat + " expected " + expected + " but got " + actual);
    }

    private static final class TerminalChecks {
        private static void verify(GameTestHelper helper, PolyArmorAccess armor) {
            var level = helper.getLevel();
            var pos = helper.absolutePos(POS);
            var base = com.cbc_terminal_ballistics.debug.InspectionSnapshot.build(level, pos, null);
            armor.fireControl$setArmorLevel(100);
            var upgraded = com.cbc_terminal_ballistics.debug.InspectionSnapshot.build(level, pos, null);
            close(helper, 100 * base.ductility(), upgraded.ductility(), "CTB ductility");
            close(helper, 100 * base.brittleness(), upgraded.brittleness(), "CTB brittleness");
            close(helper, 100 * base.spallMultiplier(), upgraded.spallMultiplier(), "CTB spall multiplier");
        }
    }
}
