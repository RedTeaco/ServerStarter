package atm.bloodworkxgaming.serverstarter.util

import atm.bloodworkxgaming.serverstarter.ServerStarter.Companion.LOGGER
import java.nio.file.FileSystems
import java.nio.file.PathMatcher
import java.nio.file.Paths
import kotlin.collections.ArrayList

/**
 * `install.ignoreFiles` 在**下载阶段**的文件名匹配规则（唯一实现，curse / modrinth 共用）。
 *
 * `ignoreFiles` 有两处用途，本对象负责第 2 处：
 * 1. 解压阶段：`AbstractZipbasedPackType.installPack` → `ZipExtractor`，全部条目按 overrides 相对路径过滤；
 * 2. 下载阶段（本对象）：只有 `mods/` 前缀的条目参与，剥掉前缀后按文件名匹配。
 *
 * 与历史实现的区别：匹配对象从「主链接的落盘名」扩展为
 * [ModFileIdentity.Identity.fileNames]（全部下载链接名 + 百分号解码名 + manifest path 名）。
 * 因此 URL 写作 `%2b`、`path` 写作 `+` 的条目（真实 mrpack 里很常见）用任一种写法都能命中，
 * 这也正是原先 `ignoreProject` 里那套文件名规则被移除后不丢能力的原因。
 */
object FileIgnoreRules {

    private const val MODS_PREFIX = "mods/"

    /**
     * 取出下载阶段生效的规则：仅 `mods/` 前缀项（与非 `mods/` 项只影响解压阶段的既有语义一致）。
     * 无前缀默认 glob，支持 `glob:` / `regex:` 强制指定；非法表达式 warn 后忽略该条（不影响安装）。
     */
    fun downloadMatchers(ignoreFiles: List<String>): List<PathMatcher> {
        val matchers = ArrayList<PathMatcher>()

        for (ignoreFile in ignoreFiles) {
            if (!ignoreFile.startsWith(MODS_PREFIX)) continue

            val raw = ignoreFile.removePrefix(MODS_PREFIX)
            if (raw.isEmpty()) {
                LOGGER.warn("Empty ignoreFiles pattern, skipping: $ignoreFile")
                continue
            }

            val spec = if (raw.startsWith("glob:") || raw.startsWith("regex:")) raw else "glob:$raw"
            try {
                matchers.add(FileSystems.getDefault().getPathMatcher(spec))
            } catch (e: IllegalArgumentException) {
                // PatternSyntaxException 也是 IllegalArgumentException 的子类
                LOGGER.warn("Invalid ignoreFiles pattern (download stage), skipping: $ignoreFile (${e.message})")
            }
        }

        return matchers
    }

    /**
     * 任一候选文件名命中即返回该名字（便于日志说明是哪一种写法命中的），全部未命中返回 null。
     * 候选名一般来自 [ModFileIdentity.Identity.fileNames]。
     */
    fun firstMatch(matchers: List<PathMatcher>, fileNames: Set<String>): String? {
        if (matchers.isEmpty()) return null

        for (name in fileNames) {
            val path = Paths.get(name)
            if (matchers.any { it.matches(path) }) return name
        }

        return null
    }
}
