package com.aliucord.manager.network.services

import com.aliucord.manager.network.models.GithubRelease
import com.aliucord.manager.network.utils.ApiResponse
import com.aliucord.manager.network.models.ControlRepoEntry
import io.ktor.client.request.header
import io.ktor.client.request.url
import io.ktor.http.HttpHeaders

class RainCodebergService(
    private val http: HttpService,
) {
    suspend fun getManagerReleases(): ApiResponse<List<GithubRelease>> {
        return http.request {
            url("https://codeberg.org/api/v1/repos/$ORG/$MANAGER_REPO/releases")
            header(HttpHeaders.CacheControl, "public, max-age=60, s-maxage=60")
        }
    }

    suspend fun getLatestXposedRelease(): ApiResponse<GithubRelease> {
        return http.request {
            url("https://codeberg.org/api/v1/repos/$ORG/$XPOSED_REPO/releases/latest")
            header(HttpHeaders.CacheControl, "public, max-age=60, s-maxage=60")
        }
    }

    suspend fun getControlRepo(): ApiResponse<List<ControlRepoEntry>> {
        return http.request {
            url("https://codeberg.org/raincord/$CONTROL_REPO/raw/branch/main/control.json")
            header(HttpHeaders.CacheControl, "public, max-age=60, s-maxage=60")
        }
    }

    companion object {
        const val ORG = "raincord"
        const val MANAGER_REPO = "rainmanager"
        const val XPOSED_REPO = "rainxposed"
        const val CONTROL_REPO = "ControlRepo"
    }
}