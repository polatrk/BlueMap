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

import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;

/** bedrock.conf: how to reach the Bedrock Dedicated Server. */
public record BedrockConfig(
        Path bdsFolder,
        String consoleMode,
        String bedrockCommand,
        String consoleCommand,
        String commandTemplate,
        int saveInterval,
        int addonPort,
        String addonToken
) {

    public static BedrockConfig load(Path configFolder, Path defaultBdsFolder) throws IOException {
        Path file = configFolder.resolve("bedrock.conf");
        if (!Files.exists(file)) {
            Files.createDirectories(configFolder);
            try (InputStream in = BedrockConfig.class.getResourceAsStream("/de/bluecolored/bluemap/bedrock/bedrock.conf")) {
                if (in == null) throw new IOException("missing bedrock.conf template");
                byte[] token = new byte[16];
                new SecureRandom().nextBytes(token);
                String template = new String(in.readAllBytes(), StandardCharsets.UTF_8)
                        .replace("${bds-folder}", defaultBdsFolder.toAbsolutePath().normalize().toString().replace("\\", "\\\\"))
                        .replace("${addon-token}", HexFormat.of().formatHex(token));
                Files.writeString(file, template);
            }
        }
        ConfigurationNode n = HoconConfigurationLoader.builder().path(file).build().load();
        String mode = n.node("console-mode").getString("wrapper").toLowerCase();
        if (!mode.equals("none") && !mode.equals("wrapper") && !mode.equals("external"))
            throw new IOException("bedrock.conf: console-mode must be none, wrapper or external");
        return new BedrockConfig(
                configFolder.resolve(n.node("bds-folder").getString(".")).normalize(),
                mode,
                n.node("bedrock-command").getString(""),
                n.node("console-command").getString(""),
                n.node("command-template").getString(""),
                n.node("save-interval").getInt(30),
                n.node("addon-port").getInt(8101),
                n.node("addon-token").getString("")
        );
    }
}
