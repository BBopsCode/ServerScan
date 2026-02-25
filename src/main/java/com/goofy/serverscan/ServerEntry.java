package com.goofy.serverscan;

public class ServerEntry {
    public String ip;
    public int port;
    public Boolean available;
    public String reason;

    public ServerEntry(String ip, int port, Boolean available) {
        this.ip = ip;
        this.port = port;
        this.available = available;
    }
}
