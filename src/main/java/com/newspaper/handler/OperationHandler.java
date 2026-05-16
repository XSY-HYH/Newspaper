package com.newspaper.handler;

import com.google.gson.JsonObject;

public interface OperationHandler {

    String handle(JsonObject data);

    default void shutdown() {
    }
}