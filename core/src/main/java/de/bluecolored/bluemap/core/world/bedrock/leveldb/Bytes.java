/*
 * This file is part of BlueMap, licensed under the MIT License (MIT).
 *
 * Copyright (c) Blue (Lukas Rieger) <https://bluecolored.de>
 * Copyright (c) contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package de.bluecolored.bluemap.core.world.bedrock.leveldb;

/**
 * Byte helpers for the LevelDB formats: varints, unsigned comparison and internal keys
 * (user key followed by 8 little-endian bytes: sequence number &lt;&lt; 8 | value type).
 */
public final class Bytes {

    public static final int TYPE_DELETION = 0;
    public static final int TYPE_VALUE = 1;
    public static final long MAX_SEQUENCE = (1L << 56) - 1;

    private Bytes() {}

    /** Position cursor used while decoding. */
    public static final class Cursor {
        public int pos;
        public Cursor(int pos) { this.pos = pos; }
    }

    public static long varint(byte[] b, Cursor c) {
        long result = 0;
        for (int shift = 0; shift < 64; shift += 7) {
            if (c.pos >= b.length) throw new IllegalStateException("truncated varint");
            int v = b[c.pos++] & 0xFF;
            result |= (long) (v & 0x7F) << shift;
            if ((v & 0x80) == 0) return result;
        }
        throw new IllegalStateException("varint too long");
    }

    public static int varint32(byte[] b, Cursor c) {
        return (int) varint(b, c);
    }

    public static int u32(byte[] b, int pos) {
        return (b[pos] & 0xFF) | (b[pos + 1] & 0xFF) << 8 | (b[pos + 2] & 0xFF) << 16 | (b[pos + 3] & 0xFF) << 24;
    }

    public static long u64(byte[] b, int pos) {
        return (u32(b, pos) & 0xFFFFFFFFL) | (long) u32(b, pos + 4) << 32;
    }

    /** Unsigned lexicographic comparison of a[aOff, aOff+aLen) and b[bOff, bOff+bLen). */
    public static int compare(byte[] a, int aOff, int aLen, byte[] b, int bOff, int bLen) {
        return java.util.Arrays.compareUnsigned(a, aOff, aOff + aLen, b, bOff, bOff + bLen);
    }

    public static int compare(byte[] a, byte[] b) {
        return java.util.Arrays.compareUnsigned(a, b);
    }

    /**
     * Internal key order: user keys ascending, then sequence numbers descending (newest first).
     * Both arguments are internal keys (at least 8 bytes).
     */
    public static int compareInternal(byte[] a, byte[] b) {
        int c = compare(a, 0, a.length - 8, b, 0, b.length - 8);
        if (c != 0) return c;
        return Long.compareUnsigned(u64(b, b.length - 8), u64(a, a.length - 8));
    }

    /** The internal key that sorts before every stored version of {@code userKey}. */
    public static byte[] seekKey(byte[] userKey) {
        byte[] k = java.util.Arrays.copyOf(userKey, userKey.length + 8);
        long tag = MAX_SEQUENCE << 8 | TYPE_VALUE;
        for (int i = 0; i < 8; i++) k[userKey.length + i] = (byte) (tag >>> (8 * i));
        return k;
    }

    public static long sequence(byte[] internalKey) {
        return u64(internalKey, internalKey.length - 8) >>> 8;
    }

    public static int type(byte[] internalKey) {
        return internalKey[internalKey.length - 8] & 0xFF;
    }

    public static boolean userKeyEquals(byte[] internalKey, byte[] userKey) {
        return internalKey.length - 8 == userKey.length &&
                java.util.Arrays.equals(internalKey, 0, userKey.length, userKey, 0, userKey.length);
    }
}
