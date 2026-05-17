package com.newspaper.ws;

import com.newspaper.encryption.EncryptionProvider;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;
import java.util.logging.Logger;

public class WsSession implements Runnable {

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private final EncryptionProvider.SessionHandler sessionHandler;
    private final MessageDispatcher dispatcher;
    private final Logger logger;
    private volatile boolean running = true;

    public WsSession(Socket socket, EncryptionProvider.SessionHandler sessionHandler,
                     MessageDispatcher dispatcher, Logger logger) throws IOException {
        this.socket = socket;
        this.in = socket.getInputStream();
        this.out = socket.getOutputStream();
        this.sessionHandler = sessionHandler;
        this.dispatcher = dispatcher;
        this.logger = logger;
    }

    @Override
    public void run() {
        try {
            if (!performHandshake()) {
                close();
                return;
            }

            logger.info("WebSocket handshake completed for " + socket.getInetAddress());

            while (running && !socket.isClosed()) {
                WsFrame.ParsedFrame frame = WsFrame.readFrame(in);
                if (frame == null) {
                    break;
                }

                handleFrame(frame);
            }
        } catch (IOException e) {
            logger.log(Level.FINE, "WebSocket connection closed: " + e.getMessage());
        } finally {
            close();
        }
    }

    private boolean performHandshake() throws IOException {
        String headers = readHttpHeaders();

        if (headers == null || headers.isEmpty()) {
            return false;
        }

        String key = extractHeader(headers, "Sec-WebSocket-Key:");

        if (key == null) {
            return false;
        }

        String acceptKey = WsFrame.generateAcceptKey(key);

        StringBuilder response = new StringBuilder();
        response.append("HTTP/1.1 101 Switching Protocols\r\n");
        response.append("Upgrade: websocket\r\n");
        response.append("Connection: Upgrade\r\n");
        response.append("Sec-WebSocket-Accept: ").append(acceptKey).append("\r\n");
        response.append("\r\n");

        out.write(response.toString().getBytes(StandardCharsets.UTF_8));
        out.flush();
        return true;
    }

    private String readHttpHeaders() throws IOException {
        StringBuilder allHeaders = new StringBuilder();

        while (true) {
            int b = in.read();
            if (b == -1) {
                return null;
            }
            allHeaders.append((char) b);

            if (allHeaders.length() >= 4) {
                String end = allHeaders.substring(allHeaders.length() - 4);
                if (end.equals("\r\n\r\n")) {
                    return allHeaders.toString();
                }
            }
        }
    }

    private String extractHeader(String headers, String headerName) {
        String[] lines = headers.split("\r\n");
        for (String line : lines) {
            if (line.toLowerCase().startsWith(headerName.toLowerCase())) {
                return line.substring(headerName.length()).trim();
            }
        }
        return null;
    }

    private void handleFrame(WsFrame.ParsedFrame frame) throws IOException {
        switch (frame.opcode()) {
            case WsFrame.OPCODE_BINARY:
                handleBinaryMessage(frame.payload());
                break;
            case WsFrame.OPCODE_TEXT:
                handleTextMessage(new String(frame.payload(), StandardCharsets.UTF_8));
                break;
            case WsFrame.OPCODE_CLOSE:
                running = false;
                sendFrame(WsFrame.createCloseFrame());
                break;
            case WsFrame.OPCODE_PING:
                sendFrame(WsFrame.createPongFrame(frame.payload()));
                break;
            case WsFrame.OPCODE_PONG:
                break;
            default:
                break;
        }
    }

    private void handleBinaryMessage(byte[] payload) throws IOException {
        try {
            byte[] response = sessionHandler.processIncoming(payload);

            if (response != null) {
                sendFrame(WsFrame.createBinaryFrame(response));
            }
        } catch (EncryptionProvider.EncryptionException e) {
            if (!sessionHandler.isAuthenticated()) {
                logger.warning("Client authentication failed: " + e.getMessage());
            } else {
                logger.warning("Encryption processing failed: " + e.getMessage());
            }
            sendFrame(WsFrame.createCloseFrame());
            running = false;
        }
    }

    private void handleTextMessage(String message) throws IOException {
        sendFrame(WsFrame.createTextFrame(
                "{\"status\":\"error\",\"message\":\"Binary frames required\"}"));
    }

    public void sendFrame(byte[] frame) {
        try {
            synchronized (out) {
                out.write(frame);
                out.flush();
            }
        } catch (IOException e) {
            logger.log(Level.FINE, "Failed to send frame: " + e.getMessage());
            running = false;
        }
    }

    public void sendBinary(byte[] data) {
        try {
            byte[] encrypted = sessionHandler.preparePush(data);
            if (encrypted != null) {
                sendFrame(WsFrame.createBinaryFrame(encrypted));
            }
        } catch (EncryptionProvider.EncryptionException e) {
            logger.log(Level.WARNING, "Failed to prepare push data: " + e.getMessage());
        }
    }

    public void close() {
        running = false;
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    public boolean isRunning() {
        return running;
    }
}