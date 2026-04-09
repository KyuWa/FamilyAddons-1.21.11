package org.kyowa.familyaddons.features

import com.google.gson.JsonParser
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback
import net.minecraft.client.MinecraftClient
import net.minecraft.component.DataComponentTypes
import net.minecraft.item.ItemStack
import net.minecraft.text.MutableText
import net.minecraft.text.Style
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import org.kyowa.familyaddons.FamilyAddons
import org.kyowa.familyaddons.config.FamilyConfigManager
import org.lwjgl.glfw.GLFW
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.text.NumberFormat
import java.util.Locale
import java.util.concurrent.CompletableFuture

object ItemPrices {

    private val http = HttpClient.newHttpClient()
    private val lowestBin = mutableMapOf<String, Double>()

    fun getLowestBin(): Map<String, Double> = synchronized(lowestBin) { lowestBin.toMap() }

    private val avgBin = mutableMapOf<String, Double>()
    private val bazaar = mutableMapOf<String, BazaarData>()
    private var lastBinFetch = 0L
    private var lastBazaarFetch = 0L
    private const val CACHE_MS = 5 * 60 * 1000L
    private var tickCounter = 0

    private val LVL200_PETS = setOf("GOLDEN_DRAGON")

    data class BazaarData(val instaBuy: Double, val instaSell: Double)

    private fun isShiftDown(): Boolean {
        val client = MinecraftClient.getInstance()
        val handle = client.window.handle
        return GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS ||
                GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS
    }

    fun register() {
        ClientTickEvents.END_CLIENT_TICK.register {
            if (!FamilyConfigManager.config.utilities.itemPrices) return@register
            if (tickCounter++ % 200 == 0) refreshIfNeeded()
        }

        ItemTooltipCallback.EVENT.register { stack, _, _, tooltip ->
            if (!FamilyConfigManager.config.utilities.itemPrices) return@register
            val id = getSkyblockId(stack) ?: return@register

            // ── Pet ───────────────────────────────────────────────────
            if (id.startsWith("PET:")) {
                val parts = id.split(":")
                val type = parts[1]; val rarity = parts[2]
                val keyLvl1 = "$type;$rarity"
                val maxKey = if (type in LVL200_PETS) "$type;$rarity+200" else "$type;$rarity+100"
                val maxLabel = if (type in LVL200_PETS) "LBIN Lvl 200: " else "LBIN Lvl 100: "
                val priceLvl1 = synchronized(lowestBin) { lowestBin[keyLvl1] }
                val priceMax = synchronized(lowestBin) { lowestBin[maxKey] }
                if (priceLvl1 != null || priceMax != null) {
                    tooltip.add(Text.empty())
                    if (priceLvl1 != null) tooltip.add(labelLine("LBIN Lvl 1: ", priceLvl1))
                    if (priceMax != null) tooltip.add(labelLine(maxLabel, priceMax))
                }
                return@register
            }

            // ── Enchanted book ────────────────────────────────────────
            if (id.startsWith("ENCHBOOK:")) {
                val parts = id.split(":")
                val enchName = parts[1]; val level = parts[2]
                val binKey = "ENCHANTED_BOOK;$enchName;$level"
                val price = synchronized(lowestBin) { lowestBin[binKey] }
                val avg = synchronized(avgBin) { avgBin[binKey] }
                if (price != null || avg != null) {
                    tooltip.add(Text.empty())
                    if (price != null) tooltip.add(labelLine("LBIN: ", price))
                    if (avg != null) tooltip.add(labelLine("AVG LBIN: ", avg))
                }
                return@register
            }

            // ── Bazaar ────────────────────────────────────────────────
            val baz = synchronized(bazaar) { bazaar[id] }
            if (baz != null) {
                tooltip.add(Text.empty())
                tooltip.add(labelLine("Bazaar Insta-Sell: ", baz.instaSell))
                tooltip.add(labelLine("Bazaar Insta-Buy: ", baz.instaBuy))
                if (isShiftDown()) {
                    val count = stack.count
                    tooltip.add(Text.empty())
                    if (count > 1) {
                        tooltip.add(labelLine("Sell Total (×$count): ", baz.instaSell * count))
                        tooltip.add(labelLine("Buy Total (×$count): ", baz.instaBuy * count))
                    } else {
                        tooltip.add(labelLine("Sell Stack (×64): ", baz.instaSell * 64))
                        tooltip.add(labelLine("Buy Stack (×64): ", baz.instaBuy * 64))
                    }
                } else {
                    tooltip.add(Text.literal("§7Hold §eShift §7for stack price")
                        .setStyle(Style.EMPTY.withItalic(true)))
                }
                return@register
            }

            // ── AH / BIN ──────────────────────────────────────────────
            val bin = synchronized(lowestBin) { lowestBin[id] }
            val avg = synchronized(avgBin) { avgBin[id] }
            if (bin != null) {
                tooltip.add(Text.empty())
                tooltip.add(labelLine("Lowest BIN: ", bin))
                if (avg != null) tooltip.add(labelLine("AVG Lowest BIN: ", avg))
            }
        }

        refreshIfNeeded()
    }

