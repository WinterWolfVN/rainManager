package com.aliucord.manager.patcher.steps.patch

import android.content.Context
import android.graphics.*
import android.graphics.drawable.InsetDrawable
import android.os.Build
import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.ContextCompat
import androidx.core.graphics.applyCanvas
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
import androidx.core.graphics.drawable.toBitmap
import com.aliucord.manager.patcher.StepRunner
import com.aliucord.manager.patcher.steps.StepGroup
import com.aliucord.manager.patcher.steps.base.Step
import com.aliucord.manager.patcher.steps.base.StepState
import com.aliucord.manager.patcher.steps.download.CopyDependenciesStep
import com.aliucord.manager.patcher.util.ArscUtil
import com.aliucord.manager.patcher.util.ArscUtil.addColorResource
import com.aliucord.manager.patcher.util.ArscUtil.addResource
import com.aliucord.manager.patcher.util.ArscUtil.getMainArscChunk
import com.aliucord.manager.patcher.util.ArscUtil.getPackageChunk
import com.aliucord.manager.patcher.util.ArscUtil.getResourceFileName
import com.aliucord.manager.patcher.util.ArscUtil.getResourceFileNames
import com.aliucord.manager.patcher.util.AxmlUtil
import com.aliucord.manager.ui.screens.patchopts.PatchOptions
import com.aliucord.manager.ui.screens.patchopts.PatchOptions.IconReplacement
import com.aliucord.manager.util.getResBytes
import com.github.diamondminer88.zip.ZipWriter
import com.google.devrel.gmscore.tools.apk.arsc.*
import dev.raincord.manager.R
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.ByteArrayOutputStream
import java.io.File

@Stable
class PatchIconsStep(private val options: PatchOptions) : Step(), KoinComponent {
    private val context: Context by inject()

    override val group = StepGroup.Patch
    override val localizedName = R.string.patch_step_patch_icon

    private val isAdaptiveIconsAvailable = Build.VERSION.SDK_INT >= 26
    private val isMonochromeIconsAvailable = Build.VERSION.SDK_INT >= 31

    override suspend fun execute(container: StepRunner) {
        container.log("isAdaptiveIconsAvailable: $isAdaptiveIconsAvailable, isMonochromeIconsAvailable: $isMonochromeIconsAvailable")

        if (!isMonochromeIconsAvailable && options.iconReplacement is IconReplacement.Original) {
            container.log("No patching necessary, skipping step")
            state = StepState.Skipped
            return
        }

        container.log("Parsing resources.arsc")
        val apk = container.getStep<CopyDependenciesStep>().patchedApk
        
        val arsc = try {
            ArscUtil.readArsc(apk)
        } catch (e: Exception) {
            container.log("ARSC parsing failed, falling back to raw path injection")
            null
        }

        if (isAdaptiveIconsAvailable && arsc != null) {
            patchAdaptiveIcons(container, apk, arsc)
        } else {
            patchRawIcons(container, apk, arsc)
        }
    }

