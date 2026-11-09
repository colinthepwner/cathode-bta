package com.btemu.net;

import com.btemu.session.Sessions;
import net.minecraft.core.world.pos.TilePos;
import org.jspecify.annotations.NonNull;
import turniplabs.halplibe.helper.network.NetworkHandler;
import turniplabs.halplibe.helper.network.NetworkMessage;
import turniplabs.halplibe.helper.network.UniversalPacket;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public final class Msgs {
	private Msgs() {}

	public interface ClientSink {
		void state(State m);
		void frames(Frames m);
		void snapshot(Snapshot m);
		void romData(RomData m);
		void tvFrame(TvFrameDown m);
		void tvAudio(TvAudioDown m);
	}

	public static ClientSink client = null;

	public static final int CHUNK = 24 * 1024;

	public static void register() {
		NetworkHandler.registerNetworkMessage(State::new);
		NetworkHandler.registerNetworkMessage(Frames::new);
		NetworkHandler.registerNetworkMessage(Snapshot::new);
		NetworkHandler.registerNetworkMessage(RomData::new);
		NetworkHandler.registerNetworkMessage(Sync::new);
		NetworkHandler.registerNetworkMessage(RomRequest::new);
		NetworkHandler.registerNetworkMessage(Input::new);
		NetworkHandler.registerNetworkMessage(Focus::new);
		NetworkHandler.registerNetworkMessage(TvFrameUp::new);
		NetworkHandler.registerNetworkMessage(TvFrameDown::new);
		NetworkHandler.registerNetworkMessage(TvAudioUp::new);
		NetworkHandler.registerNetworkMessage(TvAudioDown::new);
		NetworkHandler.registerNetworkMessage(SpeakerSave::new);
	}

	private static void writePos(UniversalPacket p, TilePos pos) {
		p.writeInt(pos.x);
		p.writeInt(pos.y);
		p.writeInt(pos.z);
	}

	private static TilePos readPos(UniversalPacket p) {
		return new TilePos(p.readInt(), p.readInt(), p.readInt());
	}

	private static void writeBytes(UniversalPacket p, byte[] b) {
		p.writeInt(b.length);
		p.writeBytes(b);
	}

	private static byte[] readBytes(UniversalPacket p) {
		int n = p.readInt();
		byte[] b = new byte[n];
		p.readBytes(b, n);
		return b;
	}

	public static final class State implements NetworkMessage {
		public TilePos pos;
		public boolean powered, noteMode, alarm;

		public int fuelSecs = -1;

		public String tvHost = "";

		public boolean tvWeb;

		public int tvApp;

		public int volume = 7;
		public String rom = "", controller = "";

		public String[] guests = {"", "", ""};
		public long crc;
		public int epoch;
		public List<String> roms = new ArrayList<>();

		public State() {}

		@Override
		public void encodeToUniversalPacket(@NonNull UniversalPacket p) {
			writePos(p, pos);
			p.writeBoolean(powered);
			p.writeBoolean(noteMode);
			p.writeBoolean(alarm);
			p.writeInt(fuelSecs);
			p.writeString(tvHost);
			p.writeBoolean(tvWeb);
			p.writeInt(tvApp);
			p.writeInt(volume);
			p.writeString(rom);
			p.writeString(controller);
			p.writeLong(crc);
			p.writeInt(epoch);
			p.writeInt(roms.size());
			for (String r : roms) p.writeString(r);
			for (String g : guests) p.writeString(g == null ? "" : g);
		}

		@Override
		public void decodeFromUniversalPacket(@NonNull UniversalPacket p) {
			pos = readPos(p);
			powered = p.readBoolean();
			noteMode = p.readBoolean();
			alarm = p.readBoolean();
			fuelSecs = p.readInt();
			tvHost = p.readString();
			tvWeb = p.readBoolean();
			tvApp = p.readInt();
			volume = p.readInt();
			rom = p.readString();
			controller = p.readString();
			crc = p.readLong();
			epoch = p.readInt();
			int n = p.readInt();
			for (int i = 0; i < n; i++) roms.add(p.readString());
			for (int i = 0; i < guests.length; i++) guests[i] = p.readString();
		}

		@Override
		public void handleClientEnv(NetworkContext c) {
			if (client != null) client.state(this);
		}
	}

	public static final class Frames implements NetworkMessage {
		public TilePos pos;
		public int epoch;
		public long bound;
		public long[] frames = new long[0];
		public int[] masks = new int[0];

		public Frames() {}

		@Override
		public void encodeToUniversalPacket(@NonNull UniversalPacket p) {
			writePos(p, pos);
			p.writeInt(epoch);
			p.writeLong(bound);
			p.writeInt(frames.length);
			for (int i = 0; i < frames.length; i++) {
				p.writeLong(frames[i]);
				p.writeInt(masks[i]);
			}
		}

		@Override
		public void decodeFromUniversalPacket(@NonNull UniversalPacket p) {
			pos = readPos(p);
			epoch = p.readInt();
			bound = p.readLong();
			int n = p.readInt();
			frames = new long[n];
			masks = new int[n];
			for (int i = 0; i < n; i++) {
				frames[i] = p.readLong();
				masks[i] = p.readInt();
			}
		}

		@Override
		public void handleClientEnv(NetworkContext c) {
			if (client != null) client.frames(this);
		}
	}

	public static final class Snapshot implements NetworkMessage {
		public TilePos pos;
		public int epoch, index, count;
		public byte[] data;

		public Snapshot() {}

		@Override
		public void encodeToUniversalPacket(@NonNull UniversalPacket p) {
			writePos(p, pos);
			p.writeInt(epoch);
			p.writeInt(index);
			p.writeInt(count);
			writeBytes(p, data);
		}

		@Override
		public void decodeFromUniversalPacket(@NonNull UniversalPacket p) {
			pos = readPos(p);
			epoch = p.readInt();
			index = p.readInt();
			count = p.readInt();
			data = readBytes(p);
		}

		@Override
		public void handleClientEnv(NetworkContext c) {
			if (client != null) client.snapshot(this);
		}
	}

	public static final class RomData implements NetworkMessage {
		public long crc;
		public int index, count;
		public byte[] data;

		public RomData() {}

		@Override
		public void encodeToUniversalPacket(@NonNull UniversalPacket p) {
			p.writeLong(crc);
			p.writeInt(index);
			p.writeInt(count);
			writeBytes(p, data);
		}

		@Override
		public void decodeFromUniversalPacket(@NonNull UniversalPacket p) {
			crc = p.readLong();
			index = p.readInt();
			count = p.readInt();
			data = readBytes(p);
		}

		@Override
		public void handleClientEnv(NetworkContext c) {
			if (client != null) client.romData(this);
		}
	}

	public static final class Sync implements NetworkMessage {
		public TilePos pos;

		public Sync() {}

		public Sync(TilePos pos) {
			this.pos = pos;
		}

		@Override
		public void encodeToUniversalPacket(@NonNull UniversalPacket p) {
			writePos(p, pos);
		}

		@Override
		public void decodeFromUniversalPacket(@NonNull UniversalPacket p) {
			pos = readPos(p);
		}

		@Override
		public void handle(NetworkContext c) {
			Sessions.onSync(c.player, pos);
		}
	}

	public static final class RomRequest implements NetworkMessage {
		public long crc;

		public RomRequest() {}

		public RomRequest(long crc) {
			this.crc = crc;
		}

		@Override
		public void encodeToUniversalPacket(@NonNull UniversalPacket p) {
			p.writeLong(crc);
		}

		@Override
		public void decodeFromUniversalPacket(@NonNull UniversalPacket p) {
			crc = p.readLong();
		}

		@Override
		public void handle(NetworkContext c) {
			Sessions.onRomRequest(c.player, crc);
		}
	}

	public static final class Input implements NetworkMessage {
		public TilePos pos;
		public int mask;

		public Input() {}

		public Input(TilePos pos, int mask) {
			this.pos = pos;
			this.mask = mask;
		}

		@Override
		public void encodeToUniversalPacket(@NonNull UniversalPacket p) {
			writePos(p, pos);
			p.writeInt(mask);
		}

		@Override
		public void decodeFromUniversalPacket(@NonNull UniversalPacket p) {
			pos = readPos(p);
			mask = p.readInt();
		}

		@Override
		public void handle(NetworkContext c) {
			Sessions.onInput(c.player, pos, mask);
		}
	}

	public static final class Focus implements NetworkMessage {
		public TilePos pos;
		public boolean want;

		public Focus() {}

		public Focus(TilePos pos, boolean want) {
			this.pos = pos;
			this.want = want;
		}

		@Override
		public void encodeToUniversalPacket(@NonNull UniversalPacket p) {
			writePos(p, pos);
			p.writeBoolean(want);
		}

		@Override
		public void decodeFromUniversalPacket(@NonNull UniversalPacket p) {
			pos = readPos(p);
			want = p.readBoolean();
		}

		@Override
		public void handle(NetworkContext c) {
			Sessions.onFocus(c.player, pos, want);
		}
	}

	public static final class SpeakerSave implements NetworkMessage {
		public TilePos speaker;
		public String name = "";

		public SpeakerSave() {}

		public SpeakerSave(TilePos speaker, String name) {
			this.speaker = speaker;
			this.name = name;
		}

		@Override
		public void encodeToUniversalPacket(@NonNull UniversalPacket p) {
			writePos(p, speaker);
			p.writeString(name);
		}

		@Override
		public void decodeFromUniversalPacket(@NonNull UniversalPacket p) {
			speaker = readPos(p);
			name = p.readString();
		}

		@Override
		public void handle(NetworkContext c) {
			com.btemu.Speakers.onRename(c.player, speaker, name);
		}
	}

	public static final class TvFrameUp implements NetworkMessage {
		public TilePos pos;
		public byte[] jpeg;

		public TvFrameUp() {}

		public TvFrameUp(TilePos pos, byte[] jpeg) {
			this.pos = pos;
			this.jpeg = jpeg;
		}

		@Override
		public void encodeToUniversalPacket(@NonNull UniversalPacket p) {
			writePos(p, pos);
			writeBytes(p, jpeg);
		}

		@Override
		public void decodeFromUniversalPacket(@NonNull UniversalPacket p) {
			pos = readPos(p);
			jpeg = readBytes(p);
		}

		@Override
		public void handle(NetworkContext c) {
			Sessions.onTvFrame(c.player, pos, jpeg);
		}
	}

	public static final class TvFrameDown implements NetworkMessage {
		public TilePos pos;
		public byte[] jpeg;

		public TvFrameDown() {}

		public TvFrameDown(TilePos pos, byte[] jpeg) {
			this.pos = pos;
			this.jpeg = jpeg;
		}

		@Override
		public void encodeToUniversalPacket(@NonNull UniversalPacket p) {
			writePos(p, pos);
			writeBytes(p, jpeg);
		}

		@Override
		public void decodeFromUniversalPacket(@NonNull UniversalPacket p) {
			pos = readPos(p);
			jpeg = readBytes(p);
		}

		@Override
		public void handleClientEnv(NetworkContext c) {
			if (client != null) client.tvFrame(this);
		}
	}

	public static final class TvAudioUp implements NetworkMessage {
		public TilePos pos;
		public byte[] pcm;

		public TvAudioUp() {}

		public TvAudioUp(TilePos pos, byte[] pcm) {
			this.pos = pos;
			this.pcm = pcm;
		}

		@Override
		public void encodeToUniversalPacket(@NonNull UniversalPacket p) {
			writePos(p, pos);
			writeBytes(p, pcm);
		}

		@Override
		public void decodeFromUniversalPacket(@NonNull UniversalPacket p) {
			pos = readPos(p);
			pcm = readBytes(p);
		}

		@Override
		public void handle(NetworkContext c) {
			Sessions.onTvAudio(c.player, pos, pcm);
		}
	}

	public static final class TvAudioDown implements NetworkMessage {
		public TilePos pos;
		public byte[] pcm;

		public TvAudioDown() {}

		public TvAudioDown(TilePos pos, byte[] pcm) {
			this.pos = pos;
			this.pcm = pcm;
		}

		@Override
		public void encodeToUniversalPacket(@NonNull UniversalPacket p) {
			writePos(p, pos);
			writeBytes(p, pcm);
		}

		@Override
		public void decodeFromUniversalPacket(@NonNull UniversalPacket p) {
			pos = readPos(p);
			pcm = readBytes(p);
		}

		@Override
		public void handleClientEnv(NetworkContext c) {
			if (client != null) client.tvAudio(this);
		}
	}

	public static void chunks(byte[] data, Consumer<byte[][]> sink) {
		int count = Math.max(1, (data.length + CHUNK - 1) / CHUNK);
		byte[][] out = new byte[count][];
		for (int i = 0; i < count; i++) {
			int from = i * CHUNK, to = Math.min(data.length, from + CHUNK);
			out[i] = java.util.Arrays.copyOfRange(data, from, to);
		}
		sink.accept(out);
	}
}
