package com.aliucord.manager.patcher.steps.install

import com.aliucord.manager.patcher.StepRunner
import com.aliucord.manager.patcher.steps.StepGroup
import com.aliucord.manager.patcher.steps.base.Step
import com.aliucord.manager.patcher.steps.download.CopyDependenciesStep
import com.aliucord.manager.patcher.steps.download.DownloadRainXposedStep
import com.aliucord.manager.patcher.util.Signer
import dev.raincord.manager.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.lsposed.patch.NPatch
import org.lsposed.patch.util.Logger
import java.io.File

class InjectRainXposedStep : Step() {
    override val group: StepGroup = StepGroup.Install
    override val localizedName: Int = R.string.patch_step_inject_rain

    suspend fun patch(
        container: StepRunner,
        outputDir: File,
        apkPaths: List<String>,
        embeddedModules: List<String>,
    ) {
        withContext(Dispatchers.IO) {
            NPatch(
                object : Logger() {
                    override fun d(p0: String?) {
                        container.log("[NPatch:D] $p0")
                    }

                    override fun e(p0: String?) {
                        container.log("[NPatch:E] $p0")
                    }

                    override fun i(p0: String?) {
                        container.log("[NPatch] $p0")
                    }
                },
                *apkPaths.toTypedArray(),
                "-o",
                outputDir.absolutePath,
                "-f",
                "-v",
                "-m",
                *embeddedModules.toTypedArray(),
                "-k",
                Signer.getKeystoreFile().absolutePath,
                "password",
                "alias",
                "password"
            ).doCommandLine()
        }
    }

    override suspend fun execute(container: StepRunner) {
        val apks = container.getStep<CopyDependenciesStep>().patchedApks
        val xposed = container.getStep<DownloadRainXposedStep>().targetFile

        container.log("Adding RainXposed module with NPatch")

        val tempDir = apks.first().parentFile!!.resolve("npatched")
        tempDir.mkdirs()

        patch(
            container,
            outputDir = tempDir,
            apkPaths = apks.map { it.absolutePath },
            embeddedModules = listOf(xposed.absolutePath)
        )

        apks.forEach { originalApk ->
            val baseName = originalApk.nameWithoutExtension
            val patchedApk = tempDir.listFiles()?.firstOrNull {
                it.name.startsWith(baseName) && it.name.contains("patched")
            }

            if (patchedApk != null && patchedApk.exists()) {
                patchedApk.copyTo(originalApk, overwrite = true)
                container.log("Replaced ${originalApk.name} with ${patchedApk.name}")
            } else {
                container.log("Warning: Could not find patched APK for ${originalApk.name}")
            }
        }

        tempDir.deleteRecursively()
    }
}
