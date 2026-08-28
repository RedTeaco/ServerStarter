package atm.bloodworkxgaming.serverstarter.mirror.installer

import atm.bloodworkxgaming.serverstarter.mirror.download.DownloadProvider
import java.io.File

/** 镜像安装器：消费 InstallPlan 完成 targets 下载、静态抽取、processors 执行。 */
interface ModLoaderInstaller {
    fun install(plan: InstallPlan, basePath: File, installerFile: File, provider: DownloadProvider)
}