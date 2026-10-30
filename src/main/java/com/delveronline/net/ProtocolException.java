package com.delveronline.net;

/** Malformed or unexpected network data. */
public class ProtocolException extends RuntimeException {
    public ProtocolException(String message) {
        super(message);
    }
}
