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

import de.bluecolored.bluemap.core.world.BlockState;
import de.bluecolored.bluemap.core.world.Chunk;
import de.bluecolored.bluemap.core.world.LightData;
import de.bluecolored.bluemap.core.world.biome.Biome;

/**
 * A Bedrock chunk column translated to Java block states.
 *
 * <p>Bedrock does not save lighting, so light is computed when first needed: sky light from the
 * column heights (blocks under the highest light-blocking block get darker with depth) and block
 * light propagated inside the chunk from light-emitting blocks.</p>
 */
public class BedrockChunk implements Chunk {

    private static final int NO_HEIGHT = Integer.MIN_VALUE;

    private final int sectionMin;
    private final BlockState[][] sections; // [section][index (x << 8 | z << 4 | y)], null = air
    private final Biome[][] biomes;        // [section][index], null = default
    private final boolean hasSkyLight;
    private final int[] surfaceY = new int[256];    // highest non-air block + 1
    private final int[] oceanFloorY = new int[256]; // highest solid, non-liquid block + 1
    private final int[] opaqueY = new int[256];     // highest light-blocking block
    private volatile byte[][] blockLight;

    public BedrockChunk(int sectionMin, BlockState[][] sections, Biome[][] biomes, boolean hasSkyLight) {
        this.sectionMin = sectionMin;
        this.sections = sections;
        this.biomes = biomes;
        this.hasSkyLight = hasSkyLight;
        computeHeights();
    }

    private void computeHeights() {
        int top = sectionMin * 16 + sections.length * 16 - 1;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int col = z << 4 | x;
                surfaceY[col] = NO_HEIGHT;
                oceanFloorY[col] = NO_HEIGHT;
                opaqueY[col] = NO_HEIGHT;
                for (int y = top; y >= sectionMin * 16; y--) {
                    BlockState s = getBlockState(x, y, z);
                    if (s.isAir()) continue;
                    if (surfaceY[col] == NO_HEIGHT) surfaceY[col] = y + 1;
                    boolean liquid = s.isWater() || s.getId().getValue().equals("lava") || s.isWaterlogged();
                    if (opaqueY[col] == NO_HEIGHT && BedrockLight.blocksLight(s)) opaqueY[col] = y;
                    if (oceanFloorY[col] == NO_HEIGHT && !liquid && BedrockLight.blocksLight(s)) oceanFloorY[col] = y + 1;
                    if (oceanFloorY[col] != NO_HEIGHT && opaqueY[col] != NO_HEIGHT) break;
                }
                int min = sectionMin * 16;
                if (surfaceY[col] == NO_HEIGHT) surfaceY[col] = min;
                if (oceanFloorY[col] == NO_HEIGHT) oceanFloorY[col] = min;
                if (opaqueY[col] == NO_HEIGHT) opaqueY[col] = min - 1;
            }
        }
    }

    @Override public boolean isGenerated() { return true; }
    @Override public boolean hasLightData() { return true; }

    @Override
    public BlockState getBlockState(int x, int y, int z) {
        int s = (y >> 4) - sectionMin;
        if (s < 0 || s >= sections.length || sections[s] == null) return BlockState.AIR;
        BlockState state = sections[s][(x & 15) << 8 | (z & 15) << 4 | (y & 15)];
        return state == null ? BlockState.AIR : state;
    }

    @Override
    public Biome getBiome(int x, int y, int z) {
        int s = (y >> 4) - sectionMin;
        if (s < 0) s = 0;
        if (s >= biomes.length) s = biomes.length - 1;
        if (s < 0 || biomes[s] == null) return Biome.DEFAULT;
        Biome b = biomes[s][(x & 15) << 8 | (z & 15) << 4 | (y & 15)];
        return b == null ? Biome.DEFAULT : b;
    }

    @Override
    public LightData getLightData(int x, int y, int z, LightData target) {
        int col = (z & 15) << 4 | (x & 15);
        int sky = 0;
        if (hasSkyLight) {
            int depth = opaqueY[col] - y;
            sky = depth < 0 ? 15 : Math.max(0, 13 - depth * 2);
        }
        int s = (y >> 4) - sectionMin;
        int block = 0;
        if (s >= 0 && s < sections.length) {
            byte[][] light = blockLight();
            if (light[s] != null) block = light[s][(x & 15) << 8 | (z & 15) << 4 | (y & 15)];
        }
        return target.set(sky, block);
    }

    private byte[][] blockLight() {
        byte[][] l = blockLight;
        if (l == null) {
            synchronized (this) {
                if (blockLight == null) blockLight = BedrockLight.propagate(sections);
                l = blockLight;
            }
        }
        return l;
    }

    @Override public int getMinY(int x, int z) { return sectionMin * 16; }
    @Override public int getMaxY(int x, int z) { return (sectionMin + sections.length) * 16 - 1; }
    @Override public boolean hasWorldSurfaceHeights() { return true; }
    @Override public int getWorldSurfaceY(int x, int z) { return surfaceY[(z & 15) << 4 | (x & 15)]; }
    @Override public boolean hasOceanFloorHeights() { return true; }
    @Override public int getOceanFloorY(int x, int z) { return oceanFloorY[(z & 15) << 4 | (x & 15)]; }
}
