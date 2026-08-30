package org.cyclops.clientdevbridge;

import com.google.gson.JsonObject;
import org.cyclops.clientdevbridge.handler.EvalHandler;
import org.cyclops.clientdevbridge.handler.InputHandler;
import org.cyclops.clientdevbridge.handler.LogHandler;
import org.cyclops.clientdevbridge.handler.PlayerHandler;
import org.cyclops.clientdevbridge.handler.ScreenHandler;
import org.cyclops.clientdevbridge.handler.ScreenshotHandler;
import org.cyclops.clientdevbridge.handler.SnapshotHandler;
import org.cyclops.clientdevbridge.handler.StatusHandler;
import org.cyclops.clientdevbridge.handler.WaitHandler;
import org.cyclops.clientdevbridge.handler.WindowHandler;
import org.cyclops.clientdevbridge.handler.WorldHandler;
import org.cyclops.clientdevbridge.logging.LogCapture;
import org.cyclops.clientdevbridge.mcadapter.ClientState;
import org.cyclops.clientdevbridge.mcadapter.IClientHooks;
import org.cyclops.clientdevbridge.mcadapter.McAdapter;
import org.cyclops.clientdevbridge.mcadapter.StateWatcher;
import org.cyclops.clientdevbridge.net.BridgeServer;
import org.cyclops.clientdevbridge.protocol.Dispatcher;
import org.cyclops.clientdevbridge.protocol.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.io.IOException;

/**
 * Loader-independent entry point of the bridge.
 *
 * Both loader entry points hand their {@link IClientHooks} to {@link #start} during client setup;
 * everything below this point is shared, and everything version-sensitive sits behind
 * {@code mcadapter}.
 *
 * @author rubensworks
 */
public class ClientDevBridge {

    public static final Logger LOGGER = LoggerFactory.getLogger(Reference.MOD_NAME);

    @Nullable
    private static BridgeServer server;
    @Nullable
    private static LogCapture logCapture;
    private static BridgeConfig config = new BridgeConfig(false, false, Reference.DEFAULT_PORT,
            java.nio.file.Paths.get("").toAbsolutePath());

    /**
     * Starts the bridge, unless {@code -Dclientdevbridge.enabled=true} was not passed.
     *
     * Failures here are logged and swallowed: a broken bridge must never stop the dev client
     * from booting, because then there would be no log to read to find out why.
     */
    public static void start(IClientHooks hooks) {
        config = BridgeConfig.fromSystemProperties();
        if (!config.isEnabled()) {
            LOGGER.info("{} is present but inert: pass -D{}=true to enable it.",
                    Reference.MOD_NAME, Reference.PROPERTY_ENABLED);
            return;
        }

        try {
            McAdapter.install(hooks);
            org.cyclops.clientdevbridge.mcadapter.VanillaExtractors.registerAll();

            logCapture = new LogCapture();

            Dispatcher dispatcher = new Dispatcher();
            StatusHandler.register(dispatcher);
            ScreenshotHandler.register(dispatcher);
            WaitHandler.register(dispatcher);
            LogHandler.register(dispatcher, logCapture.getRing());
            WorldHandler.register(dispatcher, config.getProjectDir());
            InputHandler.register(dispatcher);
            ScreenHandler.register(dispatcher);
            PlayerHandler.register(dispatcher);
            SnapshotHandler.register(dispatcher);
            WindowHandler.register(dispatcher);
            EvalHandler.register(dispatcher);

            BridgeServer bridgeServer = new BridgeServer(config.getPort(), dispatcher, ClientDevBridge::helloMessage);
            bridgeServer.start();
            server = bridgeServer;

            // Installed after the server, so that attaching the appender cannot delay the port opening.
            logCapture.install(line -> {
                BridgeServer running = server;
                if (running != null && running.hasConnections()) {
                    JsonObject params = Json.object();
                    params.addProperty("line", line);
                    running.broadcast(Dispatcher.notification("log.line", params));
                }
            });

            // Screen and world transitions are derived per tick rather than from loader events,
            // so both loaders report them at exactly the same moment.
            StateWatcher watcher = new StateWatcher((method, params) -> {
                BridgeServer running = server;
                if (running != null) {
                    running.broadcast(Dispatcher.notification(method, params));
                }
            }, !config.areToastsEnabled());
            hooks.registerClientTick(watcher::onClientTick);

            Runtime.getRuntime().addShutdownHook(new Thread(ClientDevBridge::stop, "ClientDevBridge-shutdown"));

            LOGGER.info("{} ready on loader {} (protocol {}, {} methods)",
                    Reference.MOD_NAME, hooks.getLoaderName(), Reference.PROTOCOL_VERSION,
                    dispatcher.getMethods().size());
        } catch (IOException e) {
            LOGGER.error("{} could not bind 127.0.0.1:{}. Is another client already running? "
                    + "Pass -Dclientdevbridge.port=<other> to use a different port.",
                    Reference.MOD_NAME, config.getPort(), e);
        } catch (Throwable e) {
            LOGGER.error("{} failed to start", Reference.MOD_NAME, e);
        }
    }

    /**
     * Builds the {@code hello} notification that every connection receives immediately on connect.
     */
    private static String helloMessage() {
        JsonObject params = Json.object();
        params.addProperty("protocol", Reference.PROTOCOL_VERSION);
        params.addProperty("mcVersion", ClientState.minecraftVersion());
        params.addProperty("loader", McAdapter.hooks().getLoaderName());
        params.addProperty("clientDevBridgeVersion", McAdapter.hooks().getModVersion());
        params.addProperty("evalEnabled", config.isEvalEnabled());
        // Which project this client was launched for, so a CLI that finds an unexpected client on
        // the port can say whose it is rather than calling every one of them an orphan.
        params.addProperty("projectDir",
                config.getProjectDir() == null ? null : config.getProjectDir().toString());
        params.add("mods", Json.arrayOfStrings(McAdapter.hooks().getLoadedModIds()));
        return Dispatcher.notification("hello", params);
    }

    public static void stop() {
        BridgeServer running = server;
        if (running != null) {
            running.stop();
            server = null;
        }
        LogCapture capture = logCapture;
        if (capture != null) {
            capture.uninstall();
            logCapture = null;
        }
        McAdapter.tickClock().abortAll("The client is shutting down.");
    }

    @Nullable
    public static BridgeServer getServer() {
        return server;
    }

    public static BridgeConfig getConfig() {
        return config;
    }

}
