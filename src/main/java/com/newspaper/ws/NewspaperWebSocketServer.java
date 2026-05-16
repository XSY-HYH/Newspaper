package com.newspaper.ws;

import com.newspaper.chap.ChapiemSession;
import com.newspaper.chap.CryptoUtil;
import com.newspaper.config.NewspaperConfig;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
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
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final List<WsSession> sessions = new CopyOnWriteArrayList<>();
    private final ExecutorService threadPool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "Newspaper-WS-Client");
        t.setDaemon(true);
        return t;
    });

    private ServerSocket serverSocket;
    private Thread acceptThread;

    public NewspaperWebSocketServer(NewspaperConfig config, MessageDispatcher dispatcher, Logger logger) {
        this.config = config;
        this.dispatcher = dispatcher;
        this.logger = logger;
    }

    public void start() {
        if (running.get()) {
            return;
        }

        try {
            InetAddress bindAddr = config.isIpv6()
                    ? InetAddress.getByName("::")
                    : InetAddress.getByName("0.0.0.0");

            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress(bindAddr, config.getPort()));

            running.set(true);

            acceptThread = new Thread(this::acceptLoop, "Newspaper-WS-Acceptor");
            acceptThread.setDaemon(true);
            acceptThread.start();

            logger.info("WebSocket server started on port " + config.getPort()
                    + (config.isIpv6() ? " (IPv6)" : " (IPv4)"));
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Failed to start WebSocket server: " + e.getMessage(), e);
        }
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
            byte[] preSharedKey = CryptoUtil.deriveKey(config.getPassword());
            ChapiemSession chapSession = new ChapiemSession(preSharedKey, config.getUsername());

            WsSession wsSession = new WsSession(socket, chapSession, dispatcher, logger);
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