package com.newspaper.handler;

public enum OperationType {
    CONSOLE_MESSAGE("console_message"),
    CHAT_MESSAGE("chat_message"),
    COMMAND("command"),
    COMMAND2("command2"),
    PLAYER_JOIN("player_join"),
    PLAYER_QUIT("player_quit"),
    ONLINE_PLAYERS("online_players"),
    SERVER_INFO("server_info"),
    CONFIG_MODIFY("config_modify"),
    CONFIG_RELOAD("config_reload"),
    SHUTDOWN("shutdown"),
    CONSOLE_BROADCAST("console_broadcast");

    private final String typeName;

    OperationType(String typeName) {
        this.typeName = typeName;
    }

    public String getTypeName() {
        return typeName;
    }

    public static OperationType fromString(String type) {
        for (OperationType op : values()) {
            if (op.typeName.equalsIgnoreCase(type)) {
                return op;
            }
        }
        return null;
    }
}