    private fun patchAdaptiveIcons(
        container: StepRunner,
        apk: File,
        arsc: BinaryResourceFile,
    ) {
        container.log("Parsing AndroidManifest.xml and obtaining adaptive square/round icon file paths")
        val iconResourceIds = AxmlUtil.readManifestIconInfo(apk)
        val squareIconFile = arsc.getMainArscChunk().getResourceFileName(
            resourceId = iconResourceIds.squareIcon,
            configurationName = "anydpi-v26"
        )
        val roundIconFile = arsc.getMainArscChunk().getResourceFileName(
            resourceId = iconResourceIds.roundIcon,
            configurationName = "anydpi-v26"
        )

        var foregroundIcon: BinaryResourceIdentifier? = null
        var backgroundIcon: BinaryResourceIdentifier? = null
        var monochromeIcon: BinaryResourceIdentifier? = null

        if (isMonochromeIconsAvailable) {
            container.log("Adding monochrome icon resource to arsc")

            val filePathIdx = arsc.getMainArscChunk().stringPool
                .addString("res/ic_aliucord_monochrome.xml")

            monochromeIcon = arsc.getPackageChunk().addResource(
                typeName = "drawable",
                resourceName = "ic_aliucord_monochrome",
                configurations = { it.isDefault },
                valueType = BinaryResourceValue.Type.STRING,
                valueData = filePathIdx,
            )
        }

        if (options.iconReplacement is IconReplacement.CustomColor) {
            container.log("Adding icon color resource to arsc")
            backgroundIcon = arsc.getPackageChunk()
                .addColorResource("icon_background_replacement", options.iconReplacement.color)
        }
        else if (options.iconReplacement is IconReplacement.OldDiscord) {
            container.log("Adding icon color resource to arsc")
            backgroundIcon = arsc.getPackageChunk()
                .addColorResource("icon_background_replacement", IconReplacement.OldBlurpleColor)

            container.log("Adding custom icon foreground to arsc")

            val iconPathIdx = arsc.getMainArscChunk().stringPool
                .addString("res/ic_foreground_replacement.xml")

            foregroundIcon = arsc.getPackageChunk().addResource(
                typeName = "drawable",
                resourceName = "ic_foreground_replacement",
                configurations = { it.toString() == "anydpi" },
                valueType = BinaryResourceValue.Type.STRING,
                valueData = iconPathIdx,
            )
        }
        else if (options.iconReplacement is IconReplacement.CustomImage) {
            container.log("Adding custom icon foreground to arsc")

            val iconPathIdx = arsc.getMainArscChunk().stringPool
                .addString("res/ic_foreground_replacement.png")

            foregroundIcon = arsc.getPackageChunk().addResource(
                typeName = "mipmap",
                resourceName = "ic_foreground_replacement",
                configurations = { it.toString().endsWith("dpi") },
                valueType = BinaryResourceValue.Type.STRING,
                valueData = iconPathIdx,
            )
        }

        for (rscFile in setOf(squareIconFile, roundIconFile)) {
            container.log("Patching and writing adaptive icon AXML at $rscFile")
            AxmlUtil.patchAdaptiveIcon(
                apk = apk,
                resourcePath = rscFile,
                backgroundColor = backgroundIcon,
                foregroundIcon = foregroundIcon,
                monochromeIcon = monochromeIcon,
            )
        }

        container.log("Writing other patched files back to apk")
        ZipWriter(apk, true).use {
            if (isMonochromeIconsAvailable) {
                val monochromeIconId = if (options.iconReplacement is IconReplacement.OldDiscord) {
                    R.drawable.ic_discord_monochrome
                } else {
                    R.drawable.ic_discord_old_monochrome
                }

                container.log("Writing monochrome icon AXML to apk")
                it.writeEntry("res/ic_aliucord_monochrome.xml", context.getResBytes(monochromeIconId))
            }

            if (options.iconReplacement is IconReplacement.OldDiscord) {
                container.log("Writing custom icon foreground to apk")
                it.writeEntry("res/ic_foreground_replacement.xml", context.getResBytes(R.drawable.ic_discord_old_monochrome))
            } else if (options.iconReplacement is IconReplacement.CustomImage) {
                container.log("Writing custom icon foreground to apk")
                it.writeEntry("res/ic_foreground_replacement.png", options.iconReplacement.imageBytes)
            }

            container.log("Writing resources unaligned compressed")
            it.deleteEntry("resources.arsc")
            it.writeEntry("resources.arsc", arsc.toByteArray())
        }
    }