    private fun getSkyblockId(stack: ItemStack): String? {
        val customData = stack.get(DataComponentTypes.CUSTOM_DATA) ?: return null
        val nbt = customData.copyNbt()

        val rawId: String? =
            nbt.getString("id").orElse(null)?.ifBlank { null }
                ?: nbt.getCompoundOrEmpty("ExtraAttributes").getString("id").orElse(null)?.ifBlank { null }
                ?: nbt.getCompoundOrEmpty("tag").getCompoundOrEmpty("ExtraAttributes").getString("id").orElse(null)?.ifBlank { null }
                ?: return null

        // Pet
        if (rawId == "PET") {
            val petInfoStr =
                nbt.getString("petInfo").orElse(null)?.ifBlank { null }
                    ?: nbt.getCompoundOrEmpty("ExtraAttributes").getString("petInfo").orElse(null)?.ifBlank { null }
                    ?: return null
            return try {
                val petJson = JsonParser.parseString(petInfoStr).asJsonObject
                val type = petJson.get("type")?.asString?.ifBlank { null } ?: return null
                val tier = petJson.get("tier")?.asString?.ifBlank { null } ?: return null
                val rarityIndex = listOf("COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY", "MYTHIC").indexOf(tier)
                if (rarityIndex < 0) return null
                "PET:$type:$rarityIndex"
            } catch (e: Exception) { null }
        }

        // Enchanted book — read first enchantment from ExtraAttributes.enchantments
        if (rawId == "ENCHANTED_BOOK") {
            val extraAttribs =
                nbt.getCompoundOrEmpty("ExtraAttributes").takeIf { !it.isEmpty }
                    ?: nbt.getCompoundOrEmpty("tag").getCompoundOrEmpty("ExtraAttributes")
            val enchNbt = extraAttribs.getCompoundOrEmpty("enchantments")
            if (!enchNbt.isEmpty) {
                val firstKey = enchNbt.keys.firstOrNull()
                if (firstKey != null) {
                    val level = enchNbt.getInt(firstKey).orElse(-1)
                    if (level >= 0) return "ENCHBOOK:${firstKey.uppercase()}:$level"
                }
            }
            // Fall through to normal BIN lookup for plain ENCHANTED_BOOK
        }

        return rawId
    }

    private fun refreshIfNeeded() {
        val now = System.currentTimeMillis()
        if (now - lastBinFetch > CACHE_MS) { lastBinFetch = now; fetchBin() }
        if (now - lastBazaarFetch > CACHE_MS) { lastBazaarFetch = now; fetchBazaar() }
    }

    private fun fetchBin() {
        CompletableFuture.runAsync {
            try {
                val res = http.send(
                    HttpRequest.newBuilder().uri(URI.create("https://moulberry.codes/lowestbin.json"))
                        .header("User-Agent", "FamilyAddons/1.0").GET().build(),
                    HttpResponse.BodyHandlers.ofString()
                )
                val json = JsonParser.parseString(res.body()).asJsonObject
                synchronized(lowestBin) {
                    lowestBin.clear()
                    for ((k, v) in json.entrySet()) lowestBin[k] = v.asDouble
                }
            } catch (e: Exception) { FamilyAddons.LOGGER.warn("ItemPrices: lowestBin failed: ${e.message}") }
        }

        CompletableFuture.runAsync {
            try {
                val res = http.send(
                    HttpRequest.newBuilder().uri(URI.create("https://moulberry.codes/auction_averages_lbin/1day.json"))
                        .header("User-Agent", "FamilyAddons/1.0").GET().build(),
                    HttpResponse.BodyHandlers.ofString()
                )
                val json = JsonParser.parseString(res.body()).asJsonObject
                synchronized(avgBin) {
                    avgBin.clear()
                    for ((k, v) in json.entrySet()) {
                        try {
                            val price = v.asJsonObject.get("price")?.asDouble?.takeIf { it > 0 }
                            if (price != null) avgBin[k] = price
                        } catch (_: Exception) {}
                    }
                }
            } catch (e: Exception) { FamilyAddons.LOGGER.warn("ItemPrices: avgBin failed: ${e.message}") }
        }
    }

    private fun fetchBazaar() {
        CompletableFuture.runAsync {
            try {
                val res = http.send(
                    HttpRequest.newBuilder().uri(URI.create("https://api.hypixel.net/v2/skyblock/bazaar"))
                        .header("User-Agent", "FamilyAddons/1.0").GET().build(),
                    HttpResponse.BodyHandlers.ofString()
                )
                val json = JsonParser.parseString(res.body()).asJsonObject
                if (!json.get("success").asBoolean) return@runAsync
                synchronized(bazaar) {
                    bazaar.clear()
                    for ((id, product) in json.getAsJsonObject("products").entrySet()) {
                        val qs = product.asJsonObject.getAsJsonObject("quick_status")
                        bazaar[id] = BazaarData(qs.get("buyPrice").asDouble, qs.get("sellPrice").asDouble)
                    }
                }
            } catch (e: Exception) { FamilyAddons.LOGGER.warn("ItemPrices: bazaar failed: ${e.message}") }
        }
    }

    private fun labelLine(label: String, value: Double): MutableText {
        val bold = Style.EMPTY.withBold(true)
        val nf = NumberFormat.getNumberInstance(Locale.US)
        val formatted = if (value == value.toLong().toDouble()) {
            nf.maximumFractionDigits = 0; nf.format(value.toLong())
        } else {
            nf.maximumFractionDigits = 1; nf.minimumFractionDigits = 1; nf.format(value)
        }
        return Text.literal(label).setStyle(bold.withColor(Formatting.YELLOW))
            .append(Text.literal("$formatted coins").setStyle(bold.withColor(Formatting.GOLD)))
    }
}