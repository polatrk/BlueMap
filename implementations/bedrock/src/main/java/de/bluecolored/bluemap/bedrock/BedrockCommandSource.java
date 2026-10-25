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
import com.google.gson.JsonObject;
import de.bluecolored.bluemap.common.serverinterface.CommandSource;
import de.bluecolored.bluemap.common.serverinterface.ServerWorld;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.ansi.ANSIComponentSerializer;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.Optional;

/** Command sources: the terminal, or a player using /bluemap in game (answered with tellraw). */
public final class BedrockCommandSource {

    private BedrockCommandSource() {}

    public static CommandSource console() {
        return new CommandSource() {
            @Override public void sendMessage(Component text) { System.out.println(ANSIComponentSerializer.ansi().serialize(text)); }
            @Override public boolean hasPermission(String permission) { return true; }
        };
    }

    /** In-game source: the add-on only forwards /bluemap from operators (permission level GameDirectors). */
    public static CommandSource player(BedrockPlayer player, BdsConsole console) {
        return new CommandSource() {
            @Override
            public void sendMessage(Component text) {
                // Bedrock understands legacy section-sign color codes in rawtext.
                String legacy = LegacyComponentSerializer.legacySection().serialize(text);
                JsonObject part = new JsonObject();
                part.addProperty("text", legacy);
                JsonArray raw = new JsonArray();
                raw.add(part);
                JsonObject msg = new JsonObject();
                msg.add("rawtext", raw);
                console.command("tellraw \"" + player.getRawName().replace("\"", "") + "\" " + msg);
            }
            @Override public boolean hasPermission(String permission) { return true; }
            @Override public Optional<ServerWorld> getWorld() { return Optional.ofNullable(player.getWorld()); }
            @Override public Optional<Vector3d> getPosition() { return Optional.of(player.getPosition()); }
        };
    }
}
