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

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * A LevelDB sorted table (*.ldb), opened read-only. Tables are immutable: once opened, the file can
 * be deleted by a compaction of the server and the open channel still reads the same data (on Windows
 * Java opens files with FILE_SHARE_DELETE, so the server is never prevented from deleting them).
 */
public final class TableFile implements Closeable {

    private static final long MAGIC = 0xdb4775248b80fb57L;

    /** Decompressed blocks shared by all tables, weighed by size. */
    private static final Cache<BlockId, byte[]> BLOCK_CACHE = Caffeine.newBuilder()
            .maximumWeight(64L << 20)
            .weigher((BlockId k, byte[] v) -> v.length + 64)
            .build();

    private record BlockId(long table, long offset) {}

    /** Unique id of each opened table (file numbers repeat across worlds). */
    private static final java.util.concurrent.atomic.AtomicLong IDS = new java.util.concurrent.atomic.AtomicLong();
    private final long id = IDS.incrementAndGet();

    /** Called for every entry of the table. */
    public interface EntryConsumer {
        void accept(byte[] internalKey, byte[] block, int valueOffset, int valueLength);
    }

    private final long number;
    private final FileChannel channel;
    /** Index block: last key (internal key separator) and handle of each data block. */
    private final byte[][] indexKeys;
    private final long[] blockOffsets;
    private final int[] blockSizes;

    private TableFile(long number, FileChannel channel, byte[][] indexKeys, long[] blockOffsets, int[] blockSizes) {
        this.number = number;
        this.channel = channel;
        this.indexKeys = indexKeys;
        this.blockOffsets = blockOffsets;
        this.blockSizes = blockSizes;
    }

    public long getNumber() { return number; }

    /** Per-table derived data (e.g. a chunk summary), computed once since tables never change. */
    private volatile Object summary;
    public Object getSummary() { return summary; }
    public void setSummary(Object summary) { this.summary = summary; }

    /** Iterates over every entry without polluting the block cache (full scans). */
    public void forEachUncached(EntryConsumer consumer) throws IOException {
        for (int b = 0; b < indexKeys.length; b++) {
            byte[] block = BLOCK_CACHE.getIfPresent(new BlockId(id, blockOffsets[b]));
            if (block == null) block = decompress(read(channel, blockOffsets[b], blockSizes[b] + 5), blockSizes[b]);
            forEachEntry(block, consumer);
        }
    }

    public static TableFile open(Path path, long number) throws IOException {
        FileChannel channel = FileChannel.open(path, StandardOpenOption.READ);
        try {
            long size = channel.size();
            if (size < 48) throw new IOException("table too small: " + path);
            byte[] footer = read(channel, size - 48, 48);
            if (Bytes.u64(footer, 40) != MAGIC) throw new IOException("bad table magic: " + path);
            Bytes.Cursor c = new Bytes.Cursor(0);
            Bytes.varint(footer, c); Bytes.varint(footer, c); // metaindex handle (filters, unused)
            long indexOffset = Bytes.varint(footer, c);
            int indexSize = (int) Bytes.varint(footer, c);
            byte[] index = decompress(read(channel, indexOffset, indexSize + 5), indexSize);

            java.util.List<byte[]> keys = new java.util.ArrayList<>();
            java.util.List<long[]> handles = new java.util.ArrayList<>();
            forEachEntry(index, (key, block, vo, vl) -> {
                Bytes.Cursor hc = new Bytes.Cursor(vo);
                long off = Bytes.varint(block, hc);
                long sz = Bytes.varint(block, hc);
                keys.add(key);
                handles.add(new long[]{off, sz});
            });
            long[] offsets = new long[handles.size()];
            int[] sizes = new int[handles.size()];
            for (int i = 0; i < offsets.length; i++) {
                offsets[i] = handles.get(i)[0];
                sizes[i] = (int) handles.get(i)[1];
            }
            return new TableFile(number, channel, keys.toArray(new byte[0][]), offsets, sizes);
        } catch (IOException | RuntimeException ex) {
            channel.close();
            throw ex instanceof IOException io ? io : new IOException("invalid table " + path, ex);
        }
    }

