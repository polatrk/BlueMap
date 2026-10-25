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

import com.flowpowered.math.vector.Vector2i;
import com.github.benmanes.caffeine.cache.LoadingCache;
import de.bluecolored.bluemap.core.logger.Logger;
import de.bluecolored.bluemap.core.resources.pack.datapack.DataPack;
import de.bluecolored.bluemap.core.util.Caches;
import de.bluecolored.bluemap.core.util.Grid;
import de.bluecolored.bluemap.core.util.Key;
import de.bluecolored.bluemap.core.util.WatchService;
import de.bluecolored.bluemap.core.world.*;
import lombok.Getter;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * A dimension of a Bedrock Dedicated Server world (LevelDB "db" folder), read in place without
 * ever writing to the world folder. Blocks and biomes are translated to their Java equivalents.
 */
@Getter
public class BedrockWorld implements World {

    private static final Grid CHUNK_GRID = new Grid(16);
    private static final Grid REGION_GRID = new Grid(32).multiply(CHUNK_GRID);

    private final String id;
    private final Path worldFolder;
    private final Key dimension;
    private final BedrockDimension dimensionType;
    private final DataPack dataPack;
    private final BedrockLevel level;

    private final LoadingCache<Vector2i, Chunk> chunkCache = Caches.with()
            .softValues()
            .maximumSize(10240)
            .expireAfterWrite(10, TimeUnit.MINUTES)
            .expireAfterAccess(1, TimeUnit.MINUTES)
            .build(this::loadChunk);

    private BedrockWorld(Path worldFolder, Key dimension, DataPack dataPack) {
        this.id = World.id(worldFolder, dimension);
        this.worldFolder = worldFolder;
        this.dimension = dimension;
        this.dimensionType = BedrockDimension.of(dimension);
        this.dataPack = dataPack;
        this.level = BedrockLevel.get(worldFolder);
    }

    public static BedrockWorld load(Path worldFolder, Key dimension, @Nullable Key dimensionType, DataPack dataPack) throws IOException {
        if (!BedrockLevel.isBedrockWorld(worldFolder)) throw new IOException("Not a Bedrock world (no db/CURRENT): " + worldFolder);
        BedrockWorld world = new BedrockWorld(worldFolder, dimension, dataPack);
        world.level.refresh(false);
        return world;
    }

    @Override public DimensionType getDimensionType() { return dimensionType; }
    @Override public Grid getChunkGrid() { return CHUNK_GRID; }
    @Override public Grid getRegionGrid() { return REGION_GRID; }
    @Override public Chunk getChunkAtBlock(int x, int z) { return getChunk(x >> 4, z >> 4); }
    @Override public Chunk getChunk(int x, int z) { return chunkCache.get(new Vector2i(x, z)); }

    private Chunk loadChunk(Vector2i pos) {
        IOException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return BedrockChunkLoader.load(level, dimensionType.id(), pos.getX(), pos.getY(), dimensionType, dataPack);
            } catch (IOException ex) {
                // e.g. a table vanished between snapshot and read: take a fresh snapshot and retry
                last = ex;
                try { level.refresh(true); } catch (IOException ignored) {}
            }
        }
        Logger.global.logDebug("Failed to load Bedrock chunk " + pos + " of " + id + ": " + last);
        return Chunk.ERRORED_CHUNK;
    }

    @Override
    public Region<Chunk> getRegion(int x, int z) {
        return new BedrockRegion(this, x, z);
    }

    @Override
    public Collection<Vector2i> listRegions() {
        try {
            level.refresh(false);
            Set<Vector2i> regions = new HashSet<>();
            for (long p : level.chunks(dimensionType.id()).keySet()) {
                regions.add(new Vector2i(BedrockKeys.packedX(p) >> 5, BedrockKeys.packedZ(p) >> 5));
            }
            return regions;
        } catch (IOException ex) {
            Logger.global.logError("Failed to list regions of Bedrock world " + id, ex);
            return List.of();
        }
    }

    @Override
    public WatchService<Vector2i> createRegionWatchService() {
        return new BedrockRegionWatchService(level, dimensionType.id());
    }

    @Override
    public void preloadRegionChunks(int x, int z, Predicate<Vector2i> chunkFilter) {
        // Chunks are point lookups in LevelDB, loading them on demand is as fast as preloading.
    }

    @Override
    public void invalidateChunkCache() {
        chunkCache.invalidateAll();
    }

    @Override
    public void invalidateChunkCache(int x, int z) {
        chunkCache.invalidate(new Vector2i(x, z));
    }

    @Override
    public void iterateEntities(int minX, int minZ, int maxX, int maxZ, Consumer<Entity> entityConsumer) {
        // Bedrock entities (actorprefix records) are not read yet.
    }

    /** One BlueMap region (32x32 chunks) of a Bedrock world. */
    static final class BedrockRegion implements Region<Chunk> {
        private final BedrockWorld world;
        private final int regionX, regionZ;

        BedrockRegion(BedrockWorld world, int regionX, int regionZ) {
            this.world = world;
            this.regionX = regionX;
            this.regionZ = regionZ;
        }

        private List<long[]> chunks() throws IOException {
            List<long[]> out = new ArrayList<>();
            for (Map.Entry<Long, Long> e : world.level.chunks(world.dimensionType.id()).entrySet()) {
                int x = BedrockKeys.packedX(e.getKey()), z = BedrockKeys.packedZ(e.getKey());
                if (x >> 5 == regionX && z >> 5 == regionZ) out.add(new long[]{x, z, e.getValue()});
            }
            return out;
        }

        @Override
        public void iterateAllChunks(ChunkConsumer<Chunk> consumer) throws IOException {
            world.level.refresh(false);
            for (long[] c : chunks()) {
                int x = (int) c[0], z = (int) c[1];
                // The "timestamp" BlueMap compares is the chunk's highest LevelDB sequence number.
                int stamp = (int) (c[2] ^ (c[2] >>> 32));
                if (stamp == 0) stamp = 1;
                if (!consumer.filter(x, z, stamp)) continue;
                try {
                    consumer.accept(x, z, BedrockChunkLoader.load(world.level, world.dimensionType.id(), x, z, world.dimensionType, world.dataPack));
                } catch (IOException ex) {
                    consumer.fail(x, z, ex);
                }
            }
        }

        @Override
        public long fingerprint() throws IOException {
            long h = 1125899906842597L;
            List<long[]> list = chunks();
            list.sort(Comparator.<long[]>comparingLong(c -> c[0]).thenComparingLong(c -> c[1]));
            for (long[] c : list) h = 31 * (31 * (31 * h + c[0]) + c[1]) + c[2];
            return h;
        }

        @Override
        public Chunk emptyChunk() {
            return Chunk.EMPTY_CHUNK;
        }

        @Override
        public boolean exists() {
            try {
                return !chunks().isEmpty();
            } catch (IOException ex) {
                return false;
            }
        }
    }
}
