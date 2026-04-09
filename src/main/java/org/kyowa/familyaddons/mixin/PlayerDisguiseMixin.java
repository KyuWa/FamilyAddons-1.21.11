package org.kyowa.familyaddons.mixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.entity.EntityRenderManager;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.client.render.entity.state.LivingEntityRenderState;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.client.render.state.CameraRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.mob.ZombieEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.client.model.Model;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import org.kyowa.familyaddons.EntityRefAccessor;
import org.kyowa.familyaddons.features.PlayerDisguise;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

@Mixin(LivingEntityRenderer.class)
public abstract class PlayerDisguiseMixin<T extends LivingEntity,
        S extends LivingEntityRenderState,
        M extends Model<?>> {

    private static final Map<Class<?>, Method> createRenderStateCache = new HashMap<>();

    private static LivingEntity cachedMob = null;
    private static String cachedMobId = null;
    private static Boolean cachedBaby = null;
    private static LivingEntityRenderState cachedMobState = null;
    private static Class<?> cachedRendererClass = null;

    @Inject(
            method = "render(Lnet/minecraft/client/render/entity/state/LivingEntityRenderState;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;Lnet/minecraft/client/render/state/CameraRenderState;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void onRender(S state, MatrixStack matrixStack, OrderedRenderCommandQueue queue, CameraRenderState cameraRenderState, CallbackInfo ci) {
        if (!PlayerDisguise.INSTANCE.isEnabled()) return;
        if (!(state instanceof PlayerEntityRenderState playerState)) return;

        MinecraftClient client = MinecraftClient.getInstance();
        LivingEntity entity = ((EntityRefAccessor) state).familyaddons$getEntity();
        if (!(entity instanceof PlayerEntity player)) return;

        int scope = PlayerDisguise.INSTANCE.getScope();
        boolean isSelf = player == client.player;
        if (scope == 0 && !isSelf) return;

        if (player.isInvisibleTo(client.player)) return;

        String mobId = PlayerDisguise.INSTANCE.getMobId();
        Identifier id = Identifier.tryParse(mobId);
        if (id == null) return;

        EntityType<?> type = Registries.ENTITY_TYPE.get(id);
        if (type == null || type == EntityType.PLAYER) return;

        boolean baby = PlayerDisguise.INSTANCE.isBaby();

        // Recreate mob if ID or baby changed
        if (cachedMob == null || !mobId.equals(cachedMobId) || baby != Boolean.TRUE.equals(cachedBaby)) {
            try {
                cachedMob = (LivingEntity) type.create(player.getEntityWorld(), SpawnReason.COMMAND);
            } catch (Exception e) {
                cachedMob = null;
                return;
            }
            if (cachedMob == null) return;
            cachedMobId = mobId;
            cachedBaby = baby;
            cachedMobState = null;
            cachedRendererClass = null;

            if (baby) {
                if (cachedMob instanceof AnimalEntity animal) {
                    animal.setBreedingAge(-24000);
                } else if (cachedMob instanceof ZombieEntity zombie) {
                    zombie.setBaby(true);
                }
            }
        }

        LivingEntity mob = cachedMob;
        // Sync position and rotation — only use current-tick fields (no prev* in 1.21.11)
        mob.setPos(player.getX(), player.getY(), player.getZ());
        mob.setYaw(player.getYaw());
        mob.setPitch(player.getPitch());
        mob.bodyYaw = player.bodyYaw;
        mob.headYaw = player.headYaw;

        EntityRenderManager dispatcher = client.getEntityRenderDispatcher();

        // Walk the class hierarchy to find createRenderState() — a no-arg method returning LivingEntityRenderState
        Method createRenderState = createRenderStateCache.get(mob.getClass());
        if (createRenderState == null) {
            EntityRenderer<?, ?> rendererRaw = dispatcher.getRenderer(mob);
            if (rendererRaw == null) return;
            Class<?> cls = rendererRaw.getClass();
            outer:
            while (cls != null) {
                for (Method m : cls.getDeclaredMethods()) {
                    if (m.getParameterCount() == 0 &&
                            LivingEntityRenderState.class.isAssignableFrom(m.getReturnType())) {
                        m.setAccessible(true);
                        createRenderState = m;
                        createRenderStateCache.put(mob.getClass(), m);
                        break outer;
                    }
                }
                cls = cls.getSuperclass();
            }
            if (createRenderState == null) return;
        }

        EntityRenderer<?, ?> rendererRaw = dispatcher.getRenderer(mob);
        if (rendererRaw == null) return;

        // We need the renderer typed as EntityRenderer<LivingEntity, LivingEntityRenderState>
        // Use raw types via reflection to avoid the generic capture error
        @SuppressWarnings("rawtypes")
        EntityRenderer renderer = rendererRaw;

        // Recreate mob render state only if renderer class changed
        if (cachedMobState == null || renderer.getClass() != cachedRendererClass) {
            try {
                cachedMobState = (LivingEntityRenderState) createRenderState.invoke(renderer);
                cachedRendererClass = renderer.getClass();
            } catch (Exception e) {
                return;
            }
        }

        LivingEntityRenderState mobState = cachedMobState;

        try {
            //noinspection unchecked
            renderer.updateRenderState(mob, mobState, 1.0f);
        } catch (Exception e) {
            return;
        }

        // Copy animation fields from player render state so the mob moves like the player
        mobState.bodyYaw                  = playerState.bodyYaw;
        mobState.relativeHeadYaw          = playerState.relativeHeadYaw;
        mobState.pitch                    = playerState.pitch;
        mobState.limbSwingAnimationProgress = playerState.limbSwingAnimationProgress;
        mobState.limbSwingAmplitude       = playerState.limbSwingAmplitude;
        mobState.age                      = playerState.age;
        mobState.invisible                = false;
        mobState.invisibleToPlayer        = false;
        mobState.light                    = playerState.light;
        mobState.x                        = playerState.x;
        mobState.y                        = playerState.y;
        mobState.z                        = playerState.z;

        try {
            //noinspection unchecked
            renderer.render(mobState, matrixStack, queue, cameraRenderState);
        } catch (Exception e) {
            return;
        }

        // Render the player's name tag on top
        if (playerState.displayName != null && playerState.nameLabelPos != null) {
            queue.submitLabel(
                    matrixStack,
                    playerState.nameLabelPos,
                    0,
                    playerState.displayName,
                    !playerState.sneaking,
                    playerState.light,
                    playerState.squaredDistanceToCamera,
                    cameraRenderState
            );
        }

        ci.cancel();
    }
}