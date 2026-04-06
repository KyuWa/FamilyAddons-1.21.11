package org.kyowa.familyaddons.config

import com.google.gson.annotations.Expose
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorBoolean
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorColour
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorDropdown
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorText
import io.github.notenoughupdates.moulconfig.annotations.ConfigOption

class HighlightConfig {
    @Expose @JvmField
    @ConfigOption(name = "Enable Highlight", desc = "Draw ESP boxes around entities matching the list below.")
    @ConfigEditorBoolean
    var enabled = true

    @Expose @JvmField
    @ConfigOption(name = "Mob Names", desc = "Comma-separated list of mob names to highlight. Case insensitive.")
    @ConfigEditorText
    var mobNames = ""

    @Expose @JvmField
    @ConfigOption(name = "Color", desc = "Color of the ESP box.")
    @ConfigEditorColour
    var color = "0:255:255:0:0"

    @Expose @JvmField
    @ConfigOption(name = "Drawing Style", desc = "How to draw the highlight.")
    @ConfigEditorDropdown(values = ["Box", "Outline"])
    var drawingStyle = 0

    @Expose @JvmField
    @ConfigOption(name = "Show Through Walls", desc = "Show highlight even through blocks.")
    @ConfigEditorBoolean
    var throughWalls = true
}
