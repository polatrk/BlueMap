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

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Reader of the LevelDB log format, used by both the write-ahead logs (*.log) and the MANIFEST:
 * 32 KiB blocks of records (crc:4, length:2, type:1, payload) where one logical record may span
 * several physical records (FIRST / MIDDLE / LAST). A truncated tail (file being written) ends the
 * read silently.
 */
public final class LogRecords {

    private static final int BLOCK_SIZE = 32768;
    private static final int HEADER = 7;

    private LogRecords() {}

    public static List<byte[]> read(byte[] data, int length) {
        List<byte[]> records = new ArrayList<>();
        ByteArrayOutputStream pending = null;
        int pos = 0;
        while (pos + HEADER <= length) {
            int left = BLOCK_SIZE - (pos % BLOCK_SIZE);
            if (left < HEADER) { pos += left; continue; }
            int len = (data[pos + 4] & 0xFF) | (data[pos + 5] & 0xFF) << 8;
            int type = data[pos + 6] & 0xFF;
            if (type == 0 && len == 0) { pos += left; continue; } // zero padding
            if (pos + HEADER + len > length) break;
            int start = pos + HEADER;
            pos = start + len;
            switch (type) {
                case 1 -> { // FULL
                    byte[] r = new byte[len];
                    System.arraycopy(data, start, r, 0, len);
                    records.add(r);
                    pending = null;
                }
                case 2 -> { // FIRST
                    pending = new ByteArrayOutputStream();
                    pending.write(data, start, len);
                }
                case 3 -> { // MIDDLE
                    if (pending != null) pending.write(data, start, len);
                }
                case 4 -> { // LAST
                    if (pending != null) {
                        pending.write(data, start, len);
                        records.add(pending.toByteArray());
                    }
                    pending = null;
                }
                default -> { return records; }
            }
        }
        return records;
    }
}
