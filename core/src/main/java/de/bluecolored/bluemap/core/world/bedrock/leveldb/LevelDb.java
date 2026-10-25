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

import java.io.Closeable;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Read-only, point-in-time view of a LevelDB database written by another process (Bedrock
 * Dedicated Server). Nothing is ever written to the database folder and its LOCK is ignored.
 *
 * <p>Opening reads CURRENT and replays the MANIFEST to know the live tables and their levels, opens
 * those tables (an open table stays readable even if the server deletes it later) and loads the
 * write-ahead log(s) into memory, up to their current length. Lookups follow LevelDB's order:
 * log, then level 0 tables from newest to oldest, then one candidate table per deeper level.</p>
 *
 * <p>Tables are shared between successive snapshots through a {@link TableCache}.</p>
 */
public final class LevelDb implements Closeable {

    /** Newest version of a key found in the logs. */
    private record MemEntry(long sequence, byte[] value) {} // value == null: deletion

    private record FileMeta(long number, byte[] smallest, byte[] largest) {}

    private final Path folder;
    private final TableCache tables;
    private final NavigableMap<byte[], MemEntry> mem;
    private final List<List<TableFile>> levels;
    private final List<List<FileMeta>> levelMeta;
    private final Set<Long> tableNumbers;
    private final String signature;

    private LevelDb(Path folder, TableCache tables, NavigableMap<byte[], MemEntry> mem,
                    List<List<TableFile>> levels, List<List<FileMeta>> levelMeta, String signature) {
        this.folder = folder;
        this.tables = tables;
        this.mem = mem;
        this.levels = levels;
        this.levelMeta = levelMeta;
        this.signature = signature;
        Set<Long> numbers = new HashSet<>();
        for (List<FileMeta> l : levelMeta) for (FileMeta f : l) numbers.add(f.number);
        this.tableNumbers = numbers;
    }

    public Path getFolder() { return folder; }

    /** Changes whenever the database files change (used to skip useless reloads). */
    public String getSignature() { return signature; }

    public Set<Long> getTableNumbers() { return tableNumbers; }

