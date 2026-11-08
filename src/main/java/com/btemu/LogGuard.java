package com.btemu;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.filter.AbstractFilter;

import java.util.concurrent.ConcurrentHashMap;

public final class LogGuard extends AbstractFilter {
	private static final int BURST = 5;
	private static final long WINDOW_MS = 60_000;

	private static final int MAX_KEYS = 2000;

	private static final class Count {
		long windowStart;
		int n;
		int dropped;
	}

	private final ConcurrentHashMap<String, Count> counts = new ConcurrentHashMap<>();

	public static void install() {
		try {
			LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
			LogGuard f = new LogGuard();
			f.start();
			ctx.getConfiguration().getRootLogger().addFilter(f);
			ctx.updateLoggers();
		} catch (Throwable t) {

			BtEmu.LOGGER.warn("Couldn't install the log flood guard", t);
		}
	}

	@Override
	public Result filter(LogEvent e) {
		if (!e.getLevel().isMoreSpecificThan(Level.WARN)) return Result.NEUTRAL;
		String msg = e.getMessage() == null ? "" : e.getMessage().getFormattedMessage();
		String key = e.getLoggerName() + '|' + msg;
		if (counts.size() > MAX_KEYS) counts.clear();
		Count c = counts.computeIfAbsent(key, k -> new Count());
		long now = System.currentTimeMillis();
		int droppedLast = 0;
		synchronized (c) {
			if (now - c.windowStart > WINDOW_MS) {
				droppedLast = c.dropped;
				c.windowStart = now;
				c.n = 0;
				c.dropped = 0;
			}
			if (++c.n > BURST) {
				if (c.dropped++ == 0) System.err.println("[BTEmu] Repeating log message suppressed for up to a minute: " + msg);
				return Result.DENY;
			}
		}
		if (droppedLast > 0) System.err.println("[BTEmu] " + droppedLast + " repeats of that message were suppressed: " + msg);
		return Result.NEUTRAL;
	}
}
