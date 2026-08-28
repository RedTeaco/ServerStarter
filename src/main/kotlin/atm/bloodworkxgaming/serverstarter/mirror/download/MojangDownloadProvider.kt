package atm.bloodworkxgaming.serverstarter.mirror.download

/** 官方源下载 provider：所有 URL 恒等，不做任何重写。 */
object MojangDownloadProvider : DownloadProvider {

    override fun injectURL(url: String): String = url

    override fun injectURLWithCandidates(url: String): List<String> = listOf(url)

    override fun getVersionListURLs(): List<String> =
        listOf(DownloadProviders.OFFICIAL_VERSION_MANIFEST)

    override fun getConcurrency(): Int = 4
}