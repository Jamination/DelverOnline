package com.delveronline.net;

/** Notified once per unreliable message whether the packet carrying it was acknowledged or deemed lost. */
public interface DeliveryCallback {
    void delivered();

    void lost();
}
