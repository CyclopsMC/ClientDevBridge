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
    private final java.nio.file.Path projectDir;
    private final boolean toastsEnabled;

    public BridgeConfig(boolean enabled, boolean evalEnabled, int port, java.nio.file.Path projectDir) {
        this(enabled, evalEnabled, port, projectDir, false);
    }

    public BridgeConfig(boolean enabled, boolean evalEnabled, int port, java.nio.file.Path projectDir,
                        boolean toastsEnabled) {
        this.enabled = enabled;
        this.evalEnabled = evalEnabled;
        this.port = port;
        this.projectDir = projectDir;
        this.toastsEnabled = toastsEnabled;
    }

    public static BridgeConfig fromSystemProperties() {
        return new BridgeConfig(
                Boolean.parseBoolean(System.getProperty(Reference.PROPERTY_ENABLED, "false")),
                Boolean.parseBoolean(System.getProperty(Reference.PROPERTY_EVAL, "false")),
                parsePort(System.getProperty(Reference.PROPERTY_PORT)),
                parseProjectDir(System.getProperty(Reference.PROPERTY_PROJECT_DIR)),
                Boolean.parseBoolean(System.getProperty(Reference.PROPERTY_TOASTS, "false"))
        );
    }

    /**
     * The run directory is typically {@code <project>/loader-<loader>/runs/client}, so without an
     * explicit value the grandparent's parent is the best available guess.
     */
    static java.nio.file.Path parseProjectDir(String raw) {
        if (raw != null && !raw.isBlank()) {
            return java.nio.file.Paths.get(raw.trim()).toAbsolutePath().normalize();
        }
        return java.nio.file.Paths.get("").toAbsolutePath().normalize();
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

    /**
     * Whether toast popups are allowed to render. Off by default, for reproducible screenshots.
     */
    public boolean areToastsEnabled() {
        return this.toastsEnabled;
    }

    /**
     * The consumer project's root, used to resolve {@code clientdevbridge/templates/<name>}.
     */
    public java.nio.file.Path getProjectDir() {
        return this.projectDir;
    }

}
