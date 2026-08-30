package org.cyclops.clientdevbridge.net;

import org.cyclops.clientdevbridge.ClientDevBridge;
import org.cyclops.clientdevbridge.protocol.Dispatcher;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.UnknownHostException;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * The localhost WebSocket endpoint the CLI talks to.
 *
 * The socket is bound to the loopback address only, and the server is never started unless
 * {@code -Dclientdevbridge.enabled=true} was passed, so a stray copy of this mod in a player's
 * mods folder listens on nothing.
 *
 * @author rubensworks
 */
public class BridgeServer {

    private final int port;
    private final Dispatcher dispatcher;
    private final Supplier<String> helloSupplier;
    private final List<BridgeConnection> connections = new CopyOnWriteArrayList<>();
    private final AtomicInteger connectionCounter = new AtomicInteger();

    private ServerSocket serverSocket;
    private Thread acceptThread;
    private volatile boolean running;

    public BridgeServer(int port, Dispatcher dispatcher, Supplier<String> helloSupplier) {
        this.port = port;
        this.dispatcher = dispatcher;
        this.helloSupplier = helloSupplier;
    }

    public void start() throws IOException {
        // Explicitly the IPv4 loopback, not InetAddress.getLoopbackAddress(). NeoForge's dev run
        // configuration passes -Djava.net.preferIPv6Addresses=system, which leaves the platform
        // lookup policy with neither IPV4_FIRST nor IPV6_FIRST set; Inet6AddressImpl then treats
        // that as "IPv6 first" and getLoopbackAddress() answers ::1 on any machine that has ::1
        // bound. clientdevbridge-cli connects to 127.0.0.1, so the client became unreachable and
        // waited out its whole start timeout. Fabric never showed it -- Loom sets no such property
        // -- and neither does a container without IPv6, where ::1 is not bound and the IPv4
        // fallback happens to be picked.
        this.serverSocket = new ServerSocket(this.port, 16, loopbackAddress());
        this.running = true;
        this.acceptThread = new Thread(this::acceptLoop, "ClientDevBridge-accept");
        this.acceptThread.setDaemon(true);
        this.acceptThread.start();
        // The address is read back from the socket rather than hardcoded into the message. The
        // previous version said 127.0.0.1 whatever it had actually bound, which is exactly the
        // sort of log line that costs hours.
        ClientDevBridge.LOGGER.info("ClientDevBridge listening on ws://{}:{}",
                this.serverSocket.getInetAddress().getHostAddress(), getBoundPort());
    }

    private static InetAddress loopbackAddress() throws IOException {
        try {
            return InetAddress.getByName("127.0.0.1");
        } catch (UnknownHostException e) {
            // Cannot realistically happen for a literal address, but falling back keeps a strange
            // host from stopping the bridge outright.
            return InetAddress.getLoopbackAddress();
        }
    }

    public int getBoundPort() {
        return this.serverSocket == null ? this.port : this.serverSocket.getLocalPort();
    }

    private void acceptLoop() {
        while (this.running) {
            try {
                Socket socket = this.serverSocket.accept();
                BridgeConnection[] holder = new BridgeConnection[1];
                BridgeConnection connection = new BridgeConnection(socket, this.dispatcher,
                        () -> this.connections.remove(holder[0]));
                holder[0] = connection;
                this.connections.add(connection);
                Thread thread = new Thread(() -> connection.run(this.helloSupplier.get()),
                        "ClientDevBridge-conn-" + this.connectionCounter.incrementAndGet());
                thread.setDaemon(true);
                thread.start();
            } catch (IOException e) {
                if (this.running) {
                    ClientDevBridge.LOGGER.warn("Bridge accept loop failed", e);
                }
                return;
            }
        }
    }

    /**
     * Pushes a notification to every connected client. Used for {@code log.line}, {@code screen.changed},
     * {@code world.joined} and {@code world.left}.
     */
    public void broadcast(String message) {
        for (BridgeConnection connection : this.connections) {
            if (connection.isOpen()) {
                connection.send(message);
            }
        }
    }

    public boolean hasConnections() {
        return !this.connections.isEmpty();
    }

    public void stop() {
        this.running = false;
        for (BridgeConnection connection : this.connections) {
            connection.close();
        }
        this.connections.clear();
        if (this.serverSocket != null) {
            try {
                this.serverSocket.close();
            } catch (IOException ignored) {
                // Nothing useful to do while shutting down.
            }
        }
    }

}
