package com.goofy.serverscan;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;

public class ServerScanClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ScannerController.init();

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommandManager.literal("scan")
                .executes(context -> {
                    Path path = Minecraft.getInstance().gameDirectory.toPath().resolve("servers.json");
                    ScannerController.start(path);
                    context.getSource().sendFeedback(Component.literal("§a[Scanner] Started testing servers..."));
                    return 1;
                }));
        });
    }
}
