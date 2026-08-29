package atm.bloodworkxgaming.serverstarter.packtype.zip

import atm.bloodworkxgaming.serverstarter.InternetManager
import atm.bloodworkxgaming.serverstarter.config.ConfigFile
import atm.bloodworkxgaming.serverstarter.packtype.AbstractZipbasedPackType
import atm.bloodworkxgaming.serverstarter.packtype.ManifestVersions
import atm.bloodworkxgaming.serverstarter.util.ZipExtractor
import java.io.File
import java.io.IOException
import java.nio.file.PathMatcher

class ZipFilePackType(configFile: ConfigFile, internetManager: InternetManager) : AbstractZipbasedPackType(configFile, internetManager) {
    private val oldFiles = File(basePath + "OLD_TO_DELETE/")

    override fun cleanUrl(url: String): String {
        return url
    }

    override fun postProcessing() {
    }

    /**
     * 纯 zip 格式无 manifest，版本只能来自 yaml。
     */
    override fun readManifestVersions(zip: File): ManifestVersions? {
        return null
    }

    @Throws(IOException::class)
    override fun handleZip(file: File, pathMatchers: List<PathMatcher>) {
        ZipExtractor(
                basePath = basePath,
                oldFiles = oldFiles,
                pathMatchers = pathMatchers,
                manifestEntryName = null,
                overridesPrefix = null,
                moveModsFolderFirst = false,
                rethrowOnError = false
        ).extract(file)
    }
}
