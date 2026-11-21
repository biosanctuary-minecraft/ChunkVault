package com.chunkvault.mixin;

import com.chunkvault.state.UiInputGuard;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Screen.class)
public abstract class ScreenGuardMixin {

    @Inject(method = "init()V", at = @At("HEAD"))
    private void chunkvault$onScreenInit(CallbackInfo ci) {
        UiInputGuard.onScreenOpened((Screen) (Object) this);
    }

    @Inject(method = "removed", at = @At("HEAD"))
    private void chunkvault$onScreenRemoved(CallbackInfo ci) {
        UiInputGuard.onScreenClosed((Screen) (Object) this);
    }
}
