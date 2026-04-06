package org.kyowa.familyaddons.features

import org.kyowa.familyaddons.COLOR_CODE_REGEX
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.minecraft.client.MinecraftClient
import net.minecraft.text.Text
import org.kyowa.familyaddons.FamilyAddons
import org.kyowa.familyaddons.commands.TestCommand
import org.kyowa.familyaddons.config.FamilyConfigManager

object AutoRequeue {

    private var cancelNextRequeue = false
    private var currentTier       = "infernal"
    private var diedThisRun       = false
    private var cancelReason      = ""
    private var dtRequester: String? = null
    private var announcePartyMsg: String? = null
    private var announceTicks     = 0
    private var waitingRequeue    = false
    private var requeueTicksLeft  = 0
    private var inKuudra          = false

    private val DT_PATTERN = Regex(
        """(?:^|\b)Party\b.*?([A-Za-z0-9_]{3,16})\s*:\s*[!.]dt(?:\s.*)?$""",
        RegexOption.IGNORE_CASE
    )
    private val TIER_PATTERN = Regex("""(Basic|Hot|Burning|Fiery|Infernal) Tier""", RegexOption.IGNORE_CASE)
    private val IGN_PATTERN = Regex("[A-Za-z0-9_]{3,16}")
    private val UNDT_PATTERN = Regex(
        """(?:^|\b)Party\b.*?([A-Za-z0-9_]{3,16})\s*:\s*[!.]undt\b""",
        RegexOption.IGNORE_CASE
    )

    fun register() {
        ClientReceiveMessageEvents.ALLOW_GAME.register { message, _ ->
            val plain = message.string.replace(COLOR_CODE_REGEX, "").trim()
            handleMessage(plain)
            true
        }

        ClientTickEvents.END_CLIENT_TICK.register {
            DtTitle.tick()
            updateKuudraStatus()

            if (announceTicks > 0) {
                announceTicks--
                if (announceTicks == 0) {
                    announcePartyMsg?.let {
                        MinecraftClient.getInstance().player?.networkHandler?.sendChatMessage("/pc $it requested dt!")
                    }
                    announcePartyMsg = null
                }
            }

            if (waitingRequeue) {
                if (requeueTicksLeft > 0) {
                    requeueTicksLeft--
                } else {
                    waitingRequeue = false
                    MinecraftClient.getInstance().player?.networkHandler?.sendChatCommand("instancerequeue")
                }
            }
        }
    }

    // Check scoreboard for Kuudra-related text
    private var kuudraCheckTick = 0
    private fun updateKuudraStatus() {
        if (kuudraCheckTick++ % 10 != 0) return
        try {
            val client = MinecraftClient.getInstance()
            val scoreboard = client.world?.scoreboard ?: return
            val objective = scoreboard.getObjectiveForSlot(
                net.minecraft.scoreboard.ScoreboardDisplaySlot.SIDEBAR
            ) ?: return
            val entries = scoreboard.getScoreboardEntries(objective)
            for (entry in entries) {
                val name = entry.owner.replace(COLOR_CODE_REGEX, "")
                if (name.contains("Kuudra", ignoreCase = true) ||
                    name.contains("Crimson Isle", ignoreCase = true) ||
                    name.contains("Crystal Hollow", ignoreCase = true)) {
                    inKuudra = true
                    return
                }
            }
        } catch (e: Exception) {}
    }

