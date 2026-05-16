package com.newspaper.ws;

import com.newspaper.chap.Chapiem;
import com.newspaper.chap.ChapiemSession;
import com.newspaper.chap.CryptoUtil;

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
    private final ChapiemSession chapSession;
    private final MessageDispatcher dispatcher;
    private final Logger logger;
    private volatile boolean running = true;

    public WsSession(Socket socket, ChapiemSession chapSession, MessageDispatcher dispatcher, Logger logger)
            throws IOException {
        this.socket = socket;
        this.in = socket.getInputStream();
        this.out = socket.getOutputStream();
        this.chapSession = chapSession;
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
        StringBuilder headerBuilder = new StringBuilder();
        String headers = readHttpHeaders(headerBuilder);
        if (headers == null) {
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

    private String readHttpHeaders(StringBuilder headerBuilder) throws IOException {
        int prev = -1;
        int curr;
        while ((curr = in.read()) != -1) {
            headerBuilder.append((char) curr);
            if (prev == '\r' && curr == '\n') {
                String line = headerBuilder.toString().trim();
                if (line.isEmpty()) {
                    String result = headerBuilder.toString();
                    headerBuilder.setLength(0);
                    return result;
                }
                headerBuilder.setLength(0);
            }
            prev = curr;
        }
        return null;
    }

    private String extractHeader(String headers, String headerName) {
        for (String line : headers.split("\r\n")) {
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
        if (!chapSession.isAuthenticated()) {
            Chapiem.LoginResult loginResult = Chapiem.processLogin(chapSession, payload);
            if (loginResult.success()) {
                sendFrame(WsFrame.createBinaryFrame(loginResult.responseData()));
                logger.info("Client authenticated: " + socket.getInetAddress());
            } else {
                logger.warning("Client authentication failed: " + loginResult.errorMessage());
                sendFrame(WsFrame.createCloseFrame());
                running = false;
            }
            return;
        }

        Chapiem.OperationResult result = Chapiem.processOperation(
                chapSession, payload, dispatcher::dispatch);

        if (result.needsRecovery()) {
            sendFrame(WsFrame.createBinaryFrame(result.recoveryData()));
            chapSession.updateId(result.newId());
            return;
        }

        if (result.success() && result.responseData() != null) {
            sendFrame(WsFrame.createBinaryFrame(result.responseData()));
        } else if (!result.success()) {
            byte[] errorFrame = WsFrame.createCloseFrame();
            sendFrame(errorFrame);
        }
    }

    private void handleTextMessage(String message) throws IOException {
        sendFrame(WsFrame.createTextFrame(
                "{\"status\":\"error\",\"message\":\"Binary frames required for CHAP-IEM\"}"));
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
        if (!chapSession.isAuthenticated()) {
            return;
        }

        byte[] currentKey = chapSession.getCurrentKey();
        byte[] newId = CryptoUtil.generateId();

        com.google.gson.JsonObject pushPacket = new com.google.gson.JsonObject();
        pushPacket.addProperty("status", "ok");
        pushPacket.addProperty("id", java.util.Base64.getEncoder().encodeToString(newId));
        pushPacket.addProperty("data", new String(data, StandardCharsets.UTF_8));

        byte[] encrypted = Chapiem.encryptResponse(currentKey,
                new com.google.gson.Gson().toJson(pushPacket));

        chapSession.updateId(newId);
        sendFrame(WsFrame.createBinaryFrame(encrypted));
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