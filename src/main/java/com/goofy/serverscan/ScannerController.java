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

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

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
        
        // Count how many servers are left to search
        long remaining = queue.stream()
                .filter(s -> s.reason == null || s.reason.trim().isEmpty())
                .count();
                
        return "Searching (" + remaining + " left)";
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
        if (!active) return;

        // SKIP LOGIC: Rapidly skip any server that already has a result/reason
        while (currentIndex < queue.size()) {
            ServerEntry current = queue.get(currentIndex);
            
            // If reason is null or empty, it means we haven't tested this server yet
            if (current.reason == null || current.reason.trim().isEmpty()) {
                break; 
            }
            
            // Already searched, move to the next index immediately
            currentIndex++;
        }

        // Check if we reached the end of the list after skipping
        if (currentIndex < queue.size()) {
            intentionalDisconnect = false;
            timeoutTicks = 200; // 10 seconds timeout
            
            ServerEntry current = queue.get(currentIndex);
            String formattedIp = current.ip + (current.port != 25565 && current.port != 0 ? ":" + current.port : "");
            ServerAddress address = ServerAddress.parseString(formattedIp);
            
            System.out.println("[Scanner] Deep Searching New IP: " + formattedIp);
            ConnectScreen.startConnecting(new TitleScreen(), client, address, new ServerData("Scan", formattedIp, ServerData.Type.OTHER), false, null);
        } else {
            active = false;
            System.out.println("[Scanner] End of JSON reached. All new servers processed.");
            JsonUtil.save(jsonPath, queue);
        }
    }

    public static void scheduleNext(int ticks) {
        JsonUtil.save(jsonPath, queue);
        currentIndex++;
        delayTicks = ticks;
    }

    private static final ExecutorService pingerExecutor = Executors.newFixedThreadPool(50);

    public static void fastPingAndSort(Minecraft client) {
        ServerList serverList = new ServerList(client);
        serverList.load();
        int count = serverList.size();
        if (count <= 0) return;

        AtomicInteger completed = new AtomicInteger(0);
        List<ServerData> serversToPing = new ArrayList<>();
        for(int i = 0; i < count; i++) {
            serversToPing.add(serverList.get(i));
        }

        System.out.println("[Scanner] Mass pinging " + count + " servers...");

        for (ServerData data : serversToPing) {
            // Ignore the "Scan Header" timestamp servers
            if (data.ip.equals("1.1.1.1")) {
                completed.incrementAndGet();
                continue;
            }

            pingerExecutor.submit(() -> {
                int players = pingProtocol(data.ip);
                // We use the MOTD field temporarily to store the player count for sorting
                data.motd = Component.literal(String.valueOf(players)); 
                
                if (completed.incrementAndGet() >= count) {
                    // All pings done! Sort and Refresh UI on main thread
                    client.execute(() -> finalizeSort(client, serverList, serversToPing));
                }
            });
        }
    }

    private static void finalizeSort(Minecraft client, ServerList serverList, List<ServerData> servers) {
        // Sort: Highest players first. 
        // We parse the player count we hid in the MOTD field.
        servers.sort((a, b) -> {
            int p1 = 0, p2 = 0;
            try { p1 = Integer.parseInt(a.motd.getString()); } catch (Exception e) {}
            try { p2 = Integer.parseInt(b.motd.getString()); } catch (Exception e) {}
            return Integer.compare(p2, p1);
        });

        // Clear the actual server list and re-add them in order
        while(serverList.size() > 0) { serverList.remove(serverList.get(0)); }
        for (ServerData s : servers) {
            // Restore the MOTD or leave it for vanilla to update
            s.motd = Component.literal("Sorted by Scanner"); 
            serverList.add(s, false);
        }
        
        serverList.save();
        
        // Visual Refresh
        if (client.screen instanceof JoinMultiplayerScreen) {
            client.setScreen(new JoinMultiplayerScreen(new TitleScreen()));
        }
        System.out.println("[Scanner] Sort Complete.");
    }

    // A lightweight implementation of the Minecraft Ping Protocol
    private static int pingProtocol(String address) {
        String ip = address;
        int port = 25565;
        if (address.contains(":")) {
            String[] parts = address.split(":");
            ip = parts[0];
            try { port = Integer.parseInt(parts[1]); } catch (Exception e) {}
        }

        try (Socket socket = new Socket()) {
            socket.setSoTimeout(2000);
            socket.connect(new InetSocketAddress(ip, port), 2000);
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            DataInputStream in = new DataInputStream(socket.getInputStream());

            // Handshake
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            DataOutputStream handshake = new DataOutputStream(b);
            handshake.writeByte(0x00);
            writeVarInt(handshake, 767);
            writeString(handshake, ip);
            handshake.writeShort(port);
            writeVarInt(handshake, 1);
            writeVarInt(out, b.size());
            out.write(b.toByteArray());

            // Request
            out.writeByte(0x01);
            out.writeByte(0x00);

            // Response
            readVarInt(in); // Size
            readVarInt(in); // ID
            String json = readString(in);
            
            // Fast regex-free player count pull
            if (json.contains("\"online\":")) {
                String part = json.split("\"online\":")[1].split(",")[0].split("}")[0].trim();
                return Integer.parseInt(part);
            }
        } catch (Exception e) {}
        return 0;
    }

    // Helper methods for the protocol
    private static void writeVarInt(DataOutputStream out, int value) throws IOException {
        while ((value & -128) != 0) { out.writeByte(value & 127 | 128); value >>>= 7; }
        out.writeByte(value);
    }
    private static int readVarInt(DataInputStream in) throws IOException {
        int i = 0, j = 0;
        while (true) { int k = in.readByte(); i |= (k & 127) << j++ * 7; if ((k & 128) != 128) break; }
        return i;
    }
    private static void writeString(DataOutputStream out, String s) throws IOException {
        byte[] b = s.getBytes("UTF-8"); writeVarInt(out, b.length); out.write(b);
    }
    private static String readString(DataInputStream in) throws IOException {
        int len = readVarInt(in); byte[] b = new byte[len]; in.readFully(b); return new String(b, "UTF-8");
    }
}
