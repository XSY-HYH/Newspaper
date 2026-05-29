package com.newspaper.config;

public class NewspaperConfig {

    private int port = 8080;
    private String username = "admin";
    private String password = "newspaper";
    private boolean ipv6 = false;
    private String language = "en";
    private String encryption = "chap-iem";
    private String connectionMode = "direct";
    private String reverseProxyHost = "";
    private int reverseProxyPort = 8080;
    private String reverseProxyProtocol = "ws";

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public boolean isIpv6() {
        return ipv6;
    }

    public void setIpv6(boolean ipv6) {
        this.ipv6 = ipv6;
    }

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public String getEncryption() {
        return encryption;
    }

    public void setEncryption(String encryption) {
        this.encryption = encryption;
    }

    public String getConnectionMode() {
        return connectionMode;
    }

    public void setConnectionMode(String connectionMode) {
        this.connectionMode = connectionMode;
    }

    public boolean isReverseProxy() {
        return "reverse".equalsIgnoreCase(connectionMode);
    }

    public String getReverseProxyHost() {
        return reverseProxyHost;
    }

    public void setReverseProxyHost(String reverseProxyHost) {
        this.reverseProxyHost = reverseProxyHost;
    }

    public int getReverseProxyPort() {
        return reverseProxyPort;
    }

    public void setReverseProxyPort(int reverseProxyPort) {
        this.reverseProxyPort = reverseProxyPort;
    }

    public String getReverseProxyProtocol() {
        return reverseProxyProtocol;
    }

    public void setReverseProxyProtocol(String reverseProxyProtocol) {
        this.reverseProxyProtocol = reverseProxyProtocol;
    }

    public String getReverseProxyUrl() {
        return reverseProxyProtocol + "://" + reverseProxyHost + ":" + reverseProxyPort + "/";
    }
}