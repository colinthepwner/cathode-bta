package com.btemu;

import net.minecraft.core.block.Block;
import net.minecraft.core.block.BlockLogicRotatable;
import net.minecraft.core.block.material.Materials;
import net.minecraft.core.entity.player.Player;
import net.minecraft.core.util.helper.Side;
import net.minecraft.core.world.World;
import net.minecraft.core.world.pos.TilePosc;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class BlockLogicSpeaker extends BlockLogicRotatable {

	public static java.util.function.BiConsumer<World, TilePosc> openScreen = null;

	public final boolean golden;

	public BlockLogicSpeaker(@NotNull Block<?> block, boolean golden) {
		super(block, Materials.METAL);
		this.golden = golden;
		block.withEntity(TileEntitySpeaker::new);
	}

	@Override
	public boolean onInteracted(@NotNull World world, @NotNull TilePosc tilePos, @NotNull Player player, @Nullable Side side, double xHit, double yHit) {
		if (player.isSneaking()) return false;

		if (openScreen != null && (world.isClientSide || turniplabs.halplibe.helper.EnvironmentHelper.isSinglePlayer()))
			openScreen.accept(world, tilePos);
		return true;
	}
}
