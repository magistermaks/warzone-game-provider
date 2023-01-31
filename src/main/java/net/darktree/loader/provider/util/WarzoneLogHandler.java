package net.darktree.loader.provider.util;

import net.fabricmc.loader.impl.util.log.LogCategory;
import net.fabricmc.loader.impl.util.log.LogHandler;
import net.fabricmc.loader.impl.util.log.LogLevel;

import java.io.PrintWriter;
import java.io.StringWriter;

public class WarzoneLogHandler implements LogHandler {

	private static final LogLevel MIN_STDERR_LEVEL = LogLevel.ERROR;
	private static final LogLevel MIN_STDOUT_LEVEL = LogLevel.getDefault();

	@Override
	public void log(long time, LogLevel level, LogCategory category, String msg, Throwable exc, boolean fromReplay, boolean wasSuppressed) {
		String formatted = formatLog(time, level, category, msg, exc);

		if (level.isLessThan(MIN_STDERR_LEVEL)) {
			System.out.print(formatted);
		} else {
			System.err.print(formatted);
		}
	}

	private String formatLog(long time, LogLevel level, LogCategory category, String msg, Throwable exc) {
		String ret = String.format("%tT %s: (%s) %s%n", time, level.name(), category.context, msg);

		if (exc != null) {
			StringWriter writer = new StringWriter(ret.length() + 500);

			try (PrintWriter pw = new PrintWriter(writer, false)) {
				pw.print(ret);
				exc.printStackTrace(pw);
			}

			ret = writer.toString();
		}

		return ret;
	}

	@Override
	public boolean shouldLog(LogLevel level, LogCategory category) {
		return !level.isLessThan(MIN_STDOUT_LEVEL);
	}

	@Override
	public void close() { }

}
