package com.goofy.serverscan.mixin;

import com.goofy.serverscan.ScannerController;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.file.Path;

@Mixin(JoinMultiplayerScreen.class)
public abstract class JoinMultiplayerScreenMixin extends Screen {

    @Unique
    private Button scanButton;
    @Unique
    private Button sortButton;

    protected JoinMultiplayerScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void addCustomButtons(CallbackInfo ci) {
        // Your existing Scanner Button
        this.scanButton = Button.builder(Component.literal(ScannerController.getProgressText()), button -> {
            if (!ScannerController.active) {
                ScannerController.start(Minecraft.getInstance().gameDirectory.toPath().resolve("servers.json"));
            } else {
                ScannerController.active = false;
            }
        }).bounds(5, 5, 100, 20).build();

        // THE NEW FAST-SORT BUTTON
        this.sortButton = Button.builder(Component.literal("Fast Ping & Sort"), button -> {
            ScannerController.fastPingAndSort(Minecraft.getInstance());
        }).bounds(110, 5, 100, 20).build();
        
        this.addRenderableWidget(this.scanButton);
        this.addRenderableWidget(this.sortButton);
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void updateUI(CallbackInfo ci) {
        if (this.scanButton != null) {
            this.scanButton.setMessage(Component.literal(ScannerController.getProgressText()));
        }
    }
}
