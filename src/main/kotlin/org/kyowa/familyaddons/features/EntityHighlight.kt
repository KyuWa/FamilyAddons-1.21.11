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
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.util.math.Vec3d
import org.kyowa.familyaddons.COLOR_CODE_REGEX
import org.kyowa.familyaddons.config.FamilyConfigManager

object EntityHighlight {

    val highlighted = mutableSetOf<Entity>()
    private var tick = 0

    private fun shouldScan(): Boolean {
        if (FamilyConfigManager.config.highlight.enabled) return true
        val bestiary = FamilyConfigManager.config.bestiary
        if (bestiary.zoneHighlightEnabled && bestiary.bestiaryZone != 0) return true
        if (bestiary.mobName.isNotBlank()) return true
        return false
    }

    /**
     * Returns the union of all configured highlight names. Used by `shouldScan()`'s
     * downstream logic and any external callers. Match logic itself lives in
     * `nameMatches()`, which uses different rules for manual-highlight names vs
     * zone-bestiary names.
     */
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

    /**
     * Match an entity against configured highlight names.
     *
     * Two pools with different rules:
     *  - Manual names (HighlightConfig.mobNames + BestiaryConfig.mobName): loose
     *    `.contains()` on either entity.name or customName. Preserves the old
     *    behaviour where typing "dragon" matches anything dragon-related.
     *  - Zone-bestiary names (BestiaryZoneHighlight.activeMobNames): match
     *    against the customName ONLY, after stripping all decorations (level
     *    brackets, stars, hearts, runic glyphs, etc.). The match accepts:
     *      * exact equality, OR
     *      * the stripped name with one allowed modifier word prefix
     *        ("corrupted" or "runic"), since those variants share a bestiary
     *        entry with their base mob (per Hypixel wiki).
     *    This fixes Hypixel reusing one entity type across multiple bestiary
     *    entries (Wither Skeleton ↔ Wither Spectre) while still highlighting
     *    "Corrupted Wither Skeleton" / "Runic Wither Skeleton" correctly.
     *    A pure substring match would falsely catch e.g. "Cave Spider" when
     *    "Spider" is the active target — hence the explicit modifier list.
     */
    private fun nameMatches(entity: Entity): Boolean {
        val manualNames = mutableListOf<String>()
        val zoneNames   = mutableListOf<String>()

        if (FamilyConfigManager.config.highlight.enabled) {
            FamilyConfigManager.config.highlight.mobNames
                .split(",")
                .map { it.trim().lowercase() }
                .filter { it.isNotBlank() }
                .forEach { if (it !in manualNames) manualNames.add(it) }
        }
        val bestiaryMob = FamilyConfigManager.config.bestiary.mobName.trim().lowercase()
        if (bestiaryMob.isNotBlank() && bestiaryMob !in manualNames) manualNames.add(bestiaryMob)

        if (FamilyConfigManager.config.bestiary.zoneHighlightEnabled) {
            BestiaryZoneHighlight.activeMobNames.forEach { mob ->
                val lower = mob.lowercase()
                if (lower.isNotBlank() && lower !in zoneNames) zoneNames.add(lower)
            }
        }

        if (manualNames.isEmpty() && zoneNames.isEmpty()) return false

        val name = entity.name.string.replace(COLOR_CODE_REGEX, "").lowercase()
        val customNameRaw = entity.customName?.string?.replace(COLOR_CODE_REGEX, "")?.lowercase()

        // Manual list: loose substring match on either field (legacy behaviour).
        if (manualNames.isNotEmpty()) {
            if (manualNames.any { n -> name.contains(n) || customNameRaw?.contains(n) == true }) {
                return true
            }
        }

        // Zone bestiary: stripped customName, exact equality OR allowed-modifier prefix.
        if (zoneNames.isNotEmpty() && customNameRaw != null) {
            val stripped = stripBestiaryNametag(customNameRaw)
            if (stripped.isNotBlank() && zoneNames.any { matchesWithModifier(stripped, it) }) {
                return true
            }
        }

        return false
    }

    /**
     * Allowed modifier-word prefixes that share a bestiary entry with the base mob.
     * Per Hypixel wiki, "corrupted" and "runic" are universal spawn variants — a
     * Corrupted Wither Skeleton kill counts toward the Wither Skeleton bestiary.
     * This is intentionally a small whitelist to avoid false positives like
     * "Cave Spider" matching when "Spider" is the active target.
     */
    private val ALLOWED_MODIFIERS = setOf("corrupted", "runic")

    private fun matchesWithModifier(stripped: String, target: String): Boolean {
        if (stripped == target) return true
        // "corrupted wither skeleton" matches target "wither skeleton" iff the
        // text before the target is exactly one allowed modifier word.
        if (stripped.endsWith(" $target")) {
            val prefix = stripped.removeSuffix(" $target")
            if (prefix in ALLOWED_MODIFIERS) return true
        }
        return false
    }

