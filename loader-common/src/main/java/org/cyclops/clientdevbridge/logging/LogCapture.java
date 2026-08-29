package org.cyclops.clientdevbridge.logging;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.cyclops.clientdevbridge.ClientDevBridge;

import java.util.function.Consumer;

/**
 * Attaches a Log4j appender to the root logger so that {@code log.tail} can serve the game's own
 * log without the CLI having to parse {@code gradle.log}, and so {@code log.line} notifications
 * can be pushed as they happen.
 *
 * @author rubensworks
 */
public class LogCapture {

    private static final int CAPACITY = 5000;

    private final LogRing ring = new LogRing(CAPACITY);
    private RingAppender appender;

    public LogRing getRing() {
        return this.ring;
    }

    /**
     * @param onLine called for every captured line, for the {@code log.line} notification
     */
    public void install(Consumer<String> onLine) {
        try {
            LoggerContext context = (LoggerContext) LogManager.getContext(false);
            this.appender = new RingAppender(this.ring, onLine);
            this.appender.start();
            Logger rootLogger = context.getRootLogger();
            rootLogger.addAppender(this.appender);
        } catch (Throwable e) {
            // Never let log capture stop the bridge from starting; log.tail simply stays empty.
            ClientDevBridge.LOGGER.warn("Could not attach the ClientDevBridge log appender; "
                    + "log.tail will return nothing. Use 'clientdevbridge logs --gradle' instead.", e);
        }
    }

    public void uninstall() {
        if (this.appender != null) {
            try {
                LoggerContext context = (LoggerContext) LogManager.getContext(false);
                context.getRootLogger().removeAppender(this.appender);
                this.appender.stop();
            } catch (Throwable ignored) {
                // Shutting down; nothing useful to do.
            }
            this.appender = null;
        }
    }

    private static class RingAppender extends AbstractAppender {

        private static final ThreadLocal<Boolean> REENTRANT = new ThreadLocal<>();

        private final LogRing ring;
        private final Consumer<String> onLine;

        RingAppender(LogRing ring, Consumer<String> onLine) {
            super("ClientDevBridgeRing", (Filter) null, null, true, Property.EMPTY_ARRAY);
            this.ring = ring;
            this.onLine = onLine;
        }

        @Override
        public void append(LogEvent event) {
            // Anything this appender does may itself log — a failed socket write, say — and Log4j
            // would then call back into here on the same thread. Without this guard that is an
            // infinite recursion, and Log4j only notices after printing a scary console error.
            if (Boolean.TRUE.equals(REENTRANT.get())) {
                return;
            }
            REENTRANT.set(Boolean.TRUE);
            try {
                String line = format(event);
                LogRing.Level level = LogRing.Level.parse(event.getLevel().name());
                this.ring.add(level, line);
                // A dev client emits hundreds of TRACE lines a second. Buffering them is cheap;
                // pushing every one of them to a notification is not, and nobody reads them.
                if (level.atLeast(LogRing.Level.INFO)) {
                    this.onLine.accept(line);
                }
            } finally {
                REENTRANT.remove();
            }
        }

        private static String format(LogEvent event) {
            StringBuilder builder = new StringBuilder(160);
            builder.append('[').append(event.getLevel()).append("] [")
                    .append(event.getLoggerName()).append("] ")
                    .append(event.getMessage().getFormattedMessage());
            Throwable thrown = event.getThrown();
            if (thrown != null) {
                builder.append(" | ").append(thrown.getClass().getName());
                if (thrown.getMessage() != null) {
                    builder.append(": ").append(thrown.getMessage());
                }
            }
            return builder.toString();
        }

    }

    /** Exposed so the appender type is reachable for tests and diagnostics. */
    public static Class<? extends Appender> appenderType() {
        return RingAppender.class;
    }

}
