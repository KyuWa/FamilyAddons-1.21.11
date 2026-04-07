package org.kyowa.familyaddons.config

import com.google.gson.annotations.Expose
import io.github.notenoughupdates.moulconfig.annotations.*

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

    @Expose @JvmField
    @ConfigOption(name = "Pickobulus Timer", desc = "Show a HUD timer after using Pickobulus.")
    @ConfigEditorBoolean
    var pickobulusTimer = true

    @Expose @JvmField
    @ConfigOption(name = "Pickobulus HUD Scale", desc = "Scale of the Pickobulus timer HUD (e.g. 1.5).")
    @ConfigEditorText
    var pickobulusHudScale = "1.5"

    @Expose @JvmField
    var pickobulusHudX = -1

    @Expose @JvmField
    var pickobulusHudY = -1
}
