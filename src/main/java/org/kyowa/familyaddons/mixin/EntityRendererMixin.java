package org.kyowa.familyaddons.mixin;

import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.state.EntityRenderState;
import net.minecraft.entity.Entity;
import org.kyowa.familyaddons.features.CorpseESP;
import org.kyowa.familyaddons.features.EntityHighlight;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EntityRenderer.class)
public class EntityRendererMixin {

    @Inject(method = "updateRenderState", at = @At("RETURN"))
    private void fa_updateRenderState(Entity entity, EntityRenderState state, float tickProgress, CallbackInfo ci) {
        int corpseColor = CorpseESP.INSTANCE.getOutlineColor(entity);
        if (corpseColor != 0) { state.outlineColor = corpseColor; return; }
        int highlightColor = EntityHighlight.INSTANCE.getOutlineColor(entity);
        if (highlightColor != 0) { state.outlineColor = highlightColor; }
    }
}
