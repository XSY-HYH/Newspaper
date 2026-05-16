package com.newspaper.chap;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.Base64;
import java.util.function.Function;

public final class Chapiem {

    private static final Gson GSON = new Gson();
    private static final String LOGIN_OK_PREFIX = "OK:";
    private static final String RECOVERY_PREFIX = "RESYNC:";
    private static final String RECOVERY_ACK = "RESYNC_ACK";

    private Chapiem() {
    }

    public static LoginResult processLogin(ChapiemSession session, byte[] encryptedPayload) {
        try {
            String decrypted = CryptoUtil.decryptString(session.getPreSharedKey(), encryptedPayload);
            JsonObject loginRequest = GSON.fromJson(decrypted, JsonObject.class);

            String username = loginRequest.get("username").getAsString();
            if (!username.equals(session.getExpectedUsername())) {
                return LoginResult.failure("Invalid username");
            }

            byte[] newId = CryptoUtil.generateId();
            session.completeLogin(newId);

            JsonObject response = new JsonObject();
            response.addProperty("status", "ok");
            response.addProperty("id", Base64.getEncoder().encodeToString(newId));

            String responseStr = LOGIN_OK_PREFIX + GSON.toJson(response);
            byte[] encryptedResponse = CryptoUtil.encryptString(session.getPreSharedKey(), responseStr);

            return LoginResult.success(encryptedResponse);
        } catch (Exception e) {
            return LoginResult.failure("Decryption failed: " + e.getMessage());
        }
    }

    public static OperationResult processOperation(ChapiemSession session, byte[] encryptedPayload,
                                                    Function<String, String> processor) {
        byte[] currentKey = session.getCurrentKey();

        try {
            String decrypted = CryptoUtil.decryptString(currentKey, encryptedPayload);

            String operationResult = processor.apply(decrypted);

            byte[] newId = CryptoUtil.generateId();

            JsonObject response = new JsonObject();
            response.addProperty("status", "ok");
            response.addProperty("id", Base64.getEncoder().encodeToString(newId));
            response.addProperty("data", operationResult);

            String responseStr = GSON.toJson(response);
            byte[] encryptedResponse = CryptoUtil.encryptString(currentKey, responseStr);

            session.updateId(newId);

            return OperationResult.success(encryptedResponse);
        } catch (Exception e) {
            return tryRecovery(session, encryptedPayload, processor);
        }
    }

    private static OperationResult tryRecovery(ChapiemSession session, byte[] encryptedPayload,
                                                Function<String, String> processor) {
        try {
            String decrypted = CryptoUtil.decryptString(session.getCurrentId(), encryptedPayload);

            session.enterRecovery();

            byte[] newId = CryptoUtil.generateId();
            String recoveryData = RECOVERY_PREFIX + Base64.getEncoder().encodeToString(newId);
            byte[] recoveryPacket = CryptoUtil.encryptString(session.getPreSharedKey(), recoveryData);

            return OperationResult.recovery(recoveryPacket, newId);
        } catch (Exception e2) {
            return OperationResult.failure("Operation failed and recovery not possible");
        }
    }

    public static OperationResult processRecoveryAck(ChapiemSession session, byte[] encryptedPayload) {
        try {
            String decrypted = CryptoUtil.decryptString(session.getCurrentId(), encryptedPayload);
            if (RECOVERY_ACK.equals(decrypted)) {
                session.exitRecovery();
                return OperationResult.success(null);
            }
            return OperationResult.failure("Invalid recovery ack");
        } catch (Exception e) {
            return OperationResult.failure("Recovery ack decryption failed");
        }
    }

    public static byte[] encryptResponse(byte[] key, String data) {
        return CryptoUtil.encryptString(key, data);
    }

    public static String decryptRequest(byte[] key, byte[] encryptedData) {
        return CryptoUtil.decryptString(key, encryptedData);
    }

    public record LoginResult(boolean success, byte[] responseData, String errorMessage) {
        public static LoginResult success(byte[] responseData) {
            return new LoginResult(true, responseData, null);
        }

        public static LoginResult failure(String errorMessage) {
            return new LoginResult(false, null, errorMessage);
        }
    }

    public record OperationResult(boolean success, byte[] responseData,
                                  boolean needsRecovery, byte[] recoveryData, byte[] newId) {
        public static OperationResult success(byte[] responseData) {
            return new OperationResult(true, responseData, false, null, null);
        }

        public static OperationResult failure(String errorMessage) {
            return new OperationResult(false, null, false, null, null);
        }

        public static OperationResult recovery(byte[] recoveryData, byte[] newId) {
            return new OperationResult(false, null, true, recoveryData, newId);
        }
    }
}