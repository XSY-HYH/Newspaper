package com.newspaper.handler;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.newspaper.abstraction.PlatformAbstraction;
import org.bukkit.Bukkit;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

public class ConsoleMessageHandler implements OperationHandler {

    private static final Gson GSON = new Gson();
    private final Queue<String> messageQueue = new ConcurrentLinkedQueue<>();
    private final PlatformAbstraction platform;
    private ConsoleLogHandler logHandler;
    private boolean subscribed = false;

    public ConsoleMessageHandler(PlatformAbstraction platform) {
        this.platform = platform;
    }

    @Override
    public String handle(JsonObject data) {
        String action = data.has("action") ? data.get("action").getAsString() : "subscribe";

        if ("subscribe".equals(action)) {
            return subscribe();
        } else if ("unsubscribe".equals(action)) {
            return unsubscribe();
        } else if ("poll".equals(action)) {
            return poll();
        }

        return buildError("Unknown action: " + action);
    }

    private String subscribe() {
        if (subscribed) {
            return buildSuccess("Already subscribed");
        }

        logHandler = new ConsoleLogHandler();
        Logger rootLogger = Logger.getLogger("");
        rootLogger.addHandler(logHandler);

        subscribed = true;
        return buildSuccess("Subscribed to console messages");
    }

    private String unsubscribe() {
        if (!subscribed) {
            return buildSuccess("Not subscribed");
        }

        if (logHandler != null) {
            Logger rootLogger = Logger.getLogger("");
            rootLogger.removeHandler(logHandler);
            logHandler = null;
        }

        subscribed = false;
        return buildSuccess("Unsubscribed from console messages");
    }

    private String poll() {
        JsonObject response = new JsonObject();
        response.addProperty("status", "ok");

        StringBuilder messages = new StringBuilder();
        String msg;
        int count = 0;
        while ((msg = messageQueue.poll()) != null && count < 100) {
            if (count > 0) {
                messages.append("\n");
            }
            messages.append(msg);
            count++;
        }

        response.addProperty("messages", messages.toString());
        response.addProperty("count", count);
        return GSON.toJson(response);
    }

    @Override
    public void shutdown() {
        unsubscribe();
        messageQueue.clear();
    }

    private class ConsoleLogHandler extends Handler {
        @Override
        public void publish(LogRecord record) {
            String message = record.getMessage();
            if (message != null) {
                messageQueue.offer(message);
                while (messageQueue.size() > 1000) {
                    messageQueue.poll();
                }
            }
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }

    private String buildSuccess(String message) {
        JsonObject response = new JsonObject();
        response.addProperty("status", "ok");
        response.addProperty("message", message);
        return GSON.toJson(response);
    }

    private String buildError(String message) {
        JsonObject response = new JsonObject();
        response.addProperty("status", "error");
        response.addProperty("message", message);
        return GSON.toJson(response);
    }
}