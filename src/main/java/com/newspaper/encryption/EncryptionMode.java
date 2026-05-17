package com.newspaper.encryption;

public enum EncryptionMode {
    CHAP_IEM("chap-iem"),
    TLS("tls"),
    SSH("ssh");

    private final String configValue;

    EncryptionMode(String configValue) {
        this.configValue = configValue;
    }

    public String getConfigValue() {
        return configValue;
    }

    public static EncryptionMode fromConfig(String value) {
        for (EncryptionMode mode : values()) {
            if (mode.configValue.equalsIgnoreCase(value)) {
                return mode;
            }
        }
        return CHAP_IEM;
    }
}