package com.newspaper.encryption;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.util.function.Function;

public interface EncryptionProvider {

    ServerSocket createServerSocket(int port, boolean ipv6) throws Exception;

    SessionHandler createSessionHandler(InputStream in, OutputStream out,
                                         String username, String password,
                                         Function<String, String> messageProcessor);

    ClientSessionHandler createClientSessionHandler(InputStream in, OutputStream out,
                                                     String username, String password,
                                                     Function<String, String> messageProcessor);

    boolean requiresTlsSocket();

    interface SessionHandler {

        byte[] processIncoming(byte[] rawPayload) throws EncryptionException;

        byte[] prepareOutgoing(byte[] data) throws EncryptionException;

        boolean isAuthenticated();

        byte[] preparePush(byte[] data) throws EncryptionException;

        byte[] getCurrentKey();
    }

    interface ClientSessionHandler {

        byte[] buildAuthRequest() throws EncryptionException;

        byte[] processServerResponse(byte[] rawPayload) throws EncryptionException;

        boolean isAuthenticated();

        byte[] preparePush(byte[] data) throws EncryptionException;

        byte[] prepareRequest(byte[] data) throws EncryptionException;

        byte[] getCurrentKey();
    }

    class EncryptionException extends Exception {
        public EncryptionException(String message) {
            super(message);
        }

        public EncryptionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}