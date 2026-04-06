package org.kyowa.familyaddons.party

import org.kyowa.familyaddons.COLOR_CODE_REGEX
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents

object PartyTracker {

    private val RANK_REGEX = Regex("""\[[^\]]+\]\s*""")
    private val SYMBOL_REGEX = Regex("[+★✦✧☆✪✫✬✭✮✯❖◆◇◈•●▪■▶»():,]")
    private val SPACE_REGEX = Regex("""\s+""")
    private val NAME_REGEX = Regex("[A-Za-z0-9_]{3,16}")
    private val PARTY_CHAT_REGEX = Regex("""^Party\s*[>»]\s*(?:\[[^\]]+\]\s*)?([^:]+):\s*(.+)$""")
    private val JOINED_REGEX = Regex("""^(.+?) joined the party\.$""", RegexOption.IGNORE_CASE)
    private val LEFT_REGEX = Regex("""^(.+?) left the party\.$""", RegexOption.IGNORE_CASE)

    val members = mutableSetOf<String>()
    var leader: String? = null

    fun register() {
        ClientReceiveMessageEvents.ALLOW_GAME.register { message, _ ->
            val plain = message.string.replace(COLOR_CODE_REGEX, "").trim()
            handleLine(plain)
            true
        }
    }

    private fun handleLine(plain: String) {
        val partyChat = PARTY_CHAT_REGEX.find(plain)
        if (partyChat != null) {
            val name = cleanName(partyChat.groupValues[1])
            if (name.isNotEmpty()) members.add(name)
            return
        }

        when {
            plain.startsWith("Party Leader:") -> {
                val after = plain.removePrefix("Party Leader:").trim()
                val name = cleanName(after)
                if (name.isNotEmpty()) { leader = name; members.add(name) }
            }
            plain.startsWith("Party Moderators:") || plain.startsWith("Party Members:") -> {
                extractAllNames(plain).forEach { members.add(it) }
            }
        }

        JOINED_REGEX.find(plain)?.let {
            val name = cleanName(it.groupValues[1])
            if (name.isNotEmpty()) members.add(name)
        }

        LEFT_REGEX.find(plain)?.let {
            val name = cleanName(it.groupValues[1])
            members.remove(name)
        }

        if (plain.equals("You left the party.", ignoreCase = true) ||
            plain.contains("disbanded the party", ignoreCase = true)) {
            members.clear()
            leader = null
        }
    }

    fun cleanName(s: String): String {
        var x = s.replace(RANK_REGEX, " ").trim()
        x = x.replace(SYMBOL_REGEX, " ")
        x = x.replace(SPACE_REGEX, " ").trim()
        val tokens = x.split(" ").filter { it.matches(NAME_REGEX) }
        return tokens.lastOrNull() ?: ""
    }

    private fun extractAllNames(line: String): List<String> {
        var x = line.replace(RANK_REGEX, " ")
        x = x.replace(SYMBOL_REGEX, " ")
        x = x.replace(SPACE_REGEX, " ").trim()
        return x.split(" ").filter { it.matches(NAME_REGEX) }
    }

    fun resolveMember(query: String, allowSelf: Boolean, selfName: String): String? {
        val pool = if (allowSelf) members.toList()
        else members.filter { it.lowercase() != selfName.lowercase() }
        if (pool.isEmpty()) return null
        val q = query.lowercase()
        pool.find { it.lowercase() == q }?.let { return it }
        val tie = { arr: List<String> -> arr.sortedWith(compareBy({ it.length }, { it })).firstOrNull() }
        val starts = pool.filter { it.lowercase().startsWith(q) }
        if (starts.size == 1) return starts[0]
        if (starts.size > 1) return tie(starts)
        val inc = pool.filter { it.lowercase().contains(q) }
        if (inc.isEmpty()) return null
        return tie(inc)
    }
}
