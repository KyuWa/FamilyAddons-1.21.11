package org.kyowa.familyaddons.features

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.concurrent.CompletableFuture
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientWorldEvents
import net.minecraft.client.MinecraftClient
import net.minecraft.client.util.math.MatrixStack
import net.minecraft.client.render.VertexConsumerProvider
import net.minecraft.util.math.Vec3d
import net.minecraft.util.math.Box
import org.kyowa.familyaddons.features.FamilyRenderTypes

object NpcLocations {

    data class NpcInfo(val name: String, val location: String, val x: Double, val y: Double, val z: Double)
    data class ActiveNpcWaypoint(val name: String, val x: Double, val y: Double, val z: Double)

    val activeWaypoints = mutableListOf<ActiveNpcWaypoint>()

    private var _npcs: List<NpcInfo> = emptyList()
    val npcs: List<NpcInfo> get() = _npcs

    private fun loadNpcs() {
        try {
            val stream = NpcLocations::class.java.classLoader.getResourceAsStream("npcs.json")
                ?: return
            val json = stream.bufferedReader().readText()
            val type = object : TypeToken<List<NpcInfo>>() {}.type
            _npcs = Gson().fromJson(json, type)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun hasActiveWaypoints(): Boolean = activeWaypoints.isNotEmpty()

    fun findNpc(query: String): List<NpcInfo> {
        val lower = query.lowercase()
        return npcs.filter { it.name.lowercase().contains(lower) }
    }

    fun register() {
        // Load NPC data off main thread
        java.util.concurrent.CompletableFuture.runAsync { loadNpcs() }

        ClientWorldEvents.AFTER_CLIENT_WORLD_CHANGE.register { _, _ -> activeWaypoints.clear() }


    }

    fun onWorldRender(matrices: MatrixStack, consumers: VertexConsumerProvider, cam: Vec3d) {
        if (activeWaypoints.isEmpty()) return
        matrices.push()
        matrices.translate(-cam.x, -cam.y, -cam.z)

        fun drawNpcs(alpha: Float, renderType: net.minecraft.client.render.RenderLayer) {
            val buf = consumers.getBuffer(renderType)
            val entry = matrices.peek()
            for (wp in activeWaypoints) {
                drawBoxEdges(buf, entry,
                    (wp.x - 0.5 - cam.x).toFloat(), (wp.y - cam.y).toFloat(), (wp.z - 0.5 - cam.z).toFloat(),
                    (wp.x + 0.5 - cam.x).toFloat(), (wp.y + 2.0 - cam.y).toFloat(), (wp.z + 0.5 - cam.z).toFloat(),
                    1.0f, 0.84f, 0.0f, alpha)
            }
        }

        drawNpcs(1.0f, FamilyRenderTypes.LINES)
        drawNpcs(0.3f, FamilyRenderTypes.LINES_NO_DEPTH)

        matrices.pop()
    }
    internal fun drawBoxEdges(
        buf: net.minecraft.client.render.VertexConsumer,
        entry: net.minecraft.client.util.math.MatrixStack.Entry,
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
