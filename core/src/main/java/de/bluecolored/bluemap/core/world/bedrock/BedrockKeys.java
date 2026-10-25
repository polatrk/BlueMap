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

import org.jetbrains.annotations.Nullable;

/**
 * Chunk keys of the Bedrock LevelDB format:
 * {@code x:int32LE z:int32LE [dimension:int32LE, absent for the Overworld] tag:u8 [subchunk y:int8]}.
 * See https://minecraft.wiki/w/Bedrock_Edition_level_format
 */
public final class BedrockKeys {

    public static final int TAG_DATA_3D = 0x2B;
    public static final int TAG_VERSION = 0x2C;
    public static final int TAG_DATA_2D = 0x2D;
    public static final int TAG_SUBCHUNK = 0x2F;
    public static final int TAG_BLOCK_ENTITY = 0x31;
    public static final int TAG_FINALIZED_STATE = 0x36;
    public static final int TAG_LEGACY_VERSION = 0x76;

    private BedrockKeys() {}

    /** Parsed chunk key. */
    public record ChunkKey(int dimension, int x, int z, int tag, int y) {}

    /** Parses a chunk key; null for every other kind of key (players, villages, scoreboard, ...). */
    public static @Nullable ChunkKey parse(byte[] k) {
        int len = k.length;
        if (len != 9 && len != 10 && len != 13 && len != 14) return null;
        boolean hasDim = len >= 13;
        int tag = k[hasDim ? 12 : 8] & 0xFF;
        boolean hasY = len == 10 || len == 14;
        if (hasY != (tag == TAG_SUBCHUNK)) return null;
        if (!(tag >= 0x2B && tag <= 0x41) && tag != TAG_LEGACY_VERSION && tag != 0x77) return null;
        // "digp" keys (actor digests) may have chunk-like lengths.
        if (k[0] == 'd' && k[1] == 'i' && k[2] == 'g' && k[3] == 'p') return null;
        int dim = 0;
        if (hasDim) {
            dim = int32(k, 8);
            if (dim != 1 && dim != 2) return null; // the Overworld never stores its id
        }
        return new ChunkKey(dim, int32(k, 0), int32(k, 4), tag, hasY ? k[len - 1] : 0);
    }

    public static byte[] key(int dimension, int x, int z, int tag) {
        byte[] k = new byte[dimension == 0 ? 9 : 13];
        write(k, x, z, dimension);
        k[k.length - 1] = (byte) tag;
        return k;
    }

    public static byte[] subChunkKey(int dimension, int x, int z, int y) {
        byte[] k = new byte[dimension == 0 ? 10 : 14];
        write(k, x, z, dimension);
        k[k.length - 2] = (byte) TAG_SUBCHUNK;
        k[k.length - 1] = (byte) y;
        return k;
    }

    private static void write(byte[] k, int x, int z, int dimension) {
        putInt(k, 0, x);
        putInt(k, 4, z);
        if (dimension != 0) putInt(k, 8, dimension);
    }

    /** Packs (dimension, chunk x, chunk z) into one long, used as map key. */
    public static long pack(int dimension, int x, int z) {
        return (long) dimension << 60 | ((long) x + (1L << 29) & 0x3FFFFFFFL) << 30 | ((long) z + (1L << 29) & 0x3FFFFFFFL);
    }

    public static int packedDimension(long p) { return (int) (p >>> 60); }
    public static int packedX(long p) { return (int) ((p >>> 30 & 0x3FFFFFFFL) - (1L << 29)); }
    public static int packedZ(long p) { return (int) ((p & 0x3FFFFFFFL) - (1L << 29)); }

    static int int32(byte[] b, int o) {
        return (b[o] & 0xFF) | (b[o + 1] & 0xFF) << 8 | (b[o + 2] & 0xFF) << 16 | (b[o + 3] & 0xFF) << 24;
    }

    private static void putInt(byte[] b, int o, int v) {
        b[o] = (byte) v; b[o + 1] = (byte) (v >>> 8); b[o + 2] = (byte) (v >>> 16); b[o + 3] = (byte) (v >>> 24);
    }
}