    /**
     * Reduce a Hypixel mob nametag down to just its display name.
     *
     * Strategy: rather than enumerate every prefix/suffix Hypixel uses (level
     * brackets, stars, hearts, runic glyphs, festival markers, mayor perks, etc.),
     * we keep only "name tokens" — whitespace-separated chunks made entirely of
     * letters, apostrophes, or hyphens. Anything containing a digit, bracket,
     * heart, star, or unknown symbol is decoration and gets discarded.
     *
     * Future-proof: when Hypixel adds a new symbol, it gets auto-stripped instead
     * of silently breaking matches.
     *
     * Examples (input → output):
     *   "✯ wither spectre 500❤"           → "wither spectre"
     *   "[lv50] zombie 1,234/5,000❤"      → "zombie"
     *   "wither skeleton 50❤"              → "wither skeleton"
     *   "᠅ runic ghoul ⓢ 2.5m❤"            → "runic ghoul"
     *   "[lv1] flaming spider 100❤"       → "flaming spider"
     *   "✯ bal 7m❤"                        → "bal"
     *
     * NOTE: input is already lowercased and color-code-stripped by the caller.
     */
    private fun stripBestiaryNametag(s: String): String {
        val nameTokenRegex = Regex("""^[a-z'\-]+$""")
        return s.split(Regex("""\s+"""))
            .filter { it.isNotEmpty() && nameTokenRegex.matches(it) }
            .joinToString(" ")
            .trim()
    }

    /**
     * True if [entity] represents a real connected player and must NEVER be highlighted.
     *
     * On Hypixel SkyBlock, mob NPCs are spawned as PlayerEntity instances (full player skins,
     * custom AI). A real player can be told apart from an NPC because real players have an
     * entry in the tab list (PlayerListEntry); NPC mobs do not. This is the same check used
     * by SkyHanni and Odin to avoid hitting NPCs with anti-cheat-style filters.
     *
     * Returns false for non-player entities (mobs, animals, armor stands etc.).
     */
    private fun isRealPlayer(entity: Entity): Boolean {
        if (entity !is PlayerEntity) return false
        val handler = MinecraftClient.getInstance().networkHandler ?: return false
        return handler.getPlayerListEntry(entity.uuid) != null
    }

    private fun resolveEntity(entity: Entity): Entity? {
        if (entity is ArmorStandEntity && entity.isInvisible) {
            val world = MinecraftClient.getInstance().world ?: return null
            val byId = world.getEntityById(entity.id - 1)
            // Reject the id-1 candidate if it's any real connected player (not just self).
            if (byId != null && byId !is ArmorStandEntity && !isRealPlayer(byId) && byId.isAlive) return byId
            val candidates = world.getEntitiesByClass(
                LivingEntity::class.java, entity.boundingBox.expand(0.5, 1.5, 0.5)
            ) { it !is ArmorStandEntity && !isRealPlayer(it) && it.isAlive }
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
            // Skip any real connected player up-front. This prevents matches like the search
            // term "dragon" highlighting a player named "dragonslayer213". NPC mobs that use
            // player skins (Hypixel's fake-player NPCs) pass this check because they are not
            // in the tab list — they will still be highlighted normally.
            if (isRealPlayer(entity)) return@forEach
            if (nameMatches(entity)) {
                // FIX: if resolveEntity returns null (nametag stand can't find its real mob
                // because the mob died this tick), skip entirely. The old `?: entity` fallback
                // would add the armor stand itself to `highlighted`, causing the tracer to
                // briefly snap to the stand's position before it despawns — visible flicker.
                val target = resolveEntity(entity) ?: return@forEach
                // Defensive: never highlight an invisible nametag stand directly.
                if (target is ArmorStandEntity && target.isInvisible) return@forEach
                // Defensive: resolveEntity already filters real players, but double-check.
                if (isRealPlayer(target)) return@forEach
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

            // Pick the closest N live+highlighted+in-range mobs each frame.
            // When a mob dies it leaves `highlighted` → instantly drops from this list.
            val targets = ArrayList<Entity>()
            for (entity in highlighted) {
                if (!entity.isAlive) continue
                // FIX: never run a tracer to an invisible nametag armor stand. Belt-and-braces
                // in case one ever makes it into `highlighted` through some other code path.
                if (entity is ArmorStandEntity) continue
                val dx = entity.x - cam.x
                val dy = (entity.boundingBox.minY + entity.boundingBox.maxY) / 2.0 - cam.y
                val dz = entity.z - cam.z
                if (dx * dx + dy * dy + dz * dz <= maxDistSq) targets.add(entity)
            }
            targets.sortBy { entity ->
                val dx = entity.x - cam.x
                val dy = (entity.boundingBox.minY + entity.boundingBox.maxY) / 2.0 - cam.y
                val dz = entity.z - cam.z
                dx * dx + dy * dy + dz * dz
            }
            val picked = if (targets.size > count) targets.subList(0, count) else targets

            if (picked.isNotEmpty()) {
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

                for (entity in picked) {
                    val ex = (entity.x - cam.x).toFloat()
                    val ey = ((entity.boundingBox.minY + entity.boundingBox.maxY) / 2.0 - cam.y).toFloat()
                    val ez = (entity.z - cam.z).toFloat()

                    val dx = ex - sx; val dy = ey - sy; val dz = ez - sz
                    val len = Math.sqrt((dx * dx + dy * dy + dz * dz).toDouble()).toFloat()
                    val nx = if (len > 0f) dx / len else 0f
                    val ny = if (len > 0f) dy / len else 0f
                    val nz = if (len > 0f) dz / len else 0f

                    buf.vertex(entry, sx, sy, sz)
                        .color(r, g, b, 1.0f)
                        .normal(entry, nx, ny, nz)
                        .lineWidth(2.0f)
                    buf.vertex(entry, ex, ey, ez)
                        .color(r, g, b, 1.0f)
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