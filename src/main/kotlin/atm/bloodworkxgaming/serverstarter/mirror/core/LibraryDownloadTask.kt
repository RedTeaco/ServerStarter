package atm.bloodworkxgaming.serverstarter.mirror.core

import atm.bloodworkxgaming.serverstarter.ServerStarter.Companion.LOGGER
import atm.bloodworkxgaming.serverstarter.mirror.download.DownloadProvider
import atm.bloodworkxgaming.serverstarter.mirror.installer.DownloadFailedException
import atm.bloodworkxgaming.serverstarter.mirror.installer.DownloadTarget
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.io.FileUtils
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 库下载任务：sha1 校验 + 单 URL 重试 + 候选链回退 + 并发信号量（对齐设计文档 §4.5）。
 */
class LibraryDownloadTask(
    private val httpClient: OkHttpClient,
    private val concurrency: Int = 4
) {

    /** 每个候选 URL 的最大尝试次数。 */
    private val maxAttemptsPerCandidate = 3

    /** 第 N 次失败后的退避毫秒（指数退避 1s/2s/4s）。 */
    private val backoffMillis = longArrayOf(1000L, 2000L, 4000L)

    /**
     * 并发下载 targets 到 baseDir 下（dest = baseDir/relativePath）。全部成功或抛 DownloadFailedException。
     * 任一文件失败 → 其余任务继续跑完，最后汇总抛 DownloadFailedException（记录第一个失败原因，warn 其余失败）。
     */
    fun downloadAll(targets: List<DownloadTarget>, baseDir: File, provider: DownloadProvider) {
        if (targets.isEmpty()) return
        val poolSize = minOf(concurrency, MAX_POOL).coerceAtLeast(1)
        val executor: ExecutorService = Executors.newFixedThreadPool(poolSize)
        try {
            val futures = targets.map { target ->
                executor.submit(Callable { downloadTarget(target, baseDir, provider) })
            }
            var firstError: Throwable? = null
            var failureCount = 0
            for (future in futures) {
                try {
                    future.get()
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    firstError = firstError ?: DownloadFailedException("Download interrupted", e)
                    failureCount++
                } catch (e: ExecutionException) {
                    val cause = e.cause ?: e
                    failureCount++
                    if (firstError == null) {
                        firstError = cause
                    } else {
                        LOGGER.warn("Download Failed: ${cause.message}")
                    }
                }
            }
            if (firstError != null) {
                throw DownloadFailedException("Download Failed：$failureCount/${targets.size} 个文件失败", firstError)
            }
        } finally {
            executor.shutdown()
        }
    }

    /** 下载单个 URL 到 dest（无 relativePath 语义，用于 installer 等单文件）。 */
    fun downloadToFile(url: String, dest: File, provider: DownloadProvider, sha1: String? = null) {
        downloadFile(url, dest, provider, sha1, null, dest.name)
    }

    // ---------- 内部 ----------

    private fun downloadTarget(target: DownloadTarget, baseDir: File, provider: DownloadProvider) {
        val dest = File(baseDir, target.relativePath)
        downloadFile(target.url, dest, provider, target.sha1, target.size, target.relativePath)
    }

    /**
     * 单文件下载流程（downloadAll 与 downloadToFile 共用）：
     * 1. dest 已存在且校验通过 → 跳过（幂等/断点）；
     * 2. candidates = provider.injectURLWithCandidates(url)，依次尝试，每候选最多 maxAttemptsPerCandidate 次（指数退避）；
     * 3. 下载到同目录 .part 临时文件，校验通过后原子替换 dest；
     * 4. 候选链全部失败（含校验失败）→ DownloadFailedException("下载失败 <url>（<最后错误>）")。
     */
    private fun downloadFile(
        url: String,
        dest: File,
        provider: DownloadProvider,
        sha1: String?,
        size: Long?,
        label: String
    ) {
        // 幂等/断点：dest 已存在且校验通过 → 跳过
        if (dest.exists()) {
            when {
                sha1 != null && sha1Of(dest) == sha1 -> {
                    LOGGER.info("$label Already exists, skip")
                    return
                }
                sha1 == null && size != null && dest.length() == size -> {
                    LOGGER.info("$label Already exists (size matching), skip")
                    return
                }
            }
        }

        val candidates = provider.injectURLWithCandidates(url)
        // 日志展示实际将尝试的候选链（镜像优先），避免误以为走了官方源
        if (candidates.size > 1) {
            LOGGER.info("Download $label <- ${candidates.first()}(using mirror, Official url: $url)")
        } else {
            LOGGER.info("Download $label <- $url")
        }
        var lastError: Throwable? = null
        for ((index, candidate) in candidates.withIndex()) {
            val temp = partFile(dest)
            var attempt = 0
            var candidateError: Throwable? = null
            while (attempt < maxAttemptsPerCandidate) {
                attempt++
                var downloaded = false
                try {
                    downloadAttempt(candidate, temp, sha1, size, label)
                    downloaded = true
                    replaceTemp(temp, dest)
                    return
                } catch (e: Exception) {
                    if (!downloaded) {
                        temp.delete() // 校验失败/下载中断的临时文件清理
                    }
                    candidateError = e
                    lastError = e
                    if (attempt < maxAttemptsPerCandidate) {
                        val delay = backoffMillis[(attempt - 1).coerceAtMost(backoffMillis.size - 1)]
                        try {
                            Thread.sleep(delay)
                        } catch (ie: InterruptedException) {
                            Thread.currentThread().interrupt()
                            throw DownloadFailedException("Download failed $url (download interrupted)", ie)
                        }
                    }
                }
            }
            if (index < candidates.size - 1) {
                LOGGER.warn("$label candidate download failed $candidate,using next candidate：${candidateError?.message}")
            }
        }
        throw DownloadFailedException("Download failed $url（${lastError?.message ?: "unexpected error"}）", lastError)
    }

    /**
     * 单次尝试：GET 候选 URL 下载到 temp，并按 sha1/size 校验。
     * 非 2xx / 连接异常 / 校验失败 → IOException（触发重试或候选回退）。
     */
    private fun downloadAttempt(candidateUrl: String, temp: File, sha1: String?, size: Long?, label: String) {
        val request = Request.Builder().url(candidateUrl).get().build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP error code: ${response.code} for $candidateUrl")
            }
            val body = response.body ?: throw IOException("Message body was null for $candidateUrl")
            temp.parentFile?.mkdirs()
            body.byteStream().use { input ->
                temp.outputStream().use { output -> input.copyTo(output) }
            }
        }
        // 校验：sha1 优先；sha1 为 null 时用 size；都 null 只做 HTTP 成功判断
        if (sha1 != null) {
            val actual = sha1Of(temp)
            if (actual != sha1) {
                temp.delete()
                LOGGER.warn("$label SHA-1 校验失败: 期望 $sha1，实际 $actual")
                throw IOException("SHA-1 校验失败 for $candidateUrl")
            }
        } else if (size != null && temp.length() != size) {
            temp.delete()
            LOGGER.warn("$label 大小不符: 期望 $size，实际 ${temp.length()}")
            throw IOException("文件大小不符 for $candidateUrl")
        }
    }

    /** 校验通过的临时文件替换 dest（先删 dest 再 rename；rename 失败回退复制）。 */
    private fun replaceTemp(temp: File, dest: File) {
        dest.parentFile?.mkdirs()
        if (dest.exists() && !dest.delete()) {
            throw IOException("Cannot delect old file: ${dest.absolutePath}")
        }
        if (!temp.renameTo(dest)) {
            FileUtils.copyFile(temp, dest)
            if (!temp.delete()) temp.deleteOnExit()
        }
    }

    /** dest 同目录的 .part 临时文件路径。 */
    private fun partFile(dest: File): File {
        val abs = dest.absoluteFile
        val parent = abs.parentFile ?: File(".")
        return File(parent, abs.name + ".part")
    }

    /** 流式计算文件 SHA-1（hex 小写）。 */
    private fun sha1Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-1")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        val sb = StringBuilder()
        for (b in digest.digest()) {
            sb.append(HEX[(b.toInt() shr 4) and 0x0F])
            sb.append(HEX[b.toInt() and 0x0F])
        }
        return sb.toString()
    }

    private companion object {
        const val MAX_POOL = 8
        val HEX = "0123456789abcdef".toCharArray()
    }
}