    private fun handleMessage(plain: String) {
        val config = FamilyConfigManager.config.kuudra

        val dtMatch = DT_PATTERN.find(plain)
        if (dtMatch != null) {
            val name = dtMatch.groupValues[1].trim()
            if (config.dtTitle) DtTitle.show("§e$name §crequested §fDT!")
            cancelNextRequeue = true
            dtRequester = name
            cancelReason = "dt"
            return
        }
        val undtMatch = UNDT_PATTERN.find(plain)
        if (undtMatch != null) {
            val name = undtMatch.groupValues[1].trim()
            if (config.dtTitle) DtTitle.show("${TestCommand.getFormattedName(name)} §acancelled §fDT!")
            cancelNextRequeue = false
            cancelReason = ""
            dtRequester = null
            announcePartyMsg = null
            announceTicks = 0
            return
        }
        if (!config.autoRequeue) return
        val player = MinecraftClient.getInstance().player ?: return
        val selfName = player.name.string

        // Tier detection — also sets inKuudra
        val tierMatch = TIER_PATTERN.find(plain)
        if (tierMatch != null && plain.contains("Tier")) {
            currentTier = tierMatch.groupValues[1].lowercase()
            cancelNextRequeue = false
            inKuudra = true
            if (plain.contains(selfName))             diedThisRun = false
            FamilyAddons.LOGGER.info("[FA] Tier detected: $currentTier, isLeader: ${org.kyowa.familyaddons.party.PartyTracker.leader?.lowercase() == net.minecraft.client.MinecraftClient.getInstance().player?.name?.string?.lowercase()}")
            return
        }

        // Leader detection from system messages
        if (plain.contains("You are the party leader", ignoreCase = true) ||
            plain.contains("has promoted", ignoreCase = true) && plain.contains(selfName, ignoreCase = true)) {
        }

        // Elle message — check party size
        if (plain.contains("Okay adventurers, I will go and fish up Kuudra")) {
            inKuudra = true
            checkPartySize()
            return
        }

        // Kuudra down
        if (plain == "KUUDRA DOWN!") {
            if (cancelNextRequeue) {
                cancelNextRequeue = false
                if (cancelReason == "dt" && dtRequester != null) {
                    announcePartyMsg = dtRequester
                    announceTicks = 40
                }
                dtRequester = null
                cancelReason = ""
                return
            }
            val tierAllowed = when (currentTier) {
                "basic"    -> FamilyConfigManager.config.kuudra.requeueBasic
                "hot"      -> FamilyConfigManager.config.kuudra.requeueHot
                "burning"  -> FamilyConfigManager.config.kuudra.requeueBurning
                "fiery"    -> FamilyConfigManager.config.kuudra.requeueFiery
                "infernal" -> FamilyConfigManager.config.kuudra.requeueInfernal
                else -> false
            }
            if (!tierAllowed) return

            if (diedThisRun) {
                diedThisRun = false
                waitingRequeue = true
                requeueTicksLeft = 40
            } else {
                player.networkHandler.sendChatCommand("instancerequeue")
            }
            return
        }

        // !dt — anyone in party can call
        if (inKuudra) {
            val dtMatch = DT_PATTERN.find(plain)
            if (dtMatch != null) {
                val name = dtMatch.groupValues[1].trim()
                cancelNextRequeue = true
                dtRequester = name
                cancelReason = "dt"
                DtTitle.show("§e$name §crequested §fDT!")
                return
            }

            val undtMatch = UNDT_PATTERN.find(plain)
            if (undtMatch != null) {
                val name = undtMatch.groupValues[1].trim()
                cancelNextRequeue = false
                cancelReason = ""
                dtRequester = null
                announcePartyMsg = null
                announceTicks = 0
                DtTitle.show("${TestCommand.getFormattedName(name)} §acancelled §fDT!")
                return
            }
        }

        // Party leave — cancel requeue
        if (plain.contains("left the party", ignoreCase = true) && inKuudra) {
            cancelNextRequeue = true
            cancelReason = "leave"
            dtRequester = null
            chat("§e[FA] Party member left — requeue cancelled.")
            return
        }

        // Death detect
        if (plain == "$selfName was FINAL KILLED by Kuudra!") {
            diedThisRun = true
        }
    }

    private fun checkPartySize() {
        // Count actual party members from tab list
        val size = getPartyMemberCount()
        FamilyAddons.LOGGER.info("[FA] Party size at run start: $size")
        if (size in 1..3) {
            cancelNextRequeue = true
            cancelReason = "size"
            chat("§e[FA] Auto requeue disabled — only $size players in party.")
            DtTitle.show("§cOnly §e$size §cplayers — §frequeue cancelled!")
        }
    }

    private fun getPartyMemberCount(): Int {
        return try {
            val client = MinecraftClient.getInstance()
            val tabList = client.networkHandler?.playerList ?: return 0
            // Count entries that look like player names (not NPC entries)
            // On Hypixel, party members show in tab list
            // Filter by entries that have display names matching IGN pattern
            var count = 0
            for (entry in tabList) {
                val name = entry.profile.name ?: continue
                if (name.matches(IGN_PATTERN)) count++
            }
            // Tab list includes all players in the instance, cap at reasonable party size
            count.coerceAtMost(5)
        } catch (e: Exception) { 0 }
    }

    private fun chat(msg: String) {
        MinecraftClient.getInstance().execute {
            MinecraftClient.getInstance().player?.sendMessage(Text.literal(msg), false)
        }
    }
}
