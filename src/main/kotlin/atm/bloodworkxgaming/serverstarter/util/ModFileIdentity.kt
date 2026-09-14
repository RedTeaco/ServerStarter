package atm.bloodworkxgaming.serverstarter.util

import org.apache.commons.io.FilenameUtils
import java.net.URLDecoder

/**
 * modrinth.index.json 单个 files[] 条目的"文件身份"解析（纯函数，无 IO，便于单测）。
 *
 * 背景：真实 mrpack（尤其是由 CurseForge 整合包转换而来）的 `downloads[]` 常常是
 * `[CF CDN, CF CDN, Modrinth]` 的顺序。只读 `downloads[0]` 会完全拿不到 Modrinth projectId，
 * 导致 `ignoreProject` 与 Modrinth `environment` 判定双双失效（只检查第一个下载链接的 ID 的 bug）。
 * 因此这里遍历**整个 downloads 数组**，任何一条链接能解析出身份都算数。
 *
 * 支持的链接形态（都是**直链**；mrpack 的 `downloads[]` 里不会出现项目页那种页面地址）：
 * - Modrinth：`https://cdn.modrinth.com/data/{projectId}/versions/{versionId}/{file}`
 *   （也兼容 `cdn-raw` / `staging-cdn` 等子域）；projectId 为 base62，**大小写敏感**。
 * - CurseForge CDN：`https://mediafilez.forgecdn.net/files/{fileId/1000}/{fileId%1000}/{file}`
 *   （edge / mediafilez 等子域）→ 反推 CurseForge **文件 ID**（离线，无需 API）。
 * - CurseForge API 直链：`https://api.curseforge.com/v1/mods/{projectId}/files/{fileId}/download`
 *   → 同时给出**项目 ID** 与文件 ID。
 *
 * 注意：`curseforge.com/.../download/{fileId}` 这种**项目页地址**不是下载直链、也不是 API，
 * 本对象不解析它（真要排除这类条目用 `install.ignoreFiles` 的文件名规则即可）。
 */
object ModFileIdentity {

    /** 单个 mods/ 条目的身份：各平台 ID + 可能出现的文件名（用于 name/glob/regex 规则）。 */
    data class Identity(
            val modrinthProjectId: String? = null,
            val curseProjectId: String? = null,
            val curseFileId: String? = null,
            val fileNames: Set<String> = emptySet()
    ) {
        val hasAnyId: Boolean
            get() = modrinthProjectId != null || curseProjectId != null || curseFileId != null

        /** 日志用：简明列出已识别到的身份，全部为空时返回 "none"。 */
        fun describe(): String {
            val parts = ArrayList<String>()
            modrinthProjectId?.let { parts.add("modrinth=$it") }
            curseProjectId?.let { parts.add("curseProject=$it") }
            curseFileId?.let { parts.add("curseFile=$it") }
            return if (parts.isEmpty()) "none" else parts.joinToString(", ")
        }
    }

    // Modrinth CDN：/data/{projectId}/...（projectId 为 base62，遇到 / ? # 截止）
    private val MODRINTH_DATA = Regex("""(?i)^https?://(?:[^/]*\.)?modrinth\.com/data/([A-Za-z0-9]+)(?:[/?#]|$)""")

    // CurseForge API 直链：`https://api.curseforge.com/v1/mods/{projectId}/files/{fileId}`（可选的 /api 段一并容忍）
    private val CURSEFORGE_API_FILE = Regex("""(?i)^https?://(?:[^/]*\.)?curseforge\.com/(?:api/)?v\d+/mods/(\d+)/files/(\d+)(?:[/?#]|$)""")

    // CurseForge CDN：/files/{fileId/1000}/{fileId%1000}/{file}
    private val FORGECDN_FILE = Regex("""(?i)^https?://(?:[^/]*\.)?forgecdn\.net/files/(\d+)/(\d+)(?:[/?#]|$)""")

    /**
     * 解析单个 files[] 条目。[manifestPath] 为该条目的 `path` 字段（如 `mods/xxx.jar`）。
     */
    fun fromEntry(downloadUrls: List<String>, manifestPath: String): Identity {
        var modrinth: String? = null
        var curseProject: String? = null
        var curseFile: String? = null

        for (url in downloadUrls) {
            if (modrinth == null) modrinth = modrinthProjectId(url)
            if (curseProject == null || curseFile == null) {
                val api = CURSEFORGE_API_FILE.find(url)
                if (api != null) {
                    if (curseProject == null) curseProject = api.groupValues[1]
                    if (curseFile == null) curseFile = api.groupValues[2]
                } else if (curseFile == null) {
                    curseFile = curseFileId(url)
                }
            }
            if (modrinth != null && curseFile != null) break
        }

        return Identity(modrinth, curseProject, curseFile, fileNameCandidates(downloadUrls, manifestPath))
    }

    /** Modrinth projectId（base62，大小写敏感；不做 lowercase）；非 Modrinth 链接返回 null。 */
    fun modrinthProjectId(url: String): String? =
            MODRINTH_DATA.find(url)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }

    /**
     * CurseForge 文件 ID（仅从**直链**反推，项目页地址不解析）：
     * - `.../api/v1/mods/{projectId}/files/{fileId}/...` → fileId
     * - `.../files/{a}/{b}/{file}`（forgecdn CDN）→ a*1000+b
     * 解析不出返回 null。
     */
    fun curseFileId(url: String): String? {
        CURSEFORGE_API_FILE.find(url)?.let { return it.groupValues[2] }

        FORGECDN_FILE.find(url)?.let { match ->
            val a = match.groupValues[1].toLongOrNull()
            val b = match.groupValues[2].toLongOrNull()
            if (a != null && b != null && a in 0..MAX_CDN_PREFIX) {
                return (a * 1000L + b).toString()
            }
        }

        return null
    }

    /**
     * 候选文件名（供 `name:` / `glob:` / `regex:` 规则匹配）：
     * - **主链接的 basename**：实际落盘使用的名字（`ModDownloader` 取的就是它）；
     * - 其百分号解码形式：URL 里写作 `%2b` 而 `path` 里写作 `+` 的条目（真实 mrpack 中很常见，
     *   如 `CTM-1.21-1.2.1%2b3.jar` ↔ `CTM-1.21-1.2.1+3.jar`），两种写法都应能命中；
     * - `path` 字段的 basename：manifest 声明的规范名字。
     */
    fun fileNameCandidates(downloadUrls: List<String>, manifestPath: String): Set<String> {
        val names = LinkedHashSet<String>()

        for (url in downloadUrls) {
            val name = FilenameUtils.getName(url)
            if (name.isNotBlank()) {
                names.add(name)
                decodeUrlPathSegment(name)?.let { names.add(it) }
            }
        }

        val pathName = manifestPath.substringAfterLast('/')
        if (pathName.isNotBlank()) names.add(pathName)

        return names
    }

    /**
     * URL 路径段的百分号解码。注意路径中字面 `+` 表示加号而非空格（表单语义才把 `+` 当空格），
     * 因此先把字面 `+` 换成 `%2B` 再解码；解码失败或结果相同返回 null。
     */
    private fun decodeUrlPathSegment(s: String): String? =
            try {
                URLDecoder.decode(s.replace("+", "%2B"), "UTF-8").takeIf { it != s }
            } catch (e: Exception) {
                null
            }

    private const val MAX_CDN_PREFIX = 999_999_999L
}
