package org.kyowa.familyaddons.config

import com.google.gson.annotations.Expose
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorBoolean
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorDropdown
import io.github.notenoughupdates.moulconfig.annotations.ConfigOption

class MineshaftConfig {
    @Expose @JvmField
    @ConfigOption(name = "Corpse ESP", desc = "Show boxes for unclaimed corpses in the mineshaft.")
    @ConfigEditorBoolean
    var corpseESP = true

    @Expose @JvmField
    @ConfigOption(name = "Corpse Drawing Style", desc = "How to draw corpse ESP.")
    @ConfigEditorDropdown(values = ["Box", "Outline"])
    var corpseDrawingStyle = 0

    @Expose @JvmField
    @ConfigOption(name = "Corpse Announce", desc = "Announce corpse types to party chat when entering a mineshaft.")
    @ConfigEditorBoolean
    var corpseAnnounce = true
}
