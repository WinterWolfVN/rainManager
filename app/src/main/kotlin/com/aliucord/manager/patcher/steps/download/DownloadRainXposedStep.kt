package com.aliucord.manager.patcher.steps.download

import com.aliucord.manager.manager.PathManager
import com.aliucord.manager.network.services.RainCodebergService
import com.aliucord.manager.network.utils.SemVer
import com.aliucord.manager.network.utils.getOrThrow
import com.aliucord.manager.patcher.StepRunner
import com.aliucord.manager.patcher.steps.base.DownloadStep
import dev.raincord.manager.R
import org.koin.core.component.inject

class DownloadRainXposedStep : DownloadStep() {
    private val paths: PathManager by inject()
    private val rainCodebergService: RainCodebergService by inject()

    override val localizedName = R.string.patch_step_dl_rainxposed
    override val targetFile get() = paths.cachedRainXposed(targetVersion)
    override lateinit var targetUrl: String

    lateinit var targetVersion: SemVer
        private set

    override suspend fun execute(container: StepRunner) {
        val latestRelease = rainCodebergService.getLatestXposedRelease().getOrThrow()
        container.log("Latest RainXposed release is ${latestRelease.name}")

        targetVersion = SemVer.parse(latestRelease.name)
        targetUrl = latestRelease.assets.first { it.name == "app-release.apk" }.browserDownloadUrl
        super.execute(container)
    }
}
