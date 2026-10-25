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

import de.bluecolored.bluemap.core.logger.Logger;
import de.bluecolored.bluemap.core.resources.pack.datapack.DataPack;
import de.bluecolored.bluemap.core.util.Key;
import de.bluecolored.bluemap.core.world.BlockState;
import de.bluecolored.bluemap.core.world.Chunk;
import de.bluecolored.bluemap.core.world.biome.Biome;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads one chunk column from a Bedrock world and converts it to Java block states and biomes.
 *
 * <p>SubChunkPrefix (0x2F) values, versions 8 and 9 (9 adds a signed Y byte) and 1:
 * version, layer count, [y], then per layer: (bitsPerBlock &lt;&lt; 1 | flag), packed uint32 words
 * (indices never straddle two words, x/z/y order, index = x &lt;&lt; 8 | z &lt;&lt; 4 | y), palette size
 * and little-endian NBT palette entries {name, states, version}. bitsPerBlock = 0: a single block.
 * Layer 0 holds blocks, layer 1 the water of waterlogged blocks (and snow).</p>
 *
 * <p>Data3D (0x2B): 512 bytes of heightmap, then one 3D biome storage per subchunk from the
 * bottom (header 0xFF = same as below; bits 0 = one int32 id; else words, int32 count, int32 ids).</p>
 */
final class BedrockChunkLoader {

    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

    private BedrockChunkLoader() {}

    static Chunk load(BedrockLevel level, int dimension, int x, int z, BedrockDimension dim, DataPack dataPack) throws IOException {
        if (level.chunkSequence(dimension, x, z) == null) return Chunk.EMPTY_CHUNK;
        BedrockBlockMapper mapper = BedrockBlockMapper.get();
        int sectionMin = dim.minSection();
        int count = dim.maxSection() - sectionMin + 1;
        BlockState[][] sections = new BlockState[count][];
        int lowest = Integer.MAX_VALUE, highest = Integer.MIN_VALUE;
        for (int s = 0; s < count; s++) {
            byte[] data = level.get(BedrockKeys.subChunkKey(dimension, x, z, sectionMin + s));
            if (data == null) continue;
            try {
                sections[s] = decodeSubChunk(data, mapper);
                lowest = Math.min(lowest, s);
                highest = Math.max(highest, s);
            } catch (IOException | RuntimeException ex) {
                if (REPORTED.add(ex.getMessage() == null ? ex.toString() : ex.getMessage())) {
                    Logger.global.logWarning("Skipping unreadable Bedrock subchunk (" + x + ", " + (sectionMin + s) + ", " + z + "): " + ex);
                }
            }
        }

        Biome[][] biomes = new Biome[count][];
        byte[] data3d = level.get(BedrockKeys.key(dimension, x, z, BedrockKeys.TAG_DATA_3D));
        if (data3d != null) {
            try {
                decodeBiomes(data3d, biomes, dataPack, dim);
            } catch (RuntimeException ex) {
                Logger.global.logDebug("Unreadable Data3D in chunk " + x + "," + z + ": " + ex);
            }
        }

        if (lowest == Integer.MAX_VALUE) return new BedrockChunk(sectionMin, new BlockState[0][], new Biome[0][], dim.hasSkylight());
        // Trim empty sections above and below.
        BlockState[][] trimmed = new BlockState[highest - lowest + 1][];
        Biome[][] trimmedBiomes = new Biome[highest - lowest + 1][];
        System.arraycopy(sections, lowest, trimmed, 0, trimmed.length);
        System.arraycopy(biomes, lowest, trimmedBiomes, 0, trimmedBiomes.length);
        return new BedrockChunk(sectionMin + lowest, trimmed, trimmedBiomes, dim.hasSkylight());
    }

    static BlockState[] decodeSubChunk(byte[] b, BedrockBlockMapper mapper) throws IOException {
        int version = b[0] & 0xFF;
        int pos = 1;
        int layers = 1;
        if (version == 8 || version == 9) {
            layers = b[pos++] & 0xFF;
            if (version == 9) pos++; // signed y index, the key already tells it
            if (layers > 4) throw new IOException("suspicious layer count " + layers);
        } else if (version != 1) {
            throw new IOException("unsupported subchunk version " + version + " (pre-1.2.13 numeric ids)");
        }
        BlockState[] out = null;
        int[] cursor = {pos};
        for (int l = 0; l < layers; l++) {
            int[] indices = new int[4096];
            BlockState[] palette = readStorage(b, cursor, indices, mapper);
            if (l == 0) {
                out = new BlockState[4096];
                for (int i = 0; i < 4096; i++) out[i] = palette[indices[i]];
            } else if (l == 1 && out != null) {
                for (int i = 0; i < 4096; i++) {
                    BlockState extra = palette[indices[i]];
                    if (extra.isAir()) continue;
                    BlockState base = out[i];
                    if (extra.isWater() || extra.getId().getValue().equals("water")) {
                        if (base.isAir()) out[i] = extra;
                        else out[i] = mapper.waterlogged(base);
                    } else if (base.isAir()) {
                        out[i] = extra; // e.g. snow stored in the second layer
                    }
                }
            }
        }
        return out;
    }

