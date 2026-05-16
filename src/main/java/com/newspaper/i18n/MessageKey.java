package com.newspaper.i18n;

public enum MessageKey {
    PREFIX("newspaper.prefix"),
    ENABLED("newspaper.enabled"),
    DISABLED("newspaper.disabled"),
    RELOADED("newspaper.reloaded"),
    WS_STARTED("newspaper.ws_started"),
    WS_STOPPED("newspaper.ws_stopped"),
    WS_ERROR("newspaper.ws_error"),
    WS_CLIENT_CONNECTED("newspaper.ws_client_connected"),
    WS_CLIENT_DISCONNECTED("newspaper.ws_client_disconnected"),
    WS_AUTH_SUCCESS("newspaper.ws_auth_success"),
    WS_AUTH_FAILED("newspaper.ws_auth_failed"),
    NO_PERMISSION("newspaper.no_permission"),
    CONFIG_RELOADED("newspaper.config_reloaded"),
    INVALID_ARGS("newspaper.invalid_args"),
    SHUTDOWN_INITIATED("newspaper.shutdown_initiated"),
    BROADCAST_FORMAT("newspaper.broadcast_format"),
    LANG_CREATED("newspaper.lang_created"),
    LANG_UPDATED("newspaper.lang_updated"),
    LANG_LOAD_ERROR("newspaper.lang_load_error");

    private final String path;

    MessageKey(String path) {
        this.path = path;
    }

    public String getPath() {
        return path;
    }
}