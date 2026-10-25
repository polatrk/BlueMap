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

import de.bluecolored.bluemap.core.BlueMap;
import de.bluecolored.bluemap.core.logger.Logger;
import de.bluecolored.bluemap.core.world.bedrock.leveldb.Bytes;
import de.bluecolored.bluemap.core.world.bedrock.leveldb.LevelDb;
import de.bluecolored.bluemap.core.world.bedrock.leveldb.TableFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * One Bedrock world folder (shared by its three dimensions): the current read-only snapshot of the
 * LevelDB database and an index of every chunk with the highest sequence number among its records.
 *
 * <p>LevelDB never reuses sequence numbers and compactions keep them, so this number changes if and
 * only if the chunk was written: it is used as the chunk "timestamp" BlueMap compares to decide what
 * to re-render, and to find which chunks changed between two snapshots. Building the index scans
 * each table once (tables are immutable, their summary is kept) plus the write-ahead log.</p>
 */
public final class BedrockLevel {

    private static final Map<Path, BedrockLevel> LEVELS = new ConcurrentHashMap<>();
    /** Chunk record tags taken into account for the "chunk changed" detection. */
    private static final Set<Integer> TRACKED_TAGS = Set.of(
            BedrockKeys.TAG_SUBCHUNK, BedrockKeys.TAG_DATA_3D, BedrockKeys.TAG_VERSION,
            BedrockKeys.TAG_LEGACY_VERSION, BedrockKeys.TAG_BLOCK_ENTITY
    );
    private static final long MIN_REFRESH_INTERVAL_MS = 2000;

    private record State(LevelDb db, Map<Long, Long> chunks) {}

    private final Path dbFolder;
    private final LevelDb.TableCache tables = new LevelDb.TableCache();
    private final List<Consumer<Set<Long>>> listeners = new CopyOnWriteArrayList<>();
    private volatile State state;
    private long lastCheck = 0;

    private BedrockLevel(Path worldFolder) {
        this.dbFolder = worldFolder.resolve("db");
    }

    public static BedrockLevel get(Path worldFolder) {
        return LEVELS.computeIfAbsent(worldFolder.toAbsolutePath().normalize(), BedrockLevel::new);
    }

    /** True if the folder looks like a Bedrock world (LevelDB "db" folder next to level.dat). */
    public static boolean isBedrockWorld(Path worldFolder) {
        return Files.isRegularFile(worldFolder.resolve("db").resolve("CURRENT"));
    }

    /** Notified with the packed positions of chunks that changed after a refresh. */
    public void addListener(Consumer<Set<Long>> listener) { listeners.add(listener); }
    public void removeListener(Consumer<Set<Long>> listener) { listeners.remove(listener); }

    private State state() throws IOException {
        State s = state;
        if (s == null) {
            synchronized (this) {
                if (state == null) refresh(true);
                s = state;
            }
        }
        return s;
    }

    /**
     * Opens a new snapshot if the database files changed (at most every few seconds unless forced)
     * and notifies listeners of the chunks that changed. Returns true if a new snapshot was taken.
     */
    public synchronized boolean refresh(boolean force) throws IOException {
        long now = System.currentTimeMillis();
        if (!force && now - lastCheck < MIN_REFRESH_INTERVAL_MS) return false;
        lastCheck = now;
        State old = state;
        if (old != null && LevelDb.folderSignature(dbFolder).equals(old.db.getSignature())) return false;

        long t0 = System.nanoTime();
        LevelDb db = LevelDb.open(dbFolder, tables);
        Map<Long, Long> chunks = new HashMap<>();
        try {
            for (TableFile t : db.allTables()) mergeMax(chunks, summary(t));
        } catch (IOException | RuntimeException ex) {
            db.close();
            throw ex;
        }
        db.visitLogKeys((key, seq, deletion) -> track(chunks, key, seq));
        state = new State(db, chunks);

        if (old != null) {
            Set<Long> changed = new HashSet<>();
            for (Map.Entry<Long, Long> e : chunks.entrySet()) if (!e.getValue().equals(old.chunks.get(e.getKey()))) changed.add(e.getKey());
            for (Long k : old.chunks.keySet()) if (!chunks.containsKey(k)) changed.add(k);
            // Readers may still use the previous snapshot for a moment: release its tables later.
            BlueMap.SCHEDULER.schedule(old.db::close, 60, TimeUnit.SECONDS);
            if (!changed.isEmpty()) {
                Logger.global.logDebug("Bedrock world " + dbFolder + ": " + changed.size() + " chunk(s) changed");
                for (Consumer<Set<Long>> l : listeners) l.accept(changed);
            }
        } else {
            Logger.global.logDebug("Bedrock world " + dbFolder + ": " + chunks.size() + " chunks indexed in " +
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0) + "ms");
        }
        return true;
    }

    /** Reads the newest value of a key from the current snapshot. */
    public byte[] get(byte[] key) throws IOException {
        return state().db.get(key);
    }

    /** Highest sequence number of a chunk's records, or null if the chunk does not exist. */
    public Long chunkSequence(int dimension, int x, int z) throws IOException {
        return state().chunks.get(BedrockKeys.pack(dimension, x, z));
    }

    /** All chunks of a dimension (packed positions) with their sequence numbers. */
    public Map<Long, Long> chunks(int dimension) throws IOException {
        Map<Long, Long> out = new HashMap<>();
        for (Map.Entry<Long, Long> e : state().chunks.entrySet()) {
            if (BedrockKeys.packedDimension(e.getKey()) == dimension) out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<Long, Long> summary(TableFile table) throws IOException {
        Object s = table.getSummary();
        if (s != null) return (Map<Long, Long>) s;
        Map<Long, Long> m = new HashMap<>();
        table.forEachUncached((internalKey, block, vo, vl) -> {
            byte[] userKey = Arrays.copyOf(internalKey, internalKey.length - 8);
            track(m, userKey, Bytes.sequence(internalKey));
        });
        table.setSummary(m);
        return m;
    }

    private static void track(Map<Long, Long> chunks, byte[] userKey, long seq) {
        BedrockKeys.ChunkKey k = BedrockKeys.parse(userKey);
        if (k == null || !TRACKED_TAGS.contains(k.tag())) return;
        chunks.merge(BedrockKeys.pack(k.dimension(), k.x(), k.z()), seq, Math::max);
    }

    private static void mergeMax(Map<Long, Long> into, Map<Long, Long> from) {
        for (Map.Entry<Long, Long> e : from.entrySet()) into.merge(e.getKey(), e.getValue(), Math::max);
    }
}
