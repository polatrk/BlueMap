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

import de.bluecolored.bluemap.core.logger.Logger;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Link to the BDS console.
 * <ul>
 *   <li>wrapper: BDS runs as a child process, its console is relayed to ours;</li>
 *   <li>external: BDS runs elsewhere, output is read from a command (e.g. docker logs -f) and
 *   commands are sent with a command template (e.g. docker exec ... screen -X stuff);</li>
 *   <li>none: no console at all.</li>
 * </ul>
 * Lines of the add-on ({@code [BEDMAP]...}) and of our own save cycles are consumed, not relayed.
 */
public final class BdsConsole {

    /** Console messages of the save hold/query/resume cycle (hidden while we run one). */
    private static final Pattern SAVE_NOISE = Pattern.compile(
            "Saving\\.\\.\\.|A previous save has not been completed|Data saved\\. Files are now ready to be copied|" +
            "Changes to the world are resumed|The command is already running");

    private final BedrockConfig config;
    private final List<Predicate<String>> lineHandlers = new CopyOnWriteArrayList<>();
    private volatile Process process;
    private volatile BufferedWriter stdin;
    private volatile boolean capturing = false;
    private volatile boolean expectFileList = false;
    private volatile Runnable onSaved;
    private volatile boolean running = false;

    public BdsConsole(BedrockConfig config) {
        this.config = config;
    }

    public boolean hasConsole() { return !config.consoleMode().equals("none"); }
    public boolean isRunning() { return running; }

    /** Adds a handler; returning true consumes the line (it is not relayed). */
    public void addLineHandler(Predicate<String> handler) { lineHandlers.add(handler); }

    public void start(Runnable onServerStarted) throws IOException {
        switch (config.consoleMode()) {
            case "wrapper" -> startWrapper(onServerStarted);
            case "external" -> startExternal();
            default -> { }
        }
    }

    private void startWrapper(Runnable onServerStarted) throws IOException {
        boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
        ProcessBuilder pb;
        if (!config.bedrockCommand().isBlank()) {
            pb = new ProcessBuilder(shell(config.bedrockCommand()));
        } else {
            Path exe = config.bdsFolder().resolve(windows ? "bedrock_server.exe" : "bedrock_server");
            if (!Files.exists(exe)) throw new IOException("BDS executable not found: " + exe);
            pb = new ProcessBuilder(exe.toString());
            if (!windows) pb.environment().put("LD_LIBRARY_PATH", config.bdsFolder().toString());
        }
        pb.directory(config.bdsFolder().toFile());
        pb.redirectErrorStream(true);
        Logger.global.logInfo("Starting Bedrock Dedicated Server: " + String.join(" ", pb.command()));
        process = pb.start();
        stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        running = true;
        readLines(process.getInputStream(), true, line -> {
            if (line.contains("Server started.")) onServerStarted.run();
        });
        process.onExit().thenRun(() -> {
            running = false;
            Logger.global.logWarning("Bedrock Dedicated Server stopped. The map stays available, use Ctrl+C to quit.");
        });
    }

