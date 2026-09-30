package dev.breeze.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.breeze.BreezeTag;
import dev.breeze.modules.NameTagScale;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

/**
 * 26.1 (name tags are submitted through submitNameDisplay); as from
 * 1.21.2, renderers draw from a render state filled in advance, so
 * the entity is no longer at hand when the name tag is drawn. The Breeze badge
 * (and a player's own tag) therefore goes into the state's name tag when it is
 * filled, as coloured text before the name, rather than as a separate line
 * above it as on 1.20.x.
 */
@Mixin(EntityRenderer.class)
public class EntityRendererMixin {

    @Inject(method = "extractRenderState", at = @At("RETURN"))
    private void breeze$breezeTag(Entity entity, EntityRenderState state, float partialTick, CallbackInfo ci) {
        if (!(entity instanceof Player) || state.nameTag == null) return;
        UUID id = entity.getUUID();
        if (!BreezeTag.hasTag(id)) return;
        try {
            MutableComponent line = Component.empty()
                    .append(Component.literal(BreezeTag.text(id) + " ")
                            .withStyle(st -> st.withColor(TextColor.fromRgb(BreezeTag.color(id) & 0xFFFFFF))));
            String custom = BreezeTag.custom(id);
            if (custom != null && !custom.isEmpty()) {
                line.append(Component.literal(custom + " ")
                        .withStyle(st -> st.withColor(TextColor.fromRgb(BreezeTag.customColor(id) & 0xFFFFFF))));
            }
            state.nameTag = line.append(state.nameTag);
        } catch (Throwable ignored) {}
    }

    @Inject(method = "submitNameDisplay(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V", at = @At("HEAD"))
    private void breeze$nameScalePush(EntityRenderState state, PoseStack poseStack, SubmitNodeCollector nodes, CameraRenderState camera, CallbackInfo ci) {
        if (NameTagScale.active()) {
            poseStack.pushPose();
            float s = NameTagScale.scale();
            poseStack.scale(s, s, s);
        }
    }

    @Inject(method = "submitNameDisplay(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V", at = @At("RETURN"))
    private void breeze$nameScalePop(EntityRenderState state, PoseStack poseStack, SubmitNodeCollector nodes, CameraRenderState camera, CallbackInfo ci) {
        if (NameTagScale.active()) {
            poseStack.popPose();
        }
    }
}
