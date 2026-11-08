package com.btemu.client;

import com.btemu.BlockLogicMonitor;
import com.btemu.BtEmu;
import com.btemu.MonitorGroup;
import com.btemu.TileEntityMonitor;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.render.block.model.BlockModelDispatcher;
import net.minecraft.client.render.block.model.BlockModelHorizontalRotation;
import net.minecraft.core.util.helper.Side;
import net.minecraft.client.render.item.model.ItemModelBlock;
import net.minecraft.client.render.item.model.ItemModelDispatcher;
import net.minecraft.core.block.Block;
import net.minecraft.core.world.pos.TilePos;
import net.minecraft.core.item.Item;
import net.minecraft.core.item.block.ItemBlock;
import turniplabs.halplibe.event.defs.ClientEvents;
import turniplabs.halplibe.util.dependency.Key;

public class BtEmuClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		Key key = Key.of(BtEmu.MOD_ID);
		ClientEvents.BLOCK_MODEL_RELOAD.listen(key, BtEmuClient::bindBlockModels);
		ClientEvents.ITEM_MODEL_RELOAD.listen(key, BtEmuClient::bindItemModels);
		ClientEvents.TILE_ENTITY_RENDERER_RELOAD.listen(key,
			d -> d.assignRenderer(TileEntityMonitor.class, new TileEntityRendererMonitor()));

		TileEntityMonitor.clientTick = Display::heartbeat;
		BlockLogicMonitor.clientRemoved = (world, pos) -> {
			GroupCache.clear();
			Display.onBlockRemoved(world, new TilePos(pos.x(), pos.y(), pos.z()));
		};
		BlockLogicMonitor.clientPlaced = (world, pos) -> GroupCache.clear();
		com.btemu.BlockLogicSpeaker.openScreen = (world, pos) ->
			Minecraft.getMinecraft().displayScreen(new SpeakerScreen(world, new TilePos(pos.x(), pos.y(), pos.z())));

		com.btemu.session.RomStore.dir = new java.io.File(Minecraft.getMinecraft().getMinecraftDir(), "roms");
		com.btemu.emu.CoreStore.dir = new java.io.File(Minecraft.getMinecraft().getMinecraftDir(), "btemu-cores");
		com.btemu.session.RomStore.dir();
		com.btemu.session.Sessions.localSnapshot = Display::localSnapshot;
		com.btemu.net.Msgs.client = new com.btemu.net.Msgs.ClientSink() {
			@Override public void state(com.btemu.net.Msgs.State m) { Display.onState(m); }
			@Override public void frames(com.btemu.net.Msgs.Frames m) { Display.onFrames(m); }
			@Override public void snapshot(com.btemu.net.Msgs.Snapshot m) { Display.onSnapshot(m); }
			@Override public void romData(com.btemu.net.Msgs.RomData m) { Display.onRomData(m); }
			@Override public void tvFrame(com.btemu.net.Msgs.TvFrameDown m) { Display.onTvFrame(m); }
			@Override public void tvAudio(com.btemu.net.Msgs.TvAudioDown m) { Display.onTvAudio(m); }
		};
	}

	private static void bindBlockModels(BlockModelDispatcher dispatcher) {
		bind(dispatcher, BtEmu.MONITOR, "minecraft:block/block_gold", "btemu:block/monitor_front");
		bind(dispatcher, BtEmu.IRON_MONITOR, "minecraft:block/block_iron", "btemu:block/iron_monitor_front");
		bind(dispatcher, BtEmu.DIAMOND_MONITOR, "minecraft:block/block_diamond", "btemu:block/diamond_monitor_front");
		bind(dispatcher, BtEmu.STEEL_MONITOR, "minecraft:block/block_steel", "btemu:block/steel_monitor_front");

		if (BtEmu.SPEAKER != null) dispatcher.addDispatch(new BlockModelSpeaker(BtEmu.SPEAKER, "speaker"));
		if (BtEmu.GOLDEN_SPEAKER != null) dispatcher.addDispatch(new BlockModelSpeaker(BtEmu.GOLDEN_SPEAKER, "golden_speaker"));
		if (BtEmu.WOODEN_MONITOR != null) {

			dispatcher.addDispatch(new BlockModelHorizontalRotation<>(BtEmu.WOODEN_MONITOR)
				.setAllTextures("minecraft:block/stone")
				.setTex("btemu:block/wooden_monitor_front", Side.NORTH)
				.setTex("btemu:block/wooden_monitor_hatch", Side.WEST)
				.setTex("btemu:block/wooden_monitor_vent", Side.TOP));
		}
	}

	private static void bind(BlockModelDispatcher dispatcher, Block<?> block, String texture, String front) {
		if (block == null) return;
		dispatcher.addDispatch(new BlockModelHorizontalRotation<>(block).setAllTextures(texture).setTex(front, Side.NORTH));
	}

	private static void bindItemModels(ItemModelDispatcher dispatcher) {
		BlockModelDispatcher blocks = BlockModelDispatcher.getInstance();
		for (Block<?> b : new Block<?>[]{BtEmu.MONITOR, BtEmu.IRON_MONITOR, BtEmu.DIAMOND_MONITOR, BtEmu.WOODEN_MONITOR, BtEmu.STEEL_MONITOR, BtEmu.SPEAKER, BtEmu.GOLDEN_SPEAKER}) {
			if (b == null) continue;
			if (!blocks.hasDispatch(b)) bindBlockModels(blocks);
			Item item = b.asItem();
			if (item instanceof ItemBlock<?> ib) dispatcher.addDispatch(new ItemModelBlock(ib));
		}
	}
}