    private void startExternal() {
        if (config.consoleCommand().isBlank() || config.commandTemplate().isBlank())
            throw new IllegalStateException("bedrock.conf: console-mode \"external\" needs console-command and command-template");
        Thread t = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Logger.global.logInfo("Reading the BDS console from: " + config.consoleCommand());
                    Process p = new ProcessBuilder(shell(config.consoleCommand())).redirectErrorStream(true).start();
                    process = p;
                    running = true;
                    readLinesBlocking(p.getInputStream(), false, l -> {});
                    p.waitFor();
                } catch (IOException ex) {
                    Logger.global.logWarning("Console command failed: " + ex);
                } catch (InterruptedException ex) {
                    return;
                }
                running = false;
                try { TimeUnit.SECONDS.sleep(5); } catch (InterruptedException ex) { return; }
            }
        }, "BlueMap-BDS-Console");
        t.setDaemon(true);
        t.start();
    }

    private void readLines(InputStream in, boolean relay, Consumer<String> also) {
        Thread t = new Thread(() -> readLinesBlocking(in, relay, also), "BlueMap-BDS-Output");
        t.setDaemon(true);
        t.start();
    }

    private void readLinesBlocking(InputStream in, boolean relay, Consumer<String> also) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                also.accept(line);
                if (!handle(line) && relay) System.out.println(line);
            }
        } catch (IOException ignored) {}
    }

    /** Returns true if the line was consumed. */
    private boolean handle(String line) {
        for (Predicate<String> h : lineHandlers) {
            try {
                if (h.test(line)) return true;
            } catch (RuntimeException ex) {
                Logger.global.logDebug("Console line handler failed: " + ex);
            }
        }
        if (expectFileList) {
            expectFileList = false;
            Runnable r = onSaved;
            if (r != null && line.contains(":")) { onSaved = null; r.run(); return true; }
        }
        if (capturing && SAVE_NOISE.matcher(line).find()) {
            if (line.contains("Data saved. Files are now ready to be copied")) expectFileList = true;
            return true;
        }
        return false;
    }

    /** Sends a command to the BDS console (no-op without console). */
    public void command(String command) {
        String cmd = command.replace("\r", "").replace("\n", " ");
        try {
            if (config.consoleMode().equals("wrapper")) {
                BufferedWriter w = stdin;
                if (w == null || !running) return;
                synchronized (w) { w.write(cmd); w.newLine(); w.flush(); }
            } else if (config.consoleMode().equals("external")) {
                String template = config.commandTemplate();
                if (template.contains("{cmd}")) {
                    new ProcessBuilder(shell(template.replace("{cmd}", cmd)))
                            .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
                } else {
                    // No placeholder: the command is written to the process' stdin (one line). This avoids
                    // any shell quoting issue, e.g. through ssh to a bridge script on the BDS host.
                    Process p = new ProcessBuilder(shell(template))
                            .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
                    try (Writer w = new OutputStreamWriter(p.getOutputStream(), StandardCharsets.UTF_8)) {
                        w.write(cmd);
                        w.write('\n');
                    }
                }
            }
        } catch (IOException ex) {
            Logger.global.logWarning("Failed to send '" + cmd + "' to BDS: " + ex);
        }
    }

    /**
     * Makes BDS flush the world to disk: {@code save hold}, {@code save query} until the files are
     * ready, then {@code save resume}. BlueMap reads the world in place, so nothing is copied.
     * Returns false if BDS did not answer in time.
     */
    public synchronized boolean flushWorld(long timeoutMs) {
        if (!hasConsole() || !running) return false;
        capturing = true;
        try {
            Object done = new Object();
            boolean[] saved = {false};
            onSaved = () -> { synchronized (done) { saved[0] = true; done.notifyAll(); } };
            command("save hold");
            long deadline = System.currentTimeMillis() + timeoutMs;
            synchronized (done) {
                while (!saved[0] && System.currentTimeMillis() < deadline) {
                    command("save query");
                    done.wait(1000);
                }
            }
            return saved[0];
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            onSaved = null;
            command("save resume");
            Thread t = new Thread(() -> {
                try { Thread.sleep(1500); } catch (InterruptedException ignored) {}
                capturing = false;
            });
            t.setDaemon(true);
            t.start();
        }
    }

    /** Stops BDS if we started it (never touches an external server). */
    public void stop() {
        Process p = process;
        if (p == null) return;
        if (config.consoleMode().equals("wrapper") && running) {
            command("stop");
            try {
                if (!p.waitFor(30, TimeUnit.SECONDS)) p.destroy();
            } catch (InterruptedException ex) {
                p.destroy();
            }
        } else if (config.consoleMode().equals("external")) {
            p.destroy();
        }
    }

    private static List<String> shell(String command) {
        boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
        return windows ? List.of("cmd", "/c", command) : List.of("sh", "-c", command);
    }
}
