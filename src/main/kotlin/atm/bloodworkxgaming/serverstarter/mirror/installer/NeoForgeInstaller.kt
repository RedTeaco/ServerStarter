package atm.bloodworkxgaming.serverstarter.mirror.installer

import atm.bloodworkxgaming.serverstarter.mirror.download.DownloadProvider
import okhttp3.OkHttpClient
import java.io.File

/** NeoForge 镜像安装器（thin installer 同构，委托 ThinInstallerEngine）。 */
class NeoForgeInstaller(httpClient: OkHttpClient, javaCommand: String) : ModLoaderInstaller {
    private val engine = ThinInstallerEngine(httpClient, javaCommand)

    override fun install(plan: InstallPlan, basePath: File, installerFile: File, provider: DownloadProvider) =
        engine.install(plan, basePath, installerFile, provider)
}