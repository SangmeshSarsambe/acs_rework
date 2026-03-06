package com.acs;

/**
 * Implemented by any class that wants to react to clients
 * connecting / disconnecting — currently ServerUI.
 *
 * Adding a new listener consumer later = just implement this interface.
 */
public interface ConnectionListener {
    void onClientConnected(String clientId);
    void onClientDisconnected(String clientId);
}
