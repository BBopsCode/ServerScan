package com.goofy.serverscan;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class ScannerController {
    public static List<ServerEntry> queue;
    public static int currentIndex = 0;
    public static boolean active = false;
    
    private static int delayTicks = 0;
    private static int timeoutTicks = 0;
    public static volatile boolean intentionalDisconnect = false; 
    public static Path jsonPath;

    public static String getProgressText() {
        if (!active || queue == null) return "Scan JSON";
        return "Scanning " + (currentIndex + 1) + " / " + queue.size();
    }

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!active) return;

            // 1. THE GHOST-WORLD FIX: client.screen MUST be null (meaning we are physically in-game with no menus open)
            if (client.level != null && client.screen == null && !intentionalDisconnect) {
                if (client.level.getChunkSource().getLoadedChunksCount() > 0) {
                    registerTrueHit(client);
                    return;
                }
            }

            // 2. INSTANT FAILURE WATCHDOG
            if (client.screen != null && delayTicks == 0 && !intentionalDisconnect) {
                String screenName = client.screen.getClass().getSimpleName();
                
                if (screenName.contains("Disconnected") || screenName.contains("JoinMultiplayer")) {
                    String kickReason = client.screen.getTitle().getString();
                    handleFailure(kickReason.isEmpty() ? "Connection Refused / getsockopt" : "Kicked: " + kickReason);
                    return;
                }
            }

            // 3. IMPATIENT TIMEOUT (10 Seconds)
            if (timeoutTicks > 0 && !intentionalDisconnect) {
                timeoutTicks--;
                if (timeoutTicks == 0) {
                    handleFailure("Timeout (No chunks loaded / Ghosted)");
                    return;
                }
            }

            // 4. QUEUE NAVIGATION
            if (delayTicks > 0) {
                delayTicks--;
                if (delayTicks == 0) runNext(client);
            }
        });
    }

    private static void handleFailure(String reason) {
        intentionalDisconnect = true; 
        Minecraft client = Minecraft.getInstance();
        
        ServerEntry entry = queue.get(currentIndex);
        entry.available = false;
        entry.reason = reason;
        System.out.println("[Scanner] Skipping " + entry.ip + " - " + reason);

        client.execute(() -> {
            timeoutTicks = 0;
            performSafeExit(client);
        });
    }

    public static void registerTrueHit(Minecraft client) {
        intentionalDisconnect = true; 
        
        client.execute(() -> {
            ServerEntry entry = queue.get(currentIndex);
            entry.available = true;
            entry.reason = "Success (Chunks Loaded)";
            System.out.println("[Scanner] TRUE HIT: " + entry.ip);

            if (client.keyboardHandler != null) {
                client.keyboardHandler.setClipboard(entry.ip);
            }

            ServerList serverList = new ServerList(client);
            serverList.load();
            boolean exists = false;
            for (int i = 0; i < serverList.size(); i++) {
                if (serverList.get(i).ip.equals(entry.ip)) { exists = true; break; }
            }
            if (!exists) {
                serverList.add(new ServerData("Hit: " + entry.ip, entry.ip, ServerData.Type.OTHER), false);
                serverList.save();
            }

            timeoutTicks = 0;
            performSafeExit(client);
        });
    }

    private static void performSafeExit(Minecraft client) {
        // Break the network connection
        if (client.getConnection() != null) {
            client.getConnection().getConnection().disconnect(Component.literal("Scanner: OK"));
        }
        
        // 1.21.11 Mojang Mappings: Correctly dump the world and set the screen simultaneously
        client.disconnect(new JoinMultiplayerScreen(new TitleScreen()), false);
        
        // Wait 0.5 seconds (10 ticks) before firing the next IP
        scheduleNext(10); 
    }

    public static void start(Path path) {
        jsonPath = path;
        queue = JsonUtil.load(path);
        if (queue.isEmpty()) return;

        Minecraft client = Minecraft.getInstance();
        ServerList serverList = new ServerList(client);
        serverList.load();
        
        List<ServerEntry> freshQueue = new ArrayList<>();
        for (ServerEntry entry : queue) {
            boolean exists = false;
            for (int i = 0; i < serverList.size(); i++) {
                if (serverList.get(i).ip.equalsIgnoreCase(entry.ip)) { exists = true; break; }
            }
            if (!exists) freshQueue.add(entry);
        }
        queue = freshQueue;
        JsonUtil.save(jsonPath, queue);

        if (queue.isEmpty()) { active = false; return; }

        currentIndex = 0;
        active = true;
        delayTicks = 20;

        String timeStr = new SimpleDateFormat("hh:mm a").format(new Date());
        serverList.add(new ServerData("--- Scan @ " + timeStr + " ---", "1.1.1.1", ServerData.Type.OTHER), false);
        serverList.save();
        
        client.setScreen(new JoinMultiplayerScreen(new TitleScreen()));
    }

    public static void runNext(Minecraft client) {
        if (active && currentIndex < queue.size()) {
            intentionalDisconnect = false;
            timeoutTicks = 200; // 10 seconds timeout
            
            ServerEntry current = queue.get(currentIndex);
            String formattedIp = current.ip + (current.port != 25565 && current.port != 0 ? ":" + current.port : "");
            ServerAddress address = ServerAddress.parseString(formattedIp);
            
            ConnectScreen.startConnecting(new TitleScreen(), client, address, new ServerData("Scan", formattedIp, ServerData.Type.OTHER), false, null);
        } else {
            active = false;
            JsonUtil.save(jsonPath, queue);
        }
    }

    public static void scheduleNext(int ticks) {
        JsonUtil.save(jsonPath, queue);
        currentIndex++;
        delayTicks = ticks;
    }
}
