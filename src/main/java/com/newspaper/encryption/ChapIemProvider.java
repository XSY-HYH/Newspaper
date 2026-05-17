package com.newspaper.encryption;

import com.newspaper.chap.Chapiem;
import com.newspaper.chap.ChapiemSession;
import com.newspaper.chap.CryptoUtil;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.Base64;
import java.util.function.Function;

public class ChapIemProvider implements EncryptionProvider {

    @Override
    public ServerSocket createServerSocket(int port, boolean ipv6) throws Exception {
        InetAddress bindAddr = ipv6
                ? InetAddress.getByName("::")
                : InetAddress.getByName("0.0.0.0");

        ServerSocket serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(bindAddr, port));
        return serverSocket;
    }

    @Override
    public SessionHandler createSessionHandler(InputStream in, OutputStream out,
                                                String username, String password,
                                                Function<String, String> messageProcessor) {
        byte[] preSharedKey = CryptoUtil.deriveKey(password);
        ChapiemSession chapSession = new ChapiemSession(preSharedKey, username);
        return new ChapIemSessionHandler(chapSession, messageProcessor);
    }

    @Override
    public boolean requiresTlsSocket() {
        return false;
    }

    private static class ChapIemSessionHandler implements SessionHandler {
        private final ChapiemSession chapSession;
        private final Function<String, String> messageProcessor;
        private byte[] pendingRecoveryNewId = null;

        ChapIemSessionHandler(ChapiemSession chapSession, Function<String, String> messageProcessor) {
            this.chapSession = chapSession;
            this.messageProcessor = messageProcessor;
        }

        @Override
        public byte[] processIncoming(byte[] rawPayload) throws EncryptionException {
            if (!chapSession.isAuthenticated()) {
                Chapiem.LoginResult loginResult = Chapiem.processLogin(chapSession, rawPayload);
                if (loginResult.success()) {
                    return loginResult.responseData();
                } else {
                    throw new EncryptionException("Authentication failed: " + loginResult.errorMessage());
                }
            }

            Chapiem.OperationResult result = Chapiem.processOperation(
                    chapSession, rawPayload, messageProcessor);

            if (result.needsRecovery()) {
                pendingRecoveryNewId = result.newId();
                return result.recoveryData();
            }

            if (result.success() && result.responseData() != null) {
                return result.responseData();
            } else if (!result.success()) {
                throw new EncryptionException("Operation failed");
            }

            return null;
        }

        @Override
        public byte[] prepareOutgoing(byte[] data) throws EncryptionException {
            return data;
        }

        @Override
        public boolean isAuthenticated() {
            return chapSession.isAuthenticated();
        }

        @Override
        public byte[] preparePush(byte[] data) throws EncryptionException {
            if (!chapSession.isAuthenticated()) {
                return null;
            }

            byte[] currentKey = chapSession.getCurrentKey();
            byte[] newId = CryptoUtil.generateId();

            com.google.gson.JsonObject pushPacket = new com.google.gson.JsonObject();
            pushPacket.addProperty("status", "ok");
            pushPacket.addProperty("id", Base64.getEncoder().encodeToString(newId));
            pushPacket.addProperty("data", new String(data, java.nio.charset.StandardCharsets.UTF_8));

            byte[] encrypted = Chapiem.encryptResponse(currentKey,
                    new com.google.gson.Gson().toJson(pushPacket));

            chapSession.updateId(newId);
            return encrypted;
        }

        @Override
        public byte[] getCurrentKey() {
            return chapSession.getCurrentKey();
        }
    }
}