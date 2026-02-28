package com.aliucord.manager.patcher.steps.prepare

import com.aliucord.manager.network.services.RNATrackerService
import com.aliucord.manager.network.utils.getOrThrow
import com.aliucord.manager.patcher.StepRunner
import com.aliucord.manager.patcher.steps.StepGroup
import com.aliucord.manager.patcher.steps.base.Step
import com.aliucord.manager.ui.screens.patchopts.PatchOptions
import dev.raincord.manager.R
import org.json.JSONObject
import org.json.JSONArray
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.net.URL
import kotlin.properties.Delegates

class FetchDiscordRNAStep(val options: PatchOptions) : Step(), KoinComponent {
    private val rnaTrackerService: RNATrackerService by inject()

    override val group: StepGroup = StepGroup.Prepare
    override val localizedName: Int = R.string.patch_step_fetch_rna

    var targetVersion by Delegates.notNull<Int>()
        private set

    override suspend fun execute(container: StepRunner) {
    container.log("Starting Discord version resolution...")

        val remoteVersion = fetchControlRepoVersion() // Always try ControlRepo first

        if (remoteVersion != null) {
            targetVersion = remoteVersion
            container.log("Successfully fetched default version from ControlRepo: $targetVersion")
        } else {
            container.log("ControlRepo fetch failed. Checking for developer override...")
            if (options.isDevMode && options.customVersionCode.isNotBlank()) { // Check dev mode AND custom version
                targetVersion = options.customVersionCode.toInt()
                container.log("Using manual developer override version (Dev Mode ON): $targetVersion")
            } else {
                container.log("No ControlRepo version, no valid developer override. Falling back to RNATracker Stable...")
                val latestVal = rnaTrackerService.getLatestDiscordVersions().getOrThrow()
                targetVersion = latestVal.latest.stable
            }
        }



        container.log("Selected Discord version: $targetVersion")
    }

    private suspend fun fetchControlRepoVersion(): Int? {
        return try {
            val url = "https://codeberg.org/raincord/ControlRepo/raw/branch/main/control.json"
            val response = URL(url).readText()
            val jsonArray = JSONArray(response)
            if (jsonArray.length() > 0) {
                val jsonObject = jsonArray.getJSONObject(0)
                jsonObject.optInt("discord", 0).takeIf { it != 0 }
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
}
