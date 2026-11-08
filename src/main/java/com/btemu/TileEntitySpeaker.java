package com.btemu;

import com.mojang.nbt.tags.CompoundTag;
import net.minecraft.core.block.entity.TileEntity;
import net.minecraft.core.net.packet.Packet;
import net.minecraft.core.net.packet.PacketTileEntityData;
import net.minecraft.core.world.pos.TilePos;
import org.jetbrains.annotations.NotNull;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class TileEntitySpeaker extends TileEntity {

	public static final Set<TileEntitySpeaker> LOADED = ConcurrentHashMap.newKeySet();

	public String name = "";

	public TilePos link = null;

	public boolean manual = false;

	@Override
	public void tick() {
		if (!isInvalid()) LOADED.add(this);
	}

	@Override
	public void invalidate() {
		super.invalidate();
		LOADED.remove(this);
	}

	@Override
	public void readAdditionalData(@NotNull CompoundTag tag) {
		name = tag.getStringOrDefault("Name", "");
		manual = tag.getBooleanOrDefault("Manual", false);
		link = tag.getBooleanOrDefault("Linked", false)
			? new TilePos(tag.getInteger("LinkX"), tag.getInteger("LinkY"), tag.getInteger("LinkZ")) : null;
	}

	@Override
	public void writeAdditionalData(@NotNull CompoundTag tag) {
		tag.putString("Name", name);
		tag.putBoolean("Manual", manual);
		tag.putBoolean("Linked", link != null);
		if (link != null) {
			tag.putInt("LinkX", link.x);
			tag.putInt("LinkY", link.y);
			tag.putInt("LinkZ", link.z);
		}
	}

	@Override
	public Packet getDescriptionPacket() {
		return new PacketTileEntityData(this);
	}
}
