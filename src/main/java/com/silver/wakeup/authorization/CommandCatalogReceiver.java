package com.silver.wakeup.authorization;

import com.silver.authorization.ServerId;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import org.slf4j.Logger;

/** Receives startup/runtime command catalogs without requiring an attached Minecraft player. */
public final class CommandCatalogReceiver implements AutoCloseable {
    private static final int MAX_FRAME_BYTES = 2 * 1024 * 1024;
    private final AuthorizationBackendKeys keys;
    private final AuthorizationService authorization;
    private final CommandCatalogStore catalogs;
    private final Logger log;
    private final ExecutorService clients = Executors.newCachedThreadPool(daemonFactory("WakeUpLobby-CatalogClient"));
    private volatile ServerSocket listener;
    private volatile Thread acceptThread;

    private CommandCatalogReceiver(AuthorizationBackendKeys keys, AuthorizationService authorization,
                                   CommandCatalogStore catalogs, Logger log) {
        this.keys = keys;
        this.authorization = authorization;
        this.catalogs = catalogs;
        this.log = log;
    }

    public static CommandCatalogReceiver start(Path dataDir, AuthorizationBackendKeys keys,
                                               AuthorizationService authorization,
                                               CommandCatalogStore catalogs, Logger log) throws IOException {
        Path file = dataDir.resolve("authorization-catalog.properties");
        if (Files.notExists(file)) {
            try {
                Files.createFile(file, PosixFilePermissions.asFileAttribute(
                        PosixFilePermissions.fromString("rw-------")));
            } catch (UnsupportedOperationException unsupported) {
                Files.createFile(file);
            }
            Files.writeString(file, "# Dedicated authenticated backend command-catalog listener.\n"
                    + "bind_address=0.0.0.0\nport=25576\n", StandardCharsets.UTF_8);
        }
        try { Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------")); }
        catch (UnsupportedOperationException unsupported) { /* Host ACLs apply. */ }
        Properties config = new Properties();
        try (var input = Files.newInputStream(file)) { config.load(input); }
        String bind = config.getProperty("bind_address", "0.0.0.0").trim();
        int port;
        try { port = Integer.parseInt(config.getProperty("port", "25576").trim()); }
        catch (NumberFormatException invalid) { throw new IOException("Invalid authorization catalog port", invalid); }
        if (port < 1 || port > 65535) throw new IOException("Authorization catalog port must be 1..65535");

        CommandCatalogReceiver receiver = new CommandCatalogReceiver(keys, authorization, catalogs, log);
        ServerSocket socket = new ServerSocket();
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress(bind, port));
        receiver.listener = socket;
        Thread thread = daemonFactory("WakeUpLobby-CatalogAccept").newThread(receiver::acceptLoop);
        receiver.acceptThread = thread;
        thread.start();
        log.info("[CommandPolicy] Authenticated catalog listener ready on {}:{}", bind, port);
        return receiver;
    }

    private void acceptLoop() {
        while (!listener.isClosed()) {
            try {
                Socket client = listener.accept();
                clients.execute(() -> handle(client));
            } catch (IOException failure) {
                if (!listener.isClosed()) log.warn("[CommandPolicy] Catalog listener accept failed: {}", failure.toString());
            }
        }
    }

    private void handle(Socket client) {
        try (client;
             DataInputStream input = new DataInputStream(client.getInputStream());
             DataOutputStream output = new DataOutputStream(client.getOutputStream())) {
            client.setSoTimeout(5_000);
            String claimedServer = input.readUTF();
            ServerId server = ServerId.of(claimedServer);
            byte[] key = keys.key(server).orElse(null);
            AuthorizationState state = authorization.currentState();
            int length = input.readInt();
            if (key == null || !state.enabledServers().contains(server)
                    || length < 1 || length > MAX_FRAME_BYTES) {
                output.writeBoolean(false);
                output.flush();
                log.warn("[CommandPolicy] Rejected catalog connection for unknown/disabled backend {}", server);
                return;
            }
            byte[] frame = input.readNBytes(length);
            if (frame.length != length) throw new IOException("Truncated catalog frame");
            boolean accepted = catalogs.accept(frame, server, key, Instant.now());
            output.writeBoolean(accepted);
            output.flush();
        } catch (Exception malformed) {
            log.warn("[CommandPolicy] Rejected malformed catalog connection: {}", malformed.toString());
        }
    }

    @Override
    public void close() {
        ServerSocket current = listener;
        if (current != null) {
            try { current.close(); }
            catch (IOException failure) { log.debug("[CommandPolicy] Could not close catalog listener: {}", failure.toString()); }
        }
        clients.shutdownNow();
    }

    private static ThreadFactory daemonFactory(String name) {
        return task -> {
            Thread thread = new Thread(task, name);
            thread.setDaemon(true);
            return thread;
        };
    }
}
