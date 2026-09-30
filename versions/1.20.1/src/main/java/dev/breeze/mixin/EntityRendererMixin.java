package dev.breeze.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.breeze.BreezeTag;
import dev.breeze.BreezeUsers;
import dev.breeze.modules.NameTagScale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.joml.Matrix4f;

import java.util.UUID;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EntityRenderer.class)
public class EntityRendererMixin {

    /**
     * The badge line above a player's name.
     *
     * Both the official badge and the player's own tag are drawn on one line and
     * centred as a unit, so the pair stays centred over the head rather than the
     * first part being centred and the second hanging off to one side. Each
     * piece keeps its own colour, which is why they are drawn as separate
     * segments across a shared measured width instead of one string.
     */
    @Inject(method = "renderNameTag", at = @At("HEAD"))
    private void breeze$breezeTag(Entity entity, Component displayName, PoseStack poseStack, MultiBufferSource buffer, int packedLight, CallbackInfo ci) {
        if (!(entity instanceof Player)) return;
        UUID id = entity.getUUID();
        if (!BreezeTag.hasTag(id)) return;
        try {
            Minecraft mc = Minecraft.getInstance();
            Font font = mc.font;

            String tag = BreezeTag.text(id);
            String custom = BreezeTag.custom(id);
            boolean hasCustom = custom != null && !custom.isEmpty();

            int tagW = font.width(tag);
            int customW = hasCustom ? font.width(" " + custom) : 0;

            poseStack.pushPose();
            poseStack.translate(0.0, entity.getBbHeight() + 0.5 + 0.28, 0.0);
            poseStack.mulPose(mc.getEntityRenderDispatcher().cameraOrientation());
            poseStack.scale(-0.025f, -0.025f, 0.025f);
            Matrix4f matrix = poseStack.last().pose();

            float x = -(tagW + customW) / 2.0f;
            font.drawInBatch(tag, x, 0.0f, BreezeTag.color(id), false, matrix, buffer,
                    Font.DisplayMode.NORMAL, 0, packedLight);
            if (hasCustom) {
                font.drawInBatch(" " + custom, x + tagW, 0.0f, BreezeTag.customColor(id), false, matrix, buffer,
                        Font.DisplayMode.NORMAL, 0, packedLight);
            }
            poseStack.popPose();
        } catch (Throwable ignored) {}
    }

    @Inject(method = "renderNameTag", at = @At("HEAD"))
    private void breeze$nameScalePush(Entity entity, Component displayName, PoseStack poseStack, MultiBufferSource buffer, int packedLight, CallbackInfo ci) {
        if (NameTagScale.active()) {
            poseStack.pushPose();
            float s = NameTagScale.scale();
            poseStack.scale(s, s, s);
        }
    }

    @Inject(method = "renderNameTag", at = @At("RETURN"))
    private void breeze$nameScalePop(Entity entity, Component displayName, PoseStack poseStack, MultiBufferSource buffer, int packedLight, CallbackInfo ci) {
        if (NameTagScale.active()) {
            poseStack.popPose();
        }
    }
}
