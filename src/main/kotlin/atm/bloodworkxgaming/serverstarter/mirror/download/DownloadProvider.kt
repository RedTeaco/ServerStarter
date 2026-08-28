package atm.bloodworkxgaming.serverstarter.mirror.download

/**
 * 镜像下载源抽象（对齐 HMCL DownloadProvider 精简版）。
 * download 包不知道"装什么"，只做 URL 注入与候选。
 */
interface DownloadProvider {
    /** 前缀重写单个 URL（无匹配则原样返回）。 */
    fun injectURL(url: String): String

    /** 返回候选 URL 链：[镜像, 官方]，去重；镜像失败后依次回退。 */
    fun injectURLWithCandidates(url: String): List<String>

    /** 原版版本清单 URL 候选链。 */
    fun getVersionListURLs(): List<String>

    /** 并发下载数（默认 4，上限由调用方按 8 封顶）。 */
    fun getConcurrency(): Int
}