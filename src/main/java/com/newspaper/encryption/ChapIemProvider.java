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
    public ClientSessionHandler createClientSessionHandler(InputStream in, OutputStream out,
                                                            String username, String password,
                                                            Function<String, String> messageProcessor) {
        byte[] preSharedKey = CryptoUtil.deriveKey(password);
        return new ChapIemClientSessionHandler(preSharedKey, username, messageProcessor);
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

    private static class ChapIemClientSessionHandler implements ClientSessionHandler {
        private byte[] preSharedKey;
        private byte[] currentId;
        private final String username;
        private final Function<String, String> messageProcessor;
        private boolean authenticated = false;

        ChapIemClientSessionHandler(byte[] preSharedKey, String username,
                                     Function<String, String> messageProcessor) {
            this.preSharedKey = preSharedKey;
            this.username = username;
            this.messageProcessor = messageProcessor;
        }

        @Override
        public byte[] buildAuthRequest() throws EncryptionException {
            com.google.gson.JsonObject authMsg = new com.google.gson.JsonObject();
            authMsg.addProperty("username", username);
            authMsg.addProperty("encryption", "chap-iem");

            return CryptoUtil.encryptString(preSharedKey, new com.google.gson.Gson().toJson(authMsg));
        }

        @Override
        public byte[] processServerResponse(byte[] rawPayload) throws EncryptionException {
            try {
                if (!authenticated) {
                    String decrypted = CryptoUtil.decryptString(preSharedKey, rawPayload);

                    if (decrypted.startsWith("OK:")) {
                        String jsonStr = decrypted.substring(3);
                        com.google.gson.JsonObject response = com.google.gson.JsonParser.parseString(jsonStr)
                                .getAsJsonObject();

                        String idBase64 = response.get("id").getAsString();
                        currentId = Base64.getDecoder().decode(idBase64);
                        authenticated = true;
                        return null;
                    }

                    throw new EncryptionException("Login rejected by server");
                }

                String decrypted = CryptoUtil.decryptString(currentId, rawPayload);
                com.google.gson.JsonObject json = com.google.gson.JsonParser.parseString(decrypted)
                        .getAsJsonObject();

                if (json.has("id")) {
                    String idBase64 = json.get("id").getAsString();
                    currentId = Base64.getDecoder().decode(idBase64);
                }

                if (json.has("data")) {
                    String result = messageProcessor.apply(json.get("data").getAsString());
                    return result != null ? result.getBytes(java.nio.charset.StandardCharsets.UTF_8) : null;
                }

                return null;
            } catch (EncryptionException e) {
                throw e;
            } catch (Exception e) {
                throw new EncryptionException("Client response processing failed: " + e.getMessage(), e);
            }
        }

        @Override
        public boolean isAuthenticated() {
            return authenticated;
        }

        @Override
        public byte[] preparePush(byte[] data) throws EncryptionException {
            if (!authenticated || currentId == null) {
                return null;
            }

            byte[] newId = CryptoUtil.generateId();

            com.google.gson.JsonObject pushPacket = new com.google.gson.JsonObject();
            pushPacket.addProperty("status", "ok");
            pushPacket.addProperty("id", Base64.getEncoder().encodeToString(newId));
            pushPacket.addProperty("data", new String(data, java.nio.charset.StandardCharsets.UTF_8));

            byte[] encrypted = CryptoUtil.encryptString(currentId,
                    new com.google.gson.Gson().toJson(pushPacket));

            currentId = newId;
            return encrypted;
        }

        @Override
        public byte[] prepareRequest(byte[] data) throws EncryptionException {
            if (!authenticated || currentId == null) {
                return null;
            }

            byte[] newId = CryptoUtil.generateId();

            com.google.gson.JsonObject requestPacket = new com.google.gson.JsonObject();
            requestPacket.addProperty("id", Base64.getEncoder().encodeToString(newId));
            requestPacket.addProperty("data", new String(data, java.nio.charset.StandardCharsets.UTF_8));

            byte[] encrypted = CryptoUtil.encryptString(currentId,
                    new com.google.gson.Gson().toJson(requestPacket));

            currentId = newId;
            return encrypted;
        }

        @Override
        public byte[] getCurrentKey() {
            return currentId != null ? currentId : preSharedKey;
        }
    }
}