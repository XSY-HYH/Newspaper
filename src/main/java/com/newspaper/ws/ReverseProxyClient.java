package com.newspaper.ws;

import com.newspaper.config.NewspaperConfig;
import com.newspaper.encryption.EncryptionProvider;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ReverseProxyClient {

    private static final long RETRY_INTERVAL_MS = 5 * 60 * 1000;

    private final NewspaperConfig config;
    private final EncryptionProvider encryptionProvider;
    private final MessageDispatcher dispatcher;
    private final Logger logger;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread connectThread;
    private Socket socket;
    private EncryptionProvider.SessionHandler sessionHandler;

    public ReverseProxyClient(NewspaperConfig config, EncryptionProvider encryptionProvider,
                              MessageDispatcher dispatcher, Logger logger) {
        this.config = config;
        this.encryptionProvider = encryptionProvider;
        this.dispatcher = dispatcher;
        this.logger = logger;
    }

    public void start() {
        if (running.get()) {
            return;
        }

        running.set(true);

        connectThread = new Thread(this::connectLoop, "Newspaper-ReverseProxy");
        connectThread.setDaemon(true);
        connectThread.start();

        logger.info("Reverse proxy client started, target: " + config.getReverseProxyUrl());
    }

    private void connectLoop() {
        while (running.get()) {
            try {
                logger.info("Connecting to reverse proxy server: " + config.getReverseProxyUrl());

                String host = config.getReverseProxyHost();
                int port = config.getReverseProxyPort();

                socket = new Socket(host, port);
                socket.setTcpNoDelay(true);
                socket.setKeepAlive(true);

                InputStream in = socket.getInputStream();
                OutputStream out = socket.getOutputStream();

                performWebSocketHandshake(in, out, host, port);

                sessionHandler = encryptionProvider.createSessionHandler(
                        in, out, config.getUsername(), config.getPassword(), dispatcher::dispatch);

                logger.info("Reverse proxy connected to " + config.getReverseProxyUrl());

                runSession(in, out);

                logger.warning("Reverse proxy connection lost, retrying in 5 minutes...");
            } catch (Exception e) {
                logger.log(Level.WARNING, "Reverse proxy connection failed: " + e.getMessage()
                        + ", retrying in 5 minutes...");
            } finally {
                closeSocket();
            }

            if (running.get()) {
                try {
                    Thread.sleep(RETRY_INTERVAL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        logger.info("Reverse proxy client stopped");
    }

    private void performWebSocketHandshake(InputStream in, OutputStream out,
                                            String host, int port) throws IOException {
        byte[] nonce = new byte[16];
        new java.security.SecureRandom().nextBytes(nonce);
        String wsKey = Base64.getEncoder().encodeToString(nonce);

        String handshake = "GET / HTTP/1.1\r\n"
                + "Host: " + host + ":" + port + "\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + wsKey + "\r\n"
                + "Sec-WebSocket-Version: 13\r\n"
                + "\r\n";

        out.write(handshake.getBytes(StandardCharsets.UTF_8));
        out.flush();

        StringBuilder headerBuilder = new StringBuilder();
        while (true) {
            int b = in.read();
            if (b == -1) {
                throw new IOException("Connection closed during handshake");
            }
            headerBuilder.append((char) b);
            if (headerBuilder.length() >= 4) {
                String end = headerBuilder.substring(headerBuilder.length() - 4);
                if (end.equals("\r\n\r\n")) {
                    break;
                }
            }
        }

        String response = headerBuilder.toString();
        if (!response.contains("101") || !response.contains("Switching Protocols")) {
            throw new IOException("WebSocket handshake failed: " + response.split("\r\n")[0]);
        }
    }

    private void runSession(InputStream in, OutputStream out) {
        try {
            while (running.get() && !socket.isClosed()) {
                WsFrame.ParsedFrame frame = WsFrame.readFrame(in);
                if (frame == null) {
                    break;
                }

                if (frame.opcode() == WsFrame.OPCODE_BINARY) {
                    try {
                        byte[] response = sessionHandler.processIncoming(frame.payload());
                        if (response != null) {
                            synchronized (out) {
                                out.write(WsFrame.createBinaryFrame(response));
                                out.flush();
                            }
                        }
                    } catch (EncryptionProvider.EncryptionException e) {
                        logger.warning("Reverse proxy encryption error: " + e.getMessage());
                        break;
                    }
                } else if (frame.opcode() == WsFrame.OPCODE_PING) {
                    synchronized (out) {
                        out.write(WsFrame.createPongFrame(frame.payload()));
                        out.flush();
                    }
                } else if (frame.opcode() == WsFrame.OPCODE_CLOSE) {
                    break;
                }
            }
        } catch (IOException e) {
            if (running.get()) {
                logger.log(Level.WARNING, "Reverse proxy read error: " + e.getMessage());
            }
        }
    }

    public void sendBinary(byte[] data) {
        if (sessionHandler == null || !sessionHandler.isAuthenticated()) {
            return;
        }

        try {
            byte[] encrypted = sessionHandler.preparePush(data);
            if (encrypted != null && socket != null && !socket.isClosed()) {
                OutputStream out = socket.getOutputStream();
                synchronized (out) {
                    out.write(WsFrame.createBinaryFrame(encrypted));
                    out.flush();
                }
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "Reverse proxy send failed: " + e.getMessage());
        }
    }

    private void closeSocket() {
        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (IOException ignored) {
        }
        socket = null;
        sessionHandler = null;
    }

    public void stop() {
        running.set(false);
        closeSocket();

        if (connectThread != null) {
            connectThread.interrupt();
        }

        logger.info("Reverse proxy client stopped");
    }

    public boolean isConnected() {
        return socket != null && !socket.isClosed() && sessionHandler != null && sessionHandler.isAuthenticated();
    }

    public boolean isRunning() {
        return running.get();
    }
}