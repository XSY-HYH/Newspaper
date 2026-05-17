package com.newspaper.encryption;

import com.newspaper.chap.CryptoUtil;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.security.KeyStore;
import java.util.Base64;
import java.util.function.Function;
import java.util.logging.Logger;

public class TlsProvider implements EncryptionProvider {

    private final File keystoreFile;
    private final char[] keystorePassword;
    private final Logger logger;

    public TlsProvider(File pluginDataFolder, Logger logger) {
        this.keystoreFile = new File(pluginDataFolder, "keystore.jks");
        this.keystorePassword = "newspaper-tls".toCharArray();
        this.logger = logger;
    }

    @Override
    public ServerSocket createServerSocket(int port, boolean ipv6) throws Exception {
        ensureKeystore();

        KeyStore ks = KeyStore.getInstance("JKS");
        try (FileInputStream fis = new FileInputStream(keystoreFile)) {
            ks.load(fis, keystorePassword);
        }

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, keystorePassword);

        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);

        SSLServerSocketFactory ssf = sslContext.getServerSocketFactory();

        InetAddress bindAddr = ipv6
                ? InetAddress.getByName("::")
                : InetAddress.getByName("0.0.0.0");

        SSLServerSocket sslServerSocket = (SSLServerSocket) ssf.createServerSocket(port, 50, bindAddr);
        sslServerSocket.setEnabledProtocols(new String[]{"TLSv1.2", "TLSv1.3"});
        sslServerSocket.setNeedClientAuth(false);

        return sslServerSocket;
    }

    @Override
    public SessionHandler createSessionHandler(InputStream in, OutputStream out,
                                                String username, String password,
                                                Function<String, String> messageProcessor) {
        return new TlsSessionHandler(username, password, messageProcessor);
    }

    @Override
    public boolean requiresTlsSocket() {
        return true;
    }

    private void ensureKeystore() throws Exception {
        if (keystoreFile.exists()) {
            return;
        }

        keystoreFile.getParentFile().mkdirs();
        BouncyCastleCertGenerator.generateSelfSignedKeystore(keystoreFile, keystorePassword, logger);
    }

    private static class TlsSessionHandler implements SessionHandler {
        private final String expectedUsername;
        private final byte[] preSharedKey;
        private final Function<String, String> messageProcessor;
        private boolean authenticated = false;
        private byte[] sessionId = null;

        TlsSessionHandler(String username, String password, Function<String, String> messageProcessor) {
            this.expectedUsername = username;
            this.preSharedKey = CryptoUtil.deriveKey(password);
            this.messageProcessor = messageProcessor;
        }

        @Override
        public byte[] processIncoming(byte[] rawPayload) throws EncryptionException {
            try {
                String decrypted = CryptoUtil.decryptString(preSharedKey, rawPayload);
                com.google.gson.JsonObject json = com.google.gson.JsonParser.parseString(decrypted)
                        .getAsJsonObject();

                if (!authenticated) {
                    String username = json.get("username").getAsString();
                    if (!username.equals(expectedUsername)) {
                        throw new EncryptionException("Invalid username");
                    }

                    sessionId = CryptoUtil.generateId();
                    authenticated = true;

                    com.google.gson.JsonObject response = new com.google.gson.JsonObject();
                    response.addProperty("status", "ok");
                    response.addProperty("id", Base64.getEncoder().encodeToString(sessionId));

                    return CryptoUtil.encryptString(preSharedKey, response.toString());
                }

                byte[] currentKey = sessionId;
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
                throw new EncryptionException("TLS session processing failed: " + e.getMessage(), e);
            }
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
            pushPacket.addProperty("data", new String(data, java.nio.charset.StandardCharsets.UTF_8));

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
}