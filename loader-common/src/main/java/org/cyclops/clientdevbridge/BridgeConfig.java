package org.cyclops.clientdevbridge;

/**
 * Reads the {@code clientdevbridge.*} system properties that gate this dev-only mod.
 *
 * The bridge is inert unless {@link Reference#PROPERTY_ENABLED} is explicitly set to {@code true},
 * so that shipping the jar to a player can never open a socket.
 *
 * @author rubensworks
 */
public class BridgeConfig {

    private final boolean enabled;
    private final boolean evalEnabled;
    private final int port;

    public BridgeConfig(boolean enabled, boolean evalEnabled, int port) {
        this.enabled = enabled;
        this.evalEnabled = evalEnabled;
        this.port = port;
    }

    public static BridgeConfig fromSystemProperties() {
        return new BridgeConfig(
                Boolean.parseBoolean(System.getProperty(Reference.PROPERTY_ENABLED, "false")),
                Boolean.parseBoolean(System.getProperty(Reference.PROPERTY_EVAL, "false")),
                parsePort(System.getProperty(Reference.PROPERTY_PORT))
        );
    }

    static int parsePort(String raw) {
        if (raw == null || raw.isBlank()) {
            return Reference.DEFAULT_PORT;
        }
        try {
            int port = Integer.parseInt(raw.trim());
            if (port < 1 || port > 65535) {
                return Reference.DEFAULT_PORT;
            }
            return port;
        } catch (NumberFormatException e) {
            return Reference.DEFAULT_PORT;
        }
    }

    public boolean isEnabled() {
        return this.enabled;
    }

    public boolean isEvalEnabled() {
        return this.evalEnabled;
    }

    public int getPort() {
        return this.port;
    }

}
