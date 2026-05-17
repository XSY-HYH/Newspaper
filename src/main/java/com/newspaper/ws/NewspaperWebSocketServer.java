package com.newspaper.ws;

import com.newspaper.config.NewspaperConfig;
import com.newspaper.encryption.ChapIemProvider;
import com.newspaper.encryption.EncryptionMode;
import com.newspaper.encryption.EncryptionProvider;
import com.newspaper.encryption.SshProvider;
import com.newspaper.encryption.TlsProvider;

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

public class NewspaperWebSocketServer {

    private final NewspaperConfig config;
    private final MessageDispatcher dispatcher;
    private final Logger logger;
    private final File pluginDataFolder;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final List<WsSession> sessions = new CopyOnWriteArrayList<>();
    private final ExecutorService threadPool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "Newspaper-WS-Client");
        t.setDaemon(true);
        return t;
    });

    private ServerSocket serverSocket;
    private Thread acceptThread;
    private EncryptionProvider encryptionProvider;

    public NewspaperWebSocketServer(NewspaperConfig config, MessageDispatcher dispatcher,
                                    Logger logger, File pluginDataFolder) {
        this.config = config;
        this.dispatcher = dispatcher;
        this.logger = logger;
        this.pluginDataFolder = pluginDataFolder;
    }

    public void start() {
        if (running.get()) {
            return;
        }

        try {
            EncryptionMode mode = EncryptionMode.fromConfig(config.getEncryption());
            encryptionProvider = createProvider(mode);

            serverSocket = encryptionProvider.createServerSocket(config.getPort(), config.isIpv6());

            running.set(true);

            acceptThread = new Thread(this::acceptLoop, "Newspaper-WS-Acceptor");
            acceptThread.setDaemon(true);
            acceptThread.start();

            logger.info("WebSocket server started on port " + config.getPort()
                    + " (encryption: " + mode.getConfigValue() + ")"
                    + (config.isIpv6() ? " (IPv6)" : " (IPv4)"));
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to start WebSocket server: " + e.getMessage(), e);
        }
    }

    private EncryptionProvider createProvider(EncryptionMode mode) {
        return switch (mode) {
            case TLS -> new TlsProvider(pluginDataFolder, logger);
            case SSH -> new SshProvider();
            case CHAP_IEM -> new ChapIemProvider();
        };
    }

    private void acceptLoop() {
        while (running.get() && !serverSocket.isClosed()) {
            try {
                Socket socket = serverSocket.accept();
                threadPool.submit(() -> handleClient(socket));
            } catch (IOException e) {
                if (running.get()) {
                    logger.log(Level.WARNING, "Accept error: " + e.getMessage());
                }
            }
        }
    }

    private void handleClient(Socket socket) {
        try {
            EncryptionProvider.SessionHandler sessionHandler = encryptionProvider.createSessionHandler(
                    socket.getInputStream(),
                    socket.getOutputStream(),
                    config.getUsername(),
                    config.getPassword(),
                    dispatcher::dispatch
            );

            WsSession wsSession = new WsSession(socket, sessionHandler, dispatcher, logger);
            sessions.add(wsSession);

            logger.info("WebSocket client connected: " + socket.getInetAddress().getHostAddress());

            wsSession.run();

            sessions.remove(wsSession);
            logger.info("WebSocket client disconnected: " + socket.getInetAddress().getHostAddress());
        } catch (IOException e) {
            logger.log(Level.WARNING, "Failed to handle client: " + e.getMessage());
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    public void stop() {
        running.set(false);

        for (WsSession session : sessions) {
            session.close();
        }
        sessions.clear();

        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException e) {
            logger.log(Level.WARNING, "Error closing server socket: " + e.getMessage());
        }

        threadPool.shutdownNow();
        logger.info("WebSocket server stopped");
    }

    public void broadcast(byte[] data) {
        for (WsSession session : sessions) {
            if (session.isRunning()) {
                session.sendBinary(data);
            }
        }
    }

    public boolean isRunning() {
        return running.get();
    }

    public int getActiveConnections() {
        return sessions.size();
    }
}