package org.kyowa.familyaddons.features

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.MinecraftClient
import net.minecraft.client.render.VertexConsumer
import net.minecraft.client.render.VertexConsumerProvider
import net.minecraft.client.util.math.MatrixStack
import net.minecraft.entity.Entity
import net.minecraft.entity.LivingEntity
import net.minecraft.entity.decoration.ArmorStandEntity
import net.minecraft.util.math.Vec3d
import org.kyowa.familyaddons.COLOR_CODE_REGEX
import org.kyowa.familyaddons.config.FamilyConfigManager

object EntityHighlight {

    val highlighted = mutableSetOf<Entity>()
    private var tick = 0

    // Grace-period cache for tracer targets — prevents flicker when a mob dies and its
    // nameplate armor stand briefly resolves to a different nearby entity, or when a mob
    // falls out of the highlight list for a frame or two during death animation.
    private data class TracerState(var posX: Double, var posY: Double, var posZ: Double, var lastSeenMs: Long)
    private val tracerStates = mutableMapOf<Int, TracerState>()
    private const val TRACER_GRACE_MS = 500L

    private fun shouldScan(): Boolean {
        if (FamilyConfigManager.config.highlight.enabled) return true
        val bestiary = FamilyConfigManager.config.bestiary
        if (bestiary.zoneHighlightEnabled && bestiary.bestiaryZone != 0) return true
        if (bestiary.mobName.isNotBlank()) return true
        return false
    }

    private fun getNames(): List<String> {
        val names = mutableListOf<String>()
        if (FamilyConfigManager.config.highlight.enabled) {
            FamilyConfigManager.config.highlight.mobNames
                .split(",")
                .map { it.trim().lowercase() }
                .filter { it.isNotBlank() }
                .forEach { if (it !in names) names.add(it) }
        }
        val bestiaryMob = FamilyConfigManager.config.bestiary.mobName.trim().lowercase()
        if (bestiaryMob.isNotBlank() && bestiaryMob !in names) names.add(bestiaryMob)
        if (FamilyConfigManager.config.bestiary.zoneHighlightEnabled) {
            BestiaryZoneHighlight.activeMobNames.forEach { mob ->
                val lower = mob.lowercase()
                if (lower.isNotBlank() && lower !in names) names.add(lower)
            }
        }
        return names
    }

    private fun nameMatches(entity: Entity): Boolean {
        val names = getNames()
        if (names.isEmpty()) return false
        val name = entity.name.string.replace(COLOR_CODE_REGEX, "").lowercase()
        val customName = entity.customName?.string?.replace(COLOR_CODE_REGEX, "")?.lowercase()
        return names.any { n -> name.contains(n) || customName?.contains(n) == true }
    }

    private fun resolveEntity(entity: Entity): Entity? {
        if (entity is ArmorStandEntity && entity.isInvisible) {
            val world = MinecraftClient.getInstance().world ?: return null
            val player = MinecraftClient.getInstance().player
            val byId = world.getEntityById(entity.id - 1)
            if (byId != null && byId !is ArmorStandEntity && byId != player && byId.isAlive) return byId
            val candidates = world.getEntitiesByClass(
                LivingEntity::class.java, entity.boundingBox.expand(0.5, 1.5, 0.5)
            ) { it !is ArmorStandEntity && it != player && it.isAlive }
            return candidates.minByOrNull { val dx = it.x - entity.x; val dz = it.z - entity.z; dx*dx + dz*dz }
        }
        return entity
    }

    fun getOutlineColor(entity: Entity): Int {
        val config = FamilyConfigManager.config.highlight
        if (!config.enabled) return 0
        if (config.drawingStyle != 1) return 0
        if (entity !in highlighted) return 0
        return try {
            val parts = config.color.split(":")
            val r = parts[2].toInt(); val g = parts[3].toInt(); val b = parts[4].toInt()
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        } catch (e: Exception) { 0xFFFF0000.toInt() }
    }

