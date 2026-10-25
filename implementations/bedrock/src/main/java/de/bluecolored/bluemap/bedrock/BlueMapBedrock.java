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

import de.bluecolored.bluecommands.ParseResult;
import de.bluecolored.bluemap.common.commands.CommandExecutor;
import de.bluecolored.bluemap.common.commands.Commands;
import de.bluecolored.bluemap.common.plugin.Plugin;
import de.bluecolored.bluemap.common.serverinterface.CommandSource;
import de.bluecolored.bluemap.common.serverinterface.ServerWorld;
import de.bluecolored.bluemap.core.BlueMap;
import de.bluecolored.bluemap.core.logger.Logger;
import de.bluecolored.bluemap.core.resources.pack.datapack.DataPack;
import de.bluecolored.bluemap.core.util.Key;
import org.apache.commons.cli.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * BlueMap for Bedrock Dedicated Server: runs the full BlueMap plugin (web server, live updates,
 * player markers, marker API, commands) next to BDS. The world is read in place, read-only.
 */
public class BlueMapBedrock {

    private final BedrockConfig config;
    private final Path configFolder;
    private final BdsConsole console;
    private final PlayerTracker tracker;
    private final Plugin plugin;
    private final de.bluecolored.bluecommands.Command<CommandSource, Object> commands;
    private final CommandExecutor executor;
    private AddonEndpoint endpoint;
    private Timer saveTimer;

    public BlueMapBedrock(Path configFolder, BedrockConfig config) throws IOException {
        this.config = config;
        this.configFolder = configFolder;
        this.console = new BdsConsole(config);

        Path worldFolder = config.bdsFolder().resolve("worlds").resolve(levelName(config.bdsFolder()));
        Map<String, ServerWorld> worlds = new LinkedHashMap<>();
        for (Key dim : List.of(DataPack.DIMENSION_OVERWORLD, DataPack.DIMENSION_THE_NETHER, DataPack.DIMENSION_THE_END)) {
            worlds.put(PlayerTracker.scriptId(dim), new BedrockServerWorld(worldFolder, dim, console));
        }
        this.tracker = new PlayerTracker(worlds);
        this.console.addLineHandler(tracker::handleConsoleLine);
        this.plugin = new Plugin("bedrock", new BedrockServerInterface(configFolder, new ArrayList<>(worlds.values()), tracker));
        this.commands = Commands.create(plugin);
        this.executor = new CommandExecutor(plugin);
        tracker.setCommandHandler((player, args) ->
                execute(BedrockCommandSource.player(player, console), args));
    }

    private static String levelName(Path bdsFolder) throws IOException {
        Path props = bdsFolder.resolve("server.properties");
        if (!Files.exists(props)) throw new IOException("server.properties not found in " + bdsFolder + " (check bds-folder in bedrock.conf)");
        for (String line : Files.readAllLines(props, StandardCharsets.UTF_8)) {
            String l = line.trim();
            if (l.startsWith("level-name=")) return l.substring("level-name=".length()).trim();
        }
        return "Bedrock level";
    }

    public void start() throws IOException, InterruptedException {
        CountDownLatch started = new CountDownLatch(1);
        console.start(started::countDown);
        // The world folder may only exist once BDS created it.
        if (config.consoleMode().equals("wrapper") && !started.await(5, TimeUnit.MINUTES))
            Logger.global.logWarning("BDS did not report \"Server started.\" yet, continuing anyway.");
        if (config.addonPort() > 0) endpoint = new AddonEndpoint(config.addonPort(), config.addonToken(), tracker);

        plugin.load();
        if (plugin.isLoaded()) {
            plugin.getSkinUpdater().setSkinProvider(new GeyserSkinProvider(tracker));
        }

        if (console.hasConsole() && config.saveInterval() > 0) {
            saveTimer = new Timer("BlueMap-Bedrock-Save", true);
            saveTimer.schedule(new TimerTask() {
                @Override public void run() { console.flushWorld(30000); }
            }, config.saveInterval() * 1000L, config.saveInterval() * 1000L);
        }
    }

    /** Runs a BlueMap command line ("bluemap ..." prefix optional). */
    public void execute(CommandSource source, String input) {
        String line = input.trim();
        if (!line.startsWith("bluemap")) line = ("bluemap " + line).trim();
        ParseResult<CommandSource, Object> result = commands.parse(source, line);
        CommandExecutor.ExecutionResult r = executor.execute(result);
        if (r.parseFailure()) source.sendMessage(net.kyori.adventure.text.Component.text("Unknown BlueMap command. Try: bluemap help"));
    }

