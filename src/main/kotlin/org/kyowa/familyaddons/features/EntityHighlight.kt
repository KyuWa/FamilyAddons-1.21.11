package org.kyowa.familyaddons.features

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.MinecraftClient
import net.minecraft.client.util.math.MatrixStack
import net.minecraft.client.render.VertexConsumerProvider
import net.minecraft.util.math.Vec3d
import net.minecraft.entity.Entity
import net.minecraft.entity.LivingEntity
import net.minecraft.entity.decoration.ArmorStandEntity
import org.kyowa.familyaddons.COLOR_CODE_REGEX
import org.kyowa.familyaddons.config.FamilyConfigManager

object EntityHighlight {

    val highlighted = mutableSetOf<Entity>()
    private var tick = 0

    private fun getNames(): List<String> = FamilyConfigManager.config.highlight.mobNames
        .split(",").map { it.trim().lowercase() }.filter { it.isNotBlank() }

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
            return candidates.minByOrNull { val dx = it.x - entity.x; val dz = it.z - entity.z; dx * dx + dz * dz }
        }
        return entity
    }

    // Returns ARGB color for outline mode, 0 if not highlighted or wrong mode
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
            if (!FamilyConfigManager.config.highlight.enabled) {
                if (highlighted.isNotEmpty()) highlighted.clear()
                return@register
            }
            if (tick++ % 20 != 0) return@register
            rescan()
        }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> highlighted.clear() }
    }

    fun rescan() {
        highlighted.clear()
        val world = MinecraftClient.getInstance().world ?: return
        val names = getNames()
        if (names.isEmpty()) return
        world.entities.forEach { entity ->
            if (!entity.isAlive) return@forEach
            if (nameMatches(entity)) {
                val target = resolveEntity(entity) ?: entity
                if (target.isAlive) highlighted.add(target)
            }
        }
    }

    fun onWorldRender(matrices: MatrixStack, consumers: VertexConsumerProvider, cam: Vec3d) {
        if (highlighted.isEmpty()) return
        val config = FamilyConfigManager.config.highlight
        if (!config.enabled) return
        // Skip box rendering if outline mode
        if (config.drawingStyle == 1) return

        val (r, g, b) = try {
            val parts = config.color.split(":")
            Triple(parts[2].toInt() / 255f, parts[3].toInt() / 255f, parts[4].toInt() / 255f)
        } catch (e: Exception) { Triple(1f, 0f, 0f) }

        highlighted.removeIf { !it.isAlive }

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
        if (config.throughWalls) drawAll(1.0f, FamilyRenderTypes.LINES_NO_DEPTH)
    }

    fun hasHighlighted() = highlighted.isNotEmpty() && FamilyConfigManager.config.highlight.enabled

    internal fun drawBoxEdges(
        buf: net.minecraft.client.render.VertexConsumer,
        entry: net.minecraft.client.util.math.MatrixStack.Entry,
        x1: Float, y1: Float, z1: Float, x2: Float, y2: Float, z2: Float,
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
