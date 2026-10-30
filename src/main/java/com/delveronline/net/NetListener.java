package com.delveronline.net;

/** Callbacks from {@link NetEndpoint#update()}; always invoked on the thread that calls update(). */
public interface NetListener {
    /** A connection finished its handshake (server: client accepted; client: server accepted us). */
    void onConnected(Connection connection);

    /** A connection closed (timeout, remote close, local close, or handshake failure). */
    void onDisconnected(Connection connection, String reason);

    /**
     * An application message arrived.
     *
     * @param reliable   whether it came over the reliable ordered channel
     * @param packetSeq  sender packet sequence (monotonic per connection), useful for discarding stale unreliable data
     */
    void onMessage(Connection connection, byte[] data, boolean reliable, int packetSeq);
}
