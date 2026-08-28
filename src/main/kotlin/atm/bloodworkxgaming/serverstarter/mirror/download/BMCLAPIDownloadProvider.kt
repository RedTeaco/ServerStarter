package atm.bloodworkxgaming.serverstarter.mirror.download

/**
 * BMCLAPI 镜像下载源（OpenBMCLAPI 兼容）。
 * apiRoot 默认 [DEFAULT_BMCLAPI_ROOT]，可构造参数覆盖；前缀命中后替换为 apiRoot + 后缀，保留剩余路径。
 */
class BMCLAPIDownloadProvider(private val apiRoot: String = DEFAULT_BMCLAPI_ROOT) : DownloadProvider {

    /** 当前实例的具体规则表（replacement 已按 apiRoot 拼好）。 */
    private val rules: List<MirrorRule> = buildRules(apiRoot)

    override fun injectURL(url: String): String = DownloadProviders.inject(url, rules)

    override fun injectURLWithCandidates(url: String): List<String> {
        val mirrored = injectURL(url)
        return if (mirrored == url) listOf(url) else listOf(mirrored, url)
    }

    override fun getVersionListURLs(): List<String> =
        listOf("$apiRoot/mc/game/version_manifest_v2.json", DownloadProviders.OFFICIAL_VERSION_MANIFEST)

    override fun getConcurrency(): Int = 4

    companion object {
        const val DEFAULT_BMCLAPI_ROOT = "https://bmclapi2.bangbang93.com"

        /** 官方前缀 -> apiRoot 相对后缀（完整前缀表，按任务给定顺序排列）。 */
        private val PREFIX_SUFFIX: List<Pair<String, String>> = listOf(
            "https://piston-meta.mojang.com" to "",
            "https://piston-data.mojang.com" to "",
            "https://launchermeta.mojang.com" to "",
            "https://launcher.mojang.com" to "",
            "https://libraries.minecraft.net" to "/libraries",
            "https://maven.minecraftforge.net" to "/maven",
            "https://files.minecraftforge.net/maven" to "/maven",
            "http://files.minecraftforge.net/maven" to "/maven", // 低版本forge http
            "https://maven.neoforged.net/releases" to "/maven",
            "https://meta.fabricmc.net" to "/fabric-meta",
            "https://maven.fabricmc.net" to "/maven"
        )

        /** 由前缀表生成具体规则（replacement = apiRoot + 后缀）。 */
        internal fun buildRules(apiRoot: String): List<MirrorRule> =
            PREFIX_SUFFIX.map { (prefix, suffix) -> MirrorRule(prefix, apiRoot + suffix) }
    }
}