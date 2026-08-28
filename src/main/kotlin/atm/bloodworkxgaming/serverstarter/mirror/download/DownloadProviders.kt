package atm.bloodworkxgaming.serverstarter.mirror.download

/** 下载源工厂与静态工具（对齐原型测试 DownloadProviders.rulesFor / inject）。 */
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

    /**
     * 按目标类型返回相关的前缀重写规则。
     * target ∈ neoforge | forge | fabric | vanilla；mirrorId = "bmclapi"（返回该镜像的规则子集）或 "mojang"（返回空表）。
     * 语义：neoforge → neoforged maven + libraries + piston 组；forge → forge maven + libraries + piston 组；
     *       fabric → fabric-meta + fabric maven；vanilla → piston 组（piston-meta/piston-data/launchermeta/launcher）。
     * 未知 mirrorId 抛 IllegalArgumentException。
     */
//    fun rulesFor(target: String, mirrorId: String): List<MirrorRule> {
//        if (mirrorId == "mojang") return emptyList()
//        if (mirrorId != "bmclapi") {
//            throw IllegalArgumentException("Unknown mirror id: $mirrorId (expected 'mojang' or 'bmclapi')")
//        }
//        val wanted: Set<String> = when (target) {
//            "neoforge" -> setOf(
//                "https://maven.neoforged.net/releases",
//                "https://libraries.minecraft.net",
//                "https://piston-meta.mojang.com",
//                "https://piston-data.mojang.com",
//                "https://launchermeta.mojang.com",
//                "https://launcher.mojang.com"
//            )
//            "forge" -> setOf(
//                "https://maven.minecraftforge.net",
//                "https://files.minecraftforge.net/maven",
//                "http://files.minecraftforge.net/maven",
//                "https://libraries.minecraft.net",
//                "https://piston-meta.mojang.com",
//                "https://piston-data.mojang.com",
//                "https://launchermeta.mojang.com",
//                "https://launcher.mojang.com"
//            )
//            "fabric" -> setOf("https://meta.fabricmc.net", "https://maven.fabricmc.net")
//            "vanilla" -> setOf(
//                "https://piston-meta.mojang.com",
//                "https://piston-data.mojang.com",
//                "https://launchermeta.mojang.com",
//                "https://launcher.mojang.com"
//            )
//            else -> throw IllegalArgumentException("Unknown target: $target (expected 'neoforge', 'forge', 'fabric' or 'vanilla')")
//        }
//        return BMCLAPIDownloadProvider.buildRules(DEFAULT_BMCLAPI_ROOT).filter { it.prefix in wanted }
//    }

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