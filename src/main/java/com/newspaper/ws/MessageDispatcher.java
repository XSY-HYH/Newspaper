package com.newspaper.ws;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.newspaper.handler.OperationHandler;
import com.newspaper.handler.OperationType;

import java.util.EnumMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

public class MessageDispatcher {

    private static final Gson GSON = new Gson();
    private final Map<OperationType, OperationHandler> handlers = new EnumMap<>(OperationType.class);
    private final Logger logger;

    public MessageDispatcher(Logger logger) {
        this.logger = logger;
    }

    public void registerHandler(OperationType type, OperationHandler handler) {
        handlers.put(type, handler);
    }

    public String dispatch(String rawMessage) {
        try {
            JsonObject request = GSON.fromJson(rawMessage, JsonObject.class);
            String typeStr = request.get("type").getAsString();
            OperationType type = OperationType.fromString(typeStr);

            if (type == null) {
                return buildErrorResponse("Unknown operation type: " + typeStr);
            }

            OperationHandler handler = handlers.get(type);
            if (handler == null) {
                return buildErrorResponse("No handler registered for: " + typeStr);
            }

            JsonObject data = request.has("data") ? request.getAsJsonObject("data") : new JsonObject();
            return handler.handle(data);
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to dispatch message: " + e.getMessage(), e);
            return buildErrorResponse("Internal error: " + e.getMessage());
        }
    }

    public void shutdown() {
        for (OperationHandler handler : handlers.values()) {
            try {
                handler.shutdown();
            } catch (Exception e) {
                logger.log(Level.WARNING, "Failed to shutdown handler: " + e.getMessage());
            }
        }
        handlers.clear();
    }

    private String buildErrorResponse(String message) {
        JsonObject response = new JsonObject();
        response.addProperty("status", "error");
        response.addProperty("message", message);
        return GSON.toJson(response);
    }
}