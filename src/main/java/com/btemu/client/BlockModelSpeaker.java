package com.btemu.client;

import com.btemu.BlockLogicSpeaker;
import net.minecraft.client.render.block.model.BlockModelStandard;
import net.minecraft.client.render.texture.stitcher.IconCoordinate;
import net.minecraft.client.render.texture.stitcher.TextureRegistry;
import net.minecraft.core.block.Block;
import net.minecraft.core.util.helper.Side;
import net.minecraft.core.world.WorldSource;
import net.minecraft.core.world.pos.TilePos;
import net.minecraft.core.world.pos.TilePosc;

final class BlockModelSpeaker extends BlockModelStandard<BlockLogicSpeaker> {
	private static final int UP = 1, DOWN = 2, LEFT = 4, RIGHT = 8;
	private final IconCoordinate[] icons = new IconCoordinate[16];

	BlockModelSpeaker(Block<BlockLogicSpeaker> block, String name) {
		super(block);
		for (int i = 0; i < 16; i++) icons[i] = TextureRegistry.getTexture("btemu:block/" + name + "/" + i);
		setAllTextures(icons[0]);
	}

	@Override
	public IconCoordinate getBlockTexture(WorldSource world, TilePosc pos, Side side) {
		TilePos t = new TilePos();
		int bits = 0;
		switch (side) {
			case SOUTH -> {
				if (same(world, pos.up(t))) bits |= UP;
				if (same(world, pos.down(t))) bits |= DOWN;
				if (same(world, pos.west(t))) bits |= LEFT;
				if (same(world, pos.east(t))) bits |= RIGHT;
			}
			case NORTH -> {
				if (same(world, pos.up(t))) bits |= UP;
				if (same(world, pos.down(t))) bits |= DOWN;
				if (same(world, pos.east(t))) bits |= LEFT;
				if (same(world, pos.west(t))) bits |= RIGHT;
			}
			case WEST -> {
				if (same(world, pos.up(t))) bits |= UP;
				if (same(world, pos.down(t))) bits |= DOWN;
				if (same(world, pos.north(t))) bits |= LEFT;
				if (same(world, pos.south(t))) bits |= RIGHT;
			}
			case EAST -> {
				if (same(world, pos.up(t))) bits |= UP;
				if (same(world, pos.down(t))) bits |= DOWN;
				if (same(world, pos.south(t))) bits |= LEFT;
				if (same(world, pos.north(t))) bits |= RIGHT;
			}
			default -> {
				if (same(world, pos.north(t))) bits |= UP;
				if (same(world, pos.south(t))) bits |= DOWN;
				if (same(world, pos.west(t))) bits |= LEFT;
				if (same(world, pos.east(t))) bits |= RIGHT;
			}
		}
		return icons[bits];
	}

	private boolean same(WorldSource world, TilePosc p) {
		return world.getBlockType(p) == block;
	}
}
