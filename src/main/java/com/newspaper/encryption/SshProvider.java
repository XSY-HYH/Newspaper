package com.newspaper.encryption;

import com.newspaper.chap.CryptoUtil;

import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.function.Function;

public class SshProvider implements EncryptionProvider {

    private static final int RSA_KEY_SIZE = 2048;
    private static final String SIGNATURE_ALGORITHM = "SHA256withRSA";

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
        return new SshSessionHandler(in, out, username, password, messageProcessor);
    }

    @Override
    public ClientSessionHandler createClientSessionHandler(InputStream in, OutputStream out,
                                                            String username, String password,
                                                            Function<String, String> messageProcessor) {
        return new SshClientSessionHandler(in, out, username, password, messageProcessor);
    }

    @Override
    public boolean requiresTlsSocket() {
        return false;
    }

    private static class SshSessionHandler implements SessionHandler {
        private final InputStream in;
        private final OutputStream out;
        private final String expectedUsername;
        private final byte[] preSharedKey;
        private final Function<String, String> messageProcessor;
        private boolean authenticated = false;
        private byte[] sessionId = null;
        private KeyPair serverKeyPair;

        SshSessionHandler(InputStream in, OutputStream out,
                          String username, String password,
                          Function<String, String> messageProcessor) {
            this.in = in;
            this.out = out;
            this.expectedUsername = username;
            this.preSharedKey = CryptoUtil.deriveKey(password);
            this.messageProcessor = messageProcessor;
        }

        @Override
        public byte[] processIncoming(byte[] rawPayload) throws EncryptionException {
            try {
                if (!authenticated) {
                    return handleKeyExchange(rawPayload);
                }

                byte[] currentKey = sessionId;
                String decrypted = CryptoUtil.decryptString(currentKey, rawPayload);

                String operationResult = messageProcessor.apply(decrypted);

                byte[] newId = CryptoUtil.generateId();

                com.google.gson.JsonObject response = new com.google.gson.JsonObject();
                response.addProperty("status", "ok");
                response.addProperty("id", Base64.getEncoder().encodeToString(newId));
                response.addProperty("data", operationResult);

                byte[] encryptedResponse = CryptoUtil.encryptString(currentKey, response.toString());
                sessionId = newId;

                return encryptedResponse;
            } catch (EncryptionException e) {
                throw e;
            } catch (Exception e) {
                throw new EncryptionException("SSH session processing failed: " + e.getMessage(), e);
            }
        }

        private byte[] handleKeyExchange(byte[] rawPayload) throws Exception {
            ByteBuffer buffer = ByteBuffer.wrap(rawPayload);
            int phase = buffer.getInt();

            if (phase == 1) {
                return handlePhase1(buffer);
            } else if (phase == 2) {
                return handlePhase2(buffer);
            }

            throw new EncryptionException("Invalid key exchange phase: " + phase);
        }

        private byte[] handlePhase1(ByteBuffer buffer) throws Exception {
            int clientPubKeyLen = buffer.getInt();
            byte[] clientPubKeyBytes = new byte[clientPubKeyLen];
            buffer.get(clientPubKeyBytes);

            int signatureLen = buffer.getInt();
            byte[] signatureBytes = new byte[signatureLen];
            buffer.get(signatureBytes);

            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            X509EncodedKeySpec keySpec = new X509EncodedKeySpec(clientPubKeyBytes);
            PublicKey clientPubKey = keyFactory.generatePublic(keySpec);

            byte[] challenge = new byte[32];
            new SecureRandom().nextBytes(challenge);

            byte[] expectedSignature = CryptoUtil.sha256(
                    concatenate(clientPubKeyBytes, challenge));

            Signature sig = Signature.getInstance(SIGNATURE_ALGORITHM);
            sig.initVerify(clientPubKey);
            sig.update(expectedSignature);
            if (!sig.verify(signatureBytes)) {
                throw new EncryptionException("SSH key exchange: client signature verification failed");
            }

            serverKeyPair = generateKeyPair();

            byte[] sharedSecret = deriveSharedSecret(clientPubKeyBytes);

            sessionId = CryptoUtil.sha256(concatenate(
                    clientPubKeyBytes,
                    serverKeyPair.getPublic().getEncoded(),
                    sharedSecret,
                    preSharedKey
            ));

            Signature serverSig = Signature.getInstance(SIGNATURE_ALGORITHM);
            serverSig.initSign(serverKeyPair.getPrivate());
            serverSig.update(CryptoUtil.sha256(
                    concatenate(serverKeyPair.getPublic().getEncoded(), challenge)));
            byte[] serverSignature = serverSig.sign();

            ByteBuffer response = ByteBuffer.allocate(
                    4 + 4 + serverKeyPair.getPublic().getEncoded().length
                            + 4 + challenge.length
                            + 4 + serverSignature.length);
            response.putInt(2);
            response.putInt(serverKeyPair.getPublic().getEncoded().length);
            response.put(serverKeyPair.getPublic().getEncoded());
            response.putInt(challenge.length);
            response.put(challenge);
            response.putInt(serverSignature.length);
            response.put(serverSignature);

            authenticated = true;

            return response.array();
        }

        private byte[] handlePhase2(ByteBuffer buffer) throws Exception {
            int encAuthLen = buffer.getInt();
            byte[] encAuth = new byte[encAuthLen];
            buffer.get(encAuth);

            String authData = CryptoUtil.decryptString(sessionId, encAuth);
            com.google.gson.JsonObject json = com.google.gson.JsonParser.parseString(authData)
                    .getAsJsonObject();

            String username = json.get("username").getAsString();
            if (!username.equals(expectedUsername)) {
                throw new EncryptionException("SSH key exchange: invalid username");
            }

            byte[] newId = CryptoUtil.generateId();
            sessionId = newId;

            com.google.gson.JsonObject response = new com.google.gson.JsonObject();
            response.addProperty("status", "ok");
            response.addProperty("id", Base64.getEncoder().encodeToString(newId));

            return CryptoUtil.encryptString(sessionId, response.toString());
        }

        private byte[] deriveSharedSecret(byte[] clientPubKeyBytes) {
            return CryptoUtil.sha256(concatenate(
                    clientPubKeyBytes,
                    preSharedKey,
                    serverKeyPair.getPublic().getEncoded()
            ));
        }

        @Override
        public byte[] prepareOutgoing(byte[] data) throws EncryptionException {
            return data;
        }

        @Override
        public boolean isAuthenticated() {
            return authenticated;
        }

        @Override
        public byte[] preparePush(byte[] data) throws EncryptionException {
            if (!authenticated || sessionId == null) {
                return null;
            }

            byte[] newId = CryptoUtil.generateId();

            com.google.gson.JsonObject pushPacket = new com.google.gson.JsonObject();
            pushPacket.addProperty("status", "ok");
            pushPacket.addProperty("id", Base64.getEncoder().encodeToString(newId));
            pushPacket.addProperty("data", new String(data, StandardCharsets.UTF_8));

            byte[] encrypted = CryptoUtil.encryptString(sessionId,
                    new com.google.gson.Gson().toJson(pushPacket));

            sessionId = newId;
            return encrypted;
        }

        @Override
        public byte[] getCurrentKey() {
            return sessionId != null ? sessionId : preSharedKey;
        }
    }

    private static class SshClientSessionHandler implements ClientSessionHandler {
        private final InputStream in;
        private final OutputStream out;
        private final String username;
        private final byte[] preSharedKey;
        private final Function<String, String> messageProcessor;
        private boolean authenticated = false;
        private byte[] sessionId = null;
        private KeyPair clientKeyPair;
        private int keyExchangePhase = 0;

        SshClientSessionHandler(InputStream in, OutputStream out,
                                 String username, String password,
                                 Function<String, String> messageProcessor) {
            this.in = in;
            this.out = out;
            this.username = username;
            this.preSharedKey = CryptoUtil.deriveKey(password);
            this.messageProcessor = messageProcessor;
        }

        @Override
        public byte[] buildAuthRequest() throws EncryptionException {
            try {
                clientKeyPair = generateKeyPair();
                keyExchangePhase = 1;

                byte[] clientPubKeyBytes = clientKeyPair.getPublic().getEncoded();

                byte[] challenge = new byte[32];
                new SecureRandom().nextBytes(challenge);

                byte[] signatureInput = CryptoUtil.sha256(
                        concatenate(clientPubKeyBytes, challenge));

                Signature sig = Signature.getInstance(SIGNATURE_ALGORITHM);
                sig.initSign(clientKeyPair.getPrivate());
                sig.update(signatureInput);
                byte[] signatureBytes = sig.sign();

                ByteBuffer buffer = ByteBuffer.allocate(
                        4 + 4 + clientPubKeyBytes.length
                                + 4 + challenge.length
                                + 4 + signatureBytes.length);
                buffer.putInt(1);
                buffer.putInt(clientPubKeyBytes.length);
                buffer.put(clientPubKeyBytes);
                buffer.putInt(challenge.length);
                buffer.put(challenge);
                buffer.putInt(signatureBytes.length);
                buffer.put(signatureBytes);

                return buffer.array();
            } catch (Exception e) {
                throw new EncryptionException("SSH client key exchange phase 1 failed: " + e.getMessage(), e);
            }
        }

        @Override
        public byte[] processServerResponse(byte[] rawPayload) throws EncryptionException {
            try {
                if (keyExchangePhase == 1) {
                    return handlePhase2Response(rawPayload);
                }

                if (keyExchangePhase == 2) {
                    return handlePhase3Response(rawPayload);
                }

                String decrypted = CryptoUtil.decryptString(sessionId, rawPayload);
                com.google.gson.JsonObject json = com.google.gson.JsonParser.parseString(decrypted)
                        .getAsJsonObject();

                if (json.has("id")) {
                    sessionId = Base64.getDecoder().decode(json.get("id").getAsString());
                }

                if (json.has("data")) {
                    String result = messageProcessor.apply(json.get("data").getAsString());
                    return result != null ? result.getBytes(StandardCharsets.UTF_8) : null;
                }

                return null;
            } catch (EncryptionException e) {
                throw e;
            } catch (Exception e) {
                throw new EncryptionException("SSH client response processing failed: " + e.getMessage(), e);
            }
        }

        private byte[] handlePhase2Response(byte[] rawPayload) throws Exception {
            ByteBuffer buffer = ByteBuffer.wrap(rawPayload);
            int phase = buffer.getInt();

            if (phase != 2) {
                throw new EncryptionException("Expected phase 2 response, got: " + phase);
            }

            int serverPubKeyLen = buffer.getInt();
            byte[] serverPubKeyBytes = new byte[serverPubKeyLen];
            buffer.get(serverPubKeyBytes);

            int challengeLen = buffer.getInt();
            byte[] challenge = new byte[challengeLen];
            buffer.get(challenge);

            int signatureLen = buffer.getInt();
            byte[] signatureBytes = new byte[signatureLen];
            buffer.get(signatureBytes);

            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            X509EncodedKeySpec keySpec = new X509EncodedKeySpec(serverPubKeyBytes);
            PublicKey serverPubKey = keyFactory.generatePublic(keySpec);

            byte[] expectedSigData = CryptoUtil.sha256(
                    concatenate(serverPubKeyBytes, challenge));

            Signature sig = Signature.getInstance(SIGNATURE_ALGORITHM);
            sig.initVerify(serverPubKey);
            sig.update(expectedSigData);

            if (!sig.verify(signatureBytes)) {
                throw new EncryptionException("SSH client: server signature verification failed");
            }

            byte[] sharedSecret = CryptoUtil.sha256(concatenate(
                    clientKeyPair.getPublic().getEncoded(),
                    preSharedKey,
                    serverPubKeyBytes
            ));

            sessionId = CryptoUtil.sha256(concatenate(
                    clientKeyPair.getPublic().getEncoded(),
                    serverPubKeyBytes,
                    sharedSecret,
                    preSharedKey
            ));

            keyExchangePhase = 2;

            com.google.gson.JsonObject authData = new com.google.gson.JsonObject();
            authData.addProperty("username", username);

            byte[] encAuth = CryptoUtil.encryptString(sessionId, new com.google.gson.Gson().toJson(authData));

            ByteBuffer response = ByteBuffer.allocate(4 + 4 + encAuth.length);
            response.putInt(2);
            response.putInt(encAuth.length);
            response.put(encAuth);

            return response.array();
        }

        private byte[] handlePhase3Response(byte[] rawPayload) throws Exception {
            String decrypted = CryptoUtil.decryptString(sessionId, rawPayload);
            com.google.gson.JsonObject json = com.google.gson.JsonParser.parseString(decrypted)
                    .getAsJsonObject();

            if ("ok".equals(json.get("status").getAsString()) && json.has("id")) {
                sessionId = Base64.getDecoder().decode(json.get("id").getAsString());
                authenticated = true;
                keyExchangePhase = 0;
                return null;
            }

            throw new EncryptionException("SSH client: login rejected by server");
        }

        @Override
        public boolean isAuthenticated() {
            return authenticated;
        }

        @Override
        public byte[] preparePush(byte[] data) throws EncryptionException {
            if (!authenticated || sessionId == null) {
                return null;
            }

            byte[] newId = CryptoUtil.generateId();

            com.google.gson.JsonObject pushPacket = new com.google.gson.JsonObject();
            pushPacket.addProperty("status", "ok");
            pushPacket.addProperty("id", Base64.getEncoder().encodeToString(newId));
            pushPacket.addProperty("data", new String(data, StandardCharsets.UTF_8));

            byte[] encrypted = CryptoUtil.encryptString(sessionId,
                    new com.google.gson.Gson().toJson(pushPacket));

            sessionId = newId;
            return encrypted;
        }

        @Override
        public byte[] prepareRequest(byte[] data) throws EncryptionException {
            if (!authenticated || sessionId == null) {
                return null;
            }

            byte[] newId = CryptoUtil.generateId();

            com.google.gson.JsonObject requestPacket = new com.google.gson.JsonObject();
            requestPacket.addProperty("id", Base64.getEncoder().encodeToString(newId));
            requestPacket.addProperty("data", new String(data, StandardCharsets.UTF_8));

            byte[] encrypted = CryptoUtil.encryptString(sessionId,
                    new com.google.gson.Gson().toJson(requestPacket));

            sessionId = newId;
            return encrypted;
        }

        @Override
        public byte[] getCurrentKey() {
            return sessionId != null ? sessionId : preSharedKey;
        }
    }

    private static KeyPair generateKeyPair() throws Exception {
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(RSA_KEY_SIZE);
        return keyGen.generateKeyPair();
    }

    private static byte[] concatenate(byte[]... arrays) {
        int totalLen = 0;
        for (byte[] arr : arrays) {
            totalLen += arr.length;
        }
        ByteBuffer buf = ByteBuffer.allocate(totalLen);
        for (byte[] arr : arrays) {
            buf.put(arr);
        }
        return buf.array();
    }
}