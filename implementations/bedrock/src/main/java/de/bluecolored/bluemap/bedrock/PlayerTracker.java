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
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.bluecolored.bluemap.common.serverinterface.Gamemode;
import de.bluecolored.bluemap.common.serverinterface.ServerEventListener;
import de.bluecolored.bluemap.common.serverinterface.ServerWorld;
import de.bluecolored.bluemap.core.logger.Logger;
import de.bluecolored.bluemap.core.util.Key;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Players reported by the BlueMap behavior pack, through console lines {@code [BEDMAP]{json}}
 * or HTTP POSTs. Message format (JSON):
 * <pre>
 * {"t":"hello","v":"0.2.0"}
 * {"t":"players","p":[{"n":name,"id":entityId,"x":..,"y":..,"z":..,"ry":yaw,"rx":pitch,"d":"minecraft:overworld",
 *                      "gm":"Survival","sn":false,"inv":false,"sl":15,"bl":0}]}
 * {"t":"join"|"leave","n":name,"id":entityId}
 * {"t":"cmd","n":name,"a":"render overworld"}     (in-game /bluemap command)
 * </pre>
 */
public final class PlayerTracker {

    public static final String PREFIX = "[BEDMAP]";
    private static final Pattern CONNECTED = Pattern.compile("Player connected: (.+), xuid: (\\d+)");
    private static final long EXPIRE_MS = 15000;

    private final Map<UUID, BedrockPlayer> players = new ConcurrentHashMap<>();
    private final Map<UUID, String> xuids = new ConcurrentHashMap<>();
    private final Map<String, ServerWorld> worlds;
    private final List<ServerEventListener> listeners = new CopyOnWriteArrayList<>();
    private volatile BiConsumer<BedrockPlayer, String> commandHandler = (p, c) -> {};
    private volatile long lastReport = 0;

    public PlayerTracker(Map<String, ServerWorld> worldsByScriptId) {
        this.worlds = worldsByScriptId;
        Timer timer = new Timer("BlueMap-Bedrock-Players", true);
        timer.schedule(new TimerTask() {
            @Override public void run() { expire(); }
        }, 5000, 5000);
    }

    public Map<UUID, BedrockPlayer> getPlayers() { return players; }
    public Optional<String> xuid(UUID uuid) { return Optional.ofNullable(xuids.get(uuid)); }
    public void addListener(ServerEventListener l) { listeners.add(l); }
    public void clearListeners() { listeners.clear(); }
    public void setCommandHandler(BiConsumer<BedrockPlayer, String> handler) { this.commandHandler = handler; }

    /** Console line handler: consumes add-on lines, remembers XUIDs of connecting players. */
    public boolean handleConsoleLine(String line) {
        int i = line.indexOf(PREFIX);
        if (i >= 0) {
            handle(line.substring(i + PREFIX.length()).trim(), "console");
            return true;
        }
        Matcher m = CONNECTED.matcher(line);
        if (m.find()) xuids.put(BedrockPlayer.uuidOf(m.group(1).trim()), m.group(2));
        return false;
    }

    public boolean handle(String json, String source) {
        JsonObject msg;
        try {
            JsonElement el = JsonParser.parseString(json);
            if (!el.isJsonObject()) return false;
            msg = el.getAsJsonObject();
        } catch (RuntimeException ex) {
            return false;
        }
        lastReport = System.currentTimeMillis();
        String type = str(msg, "t", "");
        switch (type) {
            case "hello" -> Logger.global.logInfo("BlueMap add-on connected via " + source + " (v" + str(msg, "v", "?") + ")");
            case "join" -> Logger.global.logInfo(str(msg, "n", "?") + " joined");
            case "leave" -> remove(BedrockPlayer.uuidOf(str(msg, "n", "")));
            case "players" -> update(msg.getAsJsonArray("p"));
            case "cmd" -> {
                BedrockPlayer p = players.get(BedrockPlayer.uuidOf(str(msg, "n", "")));
                if (p != null) commandHandler.accept(p, str(msg, "a", ""));
            }
            default -> { return false; }
        }
        return true;
    }

    private void update(JsonArray list) {
        if (list == null) return;
        long now = System.currentTimeMillis();
        Set<UUID> seen = new HashSet<>();
        for (JsonElement e : list) {
            if (!e.isJsonObject()) continue;
            JsonObject o = e.getAsJsonObject();
            String name = str(o, "n", null);
            ServerWorld world = worlds.get(str(o, "d", ""));
            if (name == null || world == null) continue;
            UUID uuid = BedrockPlayer.uuidOf(name);
            seen.add(uuid);
            boolean isNew = !players.containsKey(uuid);
            BedrockPlayer p = players.computeIfAbsent(uuid, u -> new BedrockPlayer(name, world));
            p.world = world;
            p.position = new Vector3d(num(o, "x"), num(o, "y"), num(o, "z"));
            p.rotation = new Vector3d(num(o, "rx"), num(o, "ry"), 0);
            p.sneaking = bool(o, "sn");
            p.invisible = bool(o, "inv");
            if (o.has("sl")) p.skyLight = (int) num(o, "sl");
            if (o.has("bl")) p.blockLight = (int) num(o, "bl");
            p.gamemode = gamemode(str(o, "gm", "survival"));
            p.lastSeen = now;
            if (isNew) {
                Logger.global.logInfo("Tracking " + name);
                for (ServerEventListener l : listeners) l.onPlayerJoin(uuid);
            }
        }
        // A report is the complete list: anyone missing has left.
        for (UUID uuid : new ArrayList<>(players.keySet())) if (!seen.contains(uuid)) remove(uuid);
    }

    private void remove(UUID uuid) {
        if (players.remove(uuid) != null) for (ServerEventListener l : listeners) l.onPlayerLeave(uuid);
    }

    private void expire() {
        long now = System.currentTimeMillis();
        for (BedrockPlayer p : new ArrayList<>(players.values())) if (now - p.lastSeen > EXPIRE_MS) remove(p.getUuid());
    }

    public long getLastReport() { return lastReport; }

    private static Gamemode gamemode(String s) {
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "creative" -> Gamemode.CREATIVE;
            case "adventure" -> Gamemode.ADVENTURE;
            case "spectator" -> Gamemode.SPECTATOR;
            default -> Gamemode.SURVIVAL;
        };
    }

    private static String str(JsonObject o, String k, String d) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : d;
    }

    private static double num(JsonObject o, String k) {
        JsonElement e = o.get(k);
        try { return e != null && e.isJsonPrimitive() ? e.getAsDouble() : 0; } catch (NumberFormatException ex) { return 0; }
    }

    private static boolean bool(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonPrimitive() && e.getAsBoolean();
    }

    /** Script API dimension ids to BlueMap dimension keys. */
    public static String scriptId(Key dimension) {
        return dimension.getFormatted().equals("minecraft:the_nether") ? "minecraft:nether" : dimension.getFormatted();
    }
}