    /**
     * Terminal input: "bluemap ..." goes to BlueMap, everything else to the BDS console.
     * Returns true when the user asked to quit, false when the input ended (no terminal attached).
     */
    public boolean readTerminal() throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        CommandSource source = BedrockCommandSource.console();
        String line;
        while ((line = in.readLine()) != null) {
            String l = line.trim();
            if (l.isEmpty()) continue;
            if (l.equals("bluemap") || l.startsWith("bluemap ") || l.startsWith("/bluemap")) {
                execute(source, l.replaceFirst("^/", ""));
            } else if (config.consoleMode().equals("wrapper")) {
                // We own BDS: this terminal is its console ("stop" stops BDS, then Ctrl+C quits).
                console.command(l);
            } else if (l.equals("stop") || l.equals("exit") || l.equals("quit")) {
                return true; // quits BlueMap only, never the external server
            } else if (config.consoleMode().equals("external") && l.startsWith("bds ")) {
                console.command(l.substring(4));
            } else {
                System.out.println("Commands: \"bluemap ...\", \"stop\" (quit BlueMap)" +
                        (config.consoleMode().equals("external") ? ", \"bds <command>\" (send to the server)" : ""));
            }
        }
        return false;
    }

    public void shutdown() {
        if (saveTimer != null) saveTimer.cancel();
        if (endpoint != null) endpoint.stop();
        try { plugin.unload(); } catch (Exception ex) { Logger.global.logError("Failed to unload BlueMap", ex); }
        console.stop();
    }

    /** Writes the add-on packs and the steps to enable them (nothing is written to the server). */
    static void exportAddon(Path folder) throws IOException {
        Files.createDirectories(folder);
        for (String variant : List.of("console", "net")) {
            try (var in = BlueMapBedrock.class.getResourceAsStream("/de/bluecolored/bluemap/bedrock/addon/bluemap-" + variant + ".mcpack")) {
                if (in == null) throw new IOException("pack " + variant + " missing from the jar");
                Files.copy(in, folder.resolve("bluemap-" + variant + ".mcpack"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
        String ids;
        try (var in = BlueMapBedrock.class.getResourceAsStream("/de/bluecolored/bluemap/bedrock/addon/pack-ids.json")) {
            ids = new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8);
        }
        System.out.println("Wrote bluemap-console.mcpack and bluemap-net.mcpack to " + folder.toAbsolutePath());
        System.out.println("Pack ids: " + ids.replaceAll("\\s+", " "));
        System.out.println("""
                To enable (see README): unzip one pack into <bds>/development_behavior_packs/bluemap, add
                  { "pack_id": "<header uuid>", "version": [x, y, z] }
                to worlds/<level>/world_behavior_packs.json (on a backup first) and restart BDS.
                console variant: set content-log-console-output-enabled=true in server.properties.
                net variant: enable the Beta APIs experiment and create config/<script uuid>/permissions.json,
                variables.json and secrets.json.""");
    }

    public static void main(String[] args) {
        Options options = new Options();
        options.addOption("c", "config", true, "BlueMap config folder (default: ./bluemap)");
        options.addOption("b", "bds", true, "Bedrock Dedicated Server folder, used when creating bedrock.conf (default: .)");
        options.addOption("e", "export-addon", true, "Write the BlueMap behavior packs (.mcpack) to this folder and exit");
        options.addOption("h", "help", false, "Show this help");
        CommandLine cmd;
        try {
            cmd = new DefaultParser().parse(options, args);
        } catch (ParseException ex) {
            System.err.println(ex.getMessage());
            new HelpFormatter().printHelp("java -jar bluemap-bedrock.jar", options);
            System.exit(1);
            return;
        }
        if (cmd.hasOption("h")) {
            new HelpFormatter().printHelp("java -jar bluemap-bedrock.jar", options);
            return;
        }
        if (cmd.hasOption("e")) {
            try {
                exportAddon(Path.of(cmd.getOptionValue("e")));
            } catch (IOException ex) {
                System.err.println("Export failed: " + ex);
                System.exit(1);
            }
            return;
        }
        Path configFolder = Path.of(cmd.getOptionValue("c", "bluemap"));
        Path bds = Path.of(cmd.getOptionValue("b", "."));
        Logger.global.logInfo("BlueMap " + BlueMap.VERSION + " for Bedrock Dedicated Server");
        try {
            BlueMapBedrock bm = new BlueMapBedrock(configFolder, BedrockConfig.load(configFolder, bds));
            Runtime.getRuntime().addShutdownHook(new Thread(bm::shutdown, "BlueMap-Shutdown"));
            bm.start();
            if (bm.readTerminal()) System.exit(0);
            // No terminal (service, nohup, docker without -i): keep running until stopped by a signal.
            Logger.global.logInfo("No console input attached, running until stopped (SIGTERM / Ctrl+C).");
            new CountDownLatch(1).await();
        } catch (Exception ex) {
            Logger.global.logError("BlueMap failed to start", ex);
            System.exit(1);
        }
    }
}
