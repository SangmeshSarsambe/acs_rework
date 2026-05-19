package com.acs;

public class Constants {
    // Service Configuration
    public static final String SERVICE_TYPE = "_acs._tcp.local.";
    public static final String SERVICE_NAME = "SimpleServer";
    public static final int SERVER_PORT = 6000;
    
    // Heartbeat Configuration
    public static final long HEARTBEAT_INTERVAL = 10000;      // 10 seconds
    public static final long HEARTBEAT_TIMEOUT = 30000;       // 30 seconds
    public static final long HEARTBEAT_CHECK = 5000;          // Check every 5 seconds
    
    // Message Protocol
    public static final String MSG_HEARTBEAT = "HEARTBEAT";
    public static final String MSG_HEARTBEAT_ACK = "HEARTBEAT_ACK";
    public static final String MSG_PREFIX = "MSG:";
    public static final String MSG_ACK = "ACK:";
    public static final String MSG_PING = "PING";
    public static final String MSG_PONG = "PONG";
}