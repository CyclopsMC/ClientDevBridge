package org.cyclops.clientdevbridge.logging;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * A bounded, thread-safe buffer of recent log lines, backing the {@code log.tail} method.
 *
 * Lines keep their severity so that the default tail can hide the flood of TRACE/DEBUG a dev
 * client emits — several hundred lines per second on NeoForge — while still letting a caller
 * ask for it explicitly.
 *
 * This is deliberately independent of any logging framework so it can be unit-tested and reused;
 * {@link LogCapture} is what feeds it from Log4j.
 *
 * @author rubensworks
 */
public class LogRing {

    /**
     * Severities, ordered least to most important. Anything unrecognised sorts as INFO.
     */
    public enum Level {
        TRACE, DEBUG, INFO, WARN, ERROR, FATAL;

        public static Level parse(@Nullable String name) {
            if (name != null) {
                for (Level level : values()) {
                    if (level.name().equalsIgnoreCase(name)) {
                        return level;
                    }
                }
            }
            return INFO;
        }

        public boolean atLeast(Level minimum) {
            return this.ordinal() >= minimum.ordinal();
        }
    }

    public record Entry(Level level, String text) {
    }

    private final int capacity;
    private final Deque<Entry> entries;

    public LogRing(int capacity) {
        this.capacity = capacity;
        this.entries = new ArrayDeque<>(Math.min(capacity, 1024));
    }

    public void add(Level level, String text) {
        synchronized (this.entries) {
            if (this.entries.size() >= this.capacity) {
                this.entries.removeFirst();
            }
            this.entries.addLast(new Entry(level, text));
        }
    }

    public int size() {
        synchronized (this.entries) {
            return this.entries.size();
        }
    }

    /**
     * Returns the most recent matching lines, oldest first.
     *
     * @param limit    how many lines at most, counted after filtering
     * @param filter   a regular expression each line must contain a match for, or null for all lines
     * @param minLevel the lowest severity to include
     */
    public List<String> tail(int limit, @Nullable String filter, Level minLevel) {
        Pattern pattern = compile(filter);
        List<Entry> snapshot;
        synchronized (this.entries) {
            snapshot = new ArrayList<>(this.entries);
        }

        List<String> matched = new ArrayList<>();
        for (Entry entry : snapshot) {
            if (!entry.level().atLeast(minLevel)) {
                continue;
            }
            if (pattern == null || pattern.matcher(entry.text()).find()) {
                matched.add(entry.text());
            }
        }
        if (matched.size() <= limit) {
            return matched;
        }
        return new ArrayList<>(matched.subList(matched.size() - limit, matched.size()));
    }

    /**
     * Parses a level name from the wire, rejecting unknown values rather than silently defaulting,
     * because a typo'd level would otherwise quietly return the wrong lines.
     */
    public static Level requireLevel(@Nullable String name) {
        if (name == null) {
            return Level.INFO;
        }
        for (Level level : Level.values()) {
            if (level.name().equalsIgnoreCase(name)) {
                return level;
            }
        }
        StringBuilder allowed = new StringBuilder();
        for (Level level : Level.values()) {
            if (allowed.length() > 0) {
                allowed.append(", ");
            }
            allowed.append(level.name().toLowerCase(Locale.ROOT));
        }
        throw org.cyclops.clientdevbridge.protocol.RpcException
                .invalidParams("Parameter 'level' must be one of " + allowed + ", but was '" + name + "'");
    }

    @Nullable
    private static Pattern compile(@Nullable String filter) {
        if (filter == null || filter.isEmpty()) {
            return null;
        }
        try {
            return Pattern.compile(filter);
        } catch (PatternSyntaxException e) {
            throw org.cyclops.clientdevbridge.protocol.RpcException
                    .invalidParams("Parameter 'filter' is not a valid regular expression: " + e.getDescription());
        }
    }

}
