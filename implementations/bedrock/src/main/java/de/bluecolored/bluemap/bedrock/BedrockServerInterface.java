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

import de.bluecolored.bluemap.common.serverinterface.Player;
import de.bluecolored.bluemap.common.serverinterface.Server;
import de.bluecolored.bluemap.common.serverinterface.ServerEventListener;
import de.bluecolored.bluemap.common.serverinterface.ServerWorld;
import de.bluecolored.bluemap.core.world.BlockState;
import de.bluecolored.bluemap.core.world.World;
import de.bluecolored.bluemap.core.world.bedrock.BedrockWorld;
import de.bluecolored.bluemap.core.util.Key;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.*;

/** BlueMap's view of a Bedrock Dedicated Server: its world (3 dimensions) and online players. */
public class BedrockServerInterface implements Server {

    /** Java version whose resources are used to render (matches the Bedrock to Java block mappings). */
    public static final String MINECRAFT_VERSION = "26.3";

    private final Path configFolder;
    private final List<ServerWorld> worlds;
    private final PlayerTracker players;

    public BedrockServerInterface(Path configFolder, List<ServerWorld> worlds, PlayerTracker players) {
        this.configFolder = configFolder;
        this.worlds = worlds;
        this.players = players;
    }

    @Override public @Nullable String getMinecraftVersion() { return MINECRAFT_VERSION; }
    @Override public Path getConfigFolder() { return configFolder; }
    @Override public Optional<Path> getModsFolder() { return Optional.empty(); }
    @Override public Collection<ServerWorld> getLoadedServerWorlds() { return worlds; }
    @Override public @Nullable Map<Key, BlockState> getDefaultBlockstates() { return null; }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Map<UUID, Player> getOnlinePlayers() {
        return (Map) Collections.unmodifiableMap(players.getPlayers());
    }

    @Override
    public Optional<ServerWorld> getServerWorld(World world) {
        if (world instanceof BedrockWorld bw) {
            Path folder = bw.getWorldFolder().toAbsolutePath().normalize();
            return worlds.stream()
                    .filter(w -> w.getWorldFolder().toAbsolutePath().normalize().equals(folder) && w.getDimension().equals(bw.getDimension()))
                    .findAny();
        }
        return Server.super.getServerWorld(world);
    }

    @Override public void registerListener(ServerEventListener listener) { players.addListener(listener); }
    @Override public void unregisterAllListeners() { players.clearListeners(); }
}
