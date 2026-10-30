package com.delveronline.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class ClientSettingsTest {
    @Test
    void addressesDefaultToTheStandardPort() {
        assertArrayEquals(new String[]{"example.org", "27960"}, ClientSettings.splitAddress(" example.org "));
        assertArrayEquals(new String[]{"10.0.0.2", "4000"}, ClientSettings.splitAddress("10.0.0.2:4000"));
        assertArrayEquals(new String[]{"::1", "5000"}, ClientSettings.splitAddress("[::1]:5000"));
        assertArrayEquals(new String[]{"fe80::1", "27960"}, ClientSettings.splitAddress("fe80::1"));
    }
}
