package com.delveronline.net;

import java.nio.charset.StandardCharsets;

/** Binary reader matching {@link ByteWriter}. Throws {@link ProtocolException} on malformed input. */
public final class ByteReader {
    private final byte[] buf;
    private int pos;
    private final int end;

    public ByteReader(byte[] buf) {
        this(buf, 0, buf.length);
    }

    public ByteReader(byte[] buf, int off, int len) {
        this.buf = buf;
        this.pos = off;
        this.end = off + len;
    }

    public int remaining() {
        return end - pos;
    }

    public int position() {
        return pos;
    }

    private void need(int n) {
        if (pos + n > end) throw new ProtocolException("buffer underflow");
    }

    public int readByte() {
        need(1);
        return buf[pos++];
    }

    public int readUByte() {
        return readByte() & 0xFF;
    }

    public boolean readBool() {
        return readByte() != 0;
    }

    public int readUShort() {
        need(2);
        int v = ((buf[pos] & 0xFF) << 8) | (buf[pos + 1] & 0xFF);
        pos += 2;
        return v;
    }

    public int readInt() {
        need(4);
        int v = ((buf[pos] & 0xFF) << 24) | ((buf[pos + 1] & 0xFF) << 16) | ((buf[pos + 2] & 0xFF) << 8) | (buf[pos + 3] & 0xFF);
        pos += 4;
        return v;
    }

    public long readLong() {
        long hi = readInt() & 0xFFFFFFFFL;
        long lo = readInt() & 0xFFFFFFFFL;
        return (hi << 32) | lo;
    }

    public float readFloat() {
        return Float.intBitsToFloat(readInt());
    }

    public double readDouble() {
        return Double.longBitsToDouble(readLong());
    }

    public int readVarInt() {
        int result = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            int b = readByte();
            result |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return result;
        }
        throw new ProtocolException("varint too long");
    }

    public int readSVarInt() {
        int v = readVarInt();
        return (v >>> 1) ^ -(v & 1);
    }

    public long readVarLong() {
        long result = 0;
        for (int shift = 0; shift < 70; shift += 7) {
            int b = readByte();
            result |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return result;
        }
        throw new ProtocolException("varlong too long");
    }

    public String readString() {
        int n = readVarInt();
        if (n == 0) return null;
        n -= 1;
        need(n);
        String s = new String(buf, pos, n, StandardCharsets.UTF_8);
        pos += n;
        return s;
    }

    public byte[] readBytes() {
        int n = readVarInt();
        if (n == 0) return null;
        n -= 1;
        need(n);
        byte[] b = new byte[n];
        System.arraycopy(buf, pos, b, 0, n);
        pos += n;
        return b;
    }

    public void readRaw(byte[] dst, int off, int len) {
        need(len);
        System.arraycopy(buf, pos, dst, off, len);
        pos += len;
    }

    public void skip(int n) {
        need(n);
        pos += n;
    }
}