    private fun patchRawIcons(
        container: StepRunner,
        apk: File,
        arsc: BinaryResourceFile?,
    ) {
        var allIconFiles = emptyList<String>()

        if (arsc != null) {
            try {
                container.log("Parsing AndroidManifest.xml and obtaining all square/round launcher icon file paths")
                val iconResourceIds = AxmlUtil.readManifestIconInfo(apk)
                val squareIconFiles = arsc.getMainArscChunk().getResourceFileNames(
                    resourceId = iconResourceIds.squareIcon,
                    configurations = { it.toString() != "anydpi-v26" },
                )
                val roundIconFiles = arsc.getMainArscChunk().getResourceFileNames(
                    resourceId = iconResourceIds.roundIcon,
                    configurations = { it.toString() != "anydpi-v26" },
                )
                allIconFiles = squareIconFiles + roundIconFiles
            } catch (e: Exception) {
                container.log("AXML Parsing failed. Proceeding with fallback icon paths.")
            }
        }

        if (allIconFiles.isEmpty()) {
            allIconFiles = listOf(
                "res/mipmap-hdpi-v4/ic_launcher.png",
                "res/mipmap-mdpi-v4/ic_launcher.png",
                "res/mipmap-xhdpi-v4/ic_launcher.png",
                "res/mipmap-xxhdpi-v4/ic_launcher.png",
                "res/mipmap-xxxhdpi-v4/ic_launcher.png",
                "res/mipmap-hdpi-v4/ic_launcher_round.png",
                "res/mipmap-mdpi-v4/ic_launcher_round.png",
                "res/mipmap-xhdpi-v4/ic_launcher_round.png",
                "res/mipmap-xxhdpi-v4/ic_launcher_round.png",
                "res/mipmap-xxxhdpi-v4/ic_launcher_round.png",
                "res/mipmap-hdpi/ic_launcher.png",
                "res/mipmap-mdpi/ic_launcher.png",
                "res/mipmap-xhdpi/ic_launcher.png",
                "res/mipmap-xxhdpi/ic_launcher.png",
                "res/mipmap-xxxhdpi/ic_launcher.png"
            )
        }

        val backgroundColor = when (options.iconReplacement) {
            IconReplacement.Original -> IconReplacement.AliucordColor
            IconReplacement.OldDiscord -> IconReplacement.OldBlurpleColor
            is IconReplacement.CustomColor -> options.iconReplacement.color
            is IconReplacement.CustomImage -> IconReplacement.AliucordColor
        }

        container.log("Generating static launcher icon")
        val icon = createDiscordLauncherIcon(
            backgroundColor = backgroundColor,
            oldLogo = options.iconReplacement == IconReplacement.OldDiscord,
        )
        val iconBytes = ByteArrayOutputStream().use {
            icon.compress(Bitmap.CompressFormat.PNG, 0, it)
            icon.recycle()
            it.toByteArray()
        }

        container.log("Writing patched icons back to apk")
        ZipWriter(apk, true).use {
            it.deleteEntries(allIconFiles)

            for (path in allIconFiles)
                it.writeEntry(path, iconBytes)
        }
    }

    private fun createDiscordLauncherIcon(
        backgroundColor: Color,
        oldLogo: Boolean = false,
        size: Int = 192,
    ): Bitmap {
        val paint = Paint().apply {
            style = Paint.Style.FILL
            setColor(backgroundColor.toArgb())
        }

        val drawableId = when (oldLogo) {
            false -> R.drawable.ic_discord
            true -> R.drawable.ic_discord_old
        }
        val vectorDrawable = ContextCompat.getDrawable(context, drawableId)!!
        
        val wrappedDrawable = DrawableCompat.wrap(vectorDrawable).mutate()
        DrawableCompat.setTint(wrappedDrawable, Color.White.toArgb())

        val drawable = InsetDrawable(wrappedDrawable, (size * .17f).toInt())

        val icon = createBitmap(size, size).applyCanvas {
            drawRect(Rect(0, 0, width, height), paint)
            drawable.setBounds(0, 0, width, height)
            drawable.draw(this)
        }
        
        val iconRound = RoundedBitmapDrawableFactory.create(context.resources, icon).apply {
            isCircular = true
            setAntiAlias(true)
        }.toBitmap()

        icon.recycle()
        return iconRound
    }
}