    /** Opens a snapshot, retrying when files move under our feet (compaction while reading). */
    public static LevelDb open(Path folder, TableCache tables) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < 10; attempt++) {
            try {
                return tryOpen(folder, tables);
            } catch (NoSuchFileException ex) {
                last = ex;
                try { Thread.sleep(100L * (attempt + 1)); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException(ie);
                }
            }
        }
        throw last;
    }

    /** Cheap fingerprint of the folder content (names, sizes, modification times). */
    public static String folderSignature(Path folder) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (var stream = Files.list(folder)) {
            for (Path p : stream.sorted().toList()) {
                String n = p.getFileName().toString();
                if (n.equals("LOCK") || n.startsWith("LOG")) continue;
                try {
                    sb.append(n).append(':').append(Files.size(p)).append(':')
                            .append(Files.getLastModifiedTime(p).toMillis()).append('|');
                } catch (NoSuchFileException ignored) {}
            }
        }
        return sb.toString();
    }

    private static LevelDb tryOpen(Path folder, TableCache tables) throws IOException {
        String signature = folderSignature(folder);
        String current = Files.readString(folder.resolve("CURRENT"), StandardCharsets.UTF_8).trim();
        Version version = Version.replay(readPrefix(folder.resolve(current)));

        List<List<FileMeta>> meta = new ArrayList<>();
        List<List<TableFile>> levels = new ArrayList<>();
        try {
            for (int level = 0; level < version.files.size(); level++) {
                List<FileMeta> files = new ArrayList<>(version.files.get(level).values());
                // Level 0 files overlap: newest (highest number) first. Deeper levels: ordered by key.
                if (level == 0) files.sort(Comparator.comparingLong((FileMeta f) -> f.number).reversed());
                else files.sort((a, b) -> Bytes.compareInternal(a.smallest, b.smallest));
                List<TableFile> opened = new ArrayList<>(files.size());
                levels.add(opened);
                for (FileMeta f : files) opened.add(tables.acquire(folder, f.number));
                meta.add(files);
            }
        } catch (IOException | RuntimeException ex) {
            for (List<TableFile> l : levels) for (TableFile t : l) tables.release(folder, t.getNumber());
            throw ex;
        }

        // Logs not yet flushed into tables.
        NavigableMap<byte[], MemEntry> mem = new TreeMap<>(Bytes::compare);
        List<Long> logs = new ArrayList<>();
        try (var stream = Files.list(folder)) {
            for (Path p : stream.toList()) {
                String n = p.getFileName().toString();
                if (!n.endsWith(".log")) continue;
                try {
                    long num = Long.parseLong(n.substring(0, n.length() - 4));
                    if (num >= version.logNumber || num == version.prevLogNumber) logs.add(num);
                } catch (NumberFormatException ignored) {}
            }
        }
        Collections.sort(logs);
        for (long num : logs) {
            byte[] data;
            try {
                data = readPrefix(folder.resolve("%06d.log".formatted(num)));
            } catch (NoSuchFileException ex) {
                continue; // flushed meanwhile: its content is in a table we may miss, caller retries later
            }
            for (byte[] batch : LogRecords.read(data, data.length)) applyBatch(batch, mem);
        }
        return new LevelDb(folder, tables, mem, levels, meta, signature);
    }

    /** WriteBatch: seq:u64, count:u32, then count x (type:u8, key:varstring[, value:varstring]). */
    private static void applyBatch(byte[] b, NavigableMap<byte[], MemEntry> mem) {
        if (b.length < 12) return;
        long seq = Bytes.u64(b, 0);
        int count = Bytes.u32(b, 8);
        Bytes.Cursor c = new Bytes.Cursor(12);
        try {
            for (int i = 0; i < count && c.pos < b.length; i++, seq++) {
                int type = b[c.pos++] & 0xFF;
                int klen = Bytes.varint32(b, c);
                byte[] key = Arrays.copyOfRange(b, c.pos, c.pos + klen);
                c.pos += klen;
                byte[] value = null;
                if (type == Bytes.TYPE_VALUE) {
                    int vlen = Bytes.varint32(b, c);
                    value = Arrays.copyOfRange(b, c.pos, c.pos + vlen);
                    c.pos += vlen;
                } else if (type != Bytes.TYPE_DELETION) {
                    return;
                }
                MemEntry prev = mem.get(key);
                if (prev == null || prev.sequence <= seq) mem.put(key, new MemEntry(seq, value));
            }
        } catch (RuntimeException ignored) {
            // truncated batch at the end of a log being written
        }
    }

    /** Value of {@code key}, or null when absent or deleted. */
    public byte[] get(byte[] key) throws IOException {
        Lookup l = lookup(key);
        return l == null ? null : l.value;
    }

    /** Newest version of a key with its sequence number (value null = deleted), or null if absent. */
    public record Lookup(long sequence, byte[] value) {}

    public Lookup lookup(byte[] key) throws IOException {
        MemEntry m = mem.get(key);
        if (m != null) return new Lookup(m.sequence, m.value);
        for (int level = 0; level < levels.size(); level++) {
            List<FileMeta> metas = levelMeta.get(level);
            List<TableFile> files = levels.get(level);
            if (level == 0) {
                for (int i = 0; i < files.size(); i++) {
                    if (!mayContain(metas.get(i), key)) continue;
                    TableFile.Entry e = files.get(i).get(key);
                    if (e != null) return new Lookup(e.sequence(), e.isDeletion() ? null : e.value());
                }
            } else {
                // Non-overlapping files sorted by key: binary search on the largest key.
                int lo = 0, hi = metas.size();
                while (lo < hi) {
                    int mid = (lo + hi) >>> 1;
                    if (Bytes.compare(metas.get(mid).largest, 0, metas.get(mid).largest.length - 8, key, 0, key.length) < 0) lo = mid + 1;
                    else hi = mid;
                }
                if (lo < metas.size() && mayContain(metas.get(lo), key)) {
                    TableFile.Entry e = files.get(lo).get(key);
                    if (e != null) return new Lookup(e.sequence(), e.isDeletion() ? null : e.value());
                }
            }
        }
        return null;
    }

    private static boolean mayContain(FileMeta f, byte[] key) {
        return Bytes.compare(f.smallest, 0, f.smallest.length - 8, key, 0, key.length) <= 0 &&
                Bytes.compare(f.largest, 0, f.largest.length - 8, key, 0, key.length) >= 0;
    }

    /** Called with every key stored in the logs and tables (all versions) and its sequence number. */
    public interface KeyVisitor {
        void visit(byte[] userKey, long sequence, boolean deletion);
    }

    /** Visits the keys of the logs, and of the tables through their per-table cached summaries. */
    public void visitLogKeys(KeyVisitor visitor) {
        for (Map.Entry<byte[], MemEntry> e : mem.entrySet()) visitor.visit(e.getKey(), e.getValue().sequence, e.getValue().value == null);
    }

    public List<TableFile> allTables() {
        List<TableFile> out = new ArrayList<>();
        for (List<TableFile> l : levels) out.addAll(l);
        return out;
    }

    /** Releases this snapshot's tables (closed once no snapshot uses them). */
    @Override
    public void close() {
        for (List<TableFile> l : levels) for (TableFile t : l) tables.release(folder, t.getNumber());
    }

    /** Reads the current content of a file that another process may be appending to. */
    private static byte[] readPrefix(Path path) throws IOException {
        try (FileChannel ch = FileChannel.open(path, StandardOpenOption.READ)) {
            long size = ch.size();
            java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate((int) size);
            while (buf.hasRemaining()) {
                if (ch.read(buf, buf.position()) < 0) break;
            }
            return Arrays.copyOf(buf.array(), buf.position());
        }
    }

    // ---- MANIFEST ------------------------------------------------------------------------------

    /** Result of replaying the VersionEdits of a MANIFEST. */
    private static final class Version {
        final List<Map<Long, FileMeta>> files = new ArrayList<>();
        long logNumber = 0, prevLogNumber = -1;

        static Version replay(byte[] manifest) throws IOException {
            Version v = new Version();
            for (byte[] edit : LogRecords.read(manifest, manifest.length)) v.apply(edit);
            return v;
        }

        private Map<Long, FileMeta> level(int level) {
            while (files.size() <= level) files.add(new HashMap<>());
            return files.get(level);
        }

        /** VersionEdit tags: 1 comparator, 2 log number, 3 next file, 4 last sequence, 5 compact pointer, 6 deleted file, 7 new file, 9 previous log. */
        private void apply(byte[] e) throws IOException {
            Bytes.Cursor c = new Bytes.Cursor(0);
            while (c.pos < e.length) {
                int tag = Bytes.varint32(e, c);
                switch (tag) {
                    case 1 -> { int n = Bytes.varint32(e, c); c.pos += n; }
                    case 2 -> logNumber = Bytes.varint(e, c);
                    case 3, 4 -> Bytes.varint(e, c);
                    case 5 -> { Bytes.varint32(e, c); int n = Bytes.varint32(e, c); c.pos += n; }
                    case 6 -> { int level = Bytes.varint32(e, c); long num = Bytes.varint(e, c); level(level).remove(num); }
                    case 7 -> {
                        int level = Bytes.varint32(e, c);
                        long num = Bytes.varint(e, c);
                        Bytes.varint(e, c); // file size
                        int sl = Bytes.varint32(e, c); byte[] smallest = Arrays.copyOfRange(e, c.pos, c.pos + sl); c.pos += sl;
                        int ll = Bytes.varint32(e, c); byte[] largest = Arrays.copyOfRange(e, c.pos, c.pos + ll); c.pos += ll;
                        level(level).put(num, new FileMeta(num, smallest, largest));
                    }
                    case 9 -> prevLogNumber = Bytes.varint(e, c);
                    default -> throw new IOException("unknown MANIFEST tag " + tag);
                }
            }
        }
    }

    /** Shares open tables between snapshots, with reference counting. */
    public static final class TableCache {
        private record Ref(TableFile table, int[] count) {}
        private final Map<String, Ref> open = new ConcurrentHashMap<>();

        synchronized TableFile acquire(Path folder, long number) throws IOException {
            String id = folder.toAbsolutePath() + "#" + number;
            Ref ref = open.get(id);
            if (ref == null) {
                Path path = folder.resolve("%06d.ldb".formatted(number));
                if (!Files.exists(path)) path = folder.resolve("%06d.sst".formatted(number));
                ref = new Ref(TableFile.open(path, number), new int[]{0});
                open.put(id, ref);
            }
            ref.count[0]++;
            return ref.table;
        }

        synchronized void release(Path folder, long number) {
            String id = folder.toAbsolutePath() + "#" + number;
            Ref ref = open.get(id);
            if (ref == null) return;
            if (--ref.count[0] <= 0) {
                open.remove(id);
                try { ref.table.close(); } catch (IOException ignored) {}
            }
        }
    }
}
