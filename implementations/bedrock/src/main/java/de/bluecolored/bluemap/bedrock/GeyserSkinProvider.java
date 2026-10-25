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

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.bluecolored.bluemap.api.plugin.SkinProvider;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

/**
 * Bedrock skins through GeyserMC's public API (skins of Bedrock players by XUID, converted to the
 * Java skin format). The XUID is read from the BDS console ("Player connected: name, xuid: ...").
 */
public final class GeyserSkinProvider implements SkinProvider {

    private final PlayerTracker tracker;

    public GeyserSkinProvider(PlayerTracker tracker) {
        this.tracker = tracker;
    }

    @Override
    public Optional<BufferedImage> load(UUID playerUUID) throws IOException {
        Optional<String> xuid = tracker.xuid(playerUUID);
        if (xuid.isEmpty()) return Optional.empty();
        JsonObject info;
        try (InputStream in = open("https://api.geysermc.org/v2/skin/" + xuid.get())) {
            info = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
        if (!info.has("texture_id")) return Optional.empty();
        try (InputStream in = open("https://textures.minecraft.net/texture/" + info.get("texture_id").getAsString())) {
            return Optional.ofNullable(ImageIO.read(in));
        }
    }

    private static InputStream open(String url) throws IOException {
        URLConnection c = URI.create(url).toURL().openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(10000);
        c.setRequestProperty("User-Agent", "BlueMap-Bedrock");
        return c.getInputStream();
    }
}
