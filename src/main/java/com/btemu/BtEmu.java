package com.btemu;

import net.fabricmc.api.ModInitializer;
import net.minecraft.core.block.Block;
import net.minecraft.core.block.Blocks;
import net.minecraft.core.data.registry.Registries;
import net.minecraft.core.item.Item;
import net.minecraft.core.item.ItemStack;
import net.minecraft.core.item.Items;
import net.minecraft.core.sound.BlockSounds;
import net.minecraft.core.util.collection.NamespaceID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import turniplabs.halplibe.HalpLibe;
import turniplabs.halplibe.event.defs.CommonEvents;
import turniplabs.halplibe.helper.BlockBuilder;
import turniplabs.halplibe.helper.EntityHelper;
import turniplabs.halplibe.helper.RecipeBuilder;
import turniplabs.halplibe.util.dependency.Key;

public class BtEmu implements ModInitializer {
	public static final String MOD_ID = HalpLibe.registerMod("btemu", true);
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	public static Block<BlockLogicMonitor> MONITOR;
	public static Block<BlockLogicMonitor> IRON_MONITOR;
	public static Block<BlockLogicMonitor> DIAMOND_MONITOR;
	public static Block<BlockLogicMonitor> WOODEN_MONITOR;
	public static Block<BlockLogicMonitor> STEEL_MONITOR;
	public static Block<BlockLogicSpeaker> SPEAKER;
	public static Block<BlockLogicSpeaker> GOLDEN_SPEAKER;
	public static final int SPEAKER_ID = 3905, GOLDEN_SPEAKER_ID = 3906;
	private static boolean started = false;

	@Override
	public void onInitialize() {
		LogGuard.install();
		com.btemu.session.AutoTest.start();
		EntityHelper.addMapping(TileEntityMonitor.class, NamespaceID.fromPool(MOD_ID, "monitor"));
		EntityHelper.addMapping(TileEntitySpeaker.class, NamespaceID.fromPool(MOD_ID, "speaker"));
		com.btemu.net.Msgs.register();
		CommonEvents.BEFORE_GAME_START.listen(Key.of(MOD_ID), BtEmu::registerBlocks);
		CommonEvents.AFTER_GAME_START.listen(Key.of(MOD_ID), () -> {
			RecipeBuilder.initNameSpace(MOD_ID);
			registerRecipes();
			started = true;
		});
		CommonEvents.RECIPES_NAMESPACE_INIT.listen(Key.of(MOD_ID), () -> {
			RecipeBuilder.initNameSpace(MOD_ID);
			if (started) registerRecipes();
		});
	}

	private static Block<BlockLogicMonitor> build(MonitorKind kind) {

		return new BlockBuilder(MOD_ID)
			.setHardness(3.0f)
			.setResistance(6.0f)
			.setBlockSound(BlockSounds.METAL)
			.setCreativeInventoryPlacement(new turniplabs.halplibe.helper.creativeInventory.CreativeInventoryPlacement.Category(
				turniplabs.halplibe.helper.creativeInventory.CreativeInventoryCategory.WORKBENCHES))
			.build(kind.name, kind.blockId, b -> new BlockLogicMonitor(b, kind));
	}

	private static void registerBlocks() {
		MONITOR = build(MonitorKind.GOLD);
		IRON_MONITOR = build(MonitorKind.IRON);
		DIAMOND_MONITOR = build(MonitorKind.DIAMOND);
		WOODEN_MONITOR = build(MonitorKind.WOOD);
		STEEL_MONITOR = build(MonitorKind.STEEL);
		SPEAKER = speaker("speaker", SPEAKER_ID, false);
		GOLDEN_SPEAKER = speaker("golden_speaker", GOLDEN_SPEAKER_ID, true);
	}

	private static Block<BlockLogicSpeaker> speaker(String name, int id, boolean golden) {
		return new BlockBuilder(MOD_ID)
			.setHardness(3.0f)
			.setResistance(6.0f)
			.setBlockSound(BlockSounds.METAL)
			.setCreativeInventoryPlacement(new turniplabs.halplibe.helper.creativeInventory.CreativeInventoryPlacement.Category(
				turniplabs.halplibe.helper.creativeInventory.CreativeInventoryCategory.WORKBENCHES))
			.build(name, id, b -> new BlockLogicSpeaker(b, golden));
	}

	private static void registerRecipes() {
		recipe("monitor", Items.INGOT_GOLD, MONITOR);

		RecipeBuilder.Shaped(MOD_ID).setShape("III", "IGI", "III")
			.addInput('I', Items.INGOT_IRON)
			.addInput('G', Blocks.GLASS)
			.create("iron_monitor", new ItemStack(IRON_MONITOR, 1));
		recipe("diamond_monitor", Items.DIAMOND, DIAMOND_MONITOR);
		recipe("steel_monitor", Items.INGOT_STEEL, STEEL_MONITOR);

		RecipeBuilder.Shaped(MOD_ID).setShape("PPP", "PGP", "PPP")
			.addInput('P', Blocks.STONE)
			.addInput('G', Blocks.GLASS)
			.create("wooden_monitor", new ItemStack(WOODEN_MONITOR, 1));

		int black = net.minecraft.core.util.helper.DyeColor.BLACK.blockMeta;
		RecipeBuilder.Shaped(MOD_ID).setShape("III", "IWI", "III")
			.addInput('I', Items.INGOT_IRON)
			.addInput('W', Blocks.WOOL, black)
			.create("speaker", new ItemStack(SPEAKER, 1));
		RecipeBuilder.Shaped(MOD_ID).setShape("III", "IWI", "III")
			.addInput('I', Items.INGOT_GOLD)
			.addInput('W', Blocks.WOOL, black)
			.create("golden_speaker", new ItemStack(GOLDEN_SPEAKER, 1));
		Registries.RECIPES.invalidateCaches();
	}

	private static void recipe(String name, Item metal, Block<?> out) {
		RecipeBuilder.Shaped(MOD_ID).setShape("MMM", "MGM", "MRM")
			.addInput('M', metal)
			.addInput('G', Blocks.GLASS)
			.addInput('R', Items.DUST_REDSTONE)
			.create(name, new ItemStack(out, 1));
	}
}
