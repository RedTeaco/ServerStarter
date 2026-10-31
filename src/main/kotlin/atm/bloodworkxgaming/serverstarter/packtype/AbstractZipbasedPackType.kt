package atm.bloodworkxgaming.serverstarter.packtype

import atm.bloodworkxgaming.serverstarter.InitException
import atm.bloodworkxgaming.serverstarter.InternetManager
import atm.bloodworkxgaming.serverstarter.ServerStarter
import atm.bloodworkxgaming.serverstarter.config.ConfigFile
import atm.bloodworkxgaming.serverstarter.util.ApiVerdict
import atm.bloodworkxgaming.serverstarter.util.PackObtainer
import java.io.File
import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.PathMatcher

abstract class AbstractZipbasedPackType(private val configFile: ConfigFile, protected val internetManager: InternetManager) : IPackType {
    protected val basePath = configFile.install.normalizedInstallPath

    /**
     * 平台 API 下载阶段对每个下载文件的 CF 三态判定（文件名 → ApiVerdict）。
     * 参与安装后 jar 扫描的综合决策；仅 curse 子类在下载时填充，zip 包型无平台数据保持空。
     */
    protected val apiVerdictsByFileMutable = mutableMapOf<String, ApiVerdict>()
    override val apiVerdictsByFile: Map<String, ApiVerdict> get() = apiVerdictsByFileMutable

    /**
     * ① 下载（或本地定位）整合包 zip。
     *
     * Q5：modpackUrl 恰为 "./.zip" 时，扫描进程 CWD 下最新的 `*.zip` / `*.mrpack` 作为整合包；
     * file:// 相对 CWD 解析；其余走网络下载。实现统一在 [PackObtainer]，本类只负责把
     * 包型自己的 [cleanUrl] 传下去。
     *
     * [preObtainedPack] 非空时直接复用（自动识别格式时包已经取过一次，避免重复下载）。
     */
    override fun obtainPack(): File {
        preObtainedPack?.let {
            ServerStarter.LOGGER.info("Reusing the modpack file obtained for format detection: " + it.absolutePath)
            return it
        }
        return PackObtainer.obtain(
                modpackUrl = configFile.install.modpackUrl,
                basePath = basePath,
                internetManager = internetManager,
                cleanUrl = ::cleanUrl,
                preferredFormat = configFile.install.modpackFormat)
    }

    /**
     * 自动识别 `modpackFormat` 时，`ServerStarter` 必须先取到包才能判断格式，取到的文件注入到这里复用；
     * 显式配置格式时保持 null（`obtainPack()` 照常按 URL 下载/本地定位）。
     */
    var preObtainedPack: File? = null

    /**
     * 自动识别格式时，清单可能不在包根目录（重新打包多套一层 `pack/`，或启动器导出的
     * `flame/manifest.json` + `minecraft/`）。识别结果注入这里，包型据此读取清单并剥离前缀。
     * 显式配置格式时保持 null（沿用各包型的默认值）。
     */
    var manifestEntryNameOverride: String? = null

    /** 与 [manifestEntryNameOverride] 配套的解压前缀（`overrides/` / `minecraft/` / `<dir>/`）。 */
    var overridesPrefixOverride: String? = null

    /**
     * ② 从 zip 解析最终生效版本（Q3：manifest 优先、yaml 兜底）。
     *
     * 各子类通过 [readManifestVersions] 提供 manifest 原始版本；纯 zip 格式无 manifest，
     * 版本只能来自 yaml。
     */
    override fun resolveVersions(zip: File): PackVersions {
        val manifest = readManifestVersions(zip)
        val isZipFormat = configFile.install.modpackFormat == "zip" || configFile.install.modpackFormat == "zipfile"

        val mcVersion = mergeVersion(
                manifestValue = manifest?.mcVersion,
                yamlValue = configFile.install.mcVersion,
                isZipFormat = isZipFormat,
                versionName = "MC",
                zipMessage = "zip 格式必须手填 mcVersion",
                missingMessage = "无法确定 MC 版本：整合包 manifest 未提供且 yaml mcVersion 为空"
        )
        val loaderVersion = mergeVersion(
                manifestValue = manifest?.loaderVersion,
                yamlValue = configFile.install.loaderVersion,
                isZipFormat = isZipFormat,
                versionName = "loader",
                zipMessage = "zip 格式必须手填 loaderVersion",
                missingMessage = "无法确定 loader 版本：整合包 manifest 未提供且 yaml loaderVersion 为空"
        )

        val loaderFromManifest = !manifest?.loaderVersion.isNullOrEmpty()

        return PackVersions(mcVersion, loaderVersion, if (loaderFromManifest) manifest?.loaderName else null)
    }

    /**
     * Q3 合并规则（mcVersion 与 loaderVersion 各自独立套用）：
     * - manifest 有值：yaml 非空且不同 → warn 并取 manifest；相同或 yaml 空 → 静默取 manifest。
     * - manifest 无值：yaml 非空 → 取 yaml；yaml 空 → 报错（纯 zip 格式用专门的提示）。
     */
    private fun mergeVersion(
            manifestValue: String?,
            yamlValue: String,
            isZipFormat: Boolean,
            versionName: String,
            zipMessage: String,
            missingMessage: String
    ): String {
        val manifest = manifestValue
        if (manifest != null && manifest.isNotEmpty()) {
            if (yamlValue.isNotEmpty() && yamlValue != manifest) {
                ServerStarter.LOGGER.warn("yaml $versionName version $yamlValue is overridden by modpack version $manifest")
            }
            return manifest
        }

        if (yamlValue.isNotEmpty()) {
            return yamlValue
        }

        throw InitException(if (isZipFormat) zipMessage else missingMessage)
    }

    /**
     * ④ 解压 overrides + 下载模组（Q2 第四步）。
     */
    override fun installPack(zip: File) {
        if (configFile.install.modpackUrl.isEmpty()) return   // 防御；ServerStarter 已在外层守卫
        File(basePath).mkdirs()

        try {
            val patterns = configFile.install.ignoreFiles
                    .map {
                        val s = if (it.startsWith("glob:") || it.startsWith("regex:"))
                            it
                        else
                            "glob:$it"

                        FileSystems.getDefault().getPathMatcher(s)
                    }

            handleZip(zip, patterns)
            postProcessing()
        } catch (e: IOException) {
            ServerStarter.LOGGER.error("Error while installing pack", e)
            throw e
        }
    }

    /**
     * 从整合包 zip 内解析 manifest 中的原始版本；无 manifest 条目时返回 null。
     */
    @Throws(IOException::class)
    protected abstract fun readManifestVersions(zip: File): ManifestVersions?

    /**
     * Overwrite this function to clean the url before downloading it
     */
    protected abstract fun cleanUrl(url: String): String
    protected abstract fun handleZip(file: File, pathMatchers: List<PathMatcher>)
    protected abstract fun postProcessing()
}
