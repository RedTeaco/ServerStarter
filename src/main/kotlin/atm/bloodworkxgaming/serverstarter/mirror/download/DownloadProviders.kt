package atm.bloodworkxgaming.serverstarter.mirror.download

/** 下载源工厂与静态工具（对齐原型测试 DownloadProviders.inject）。 */
object DownloadProviders {
    const val DEFAULT_BMCLAPI_ROOT = "https://bmclapi2.bangbang93.com"
    const val OFFICIAL_VERSION_MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"

    /**
     * 按配置选 provider：source ∈ "mojang" | "bmclapi"；mirrorUrl 非空时作为 apiRoot 覆盖（仅 bmclapi 生效）。
     * 未知 source 抛 IllegalArgumentException。
     */
    fun create(source: String, mirrorUrl: String = ""): DownloadProvider = when (source) {
        "mojang" -> MojangDownloadProvider
        "bmclapi" -> BMCLAPIDownloadProvider(if (mirrorUrl.isEmpty()) DEFAULT_BMCLAPI_ROOT else mirrorUrl)
        else -> throw IllegalArgumentException("Unknown download source: $source (expected 'mojang' or 'bmclapi')")
    }

    /** 按规则表重写 URL：第一个命中前缀的规则生效；无命中原样返回。 */
    fun inject(url: String, rules: List<MirrorRule>): String {
        for (rule in rules) {
            if (url.startsWith(rule.prefix)) {
                return rule.replacement + url.substring(rule.prefix.length)
            }
        }
        return url
    }
}