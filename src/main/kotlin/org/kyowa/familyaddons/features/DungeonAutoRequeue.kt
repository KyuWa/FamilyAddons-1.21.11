package org.kyowa.familyaddons.features

import org.kyowa.familyaddons.COLOR_CODE_REGEX
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.MinecraftClient
import net.minecraft.text.Text
import org.kyowa.familyaddons.commands.TestCommand
import org.kyowa.familyaddons.config.FamilyConfigManager

object DungeonAutoRequeue {

    private var cancelNextRequeue = false
    private var dtRequester: String? = null
    private var announcePartyMsg: String? = null
    private var announceTicks = 0
    private var requeueTicksLeft = 0
    private var waitingRequeue = false

    private val DT_PATTERN = Regex(
        """(?:^|\b)Party\b.*?([A-Za-z0-9_]{3,16})\s*:\s*[!.]dt(?:\s.*)?$""",
        RegexOption.IGNORE_CASE
    )
    private val IGN_PATTERN = Regex("[A-Za-z0-9_]{3,16}")
    private val UNDT_PATTERN = Regex(
        """(?:^|\b)Party\b.*?([A-Za-z0-9_]{3,16})\s*:\s*[!.]undt\b""",
        RegexOption.IGNORE_CASE
    )

    fun register() {
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
            cancelNextRequeue = false
            dtRequester = null
            waitingRequeue = false
            requeueTicksLeft = 0
        }

        ClientReceiveMessageEvents.ALLOW_GAME.register { message, _ ->
            val plain = message.string.replace(COLOR_CODE_REGEX, "").trim()
            handleMessage(plain)
            true
        }

        ClientTickEvents.END_CLIENT_TICK.register {
            DungeonDtTitle.tick()

            // DT announce
            if (announceTicks > 0) {
                announceTicks--
                if (announceTicks == 0) {
                    announcePartyMsg?.let {
                        MinecraftClient.getInstance().player?.networkHandler?.sendChatMessage("/pc $it requested dt!")
                    }
                    announcePartyMsg = null
                }
            }

            // Delayed requeue (only used when delay > 0)
            if (waitingRequeue) {
                if (requeueTicksLeft > 0) {
                    requeueTicksLeft--
                } else {
                    waitingRequeue = false
                    // Final check before firing
                    if (!cancelNextRequeue) {
                        MinecraftClient.getInstance().player?.networkHandler?.sendChatCommand("instancerequeue")
                    }
                }
            }
        }
    }

    private fun handleMessage(plain: String) {
        val config = FamilyConfigManager.config.dungeons

        // !dt — set flag, show title
        val dtMatch = DT_PATTERN.find(plain)
        if (dtMatch != null) {
            val name = dtMatch.groupValues[1].trim()
            if (config.dtTitle) {
                DungeonDtTitle.show("${TestCommand.getFormattedName(name)} §crequested §fDT!")
            }
            cancelNextRequeue = true
            dtRequester = name
            return
        }

        // !undt — clear flag
        val undtMatch = UNDT_PATTERN.find(plain)
        if (undtMatch != null) {
            val name = undtMatch.groupValues[1].trim()
            if (config.dtTitle) {
                DungeonDtTitle.show("${TestCommand.getFormattedName(name)} §acancelled §fDT!")
            }
            cancelNextRequeue = false
            dtRequester = null
            announcePartyMsg = null
            announceTicks = 0
            return
        }

        if (!config.autoRequeue) return

        // New dungeon run starting — reset cancel flag (mirrors CT's "entered" handler)
        if (plain.contains("The Catacombs") && plain.contains("entered")) {
            cancelNextRequeue = false
            dtRequester = null
            return
        }

        // End of dungeon
        if (plain.contains("> EXTRA STATS <")) {
            // cancelNextRequeue check — exactly like CT's KUUDRA DOWN! handler
            if (cancelNextRequeue) {
                cancelNextRequeue = false
                if (dtRequester != null) {
                    announcePartyMsg = dtRequester
                    announceTicks = 40
                    dtRequester = null
                }
                return
            }

            // Party size check
            if (config.checkPartySize) {
                val size = getPartyMemberCount()
                if (size in 1..4) {
                    chat("§e[FA] Dungeon requeue cancelled — only $size players in party.")
                    DungeonDtTitle.show("§cOnly §e$size §cplayers — §frequeue cancelled!")
                    return
                }
            }

            val delay = (config.requeueDelaySecs * 20).toInt()
            if (delay <= 0) {
                // Fire immediately — same as CT
                MinecraftClient.getInstance().player?.networkHandler?.sendChatCommand("instancerequeue")
            } else {
                waitingRequeue = true
                requeueTicksLeft = delay
            }
            return
        }

        // Party leave
        if (plain.contains("left the party", ignoreCase = true)) {
            cancelNextRequeue = true
            chat("§e[FA] Party member left — dungeon requeue cancelled.")
        }
    }

    private fun getPartyMemberCount(): Int {
        return org.kyowa.familyaddons.party.PartyTracker.members.size
    }

    private fun chat(msg: String) {
        MinecraftClient.getInstance().execute {
            MinecraftClient.getInstance().player?.sendMessage(Text.literal(msg), false)
        }
    }
}
