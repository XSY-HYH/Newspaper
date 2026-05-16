package com.newspaper.ws;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

public final class WsFrame {

    public static final int OPCODE_TEXT = 0x1;
    public static final int OPCODE_BINARY = 0x2;
    public static final int OPCODE_CLOSE = 0x8;
    public static final int OPCODE_PING = 0x9;
    public static final int OPCODE_PONG = 0xA;

    private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private WsFrame() {
    }

    public static byte[] createFrame(byte[] payload, int opcode) {
        int length = payload.length;
        ByteArrayOutputStream frame = new ByteArrayOutputStream();

        frame.write(0x80 | opcode);

        if (length <= 125) {
            frame.write(length);
        } else if (length <= 65535) {
            frame.write(126);
            frame.write((length >> 8) & 0xFF);
            frame.write(length & 0xFF);
        } else {
            frame.write(127);
            for (int i = 7; i >= 0; i--) {
                frame.write((int) ((length >> (8 * i)) & 0xFF));
            }
        }

        try {
            frame.write(payload);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create frame", e);
        }

        return frame.toByteArray();
    }

    public static byte[] createTextFrame(String text) {
        return createFrame(text.getBytes(StandardCharsets.UTF_8), OPCODE_TEXT);
    }

    public static byte[] createBinaryFrame(byte[] data) {
        return createFrame(data, OPCODE_BINARY);
    }

    public static byte[] createCloseFrame() {
        return createFrame(new byte[0], OPCODE_CLOSE);
    }

    public static byte[] createPongFrame(byte[] pingData) {
        return createFrame(pingData, OPCODE_PONG);
    }

    public static ParsedFrame readFrame(InputStream in) throws IOException {
        int firstByte = in.read();
        if (firstByte == -1) {
            return null;
        }

        int opcode = firstByte & 0x0F;
        boolean fin = (firstByte & 0x80) != 0;

        int secondByte = in.read();
        if (secondByte == -1) {
            return null;
        }

        boolean masked = (secondByte & 0x80) != 0;
        long payloadLength = secondByte & 0x7F;

        if (payloadLength == 126) {
            payloadLength = ((in.read() & 0xFF) << 8) | (in.read() & 0xFF);
        } else if (payloadLength == 127) {
            payloadLength = 0;
            for (int i = 0; i < 8; i++) {
                payloadLength = (payloadLength << 8) | (in.read() & 0xFF);
            }
        }

        byte[] maskKey = null;
        if (masked) {
            maskKey = new byte[4];
            int bytesRead = in.read(maskKey);
            if (bytesRead < 4) {
                return null;
            }
        }

        byte[] payload = new byte[(int) payloadLength];
        int totalRead = 0;
        while (totalRead < payloadLength) {
            int read = in.read(payload, totalRead, (int) (payloadLength - totalRead));
            if (read == -1) {
                return null;
            }
            totalRead += read;
        }

        if (masked && maskKey != null) {
            for (int i = 0; i < payload.length; i++) {
                payload[i] = (byte) (payload[i] ^ maskKey[i % 4]);
            }
        }

        return new ParsedFrame(fin, opcode, payload);
    }

    public static String generateAcceptKey(String clientKey) {
        try {
            String concatenated = clientKey + WS_GUID;
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] hash = sha1.digest(concatenated.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate accept key", e);
        }
    }

    public record ParsedFrame(boolean fin, int opcode, byte[] payload) {
    }
}