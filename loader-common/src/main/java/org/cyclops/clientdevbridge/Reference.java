package org.cyclops.clientdevbridge;

/**
 * Static mod constants.
 *
 * @author rubensworks
 */
public class Reference {

    public static final String MOD_ID = "clientdevbridge";
    public static final String MOD_NAME = "ClientDevBridge";

    /**
     * The wire protocol version, see the {@code hello} handshake.
     * This value is identical on every Minecraft-version branch of this repository;
     * only additive changes are allowed within a version.
     */
    public static final int PROTOCOL_VERSION = 1;

    /**
     * Only when this system property is {@code true} does the bridge server start at all.
     */
    public static final String PROPERTY_ENABLED = "clientdevbridge.enabled";
    /**
     * TCP port the bridge server binds on, always on 127.0.0.1.
     */
    public static final String PROPERTY_PORT = "clientdevbridge.port";
    /**
     * Gates the {@code eval} method, which is a full scripting escape hatch.
     */
    public static final String PROPERTY_EVAL = "clientdevbridge.eval";
    /**
     * The consumer project's root directory, so the mod can find world templates committed there.
     * Set by the generated init script; falls back to the run directory's grandparent.
     */
    public static final String PROPERTY_PROJECT_DIR = "clientdevbridge.projectDir";

    public static final int DEFAULT_PORT = 25599;

}
