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

import de.bluecolored.bluemap.core.resources.pack.datapack.DataPack;
import de.bluecolored.bluemap.core.util.Key;
import de.bluecolored.bluemap.core.world.DimensionType;

/** Bedrock dimensions: numeric id in chunk keys and vertical range (the Nether is only 128 high). */
public enum BedrockDimension implements DimensionType {

    OVERWORLD(0, -4, 19, true, false, 0f, null, 1.0, Key.minecraft("plains")),
    NETHER(1, 0, 7, false, true, 0.1f, 18000L, 8.0, Key.minecraft("nether_wastes")),
    END(2, 0, 15, false, false, 0f, 6000L, 1.0, Key.minecraft("the_end"));

    private final int id, minSection, maxSection;
    private final boolean skylight, ceiling;
    private final float ambientLight;
    private final Long fixedTime;
    private final double coordinateScale;
    private final Key defaultBiome;

    BedrockDimension(int id, int minSection, int maxSection, boolean skylight, boolean ceiling, float ambientLight,
                     Long fixedTime, double coordinateScale, Key defaultBiome) {
        this.id = id;
        this.minSection = minSection;
        this.maxSection = maxSection;
        this.skylight = skylight;
        this.ceiling = ceiling;
        this.ambientLight = ambientLight;
        this.fixedTime = fixedTime;
        this.coordinateScale = coordinateScale;
        this.defaultBiome = defaultBiome;
    }

    public static BedrockDimension of(Key dimension) {
        if (DataPack.DIMENSION_THE_NETHER.equals(dimension)) return NETHER;
        if (DataPack.DIMENSION_THE_END.equals(dimension)) return END;
        return OVERWORLD;
    }

    public int id() { return id; }
    public int minSection() { return minSection; }
    public int maxSection() { return maxSection; }
    public Key defaultBiome() { return defaultBiome; }

    @Override public boolean hasSkylight() { return skylight; }
    @Override public boolean hasCeiling() { return ceiling; }
    @Override public float getAmbientLight() { return ambientLight; }
    @Override public int getMinY() { return minSection * 16; }
    @Override public int getHeight() { return (maxSection - minSection + 1) * 16; }
    @Override public Long getFixedTime() { return fixedTime; }
    @Override public double getCoordinateScale() { return coordinateScale; }
}
