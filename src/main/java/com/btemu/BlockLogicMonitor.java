package com.btemu;

import net.minecraft.core.block.Block;
import net.minecraft.core.block.BlockLogicRotatable;
import net.minecraft.core.block.material.Materials;
import net.minecraft.core.entity.player.Player;
import net.minecraft.core.util.helper.Direction;
import net.minecraft.core.util.helper.Side;
import net.minecraft.core.world.World;
import net.minecraft.core.world.pos.TilePosc;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class BlockLogicMonitor extends BlockLogicRotatable {
	public interface ClientInteract {
		void use(World world, TilePosc pos, Player player);
	}

	public static ClientInteract clientInteract = null;

	public static java.util.function.BiConsumer<World, TilePosc> clientRemoved = null;

	public final MonitorKind kind;

	public BlockLogicMonitor(@NotNull Block<?> block, MonitorKind kind) {
		super(block, Materials.METAL);
		this.kind = kind;
		block.withEntity(TileEntityMonitor::new);
	}

	public static java.util.function.BiConsumer<World, TilePosc> clientPlaced = null;

	@Override
	public void onPlacedByMob(@NotNull World world, @NotNull TilePosc tilePos, @NotNull Side side, @NotNull net.minecraft.core.entity.Mob mob, double xHit, double yHit) {
		super.onPlacedByMob(world, tilePos, side, mob, xHit, yHit);

		if (!kind.wooden) {
			float pitch = mob.xRot;
			if (pitch > 50f || pitch < -50f) {
				double yaw = Math.toRadians(mob.yRot);
				double lx = -Math.sin(yaw), lz = Math.cos(yaw);
				int ux = 0, uz = 0;
				if (Math.abs(lx) > Math.abs(lz)) ux = lx > 0 ? 1 : -1; else uz = lz > 0 ? 1 : -1;
				Direction face = pitch > 0 ? Direction.UP : Direction.DOWN;

				int[][] around = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
				for (int[] a : around) {
					net.minecraft.core.world.pos.TilePos n = new net.minecraft.core.world.pos.TilePos(tilePos.x() + a[0], tilePos.y(), tilePos.z() + a[1]);
					if (world.getBlockId(n.x, n.y, n.z) != world.getBlockId(tilePos.x(), tilePos.y(), tilePos.z())) continue;
					int nm = world.getBlockData(n);
					if ((nm & 7) != face.id) continue;
					int[] u = MonitorGroup.upFor(nm, MonitorGroup.facing3(nm));
					ux = u[0];
					uz = u[2];
					break;
				}

				world.setBlockDataNotify(tilePos, face.id | MonitorGroup.upBits(ux, uz));
			}
		}
		MonitorGroup.invalidate();
		if (clientPlaced != null) clientPlaced.accept(world, tilePos);
	}

	@Override
	public void onRemoved(@NotNull World world, @NotNull TilePosc tilePos, int data) {
		if (!world.isClientSide && kind.wooden && world.getTileEntity(tilePos) instanceof TileEntityMonitor te) {
			te.dropContents(world, tilePos.x(), tilePos.y(), tilePos.z());
		}
		super.onRemoved(world, tilePos, data);
		MonitorGroup.invalidate();
		if (!world.isClientSide) com.btemu.session.Sessions.onRemoved(world, tilePos);
		if (clientRemoved != null) clientRemoved.accept(world, tilePos);
	}

	@Override
	public boolean onInteracted(@NotNull World world, @NotNull TilePosc tilePos, @NotNull Player player, @Nullable Side side, double xHit, double yHit) {
		if (world.isClientSide) return true;

		if (kind.wooden && side != null && world.getTileEntity(tilePos) instanceof TileEntityMonitor te) {
			int[] f = MonitorGroup.facing(world.getBlockData(tilePos) & 7);
			int leftX = -f[1], leftZ = f[0];
			if (side.offsetX() == leftX && side.offsetZ() == leftZ && side.offsetY() == 0) {
				player.displayContainerScreen(te);
				return true;
			}
		}
		if (player.isSneaking()) com.btemu.session.Sessions.onUse(world, tilePos, player);
		else com.btemu.session.Sessions.toggleFocus(world, tilePos, player);
		return true;
	}

	@Override
	public void animationTick(@NotNull World world, @NotNull TilePosc tilePos, @NotNull java.util.Random rand) {
		if (!kind.wooden || (world.getBlockData(tilePos) & TileEntityMonitor.SMOKING) == 0) return;
		for (int i = 0; i < 2; i++) {
			double x = tilePos.x() + 0.3 + rand.nextDouble() * 0.4;
			double z = tilePos.z() + 0.3 + rand.nextDouble() * 0.4;
			world.spawnParticle("largesmoke", x, tilePos.y() + 1.02, z, 0.0, 0.03, 0.0, 0, false);
			world.spawnParticle("ventsmoke", x, tilePos.y() + 1.02, z, 0.0, 0.05, 0.0, 0, false);
		}
	}
}