    private static BlockState[] readStorage(byte[] b, int[] cursor, int[] indices, BedrockBlockMapper mapper) throws IOException {
        int pos = cursor[0];
        int header = b[pos++] & 0xFF;
        int bits = header >> 1;
        if ((header & 1) == 1) throw new IOException("runtime palette in a saved subchunk");
        int paletteSize;
        if (bits == 0) {
            // Single block: some writers still store a palette size of 1, others start the NBT directly.
            if (b[pos] == 0x0A) paletteSize = 1;
            else { paletteSize = BedrockKeys.int32(b, pos); pos += 4; }
        } else {
            if (bits > 16 || (bits > 6 && bits != 8 && bits != 16)) throw new IOException("invalid bits per block " + bits);
            int perWord = 32 / bits;
            int words = (4096 + perWord - 1) / perWord;
            int mask = (1 << bits) - 1;
            int i = 0;
            for (int w = 0; w < words; w++) {
                int word = BedrockKeys.int32(b, pos + w * 4);
                for (int j = 0; j < perWord && i < 4096; j++) indices[i++] = (word >>> (j * bits)) & mask;
            }
            pos += words * 4;
            paletteSize = BedrockKeys.int32(b, pos);
            pos += 4;
        }
        if (paletteSize < 1 || paletteSize > 4096) throw new IOException("invalid palette size " + paletteSize);
        BlockState[] palette = new BlockState[paletteSize];
        LeNbt nbt = new LeNbt(b, pos);
        for (int p = 0; p < paletteSize; p++) {
            Map<String, Object> entry = nbt.readCompound();
            Object name = entry.get("name");
            Object states = entry.get("states");
            @SuppressWarnings("unchecked")
            Map<String, Object> st = states instanceof Map ? (Map<String, Object>) states : Map.of();
            palette[p] = mapper.map(name instanceof String s ? s : "minecraft:air", st);
        }
        cursor[0] = nbt.position();
        for (int i = 0; i < 4096; i++) if (indices[i] >= paletteSize) throw new IOException("palette index out of range");
        return palette;
    }

    private static void decodeBiomes(byte[] b, Biome[][] out, DataPack dataPack, BedrockDimension dim) {
        Map<Integer, Biome> resolved = new HashMap<>();
        int pos = 512;
        Biome[] prev = null;
        for (int s = 0; pos < b.length && s < out.length; s++) {
            int header = b[pos++] & 0xFF;
            if (header == 0xFF) { out[s] = prev; continue; }
            int bits = header >> 1;
            Biome[] section = new Biome[4096];
            if (bits == 0) {
                Biome biome = biome(BedrockKeys.int32(b, pos), resolved, dataPack, dim);
                pos += 4;
                java.util.Arrays.fill(section, biome);
            } else {
                int perWord = 32 / bits;
                int words = (4096 + perWord - 1) / perWord;
                int mask = (1 << bits) - 1;
                int[] idx = new int[4096];
                int i = 0;
                for (int w = 0; w < words; w++) {
                    int word = BedrockKeys.int32(b, pos + w * 4);
                    for (int j = 0; j < perWord && i < 4096; j++) idx[i++] = (word >>> (j * bits)) & mask;
                }
                pos += words * 4;
                int size = BedrockKeys.int32(b, pos);
                pos += 4;
                Biome[] palette = new Biome[size];
                for (int p = 0; p < size; p++) palette[p] = biome(BedrockKeys.int32(b, pos + p * 4), resolved, dataPack, dim);
                pos += size * 4;
                for (int k = 0; k < 4096; k++) section[k] = idx[k] < size ? palette[idx[k]] : Biome.DEFAULT;
            }
            out[s] = section;
            prev = section;
        }
    }

    private static Biome biome(int id, Map<Integer, Biome> resolved, DataPack dataPack, BedrockDimension dim) {
        return resolved.computeIfAbsent(id, i -> {
            Key key = BedrockBiomes.javaBiome(i, dim.defaultBiome());
            Biome biome = dataPack.getBiome(key);
            return biome != null ? biome : Biome.DEFAULT;
        });
    }
}
