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

    /** Called when client metadata (e.g. hostname) is received after initial connect. */
    default void onClientInfoUpdated(String clientId) {}

    /** Called when a client's input lock state changes (ACK:LOCKED, ACK:UNLOCKED, BACKDOOR_UNLOCK). */
    default void onClientLockStateChanged(String clientId, boolean locked) {}
}