    /**
     * Newest entry for {@code userKey} in this table, or null if the table has none.
     * Returns the internal key (to read the sequence number / type) and the value.
     */
    public Entry get(byte[] userKey) throws IOException {
        byte[] target = Bytes.seekKey(userKey);
        // First data block whose last key is >= target.
        int lo = 0, hi = indexKeys.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (Bytes.compareInternal(indexKeys[mid], target) < 0) lo = mid + 1;
            else hi = mid;
        }
        for (int b = lo; b < indexKeys.length; b++) {
            byte[] block = block(b);
            Entry[] found = new Entry[1];
            boolean[] passed = new boolean[1];
            forEachEntry(block, (key, blk, vo, vl) -> {
                if (found[0] != null || passed[0]) return;
                if (Bytes.compareInternal(key, target) < 0) return;
                if (Bytes.userKeyEquals(key, userKey)) {
                    byte[] value = new byte[vl];
                    System.arraycopy(blk, vo, value, 0, vl);
                    found[0] = new Entry(key, value);
                } else {
                    passed[0] = true;
                }
            });
            if (found[0] != null) return found[0];
            if (passed[0]) return null;
        }
        return null;
    }

    /** Iterates over every entry of the table in key order. */
    public void forEach(EntryConsumer consumer) throws IOException {
        for (int b = 0; b < indexKeys.length; b++) forEachEntry(block(b), consumer);
    }

    private byte[] block(int i) throws IOException {
        BlockId bid = new BlockId(id, blockOffsets[i]);
        byte[] cached = BLOCK_CACHE.getIfPresent(bid);
        if (cached != null) return cached;
        byte[] block = decompress(read(channel, blockOffsets[i], blockSizes[i] + 5), blockSizes[i]);
        BLOCK_CACHE.put(bid, block);
        return block;
    }

    /**
     * Block layout: entries (shared:varint, nonShared:varint, valueLength:varint, key delta, value),
     * then the restart array (u32 each) and its count (u32). Keys are prefix-compressed.
     */
    static void forEachEntry(byte[] block, EntryConsumer consumer) {
        int numRestarts = Bytes.u32(block, block.length - 4);
        int end = block.length - 4 - numRestarts * 4;
        Bytes.Cursor c = new Bytes.Cursor(0);
        byte[] last = new byte[0];
        while (c.pos < end) {
            int shared = Bytes.varint32(block, c);
            int nonShared = Bytes.varint32(block, c);
            int valueLength = Bytes.varint32(block, c);
            byte[] key = new byte[shared + nonShared];
            System.arraycopy(last, 0, key, 0, shared);
            System.arraycopy(block, c.pos, key, shared, nonShared);
            c.pos += nonShared;
            consumer.accept(key, block, c.pos, valueLength);
            c.pos += valueLength;
            last = key;
        }
    }

    /** Raw block + 5 byte trailer (compression type, crc). Mojang: 0 none, 2 zlib, 4 raw deflate. */
    private static byte[] decompress(byte[] raw, int size) throws IOException {
        int type = raw[size] & 0xFF;
        switch (type) {
            case 0: {
                byte[] out = new byte[size];
                System.arraycopy(raw, 0, out, 0, size);
                return out;
            }
            case 2:
            case 4: {
                Inflater inflater = new Inflater(type == 4);
                try {
                    inflater.setInput(raw, 0, size);
                    ByteArrayOutputStream out = new ByteArrayOutputStream(size * 4);
                    byte[] buf = new byte[65536];
                    while (!inflater.finished()) {
                        int n = inflater.inflate(buf);
                        if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break;
                        out.write(buf, 0, n);
                    }
                    return out.toByteArray();
                } catch (DataFormatException ex) {
                    throw new IOException("corrupt compressed block", ex);
                } finally {
                    inflater.end();
                }
            }
            default:
                throw new IOException("unsupported block compression " + type);
        }
    }

    private static byte[] read(FileChannel channel, long offset, int length) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(length);
        while (buf.hasRemaining()) {
            int n = channel.read(buf, offset + buf.position());
            if (n < 0) throw new IOException("unexpected end of table");
        }
        return buf.array();
    }

    @Override
    public void close() throws IOException {
        channel.close();
        BLOCK_CACHE.asMap().keySet().removeIf(k -> k.table == id);
    }

    /** A stored entry: internal key (user key + sequence/type) and value. */
    public record Entry(byte[] internalKey, byte[] value) {
        public long sequence() { return Bytes.sequence(internalKey); }
        public boolean isDeletion() { return Bytes.type(internalKey) == Bytes.TYPE_DELETION; }
    }
}