    fun register() {
        ClientTickEvents.END_CLIENT_TICK.register { _ ->
            if (!shouldScan()) {
                if (highlighted.isNotEmpty()) highlighted.clear()
                return@register
            }
            val interval = FamilyConfigManager.config.utilities.highlightRescanInterval.toInt().coerceIn(1, 20)
            if (tick++ % interval != 0) return@register
            rescan()
        }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> highlighted.clear() }
    }

    fun rescan() {
        highlighted.clear()
        val world = MinecraftClient.getInstance().world ?: return
        if (!shouldScan()) return
        world.entities.forEach { entity ->
            if (!entity.isAlive) return@forEach
            if (nameMatches(entity)) {
                val target = resolveEntity(entity) ?: entity
                if (target.isAlive) highlighted.add(target)
            }
        }
    }

    fun onWorldRender(matrices: MatrixStack, consumers: VertexConsumerProvider, cam: Vec3d) {
        val config = FamilyConfigManager.config.highlight
        if (!config.enabled) return
        if (highlighted.isEmpty()) return

        val (r, g, b) = try {
            val parts = config.color.split(":")
            Triple(parts[2].toInt() / 255f, parts[3].toInt() / 255f, parts[4].toInt() / 255f)
        } catch (e: Exception) { Triple(1f, 0f, 0f) }

        highlighted.removeIf { !it.isAlive }

        // ── ESP boxes ─────────────────────────────────────────────────
        if (config.drawingStyle == 0) {
            fun drawAll(alpha: Float, renderType: net.minecraft.client.render.RenderLayer) {
                val buf = consumers.getBuffer(renderType)
                val entry = matrices.peek()
                for (entity in highlighted) {
                    if (!entity.isAlive) continue
                    val bb = entity.boundingBox
                    drawBoxEdges(buf, entry,
                        (bb.minX - cam.x).toFloat(), (bb.minY - cam.y).toFloat(), (bb.minZ - cam.z).toFloat(),
                        (bb.maxX - cam.x).toFloat(), (bb.maxY - cam.y).toFloat(), (bb.maxZ - cam.z).toFloat(),
                        r, g, b, alpha)
                }
            }
            drawAll(1.0f, FamilyRenderTypes.LINES)
            drawAll(0.3f, FamilyRenderTypes.LINES_NO_DEPTH)
        }

        // ── Tracer lines ──────────────────────────────────────────────
        // Start offset 0.5 blocks forward from camera to avoid near-plane clipping.
        // That point is directly in front of the camera → projects to crosshair.
        // Uses LINES_NO_DEPTH so the tracer always draws on top of world geometry.
        if (config.tracerEnabled) {
            val count = config.tracerCount.toInt().coerceIn(1, 20)
            val maxBlocks = config.tracerChunkRange.toDouble() * 16.0
            val maxDistSq = maxBlocks * maxBlocks
            val now = System.currentTimeMillis()

            // Current-frame candidates: alive, in range, closest N
            val currentCandidates = highlighted
                .filter { entity ->
                    if (!entity.isAlive) return@filter false
                    val dx = entity.x - cam.x
                    val dy = (entity.boundingBox.minY + entity.boundingBox.maxY) / 2.0 - cam.y
                    val dz = entity.z - cam.z
                    (dx * dx + dy * dy + dz * dz) <= maxDistSq
                }
                .sortedBy { entity ->
                    val dx = entity.x - cam.x
                    val dy = (entity.boundingBox.minY + entity.boundingBox.maxY) / 2.0 - cam.y
                    val dz = entity.z - cam.z
                    dx * dx + dy * dy + dz * dz
                }
                .take(count)

            // Refresh cached state for current candidates
            for (entity in currentCandidates) {
                val midY = (entity.boundingBox.minY + entity.boundingBox.maxY) / 2.0
                val existing = tracerStates[entity.id]
                if (existing != null) {
                    existing.posX = entity.x
                    existing.posY = midY
                    existing.posZ = entity.z
                    existing.lastSeenMs = now
                } else {
                    tracerStates[entity.id] = TracerState(entity.x, midY, entity.z, now)
                }
            }

            // Expire stale states
            tracerStates.entries.removeIf { (_, s) -> now - s.lastSeenMs > TRACER_GRACE_MS }

            // Cap cache size so it can't grow unbounded when many mobs cycle through range
            if (tracerStates.size > count * 3) {
                val keepIds = tracerStates.entries
                    .sortedByDescending { it.value.lastSeenMs }
                    .take(count * 3)
                    .map { it.key }
                    .toSet()
                tracerStates.keys.retainAll(keepIds)
            }

            if (tracerStates.isNotEmpty()) {
                val camera = MinecraftClient.getInstance().gameRenderer.camera
                val yawRad = Math.toRadians(camera.yaw.toDouble())
                val pitchRad = Math.toRadians(camera.pitch.toDouble())
                val fwdX = -Math.sin(yawRad) * Math.cos(pitchRad)
                val fwdY = -Math.sin(pitchRad)
                val fwdZ = Math.cos(yawRad) * Math.cos(pitchRad)

                val startOffset = 0.5
                val sx = (fwdX * startOffset).toFloat()
                val sy = (fwdY * startOffset).toFloat()
                val sz = (fwdZ * startOffset).toFloat()

                val buf = consumers.getBuffer(FamilyRenderTypes.LINES_NO_DEPTH)
                val entry = matrices.peek()

                for (state in tracerStates.values) {
                    // Fade alpha out over the grace period for a smooth transition
                    val age = now - state.lastSeenMs
                    val alpha = if (age <= 0L) 1.0f
                    else (1.0f - age.toFloat() / TRACER_GRACE_MS.toFloat()).coerceIn(0.0f, 1.0f)

                    val ex = (state.posX - cam.x).toFloat()
                    val ey = (state.posY - cam.y).toFloat()
                    val ez = (state.posZ - cam.z).toFloat()

                    val dx = ex - sx; val dy = ey - sy; val dz = ez - sz
                    val len = Math.sqrt((dx * dx + dy * dy + dz * dz).toDouble()).toFloat()
                    val nx = if (len > 0f) dx / len else 0f
                    val ny = if (len > 0f) dy / len else 0f
                    val nz = if (len > 0f) dz / len else 0f

                    buf.vertex(entry, sx, sy, sz)
                        .color(r, g, b, alpha)
                        .normal(entry, nx, ny, nz)
                        .lineWidth(2.0f)
                    buf.vertex(entry, ex, ey, ez)
                        .color(r, g, b, alpha)
                        .normal(entry, nx, ny, nz)
                        .lineWidth(2.0f)
                }
            }
        }
    }

    fun hasHighlighted() = highlighted.isNotEmpty() && shouldScan()

    internal fun drawBoxEdges(
        buf: VertexConsumer,
        entry: MatrixStack.Entry,
        x1: Float, y1: Float, z1: Float,
        x2: Float, y2: Float, z2: Float,
        r: Float, g: Float, b: Float, a: Float
    ) {
        val edges = arrayOf(
            floatArrayOf(x1,y1,z1,x2,y1,z1), floatArrayOf(x2,y1,z1,x2,y1,z2),
            floatArrayOf(x2,y1,z2,x1,y1,z2), floatArrayOf(x1,y1,z2,x1,y1,z1),
            floatArrayOf(x1,y2,z1,x2,y2,z1), floatArrayOf(x2,y2,z1,x2,y2,z2),
            floatArrayOf(x2,y2,z2,x1,y2,z2), floatArrayOf(x1,y2,z2,x1,y2,z1),
            floatArrayOf(x1,y1,z1,x1,y2,z1), floatArrayOf(x2,y1,z1,x2,y2,z1),
            floatArrayOf(x2,y1,z2,x2,y2,z2), floatArrayOf(x1,y1,z2,x1,y2,z2)
        )
        for (e in edges) {
            val dx = e[3]-e[0]; val dy = e[4]-e[1]; val dz = e[5]-e[2]
            buf.vertex(entry, e[0], e[1], e[2]).color(r, g, b, a).normal(entry, dx, dy, dz).lineWidth(2.0f)
            buf.vertex(entry, e[3], e[4], e[5]).color(r, g, b, a).normal(entry, dx, dy, dz).lineWidth(2.0f)
        }
    }
}