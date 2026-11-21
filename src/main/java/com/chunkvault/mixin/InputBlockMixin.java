package com.chunkvault.mixin;

import com.chunkvault.state.UiInputGuard;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardInput.class)
public abstract class InputBlockMixin extends ClientInput {

    @Inject(method = "tick", at = @At("TAIL"))
    private void chunkvault$blockInputWhenMenuOpen(CallbackInfo ci) {
        if (UiInputGuard.isFullBlock()) {
            this.keyPresses = Input.EMPTY;
            this.moveVector = Vec2.ZERO;
            KeyMapping.releaseAll();
            return;
        }
        if (UiInputGuard.isShiftBlocked() && this.keyPresses.shift()) {
            this.keyPresses = new Input(
                    this.keyPresses.forward(),
                    this.keyPresses.backward(),
                    this.keyPresses.left(),
                    this.keyPresses.right(),
                    this.keyPresses.jump(),
                    false,
                    this.keyPresses.sprint()
            );
        }
    }
}
