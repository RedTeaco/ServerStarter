package atm.bloodworkxgaming.serverstarter.util

import atm.bloodworkxgaming.serverstarter.InternetManager
import atm.bloodworkxgaming.serverstarter.ServerStarter.Companion.LOGGER
import org.apache.commons.io.FilenameUtils
import java.io.File
import java.io.IOException
import java.net.URISyntaxException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.collections.ArrayList

/**
 * 共享模组下载器（Q7 §7.2）：收敛 curse / modrinth 的并行下载 + fallback 重试 +
 * [n/total] 日志。shouldSkip 钩子由 curse 侧（ignorePatterns）注入；
 * 命中忽略规则时真正跳过（修正现状"只记日志仍下载"的 bug）。
 */
class ModDownloader(private val basePath: String, private val internetManager: InternetManager) {
    fun downloadAll(urls: Collection<String>, shouldSkip: ((String) -> Boolean)? = null) {
        val count = AtomicInteger(0)
        val totalCount = urls.size
        val fallbackList = ArrayList<String>()

        urls.stream().parallel().forEach { downloadSingle(it, count, totalCount, fallbackList, shouldSkip) }

        val secondFail = ArrayList<String>()
        fallbackList.forEach { downloadSingle(it, count, totalCount, secondFail, shouldSkip) }

        if (secondFail.isNotEmpty()) {
            LOGGER.warn("Failed to download (a) mod(s):")
            secondFail.forEach { LOGGER.warn("\t" + it) }
        }
    }

    private fun downloadSingle(mod: String, counter: AtomicInteger, totalCount: Int, fallbackList: MutableList<String>, shouldSkip: ((String) -> Boolean)?) {
        try {
            val modName = FilenameUtils.getName(mod)
            if (shouldSkip?.invoke(modName) == true) {
                LOGGER.info("[" + counter.incrementAndGet() + "/" + totalCount + "] Skipped ignored mod: " + modName)
                return
            }

            internetManager.downloadToFile(mod, File(basePath + "mods/" + modName))
            LOGGER.info("[" + String.format("% 3d", counter.incrementAndGet()) + "/" + totalCount + "] Downloaded mod: " + modName)

        } catch (e: IOException) {
            LOGGER.error("Failed to download mod", e)
            fallbackList.add(mod)

        } catch (e: URISyntaxException) {
            LOGGER.error("Invalid url for $mod", e)
        }
    }
}