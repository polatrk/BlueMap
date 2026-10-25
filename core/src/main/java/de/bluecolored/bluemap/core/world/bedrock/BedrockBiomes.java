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

import de.bluecolored.bluemap.core.util.Key;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/** Bedrock numeric biome ids to Java biome keys (generated from GeyserMC's biomes.json). */
public final class BedrockBiomes {

    private static final Map<Integer, Key> BIOMES = new HashMap<>();

    static {
        try (InputStream in = BedrockBiomes.class.getResourceAsStream("/de/bluecolored/bluemap/bedrock/bedrock-biomes.txt.gz")) {
            if (in == null) throw new IOException("missing bedrock-biomes.txt.gz");
            BufferedReader reader = new BufferedReader(new InputStreamReader(new GZIPInputStream(in), StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                int t = line.indexOf('\t');
                if (t < 0) continue;
                BIOMES.put(Integer.parseInt(line.substring(0, t)), Key.parse(line.substring(t + 1), Key.MINECRAFT_NAMESPACE));
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to load the Bedrock biome mappings", ex);
        }
    }

    private BedrockBiomes() {}

    public static Key javaBiome(int bedrockId, Key fallback) {
        return BIOMES.getOrDefault(bedrockId, fallback);
    }
}
