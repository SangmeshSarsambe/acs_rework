package com.acs;

/**
 * Listener interface for connection events.
 * ServerUI implements this to keep the card grid in sync.
 */
public interface ConnectionListener {

    /** Called when a new client connects. */
    void onClientConnected(String clientId);

    /** Called when a client disconnects — card stays, shows DISCONNECTED state. */
    void onClientDisconnected(String clientId);

    /** Called when operator clicks Remove on a disconnected client — card removed. */
    void onDisconnectedClientRemoved(String clientIp);
}