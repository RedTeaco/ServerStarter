package atm.bloodworkxgaming.serverstarter.mirror.installer

import atm.bloodworkxgaming.serverstarter.ServerStarter.Companion.LOGGER
import atm.bloodworkxgaming.serverstarter.mirror.core.InstallerZip
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.jar.JarFile

/**
 * 外部 JVM 执行 server 侧 processors（对齐 HMCL ProcessorTask）。
 * EXTRACT_FILES 与 DOWNLOAD_MOJMAPS 不 spawn JVM（前者由编排层静态抽取，后者特判直接下载）。
 */
class ProcessorRunner(
    private val javaCommand: String,   // 有效 java 路径（与服务器启动同一套解析逻辑）
    private val basePath: File,        // 安装根目录
    private val installerFile: File,   // 安装器 jar（{INSTALLER}）
    private val extractDir: File,      // 安装器 zip 内 /data/xxx 的解压临时目录（可先清理重建）
    private val mojmapResolver: (mcVersion: String, targetFile: File) -> Unit
) {
    private companion object {
        /** resolveArg 迭代上限，防 token 相互引用死循环。 */
        const val MAX_RESOLVE_ROUNDS = 8
    }

    /** 按序执行 plan.processors。任一失败抛 ProcessorExecutionException。 */
    fun run(plan: InstallPlan) {
        // 1. extractDir 清理重建
        if (extractDir.exists()) {
            extractDir.deleteRecursively()
        }
        extractDir.mkdirs()

        for (processor in plan.processors) {
            // a. EXTRACT_FILES：不 spawn JVM，由编排层静态抽取
            if (processor.args.contains("EXTRACT_FILES")) {
                LOGGER.info("Skip EXTRACT_FILES (Static Extraction)")
                continue
            }
            // b. DOWNLOAD_MOJMAPS：特判直接下载，不走 JVM
            if (processor.args.contains("DOWNLOAD_MOJMAPS")) {
                handleDownloadMojmaps(processor, plan)
                continue
            }
            // c. 幂等：outputs 全部已存在且 sha1 匹配 → 跳过
            val resolvedOutputs = resolveOutputs(plan, processor.outputs)
            if (isUpToDate(resolvedOutputs)) {
                LOGGER.info("Processor is up to date. Skipping.")
                continue
            }
            // d. 定位工具 jar（libraries/<mavenPath>）
            val toolJar = File(File(basePath, "libraries"), InstallerJsonExtractor.mavenPath(processor.jar))
            if (!toolJar.isFile) {
                throw ProcessorExecutionException("Processor toolkit not downloaded: ${toolJar.absolutePath}(library: ${processor.jar})")
            }
            // e-h. 构建命令并启动外部 JVM
            val command = buildCommand(processor, plan)
            LOGGER.info("execute processor：${processor.jar}")
            val exitCode = try {
                ProcessBuilder(command).apply {
                    inheritIO()
                    directory(basePath)
                }.start().waitFor()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw ProcessorExecutionException("Processor execution interrupted: ${processor.jar}", e)
            } catch (e: IOException) {
                throw ProcessorExecutionException("Failed to start processor process: ${processor.jar}", e)
            }
            if (exitCode != 0) {
                throw ProcessorExecutionException("processor exit code $exitCode: ${processor.jar}")
            }
            // i. outputs 校验：不符 → 删该文件 + 抛异常
            verifyOutputs(resolvedOutputs, afterRun = true)
        }
        LOGGER.info("All processors completed")
    }

    /**
     * args 含 "EXTRACT_FILES" 或 "DOWNLOAD_MOJMAPS" → true。
     * 二者均不走 JVM（前者跳过，后者特判），供编排/测试预筛使用。
     */
//    internal fun isSkipped(processor: Processor): Boolean =
//        processor.args.contains("EXTRACT_FILES") || processor.args.contains("DOWNLOAD_MOJMAPS")

    /**
     * token 解析（纯函数，/data/xxx 的懒抽取除外）：
     * 1) 整串恰为 [maven坐标] → basePath/libraries/<mavenPath> 绝对路径；
     * 2) 以 "/data/" 开头 → 从安装器 zip 懒抽取到 extractDir 后给绝对路径（条目缺失 → ProcessorExecutionException）；
     * 3) 串内替换 {VAR}（SIDE/MINECRAFT_JAR/MINECRAFT_VERSION/ROOT/INSTALLER/LIBRARY_DIR → plan.data），
     *    替换结果可能再次命中 [坐标] / /data/ / 新 {…}，迭代解析直到稳定，最多 MAX_RESOLVE_ROUNDS 轮。
     */
    internal fun resolveArg(arg: String, plan: InstallPlan): String {
        val warned = mutableSetOf<String>()
        var current = arg
        repeat(MAX_RESOLVE_ROUNDS) {
            val next = resolveOnce(current, plan, warned)
            if (next == current) return normalizePath(current)
            current = next
        }
        return normalizePath(current)
    }

    /** outputs 全部已存在且 sha1 匹配 → true；空 outputs → false（无法幂等，需执行）。 */
    internal fun isUpToDate(outputs: Map<String, String>): Boolean {
        if (outputs.isEmpty()) return false
        for ((relPath, expectedSha1) in outputs) {
            val file = outputFile(relPath)
            if (!file.isFile) return false
            if (!sha1Hex(file).equals(expectedSha1, ignoreCase = true)) return false
        }
        return true
    }

    /**
     * 校验 outputs（key 为相对 basePath 的路径或解析后的绝对路径，value 为期望 sha1）。
     * 任一输出缺失或 sha1 不匹配 → ProcessorExecutionException；
     * afterRun=true（运行后校验）时先删除该文件，false（DOWNLOAD_MOJMAPS 特判）只校验不删除。
     */
    internal fun verifyOutputs(outputs: Map<String, String>, afterRun: Boolean) {
        for ((relPath, expectedSha1) in outputs) {
            val file = outputFile(relPath)
            if (!file.isFile) {
                throw ProcessorExecutionException("Processor output file missing: $relPath")
            }
            val actual = sha1Hex(file)
            if (actual.equals(expectedSha1, ignoreCase = true)) continue
            if (afterRun) {
                file.delete()
            }
            throw ProcessorExecutionException("Processor output file sha1 mismatch: $relPath（expected $expectedSha1，actual $actual）")
        }
    }

    /** outputs key 已解析为绝对路径时直接使用（Windows 上 File(parent, 绝对路径) 会错误拼接），否则相对 basePath。 */
    private fun outputFile(key: String): File {
        val f = File(key)
        return if (f.isAbsolute) f else File(basePath, key)
    }

    /** 读取 jar manifest 的 Main-Class；缺失/无法读取 → ProcessorExecutionException。 */
    internal fun readMainClass(jarFile: File): String {
        try {
            JarFile(jarFile).use { jar ->
                val mainClass = jar.manifest?.mainAttributes?.getValue("Main-Class")
                if (mainClass.isNullOrBlank()) {
                    throw ProcessorExecutionException("The tool JAR is missing the Main-Class manifest attribute: ${jarFile.absolutePath}")
                }
                return mainClass.trim()
            }
        } catch (e: ProcessorExecutionException) {
            throw e
        } catch (e: Exception) {
            throw ProcessorExecutionException("Failed to read tool JAR: ${jarFile.absolutePath}", e)
        }
    }

    /** 产出命令列表 [java, -cp, classpath, MainClass, args...]；classpath 为空时只用工具 jar。 */
    internal fun buildCommand(processor: Processor, plan: InstallPlan): List<String> {
        val toolJar = File(File(basePath, "libraries"), InstallerJsonExtractor.mavenPath(processor.jar))
        if (!toolJar.isFile) {
            throw ProcessorExecutionException("Processor toolkit not downloaded: ${toolJar.absolutePath}(library: ${processor.jar})")
        }
        val classpath = if (processor.classpath.isEmpty()) {
            toolJar.absolutePath
        } else {
            processor.classpath.joinToString(File.pathSeparator) { coord ->
                File(File(basePath, "libraries"), InstallerJsonExtractor.mavenPath(coord)).absolutePath
            }
        }
        val mainClass = readMainClass(toolJar)
        val args = processor.args.map { resolveArg(it, plan) }
        return listOf(javaCommand, "-cp", classpath, mainClass) + args
    }

    // ---------- 内部实现 ----------

    /** DOWNLOAD_MOJMAPS 特判：不 spawn JVM，解析 --version/--output 直接走 mojmapResolver。 */
    private fun handleDownloadMojmaps(processor: Processor, plan: InstallPlan) {
        val mcVersion = argAfter(processor.args, "--version")
            ?: throw ProcessorExecutionException("DOWNLOAD_MOJMAPS missing --version args")
        val outputArg = argAfter(processor.args, "--output")
            ?: throw ProcessorExecutionException("DOWNLOAD_MOJMAPS missing --output args")
        val targetFile = File(resolveArg(outputArg, plan))
        LOGGER.info("DOWNLOAD_MOJMAPS：downloading mojmap of MC $mcVersion  → ${targetFile.absolutePath}")
        mojmapResolver(mcVersion, targetFile)
        // outputs 存在（非空）则校验；特判路径只校验不删除
        verifyOutputs(resolveOutputs(plan, processor.outputs), afterRun = false)
    }

    private fun argAfter(args: List<String>, flag: String): String? {
        val idx = args.indexOf(flag)
        if (idx < 0 || idx + 1 >= args.size) return null
        return args[idx + 1]
    }

    /** outputs 的 key 同样走 resolveArg（token key 如 {MC_SLIM} → 实际路径；普通相对路径原样保留）。 */
    private fun resolveOutputs(plan: InstallPlan, outputs: Map<String, String>): Map<String, String> {
        if (outputs.isEmpty()) return outputs
        val resolved = LinkedHashMap<String, String>()
        for ((key, value) in outputs) {
            resolved[resolveArg(key, plan)] = value
        }
        return resolved
    }

    /** 单轮解析：整串 [坐标] / /data/xxx / {VAR} 替换，未命中特殊标记则原样返回。 */
    private fun resolveOnce(arg: String, plan: InstallPlan, warned: MutableSet<String>): String {
        // 1) 整串恰为 [maven坐标] → libraries/<mavenPath>
        if (arg.length >= 2 && arg.startsWith("[") && arg.endsWith("]")) {
            val inner = arg.substring(1, arg.length - 1)
            if (inner.contains(':') && !inner.contains('[') && !inner.contains(']')) {
                return File(File(basePath, "libraries"), InstallerJsonExtractor.mavenPath(inner)).absolutePath
            }
        }
        // 2) /data/xxx → 从安装器 zip 懒抽取（不存在才抽）
        if (arg.startsWith("/data/")) {
            val entryName = arg.substring(1) // 去掉前导 '/'
            val target = File(extractDir, entryName)
            if (!target.isFile) {
                try {
                    InstallerZip.extractEntry(installerFile, entryName, target)
                } catch (e: MirrorInstallException) {
                    throw ProcessorExecutionException("安装器 zip 缺少条目: $entryName", e)
                }
            }
            return target.absolutePath
        }
        // 3) {VAR} 替换
        if (arg.contains('{')) {
            var result = arg
            for ((key, value) in builtinTokens(plan)) {
                result = result.replace("{$key}", value)
            }
            val tokenPattern = Regex("\\{([^{}]+)\\}")
            for (m in tokenPattern.findAll(result)) {
                val key = m.groupValues[1]
                val value = plan.data[key]
                if (value != null) {
                    result = result.replace("{$key}", value)
                } else if (warned.add(key)) {
                    LOGGER.warn("未知 processor data token: {$key}，原样保留")
                }
            }
            return result
        }
        return arg
    }

    private fun builtinTokens(plan: InstallPlan): List<Pair<String, String>> = listOf(
        "SIDE" to "server",
        "MINECRAFT_JAR" to File(basePath, plan.serverJarPath).absolutePath,
        "MINECRAFT_VERSION" to plan.mcVersion,
        "ROOT" to basePath.absolutePath,
        "INSTALLER" to installerFile.absolutePath,
        "LIBRARY_DIR" to File(basePath, "libraries").absolutePath
    )

    /** 绝对路径做分隔符归一（Windows: '/' → '\'），其余原样返回。 */
    private fun normalizePath(s: String): String {
        val absolute = s.startsWith("/") || s.startsWith("\\") ||
            (s.length >= 3 && s[1] == ':' && (s[2] == '/' || s[2] == '\\'))
        return if (absolute) File(s).absolutePath else s
    }

    /** SHA-1 流式计算（十六进制小写）。 */
    private fun sha1Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-1")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var n = input.read(buffer)
            while (n >= 0) {
                if (n > 0) digest.update(buffer, 0, n)
                n = input.read(buffer)
            }
        }
        val bytes = digest.digest()
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(Character.forDigit((b.toInt() ushr 4) and 0xF, 16))
            sb.append(Character.forDigit(b.toInt() and 0xF, 16))
        }
        return sb.toString()
    }
}