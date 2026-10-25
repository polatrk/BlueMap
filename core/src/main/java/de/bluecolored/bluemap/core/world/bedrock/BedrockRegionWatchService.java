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
import de.bluecolored.bluemap.core.logger.Logger;
import de.bluecolored.bluemap.core.util.WatchService;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Reports the regions of a Bedrock dimension whose chunks changed. Changes are found by polling the
 * database folder: a new snapshot is taken when files changed, and chunks whose highest sequence
 * number moved are reported (see {@link BedrockLevel}).
 */
final class BedrockRegionWatchService implements WatchService<Vector2i> {

    private static final long POLL_INTERVAL_MS = 5000;

    private final BedrockLevel level;
    private final int dimension;
    private final Set<Vector2i> pending = new LinkedHashSet<>();
    private final Consumer<Set<Long>> listener;
    private volatile boolean closed = false;

    BedrockRegionWatchService(BedrockLevel level, int dimension) {
        this.level = level;
        this.dimension = dimension;
        this.listener = changed -> {
            synchronized (pending) {
                for (long p : changed) {
                    if (BedrockKeys.packedDimension(p) != dimension) continue;
                    pending.add(new Vector2i(BedrockKeys.packedX(p) >> 5, BedrockKeys.packedZ(p) >> 5));
                }
                pending.notifyAll();
            }
        };
        level.addListener(listener);
    }

    private void check() {
        try {
            level.refresh(false);
        } catch (IOException ex) {
            Logger.global.logDebug("Bedrock world refresh failed (will retry): " + ex);
        }
    }

    private @Nullable List<Vector2i> drain() {
        synchronized (pending) {
            if (pending.isEmpty()) return null;
            List<Vector2i> out = new ArrayList<>(pending);
            pending.clear();
            return out;
        }
    }

    @Override
    public @Nullable List<Vector2i> poll() {
        if (closed) throw new ClosedException();
        check();
        return drain();
    }

    @Override
    public @Nullable List<Vector2i> poll(long timeout, TimeUnit unit) throws InterruptedException {
        long deadline = System.currentTimeMillis() + unit.toMillis(timeout);
        while (!closed) {
            check();
            List<Vector2i> r = drain();
            if (r != null) return r;
            long left = deadline - System.currentTimeMillis();
            if (left <= 0) return null;
            synchronized (pending) { pending.wait(Math.min(left, POLL_INTERVAL_MS)); }
        }
        throw new ClosedException();
    }

    @Override
    public List<Vector2i> take() throws InterruptedException {
        while (true) {
            List<Vector2i> r = poll(POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
            if (r != null) return r;
        }
    }

    @Override
    public void close() {
        closed = true;
        level.removeListener(listener);
        synchronized (pending) { pending.notifyAll(); }
    }
}
