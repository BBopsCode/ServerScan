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

    protected JoinMultiplayerScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void addScanButton(CallbackInfo ci) {
        this.scanButton = Button.builder(Component.literal(ScannerController.getProgressText()), button -> {
            if (!ScannerController.active) {
                Path path = Minecraft.getInstance().gameDirectory.toPath().resolve("servers.json");
                ScannerController.start(path);
            } else {
                ScannerController.active = false;
                button.setMessage(Component.literal("Scan Cancelled"));
            }
        })
        .bounds(5, 5, 120, 20)
        .build();
        
        this.addRenderableWidget(this.scanButton);
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void updateButtonText(CallbackInfo ci) {
        if (this.scanButton != null && ScannerController.active) {
            this.scanButton.setMessage(Component.literal(ScannerController.getProgressText()));
        }
    }
}
