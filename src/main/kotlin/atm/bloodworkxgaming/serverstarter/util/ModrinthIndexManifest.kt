package atm.bloodworkxgaming.serverstarter.util

import atm.bloodworkxgaming.serverstarter.ServerStarter.Companion.LOGGER
import com.google.gson.JsonObject
import org.apache.commons.io.FilenameUtils
import kotlin.collections.ArrayList

/**
 * `modrinth.index.json` 的 `files[]` 解析（纯函数：只吃 [JsonObject]，不碰磁盘与网络）。
 *
 * 从 `ModrinthPackType.postProcessing` 抽出来，一是让"只检查第一个下载链接"这类逻辑
 * 可以在无网络环境下被单测覆盖，二是把 JSON 字段的空值防御集中在一处：
 * - `path` 缺失/非字符串 → 跳过该条目（不再抛 ClassCastException）；
 * - 非 `mods/` 前缀（`shaderpacks/`、`resourcepacks/` 等）→ 跳过；
 * - `env.server == "unsupported"` → 跳过（ResourcePack / ShaderPack 等）；
 * - `downloads` 缺失/非数组/全空白 → 跳过并 warn；
 * - 身份解析遍历**整个** downloads 数组（[ModFileIdentity.fromEntry]）。
 */
object ModrinthIndexManifest {

    /** 一个待处理的 `mods/` 条目。 */
    data class Entry(
            /** 有序候选下载链接（第一个优先，失败按序回退）。 */
            val urls: List<String>,
            /** 主链接（`urls[0]`）的文件名——即 `ModDownloader` 实际落盘使用的名字。 */
            val fileName: String,
            /** manifest 声明的路径（如 `mods/xxx.jar`）。 */
            val path: String,
            /** 跨平台身份（Modrinth 项目 ID / CurseForge 项目 ID / 文件 ID / 候选文件名）。 */
            val identity: ModFileIdentity.Identity,
            /** index 声明的 `env.server` 值；缺失为 null。 */
            val serverEnv: String?
    )

    /** 解析根对象；`files` 缺失或不是数组时返回空列表。 */
    fun parse(root: JsonObject): List<Entry> {
        val files = root.get("files")?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
        val entries = ArrayList<Entry>(files.size())

        for (element in files) {
            val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: continue

            val path = obj.get("path")?.takeIf { it.isJsonPrimitive }?.asString ?: continue
            if (path.substringBefore("/") != "mods") {
                continue
            }

            // null 安全读取：env 缺失 / server 键缺失时按"无 index 判定"处理，不抛异常
            val serverEnv = obj.get("env")?.takeIf { it.isJsonObject }?.asJsonObject
                    ?.get("server")?.takeIf { it.isJsonPrimitive }?.asString
            if (serverEnv != null && serverEnv.equals("unsupported", ignoreCase = true)) {
                continue
            }

            val urls = obj.get("downloads")?.takeIf { it.isJsonArray }?.asJsonArray
                    ?.mapNotNull { url -> url.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString }
                    ?.filter { it.isNotBlank() }
                    ?: emptyList()
            if (urls.isEmpty()) {
                LOGGER.warn("No usable download link in manifest, skipping: $path")
                continue
            }

            // 身份解析遍历**全部**下载链接：真实 mrpack（尤其 CF 转换包）的 downloads 常是
            // [CF CDN, CF CDN, Modrinth]，只看 downloads[0] 会拿不到 Modrinth projectId，
            // 使 ignoreProject 与 environment 判定同时失效。
            entries.add(Entry(
                    urls = urls,
                    fileName = FilenameUtils.getName(urls[0]),
                    path = path,
                    identity = ModFileIdentity.fromEntry(urls, path),
                    serverEnv = serverEnv
            ))
        }

        return entries
    }
}
