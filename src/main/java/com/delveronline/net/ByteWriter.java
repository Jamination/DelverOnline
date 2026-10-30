package com.delveronline.net;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Growable little-endian-free (big-endian) binary writer with varint support. Not thread-safe. */
public final class ByteWriter {
    private byte[] buf;
    private int pos;

    public ByteWriter() {
        this(256);
    }

    public ByteWriter(int capacity) {
        buf = new byte[Math.max(16, capacity)];
    }

    private void ensure(int extra) {
        int need = pos + extra;
        if (need > buf.length) buf = Arrays.copyOf(buf, Math.max(need, buf.length * 2));
    }

    public ByteWriter reset() {
        pos = 0;
        return this;
    }

    public int size() {
        return pos;
    }

    public byte[] array() {
        return buf;
    }

    public byte[] toByteArray() {
        return Arrays.copyOf(buf, pos);
    }

    public ByteWriter writeByte(int v) {
        ensure(1);
        buf[pos++] = (byte) v;
        return this;
    }

    public ByteWriter writeBool(boolean v) {
        return writeByte(v ? 1 : 0);
    }

    public ByteWriter writeShort(int v) {
        ensure(2);
        buf[pos++] = (byte) (v >>> 8);
        buf[pos++] = (byte) v;
        return this;
    }

    public ByteWriter writeInt(int v) {
        ensure(4);
        buf[pos++] = (byte) (v >>> 24);
        buf[pos++] = (byte) (v >>> 16);
        buf[pos++] = (byte) (v >>> 8);
        buf[pos++] = (byte) v;
        return this;
    }

    public ByteWriter writeLong(long v) {
        writeInt((int) (v >>> 32));
        return writeInt((int) v);
    }

    public ByteWriter writeFloat(float v) {
        return writeInt(Float.floatToRawIntBits(v));
    }

    public ByteWriter writeDouble(double v) {
        return writeLong(Double.doubleToRawLongBits(v));
    }

    /** Unsigned LEB128 varint. */
    public ByteWriter writeVarInt(int v) {
        ensure(5);
        while ((v & ~0x7F) != 0) {
            buf[pos++] = (byte) ((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        buf[pos++] = (byte) v;
        return this;
    }

    /** ZigZag-encoded signed varint. */
    public ByteWriter writeSVarInt(int v) {
        return writeVarInt((v << 1) ^ (v >> 31));
    }

    public ByteWriter writeVarLong(long v) {
        ensure(10);
        while ((v & ~0x7FL) != 0) {
            buf[pos++] = (byte) ((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        buf[pos++] = (byte) v;
        return this;
    }

    public ByteWriter writeString(String s) {
        if (s == null) {
            writeVarInt(0);
            return this;
        }
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        writeVarInt(b.length + 1);
        return writeRaw(b, 0, b.length);
    }

    public ByteWriter writeBytes(byte[] b) {
        if (b == null) {
            writeVarInt(0);
            return this;
        }
        writeVarInt(b.length + 1);
        return writeRaw(b, 0, b.length);
    }

    public ByteWriter writeRaw(byte[] b, int off, int len) {
        ensure(len);
        System.arraycopy(b, off, buf, pos, len);
        pos += len;
        return this;
    }

    /** Overwrites a previously written big-endian short at {@code at}. */
    public void patchShort(int at, int v) {
        buf[at] = (byte) (v >>> 8);
        buf[at + 1] = (byte) v;
    }
}
