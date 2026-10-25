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

import com.sun.net.httpserver.HttpServer;
import de.bluecolored.bluemap.core.logger.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * HTTP endpoint for the "net" variant of the add-on (Script API @minecraft/server-net):
 * {@code POST /players} with {@code Authorization: Bearer <token>} and a JSON message body.
 */
public final class AddonEndpoint {

    private final HttpServer server;

    public AddonEndpoint(int port, String token, PlayerTracker tracker) throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/players", exchange -> {
            try (exchange) {
                if (!"POST".equals(exchange.getRequestMethod())) { exchange.sendResponseHeaders(405, -1); return; }
                String auth = exchange.getRequestHeaders().getFirst("Authorization");
                String given = auth == null ? "" : auth.startsWith("Bearer ") ? auth.substring(7) : auth;
                if (token.isEmpty() || !MessageDigest.isEqual(given.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8))) {
                    exchange.sendResponseHeaders(401, -1);
                    return;
                }
                byte[] body;
                try (InputStream in = exchange.getRequestBody()) { body = in.readNBytes(256 * 1024); }
                boolean ok = tracker.handle(new String(body, StandardCharsets.UTF_8), "http");
                exchange.sendResponseHeaders(ok ? 204 : 400, -1);
            }
        });
        server.start();
        Logger.global.logInfo("Add-on endpoint listening on port " + port);
    }

    public void stop() { server.stop(0); }
}
