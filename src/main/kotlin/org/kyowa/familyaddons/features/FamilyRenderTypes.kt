package org.kyowa.familyaddons.features

import com.mojang.blaze3d.platform.DepthTestFunction
import net.minecraft.client.gl.RenderPipelines
import net.minecraft.client.render.LayeringTransform
import net.minecraft.client.render.OutputTarget
import net.minecraft.client.render.RenderLayer
import net.minecraft.client.render.RenderSetup
import net.minecraft.util.Identifier

object FamilyRenderTypes {

    val LINES: RenderLayer by lazy {
        RenderLayer.of(
            "familyaddons_lines",
            RenderSetup.builder(RenderPipelines.LINES)
                .layeringTransform(LayeringTransform.VIEW_OFFSET_Z_LAYERING)
                .outputTarget(OutputTarget.MAIN_TARGET)
                .build()
        )
    }

    val LINES_NO_DEPTH: RenderLayer by lazy {
        val pipeline = RenderPipelines.register(
            com.mojang.blaze3d.pipeline.RenderPipeline.builder(RenderPipelines.RENDERTYPE_LINES_SNIPPET)
                .withLocation(Identifier.of("familyaddons", "pipeline/lines_no_depth"))
                .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
                .withDepthWrite(false)
                .build()
        )
        RenderLayer.of(
            "familyaddons_lines_no_depth",
            RenderSetup.builder(pipeline)
                .outputTarget(OutputTarget.MAIN_TARGET)
                .build()
        )
    }
}