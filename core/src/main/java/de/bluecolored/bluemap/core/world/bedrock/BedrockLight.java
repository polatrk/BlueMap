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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Approximate lighting for Bedrock chunks, which are saved without light data. Opacity and light
 * emission are derived from the Java block id: good enough for map shading and night mode.
 */
final class BedrockLight {

    /** Blocks that let light through (everything else is treated as a full opaque block). */
    private static final Pattern TRANSPARENT = Pattern.compile(
            "air|water|lava|bubble_column|.*glass.*|.*leaves|.*pane|.*fence.*|.*wall|.*slab|.*stairs|.*door|.*trapdoor|.*torch|.*lantern|" +
            ".*sign|.*banner|.*button|lever|.*rail|.*carpet|.*flower.*|.*sapling|.*grass|.*fern|.*bush|.*vines?|kelp.*|.*seagrass|.*coral.*|" +
            ".*mushroom|.*roots|.*sprouts|ladder|.*chain|iron_bars|.*candle.*|.*head|.*skull|.*pot|snow|cobweb|sugar_cane|bamboo.*|wheat|carrots|" +
            "potatoes|beetroots|.*stem|.*dripleaf.*|glow_lichen|scaffolding|.*pressure_plate|tripwire.*|redstone_wire|repeater|comparator|" +
            ".*campfire|.*azalea|spore_blossom|pink_petals|lily_pad|cactus|.*fire|end_rod|.*lightning_rod|.*chest|.*bed|cake|.*anvil|hopper|" +
            ".*cauldron|brewing_stand|enchanting_table|lectern|bell|grindstone|stonecutter|composter|beacon|conduit|ice|slime_block|honey_block|" +
            "frogspawn|.*egg|.*amethyst_bud|amethyst_cluster|pointed_dripstone|leaf_litter|wildflowers|.*dry_grass|.*eyeblossom|pale_hanging_moss|" +
            ".*moss_carpet|mangrove_propagule|pitcher_.*|torchflower.*|structure_void|barrier|light|sweet_berry_bush|nether_wart|cocoa|" +
            "big_dripleaf|small_dripleaf|hanging_roots|sculk_vein|resin_clump|.*_bars|.*_grate|daylight_detector|.*shelf|sulfur_spike"
    );

    private static final Map<String, Integer> EMISSION = Map.ofEntries(
            Map.entry("torch", 14), Map.entry("wall_torch", 14), Map.entry("soul_torch", 10), Map.entry("soul_wall_torch", 10),
            Map.entry("copper_torch", 14), Map.entry("copper_wall_torch", 14), Map.entry("redstone_torch", 7), Map.entry("redstone_wall_torch", 7),
            Map.entry("lantern", 15), Map.entry("soul_lantern", 10), Map.entry("glowstone", 15), Map.entry("sea_lantern", 15),
            Map.entry("lava", 15), Map.entry("fire", 15), Map.entry("soul_fire", 10), Map.entry("campfire", 15), Map.entry("soul_campfire", 10),
            Map.entry("jack_o_lantern", 15), Map.entry("shroomlight", 15), Map.entry("beacon", 15), Map.entry("conduit", 15),
            Map.entry("end_rod", 14), Map.entry("end_gateway", 15), Map.entry("end_portal", 15), Map.entry("magma_block", 3),
            Map.entry("crying_obsidian", 10), Map.entry("respawn_anchor", 15), Map.entry("ochre_froglight", 15),
            Map.entry("verdant_froglight", 15), Map.entry("pearlescent_froglight", 15), Map.entry("glow_lichen", 7),
            Map.entry("nether_portal", 11), Map.entry("enchanting_table", 7), Map.entry("ender_chest", 7), Map.entry("brewing_stand", 1),
            Map.entry("sea_pickle", 6), Map.entry("amethyst_cluster", 5), Map.entry("large_amethyst_bud", 4), Map.entry("cave_vines_plant", 0),
            Map.entry("sculk_catalyst", 6), Map.entry("redstone_lamp", 0), Map.entry("copper_bulb", 0)
    );

    private static final Map<BlockState, Boolean> BLOCKS_LIGHT = new ConcurrentHashMap<>();

    private BedrockLight() {}

    static boolean blocksLight(BlockState s) {
        if (s.isAir()) return false;
        return BLOCKS_LIGHT.computeIfAbsent(s, st -> !TRANSPARENT.matcher(st.getId().getValue()).matches());
    }

    static int emission(BlockState s) {
        String id = s.getId().getValue();
        Integer e = EMISSION.get(id);
        int light = e == null ? 0 : e;
        if ("true".equals(s.getProperties().get("lit"))) {
            // lit redstone lamps, furnaces, candles, copper bulbs...
            light = Math.max(light, id.contains("candle") ? 3 * Integer.parseInt(s.getProperties().getOrDefault("candles", "1")) : 13);
        }
        if (id.equals("cave_vines") || id.equals("cave_vines_plant")) light = "true".equals(s.getProperties().get("berries")) ? 14 : 0;
        if ("false".equals(s.getProperties().get("lit")) && (id.contains("campfire"))) light = 0;
        return light;
    }

    /** Breadth-first block light propagation inside one chunk column. */
    static byte[][] propagate(BlockState[][] sections) {
        int n = sections.length;
        byte[][] light = new byte[n][];
        int height = n * 16;
        int[] queue = new int[4096];
        int head = 0, tail = 0;
        for (int s = 0; s < n; s++) {
            if (sections[s] == null) continue;
            for (int i = 0; i < 4096; i++) {
                BlockState st = sections[s][i];
                if (st == null) continue;
                int e = emission(st);
                if (e <= 0) continue;
                if (light[s] == null) light[s] = new byte[4096];
                light[s][i] = (byte) e;
                if (tail == queue.length) queue = java.util.Arrays.copyOf(queue, queue.length * 2);
                queue[tail++] = (s * 16 + (i & 15)) << 8 | (i >> 8) << 4 | (i >> 4 & 15); // y << 8 | x << 4 | z
            }
        }
        int[][] dirs = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        while (head < tail) {
            int p = queue[head++];
            int y = p >> 8, x = p >> 4 & 15, z = p & 15;
            int level = light[y >> 4][x << 8 | z << 4 | (y & 15)];
            if (level <= 1) continue;
            for (int[] d : dirs) {
                int nx = x + d[0], ny = y + d[1], nz = z + d[2];
                if (nx < 0 || nx > 15 || nz < 0 || nz > 15 || ny < 0 || ny >= height) continue;
                int ns = ny >> 4;
                int ni = nx << 8 | nz << 4 | (ny & 15);
                BlockState nst = sections[ns] == null ? null : sections[ns][ni];
                if (nst != null && blocksLight(nst)) continue;
                if (light[ns] == null) light[ns] = new byte[4096];
                if (light[ns][ni] >= level - 1) continue;
                light[ns][ni] = (byte) (level - 1);
                if (tail == queue.length) queue = java.util.Arrays.copyOf(queue, queue.length * 2);
                queue[tail++] = ny << 8 | nx << 4 | nz;
            }
        }
        return light;
    }
}
