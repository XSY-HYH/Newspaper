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