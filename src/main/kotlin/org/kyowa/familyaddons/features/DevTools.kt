package org.kyowa.familyaddons.features

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.screen.ChatScreen
import net.minecraft.component.DataComponentTypes
import net.minecraft.scoreboard.Team
import net.minecraft.text.Text
import org.kyowa.familyaddons.COLOR_CODE_REGEX
import org.kyowa.familyaddons.config.FamilyConfigManager
import org.lwjgl.glfw.GLFW

object DevTools {

    private var scoreboardWasDown = false
    private var tabListWasDown = false
    private var itemNbtWasDown = false
    private var copyRawWasDown = false

    fun register() {
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            val cfg = FamilyConfigManager.config.dev
            val handle = client.window.handle

            fun isDown(key: Int) = key != GLFW.GLFW_KEY_UNKNOWN &&
                    GLFW.glfwGetKey(handle, key) == GLFW.GLFW_PRESS

            val scoreboardDown = isDown(cfg.scoreboardKey)
            val tabListDown    = isDown(cfg.tabListKey)
            val itemNbtDown    = isDown(cfg.itemNbtKey)
            val copyRawDown    = isDown(cfg.copyRawChatKey)

            if (client.currentScreen == null) {
                if (scoreboardDown && !scoreboardWasDown) grabScoreboard(client)
                if (tabListDown && !tabListWasDown) grabTabList(client)
                if (itemNbtDown && !itemNbtWasDown) grabItemNbt(client)
            }

            if (client.currentScreen is ChatScreen) {
                if (copyRawDown && !copyRawWasDown) copyHoveredChat(client)
            }

            scoreboardWasDown = scoreboardDown
            tabListWasDown    = tabListDown
            itemNbtWasDown    = itemNbtDown
            copyRawWasDown    = copyRawDown
        }
    }

    fun getScoreboardLines(client: MinecraftClient): List<String> {
        val scoreboard = client.world?.scoreboard ?: return emptyList()
        val objective = scoreboard.getObjectiveForSlot(
            net.minecraft.scoreboard.ScoreboardDisplaySlot.SIDEBAR
        ) ?: return emptyList()
        return scoreboard.getScoreboardEntries(objective)
            .filter { !it.hidden() }
            .map { entry ->
                val team = scoreboard.getScoreHolderTeam(entry.owner())
                Team.decorateName(team, entry.name()).string.replace(COLOR_CODE_REGEX, "").trim()
            }
            .filter { it.isNotEmpty() }
    }

    private fun grabScoreboard(client: MinecraftClient) {
        val player = client.player ?: return
        val lines = getScoreboardLines(client)
        if (lines.isEmpty()) {
            chat(client, "§6[FA Dev] §cNo scoreboard entries found."); return
        }
        chat(client, "§6[FA Dev] §eScoreboard (${lines.size} lines):")
        lines.forEach { line ->
            player.sendMessage(Text.literal("  §f$line"), false)
        }
        client.keyboard.clipboard = lines.joinToString("\n")
        chat(client, "§6[FA Dev] §7Copied to clipboard.")
    }

    private fun grabTabList(client: MinecraftClient) {
        val player = client.player ?: return
        val tabList = client.networkHandler?.playerList ?: run {
            chat(client, "§6[FA Dev] §cNo tab list found."); return
        }
        chat(client, "§6[FA Dev] §eTab list entries (${tabList.size}):")
        val sb = StringBuilder()
        tabList.forEach { entry ->
            val displayName = entry.displayName?.string ?: "(no display name)"
            val profileName = entry.profile.name ?: "(no profile name)"
            val clean = displayName.replace(COLOR_CODE_REGEX, "")
            player.sendMessage(Text.literal("  §7profile: §f$profileName §8| §7clean: §f$clean"), false)
            sb.appendLine("profile=$profileName | clean=$clean")
        }
        client.keyboard.clipboard = sb.toString()
        chat(client, "§6[FA Dev] §7All entries copied to clipboard.")
    }

    private fun grabItemNbt(client: MinecraftClient) {
        val player = client.player ?: return
        val stack = player.mainHandStack
        if (stack.isEmpty) {
            chat(client, "§6[FA Dev] §cNo item in main hand."); return
        }
        val name = stack.name.string.replace(COLOR_CODE_REGEX, "")
        chat(client, "§6[FA Dev] §eItem: §f$name §7| Count: §f${stack.count}")

        val customData = stack.get(DataComponentTypes.CUSTOM_DATA)
        if (customData != null) {
            val nbt = customData.copyNbt()
            chat(client, "§6[FA Dev] §eNBT keys:")
            nbt.keys.forEach { key ->
                val value = nbt.get(key).toString()
                val truncated = if (value.length > 80) value.take(80) + "..." else value
                player.sendMessage(Text.literal("  §e$key §8= §f$truncated"), false)
            }
            client.keyboard.clipboard = nbt.toString()
            chat(client, "§6[FA Dev] §7Full NBT copied to clipboard.")
        } else {
            chat(client, "§6[FA Dev] §7No custom NBT on this item.")
        }
    }

    private fun copyHoveredChat(client: MinecraftClient) {
        // In 1.21.11, ChatHud does not expose getTextStyleAt publicly.
        // We instead grab the raw text of the most recently visible message
        // at the mouse position using the message list via reflection, or
        // fall back to copying the last received raw message string.
        try {
            val chatHud = client.inGameHud?.chatHud ?: run {
                chat(client, "§6[FA Dev] §cCould not access chat HUD."); return
            }
            val mx = client.mouse.x / client.window.scaleFactor
            val my = client.mouse.y / client.window.scaleFactor

            // Try reflection to call getTextStyleAt if it exists under any name
            val method = chatHud.javaClass.methods.firstOrNull { m ->
                m.parameterCount == 2 &&
                        m.parameterTypes[0] == Double::class.java &&
                        m.parameterTypes[1] == Double::class.java
            }
            if (method != null) {
                val result = method.invoke(chatHud, mx, my)
                if (result != null) {
                    val raw = result.toString()
                    client.keyboard.clipboard = raw
                    chat(client, "§6[FA Dev] §7Copied style: §f${raw.take(100)}")
                    return
                }
            }
            chat(client, "§6[FA Dev] §cNo hovered message found. Try hovering directly over a chat line.")
        } catch (e: Exception) {
            chat(client, "§6[FA Dev] §cCopy chat failed: ${e.message?.take(60)}")
        }
    }

    private fun chat(client: MinecraftClient, msg: String) {
        client.execute {
            client.player?.sendMessage(Text.literal(msg), false)
        }
    }
}