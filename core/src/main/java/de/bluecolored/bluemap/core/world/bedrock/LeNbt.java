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
package de.bluecolored.bluemap.core.world.bedrock;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal little-endian NBT reader (Bedrock stores palettes, block entities and level.dat in
 * little-endian NBT). Compounds become {@code Map<String, Object>}, lists {@code List<Object>},
 * numbers their boxed Java type, strings {@code String}, arrays primitive arrays.
 */
public final class LeNbt {

    private final byte[] b;
    private int pos;

    public LeNbt(byte[] data, int offset) {
        this.b = data;
        this.pos = offset;
    }

    public int position() { return pos; }

    /** Reads one named root tag (normally a compound) and returns its value. */
    public Object readRoot() throws IOException {
        try {
            int type = u8();
            if (type == 0) return null;
            string(); // root name
            return payload(type, 0);
        } catch (IndexOutOfBoundsException ex) {
            throw new IOException("truncated NBT", ex);
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> readCompound() throws IOException {
        Object v = readRoot();
        if (!(v instanceof Map)) throw new IOException("NBT root is not a compound");
        return (Map<String, Object>) v;
    }

    private Object payload(int type, int depth) throws IOException {
        if (depth > 64) throw new IOException("NBT nested too deep");
        switch (type) {
            case 1: return (byte) u8();
            case 2: { short v = (short) (u8() | u8() << 8); return v; }
            case 3: return i32();
            case 4: { long lo = i32() & 0xFFFFFFFFL; long hi = i32(); return hi << 32 | lo; }
            case 5: return Float.intBitsToFloat(i32());
            case 6: { long lo = i32() & 0xFFFFFFFFL; long hi = i32(); return Double.longBitsToDouble(hi << 32 | lo); }
            case 7: { int n = len(); byte[] a = new byte[n]; System.arraycopy(b, pos, a, 0, n); pos += n; return a; }
            case 8: return string();
            case 9: {
                int et = u8(); int n = len();
                List<Object> list = new ArrayList<>(n);
                for (int i = 0; i < n; i++) list.add(payload(et, depth + 1));
                return list;
            }
            case 10: {
                Map<String, Object> map = new LinkedHashMap<>();
                for (;;) {
                    int t = u8();
                    if (t == 0) return map;
                    String name = string();
                    map.put(name, payload(t, depth + 1));
                }
            }
            case 11: { int n = len(); int[] a = new int[n]; for (int i = 0; i < n; i++) a[i] = i32(); return a; }
            case 12: { int n = len(); long[] a = new long[n]; for (int i = 0; i < n; i++) { long lo = i32() & 0xFFFFFFFFL; a[i] = (long) i32() << 32 | lo; } return a; }
            default: throw new IOException("unknown NBT tag " + type);
        }
    }

    private int u8() { return b[pos++] & 0xFF; }

    private int i32() { int v = (b[pos] & 0xFF) | (b[pos + 1] & 0xFF) << 8 | (b[pos + 2] & 0xFF) << 16 | (b[pos + 3] & 0xFF) << 24; pos += 4; return v; }

    private int len() throws IOException {
        int n = i32();
        if (n < 0 || n > b.length - pos + 16) throw new IOException("invalid NBT length " + n);
        return n;
    }

    private String string() {
        int n = u8() | u8() << 8;
        String s = new String(b, pos, n, StandardCharsets.UTF_8);
        pos += n;
        return s;
    }
}
