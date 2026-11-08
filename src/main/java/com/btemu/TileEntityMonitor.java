package com.btemu;

import com.mojang.nbt.tags.CompoundTag;
import net.minecraft.core.block.entity.TileEntity;
import net.minecraft.core.net.packet.Packet;
import net.minecraft.core.net.packet.PacketTileEntityData;
import org.jetbrains.annotations.NotNull;

import java.util.function.Consumer;

public class TileEntityMonitor extends TileEntity implements net.minecraft.core.player.inventory.container.Container {

	public static final int SMOKING = 8;

	public static final int OVERHEAT_TICKS = 20 * 60 * 10;

	public static final int COOL_TICKS = 20 * 60 * 3;

	public static Consumer<TileEntityMonitor> clientTick = null;

	@NotNull
	public String rom = "";

	public boolean powered = false;

	public boolean noteBlocks = false;

	public int volume = 7;

	public byte[] savedState = null;

	private net.minecraft.core.item.ItemStack[] firebox = new net.minecraft.core.item.ItemStack[9];

	public int burnTicks = 0;

	public int heatTicks = 0;

	public int coolTicks = 0;

	@Override
	public void tick() {
		if (worldObj == null) return;

		if (!worldObj.isClientSide) {
			com.btemu.session.Sessions.tick(this);
			MonitorKind k = kind();
			if (k != null && k.wooden) woodTick();
		}

		if (clientTick != null) clientTick.accept(this);
	}

	private void woodTick() {
		if (coolTicks > 0) {
			coolTicks--;
			if (coolTicks == 0) setChanged();
		}
		com.btemu.session.Session s = com.btemu.session.Sessions.get(worldObj, tilePos);
		boolean smoking = coolTicks > 0 || (s != null && s.isRunning());
		int data = worldObj.getBlockData(tilePos);
		boolean was = (data & SMOKING) != 0;
		if (smoking != was) worldObj.setBlockDataNotify(tilePos, smoking ? data | SMOKING : data & ~SMOKING);
	}

	public int fuelTicksRemaining() {
		long t = Math.max(0, burnTicks);
		for (net.minecraft.core.item.ItemStack st : firebox) {
			if (st == null) continue;
			t += (long) net.minecraft.core.crafting.LookupFuelFurnace.instance.getFuelYield(st) * st.stackSize;
		}
		return (int) Math.min(Integer.MAX_VALUE, t);
	}

	public boolean hasFuel() {
		for (net.minecraft.core.item.ItemStack st : firebox) {
			if (st != null && net.minecraft.core.crafting.LookupFuelFurnace.instance.getFuelYield(st) > 0) return true;
		}
		return false;
	}

	public int takeFuel() {
		for (int i = 0; i < firebox.length; i++) {
			net.minecraft.core.item.ItemStack st = firebox[i];
			if (st == null) continue;
			int y = net.minecraft.core.crafting.LookupFuelFurnace.instance.getFuelYield(st);
			if (y <= 0) continue;
			removeItem(i, 1);
			return y;
		}
		return 0;
	}

	public static final int FUEL_SLOT = 4;

	@Override
	public boolean locked(int slot) {
		return slot != FUEL_SLOT;
	}

	@Override
	public int getContainerSize() {
		return firebox.length;
	}

	@Override
	public net.minecraft.core.item.ItemStack getItem(int slot) {
		return firebox[slot];
	}

	@Override
	public net.minecraft.core.item.ItemStack removeItem(int slot, int amount) {
		net.minecraft.core.item.ItemStack st = firebox[slot];
		if (st == null) return null;
		if (st.stackSize <= amount) {
			firebox[slot] = null;
			setChanged();
			return st;
		}
		net.minecraft.core.item.ItemStack part = st.splitStack(amount);
		if (st.stackSize <= 0) firebox[slot] = null;
		setChanged();
		return part;
	}

	@Override
	public void setItem(int slot, net.minecraft.core.item.ItemStack stack) {
		firebox[slot] = stack;
		if (stack != null && stack.stackSize > getMaxStackSize()) stack.stackSize = getMaxStackSize();
		setChanged();
	}

	@Override
	public String getNameTranslationKey() {
		return "btemu.firebox";
	}

	@Override
	public int getMaxStackSize() {
		return 64;
	}

	@Override
	public boolean stillValid(net.minecraft.core.entity.player.Player player) {
		if (worldObj == null || worldObj.getTileEntity(tilePos) != this) return false;
		return player.distanceToSqr(tilePos.x + 0.5, tilePos.y + 0.5, tilePos.z + 0.5) <= 64.0;
	}

	@Override
	public void sort() {
	}

	@Override
	public void dropContents(net.minecraft.core.world.World world, int x, int y, int z) {
		super.dropContents(world, x, y, z);
		for (int i = 0; i < firebox.length; i++) {
			if (firebox[i] == null) continue;
			world.dropItem(x, y, z, firebox[i]);
			firebox[i] = null;
		}
	}

	public MonitorKind kind() {
		return MonitorKind.byBlockId(getBlockId());
	}

	@Override
	public void readAdditionalData(@NotNull CompoundTag tag) {
		rom = tag.getStringOrDefault("Rom", "");

		powered = tag.getBooleanOrDefault("Powered", !rom.isEmpty());
		noteBlocks = tag.getBooleanOrDefault("NoteBlocks", false);
		volume = tag.getIntegerOrDefault("Volume", 7);
		savedState = tag.containsKey("State") ? tag.getByteArray("State") : null;
		burnTicks = tag.getIntegerOrDefault("Burn", 0);
		heatTicks = tag.getIntegerOrDefault("Heat", 0);
		coolTicks = tag.getIntegerOrDefault("Cool", 0);
		firebox = new net.minecraft.core.item.ItemStack[9];
		if (tag.containsKey("Firebox")) {
			com.mojang.nbt.tags.ListTag list = tag.getList("Firebox");
			for (int i = 0; i < list.tagCount(); i++) {
				CompoundTag t = (CompoundTag) list.tagAt(i);
				int slot = t.getByte("Slot") & 0xFF;
				if (slot < firebox.length) firebox[slot] = net.minecraft.core.item.ItemStack.readItemStackFromNbt(t);
			}

			if (firebox[FUEL_SLOT] == null)
				for (int i = 0; i < firebox.length; i++)
					if (firebox[i] != null) {
						firebox[FUEL_SLOT] = firebox[i];
						firebox[i] = null;
						break;
					}
		}
	}

	@Override
	public void writeAdditionalData(@NotNull CompoundTag tag) {
		tag.putString("Rom", rom);
		tag.putBoolean("Powered", powered);
		tag.putBoolean("NoteBlocks", noteBlocks);
		tag.putInt("Volume", volume);
		tag.putInt("Burn", burnTicks);
		tag.putInt("Heat", heatTicks);
		tag.putInt("Cool", coolTicks);
		com.mojang.nbt.tags.ListTag list = new com.mojang.nbt.tags.ListTag();
		for (int i = 0; i < firebox.length; i++) {
			if (firebox[i] == null) continue;
			CompoundTag t = new CompoundTag();
			t.putByte("Slot", (byte) i);
			firebox[i].writeToNBT(t);
			list.addTag(t);
		}
		tag.put("Firebox", list);
		byte[] state = worldObj != null && !worldObj.isClientSide ? com.btemu.session.Sessions.persistentState(this) : null;
		if (state == null) state = savedState;
		if (state != null && powered) tag.putByteArray("State", state);
	}

	@Override
	public Packet getDescriptionPacket() {
		return new PacketTileEntityData(this);
	}
}
