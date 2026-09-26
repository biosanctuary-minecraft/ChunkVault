package com.chunkvault.mixin;

import com.chunkvault.state.LastInteraction;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * CHECK: on the client, right-clicking a block goes through
 * MultiPlayerGameMode#useItemOn(LocalPlayer, InteractionHand, BlockHitResult).
 * This has been the shape of this call since 1.13-ish; the class name itself
 * was "PlayerControllerMP" pre-1.17 under older Yarn/Mojmap snapshots, not
 * relevant here since we're targeting 26.2, but if this mixin fails to find
 * its target, search for whatever method fires GameMode-side when you
 * right-click a block (NOT the Item/Block use-callbacks — those fire too
 * late relative to the network packet Fabric API events, which is why we
 * hook here instead of using UseBlockCallback).
 */
@Mixin(MultiPlayerGameMode.class)
public abstract class BlockInteractionMixin {

    @Inject(method = "useItemOn", at = @At("HEAD"))
    private void chunkvault$onUseItemOn(LocalPlayer player, InteractionHand hand, BlockHitResult hitResult,
                                         CallbackInfoReturnable<InteractionResult> cir) {
        LastInteraction.set(hitResult.getBlockPos());
    }
}
