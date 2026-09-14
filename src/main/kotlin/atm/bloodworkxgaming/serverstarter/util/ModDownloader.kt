package atm.bloodworkxgaming.serverstarter.util

import atm.bloodworkxgaming.serverstarter.InternetManager
import atm.bloodworkxgaming.serverstarter.ServerStarter.Companion.LOGGER
import org.apache.commons.io.FilenameUtils
import java.io.File
import java.io.IOException
import java.net.URISyntaxException
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.collections.ArrayList

/**
 * 共享模组下载器（Q7 §7.2）：收敛 curse / modrinth 的并行下载 + fallback 重试 +
 * [n/total] 日志。shouldSkip 钩子由 curse 侧（ignorePatterns）注入；
 * 命中忽略规则时真正跳过（修正现状"只记日志仍下载"的 bug）。
 *
 * 两个入口：
 * - [downloadAll]：单链接（curse 侧使用，行为与历史一致：失败后整体重试一轮）；
 * - [downloadTargets]：一个文件多个候选链接（modrinth 侧使用，mrpack 的 downloads[] 常是
 *   `[CF, CF, Modrinth]`），**优先第一个链接**，失败再按序尝试后续链接；
 *   落盘文件名始终取主链接的文件名，避免 fallback 链接的命名差异导致文件名漂移。
 */
class ModDownloader(private val basePath: String, private val internetManager: InternetManager) {

    /** 一个待下载文件：有序候选链接（第一个优先）+ 目标文件名。 */
    data class DownloadTarget(val urls: List<String>, val fileName: String) {
        constructor(url: String) : this(listOf(url), FilenameUtils.getName(url))
    }

    /** 单链接下载（历史入口，curse 包型使用）。 */
    fun downloadAll(urls: Collection<String>, shouldSkip: ((String) -> Boolean)? = null) {
        downloadTargets(urls.map { DownloadTarget(it) }, shouldSkip)
    }

    /** 多候选链接下载：优先 `urls[0]`，失败按序回退。 */
    fun downloadTargets(targets: Collection<DownloadTarget>, shouldSkip: ((String) -> Boolean)? = null) {
        val count = AtomicInteger(0)
        val totalCount = targets.size
        val fallbackList = Collections.synchronizedList(ArrayList<DownloadTarget>())

        targets.stream().parallel().forEach { downloadSingle(it, count, totalCount, fallbackList, shouldSkip) }

        val secondFail = ArrayList<DownloadTarget>()
        fallbackList.forEach { downloadSingle(it, count, totalCount, secondFail, shouldSkip) }

        if (secondFail.isNotEmpty()) {
            LOGGER.warn("Failed to download (a) mod(s):")
            secondFail.forEach { LOGGER.warn("\t" + it.fileName + " <- " + it.urls.joinToString()) }
        }
    }

    private fun downloadSingle(
            target: DownloadTarget,
            counter: AtomicInteger,
            totalCount: Int,
            fallbackList: MutableList<DownloadTarget>,
            shouldSkip: ((String) -> Boolean)?
    ) {
        val modName = target.fileName

        if (shouldSkip?.invoke(modName) == true) {
            LOGGER.info("[" + counter.incrementAndGet() + "/" + totalCount + "] Skipped ignored mod: " + modName)
            return
        }

        var lastError: IOException? = null

        for (url in target.urls) {
            try {
                internetManager.downloadToFile(url, File(basePath + "mods/" + modName))
                LOGGER.info("[" + String.format("% 3d", counter.incrementAndGet()) + "/" + totalCount + "] Downloaded mod: " + modName)
                return

            } catch (e: IOException) {
                lastError = e
                if (target.urls.size > 1) {
                    LOGGER.warn("Failed to download from $url, trying next link for $modName (${e.message})")
                }

            } catch (e: URISyntaxException) {
                LOGGER.error("Invalid url for $url", e)
            }
        }

        if (lastError != null) {
            LOGGER.error("Failed to download mod", lastError)
        }
        fallbackList.add(target)
    }
}
