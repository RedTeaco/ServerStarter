package atm.bloodworkxgaming.serverstarter.mirror.installer

import atm.bloodworkxgaming.serverstarter.OSUtil
import atm.bloodworkxgaming.serverstarter.ServerStarter.Companion.LOGGER
import atm.bloodworkxgaming.serverstarter.mirror.core.InstallerZip
import atm.bloodworkxgaming.serverstarter.mirror.core.LibraryDownloadTask
import atm.bloodworkxgaming.serverstarter.mirror.download.DownloadProvider
import atm.bloodworkxgaming.serverstarter.mirror.version.VersionManifestClient
import okhttp3.OkHttpClient
import java.io.File

/**
 * NeoForge/Forge 共用安装引擎（两者同构 thin installer，对齐设计文档 §4.3.1 步骤 3-7）：
 * 补全原版 server.jar URL → 下载全部 targets → 静态抽取（替代 EXTRACT_FILES）→ 执行 server 侧 processors。
 * versionType==1 全量；==2 只做下载 + 静态抽取不跑 processors；==0 不应到达此处（编排层已拒绝）。
 */
internal class ThinInstallerEngine(
    private val httpClient: OkHttpClient,
    private val javaCommand: String
) {

    /** 完整镜像安装流程。任一步异常向上抛（由 LoaderManager 兜底回退 --installServer）。 */
    fun install(plan: InstallPlan, basePath: File, installerFile: File, provider: DownloadProvider) {
        var extractDir: File? = null
        try {
            // 1. 补全原版 server.jar URL：vanillaServerUrl 缺失时走版本清单（镜像注入）
            var serverUrl = plan.vanillaServerUrl
            var serverSha1 = plan.vanillaServerSha1
            if (serverUrl == null) {
                val info = VersionManifestClient(httpClient, provider).resolveVanillaVersion(plan.mcVersion)
                serverUrl = info.serverUrl
                    ?: throw DownloadFailedException("vanilla server.jar URL missing: ${plan.mcVersion}")
                serverSha1 = info.serverSha1
            }

            // 2. 下载全部 targets（profile.libraries ∪ version.json.libraries ∪ 原版 server.jar）
            val targets = plan.targets + DownloadTarget(plan.serverJarPath, serverUrl, serverSha1, null)
            LOGGER.info("Total downloads: ${targets.size}")
            LibraryDownloadTask(httpClient, minOf(provider.getConcurrency(), 8))
                .downloadAll(targets, basePath, provider)

            // 3. 静态抽取（替代 EXTRACT_FILES，免一次 JVM）
            extractStaticFiles(plan, basePath, installerFile)

            // 4. 执行 server 侧 processors（versionType==1 且非空）
            if (plan.versionType == 1 && plan.processors.isNotEmpty()) {
                extractDir = File.createTempFile("serverstarter-extract", "").apply { delete(); mkdirs() }
                ProcessorRunner(javaCommand, basePath, installerFile, extractDir) { mc, target ->
                    downloadMojmaps(mc, target, provider)
                }.run(plan)
            }

            LOGGER.info("install success: ${plan.mcVersion} ${plan.loaderVersion}")
        } finally {
            extractDir?.deleteRecursively()
        }
    }

    /** 静态抽取：优先解析 EXTRACT_FILES processor 的 --from/--to 对；无则走硬编码兜底布局。 */
    internal fun extractStaticFiles(plan: InstallPlan, basePath: File, installerFile: File) {
        val extractProcessor = plan.processors.firstOrNull { it.args.contains("EXTRACT_FILES") }
        if (extractProcessor != null) {
            extractFromProcessorArgs(plan, basePath, installerFile, extractProcessor.args)
        } else {
            extractFallback(plan, basePath, installerFile)
        }
    }

    // ---------- EXTRACT_FILES 解析 ----------

    /**
     * 解析 EXTRACT_FILES args 的 --from X --to Y 对。
     * 顺序遍历：遇 "--from" 记 X，遇 "--to" 记 Y 并成对；
     * "--optional"/"--exec" 置标记：若有未成对的 --from 则作用于该对，否则回溯作用于最近已成的对
     * （真实安装器形如 "--to {ROOT}/run.sh --exec {ROOT}/run.sh"、"--to {ROOT}/x --optional {ROOT}/x"）。
     */
    private fun extractFromProcessorArgs(plan: InstallPlan, basePath: File, installerFile: File, args: List<String>) {
        val resolver = ProcessorRunner(javaCommand, basePath, installerFile, File(basePath, ".extract")) { _, _ -> }
        for (pair in parseExtractPairs(args)) {
            val destFile = File(resolver.resolveArg(pair.to, plan))
            if (InstallerZip.hasEntry(installerFile, pair.from)) {
                InstallerZip.extractEntry(installerFile, pair.from, destFile)
                if (pair.exec && OSUtil.isLinux && destFile.name.endsWith(".sh")) {
                    destFile.setExecutable(true, false)
                }
            } else if (pair.optional) {
                LOGGER.info("skip optional: ${pair.from}")
            } else {
                throw MirrorInstallException("EXTRACT_FILES missing: ${pair.from}")
            }
        }
    }

    /** --from/--to 对解析结果。 */
    private data class ExtractPair(
        val from: String,
        val to: String,
        var optional: Boolean = false,
        var exec: Boolean = false
    )

    private fun parseExtractPairs(args: List<String>): List<ExtractPair> {
        val pairs = mutableListOf<ExtractPair>()
        var pendingFrom: String? = null
        var pendingOptional = false
        var pendingExec = false
        var i = 0
        while (i < args.size) {
            when (args[i]) {
                "--from" -> {
                    val from = args.getOrNull(i + 1)
                    if (from != null) {
                        pendingFrom = from
                        i++
                    }
                }
                "--to" -> {
                    val from = pendingFrom
                    val to = args.getOrNull(i + 1)
                    if (from != null && to != null) {
                        pairs.add(ExtractPair(from, to, pendingOptional, pendingExec))
                        pendingFrom = null
                        pendingOptional = false
                        pendingExec = false
                        i++
                    }
                }
                "--optional" -> {
                    val last = pairs.lastOrNull()
                    if (pendingFrom == null && last != null) {
                        last.optional = true
                    } else {
                        pendingOptional = true
                    }
                }
                "--exec" -> {
                    val last = pairs.lastOrNull()
                    if (pendingFrom == null && last != null) {
                        last.exec = true
                    } else {
                        pendingExec = true
                    }
                }
            }
            i++
        }
        return pairs
    }

    // ---------- 兜底布局（versionType==2 或缺失 EXTRACT_FILES） ----------

    private fun extractFallback(plan: InstallPlan, basePath: File, installerFile: File) {
        val argsDir = if (plan.isNeoForge) {
            File(basePath, "libraries/net/neoforged/neoforge/${plan.loaderVersion}")
        } else {
            File(basePath, "libraries/net/minecraftforge/forge/${plan.mcVersion}-${forgeVersion(plan)}")
        }
        extractFirstAvailable(installerFile, argsDir, "unix_args.txt")
        extractFirstAvailable(installerFile, argsDir, "win_args.txt")
        extractFirstAvailable(installerFile, basePath, "run.sh")
        extractFirstAvailable(installerFile, basePath, "run.bat")
        extractFirstAvailable(installerFile, basePath, "user_jvm_args.txt")
    }

    /** 条目优先 "data/xxx" 再 "xxx"；都缺 → info 跳过（兜底不视为失败）。 */
    private fun extractFirstAvailable(installerFile: File, destDir: File, name: String) {
        val entry = when {
            InstallerZip.hasEntry(installerFile, "data/$name") -> "data/$name"
            InstallerZip.hasEntry(installerFile, name) -> name
            else -> null
        }
        if (entry == null) {
            LOGGER.info("missing data/$name，skip")
            return
        }
        InstallerZip.extractEntry(installerFile, entry, File(destDir, name))
    }

    /** Forge loaderVersion → forgeVersion：依次尝试去掉 "{mc}-forge-" 或 "forge-" 前缀，剩余部分。 */
    private fun forgeVersion(plan: InstallPlan): String {
        val mcPrefix = "${plan.mcVersion}-forge-"
        return when {
            plan.loaderVersion.startsWith(mcPrefix) -> plan.loaderVersion.substring(mcPrefix.length)
            plan.loaderVersion.startsWith("forge-") -> plan.loaderVersion.substring("forge-".length)
            else -> plan.loaderVersion
        }
    }

    // ---------- DOWNLOAD_MOJMAPS 特判下载 ----------

    private fun downloadMojmaps(mcVersion: String, target: File, provider: DownloadProvider) {
        val info = VersionManifestClient(httpClient, provider).resolveVanillaVersion(mcVersion)
        val url = info.serverMappingsUrl
            ?: throw DownloadFailedException("vanilla server_mappings URL missing: $mcVersion")
        LibraryDownloadTask(httpClient, 4).downloadToFile(url, target, provider, info.serverMappingsSha1)
    }
}