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
package de.bluecolored.bluemap.bedrock;

import com.flowpowered.math.vector.Vector3d;
import de.bluecolored.bluemap.common.plugin.text.Text;
import de.bluecolored.bluemap.common.serverinterface.Gamemode;
import de.bluecolored.bluemap.common.serverinterface.Player;
import de.bluecolored.bluemap.common.serverinterface.ServerWorld;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** A player reported by the BlueMap behavior pack. */
public class BedrockPlayer extends Player {

    private final UUID uuid;
    private final String name;
    volatile ServerWorld world;
    volatile Vector3d position = Vector3d.ZERO;
    volatile Vector3d rotation = Vector3d.ZERO;
    volatile int skyLight = 15, blockLight = 0;
    volatile boolean sneaking, invisible;
    volatile Gamemode gamemode = Gamemode.SURVIVAL;
    volatile long lastSeen = System.currentTimeMillis();

    public BedrockPlayer(String name, ServerWorld world) {
        this.name = name;
        this.uuid = uuidOf(name);
        this.world = world;
    }

    /** Bedrock players have no Java UUID: a stable one is derived from the (unique) player name. */
    public static UUID uuidOf(String name) {
        return UUID.nameUUIDFromBytes(("bedrock:" + name).getBytes(StandardCharsets.UTF_8));
    }

    public String getRawName() { return name; }

    @Override public UUID getUuid() { return uuid; }
    @Override public Text getName() { return Text.of(name); }
    @Override public ServerWorld getWorld() { return world; }
    @Override public Vector3d getPosition() { return position; }
    @Override public Vector3d getRotation() { return rotation; }
    @Override public int getSkyLight() { return skyLight; }
    @Override public int getBlockLight() { return blockLight; }
    @Override public boolean isSneaking() { return sneaking; }
    @Override public boolean isInvisible() { return invisible; }
    @Override public Gamemode getGamemode() { return gamemode; }
}
