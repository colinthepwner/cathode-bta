package com.btemu.net;

import com.btemu.mixin.NetworkManagerAccessor;
import net.minecraft.core.entity.player.Player;
import net.minecraft.core.net.NetworkManager;
import turniplabs.halplibe.helper.EnvironmentHelper;
import turniplabs.halplibe.helper.network.NetworkHandler;
import turniplabs.halplibe.helper.network.NetworkMessage;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public final class BulkSend {
	private BulkSend() {}

	private static final int HIGH_WATER = 256 * 1024;

	private static final Map<Player, ArrayDeque<NetworkMessage>> QUEUES = new HashMap<>();

	public static synchronized void send(Player p, NetworkMessage m) {
		if (!EnvironmentHelper.isServerEnvironment() || Server.net(p) == null) {
			NetworkHandler.sendToPlayer(p, m);
			return;
		}
		QUEUES.computeIfAbsent(p, k -> new ArrayDeque<>()).add(m);
		pumpOne(p, QUEUES.get(p));
	}

	public static synchronized void cancel(Player p, java.util.function.Predicate<NetworkMessage> which) {
		ArrayDeque<NetworkMessage> q = QUEUES.get(p);
		if (q != null) q.removeIf(which);
	}

	public static synchronized void pump() {
		Iterator<Map.Entry<Player, ArrayDeque<NetworkMessage>>> it = QUEUES.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<Player, ArrayDeque<NetworkMessage>> e = it.next();
			Player p = e.getKey();
			NetworkManager nm = net(p);
			if (nm == null || p.removed) {
				it.remove();
				continue;
			}
			pumpOne(p, e.getValue());
			if (e.getValue().isEmpty()) it.remove();
		}
	}

	private static void pumpOne(Player p, ArrayDeque<NetworkMessage> q) {
		NetworkManager nm = net(p);
		if (nm == null) return;

		while (!q.isEmpty() && ((NetworkManagerAccessor) nm).btemu$getQueuedBytes() < HIGH_WATER) {
			NetworkHandler.sendToPlayer(p, q.poll());
		}
	}

	private static NetworkManager net(Player p) {
		return EnvironmentHelper.isServerEnvironment() ? Server.net(p) : null;
	}

	private static final class Server {
		static NetworkManager net(Player p) {
			if (!(p instanceof net.minecraft.server.entity.player.PlayerServer ps) || ps.playerNetServerHandler == null) return null;
			return ps.playerNetServerHandler.netManager;
		}
	}
}
