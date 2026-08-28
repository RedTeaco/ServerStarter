package atm.bloodworkxgaming.serverstarter.mirror.installer

import atm.bloodworkxgaming.serverstarter.mirror.core.InstallerZip
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File

/**
 * 从安装器 zip 解析安装计划：读 install_profile.json（+ 可选 version.json），
 * 产出 InstallPlan。所有 JSON 字段缺失均用 has()/isJson* 判空，不抛 NPE；未知字段忽略。
 */
object InstallerJsonExtractor {
    private const val INSTALL_PROFILE = "install_profile.json"
    private const val VERSION_JSON = "version.json"
    private const val DEFAULT_LIBRARY_URL = "https://libraries.minecraft.net/"
    private const val NEOFORGE_PREFIX = "neoforge-"

    /** 从安装器 zip 读取 install_profile.json + version.json，产出 InstallPlan。zip 无效/缺 install_profile.json → MirrorInstallException。 */
    fun parsePlan(installerFile: File): InstallPlan {
        val zipFile = InstallerZip.openAndVerify(installerFile)
        try {
            val profileText = zipFile.getInputStream(zipFile.getEntry(INSTALL_PROFILE))
                .bufferedReader(Charsets.UTF_8).use { it.readText() }
            val profile = JsonParser.parseString(profileText).asJsonObject

            // version.json 可选：缺失时不影响解析（vanillaServerUrl 走版本清单兜底）
            val versionText = zipFile.getEntry(VERSION_JSON)?.let { entry ->
                zipFile.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
            }
            val version = versionText?.let { JsonParser.parseString(it).asJsonObject }

            val mcVersion = profile.get("minecraft")?.takeIf { it.isJsonPrimitive }?.asString ?: ""
            val rawLoaderVersion = profile.get("version")?.takeIf { it.isJsonPrimitive }?.asString ?: ""
            val loaderVersion = if (rawLoaderVersion.startsWith(NEOFORGE_PREFIX)) {
                rawLoaderVersion.substring(NEOFORGE_PREFIX.length)
            } else {
                rawLoaderVersion
            }
            val profileName = profile.get("profile")?.takeIf { it.isJsonPrimitive }?.asString?.lowercase() ?: ""
            val isNeoForge = profileName.contains("neoforge")

            // versionType：内联 versionInfo → 0；processors 非空 → 1；否则 → 2
            val versionType = when {
                profile.has("versionInfo") && profile.get("versionInfo").isJsonObject -> 0
                hasProcessors(profile) -> 1
                else -> 2
            }

            val serverJarPath = profile.get("serverJarPath")?.takeIf { it.isJsonPrimitive }?.asString
                ?.replace("{LIBRARY_DIR}", "libraries")
                ?.replace("{MINECRAFT_VERSION}", mcVersion) ?: ""

            val data = parseData(profile)
            val processors = parseProcessors(profile)
            val targets = parseTargets(profile, version)

            // version.json 缺失 downloads / downloads.server 时不抛错，返回 null
            var vanillaServerUrl: String? = null
            var vanillaServerSha1: String? = null
            val downloads = version?.get("downloads")
            if (downloads != null && downloads.isJsonObject) {
                val server = downloads.asJsonObject.get("server")
                if (server != null && server.isJsonObject) {
                    vanillaServerUrl = server.asJsonObject.get("url")?.takeIf { it.isJsonPrimitive }?.asString
                    vanillaServerSha1 = server.asJsonObject.get("sha1")?.takeIf { it.isJsonPrimitive }?.asString
                }
            }

            return InstallPlan(
                mcVersion = mcVersion,
                loaderVersion = loaderVersion,
                versionType = versionType,
                isNeoForge = isNeoForge,
                serverJarPath = serverJarPath,
                vanillaServerUrl = vanillaServerUrl,
                vanillaServerSha1 = vanillaServerSha1,
                targets = targets,
                processors = processors,
                data = data
            )
        } finally {
            zipFile.close()
        }
    }

    /** maven 坐标 → 相对路径："group:artifact:version[:classifier][@ext]" → group.replace('.','/')/artifact/version/artifact-version[-classifier].ext（ext 默认 jar）。 */
    fun mavenPath(coord: String): String {
        var ext = "jar"
        var base = coord
        val atIdx = coord.lastIndexOf('@')
        if (atIdx >= 0) {
            ext = coord.substring(atIdx + 1)
            base = coord.substring(0, atIdx)
        }
        val parts = base.split(':')
        if (parts.size < 3) return coord
        val group = parts[0]
        val artifact = parts[1]
        val version = parts[2]
        val classifier = if (parts.size > 3) parts[3] else null
        val fileName = StringBuilder(artifact)
            .append('-').append(version)
            .append(if (classifier != null && classifier.isNotEmpty()) "-$classifier" else "")
            .append('.').append(ext)
            .toString()
        return group.replace('.', '/') + '/' + artifact + '/' + version + '/' + fileName
    }

    /** 语义化 MC 版本比较：按 '.' 分段数值比较，缺失段视为 0（如 1.21 < 1.21.1 → 负数）。 */
//    fun compareMcVersion(a: String, b: String): Int {
//        val pa = a.split('.').map { it.toIntOrNull() ?: 0 }
//        val pb = b.split('.').map { it.toIntOrNull() ?: 0 }
//        val len = maxOf(pa.size, pb.size)
//        for (i in 0 until len) {
//            val x = if (i < pa.size) pa[i] else 0
//            val y = if (i < pb.size) pb[i] else 0
//            if (x != y) return x.compareTo(y)
//        }
//        return 0
//    }

    private fun hasProcessors(profile: JsonObject): Boolean {
        val processors = profile.get("processors")
        return processors != null && processors.isJsonArray && processors.asJsonArray.size() > 0
    }

    /** data 表：字符串直接用；{client,server} 对象优先 server，缺 server 用 client；null/缺 → 跳过。 */
    private fun parseData(profile: JsonObject): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        val dataObj = profile.get("data")
        if (dataObj == null || !dataObj.isJsonObject) return result
        for ((key, value) in dataObj.asJsonObject.entrySet()) {
            when {
                value.isJsonPrimitive && value.asJsonPrimitive.isString -> result[key] = value.asString
                value.isJsonObject -> {
                    val obj = value.asJsonObject
                    val serverVal = obj.get("server")
                    val clientVal = obj.get("client")
                    val resolved = when {
                        serverVal != null && serverVal.isJsonPrimitive && serverVal.asJsonPrimitive.isString -> serverVal.asString
                        clientVal != null && clientVal.isJsonPrimitive && clientVal.asJsonPrimitive.isString -> clientVal.asString
                        else -> null
                    }
                    if (resolved != null) result[key] = resolved
                }
                // null 或其他类型 → 跳过
            }
        }
        return result
    }

    /** processors：过滤 sides 缺失 或 包含 "server" 的项。 */
    private fun parseProcessors(profile: JsonObject): List<Processor> {
        val result = mutableListOf<Processor>()
        val arr = profile.get("processors")
        if (arr == null || !arr.isJsonArray) return result
        for (elem in arr.asJsonArray) {
            if (!elem.isJsonObject) continue
            val p = elem.asJsonObject
            val sides = p.get("sides")?.takeIf { it.isJsonArray }?.asJsonArray?.map { it.asString } ?: emptyList()
            if (sides.isNotEmpty() && !sides.contains("server")) continue
            val jar = p.get("jar")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString ?: continue
            val classpath = p.get("classpath")?.takeIf { it.isJsonArray }?.asJsonArray?.map { it.asString } ?: emptyList()
            val args = p.get("args")?.takeIf { it.isJsonArray }?.asJsonArray?.map { it.asString } ?: emptyList()
            val outputs = LinkedHashMap<String, String>()
            val outObj = p.get("outputs")
            if (outObj != null && outObj.isJsonObject) {
                for ((k, v) in outObj.asJsonObject.entrySet()) {
                    if (v.isJsonPrimitive && v.asJsonPrimitive.isString) outputs[k] = v.asString
                }
            }
            result.add(Processor(jar, classpath, args, outputs))
        }
        return result
    }

    /** targets：profile.libraries ∪ version.json.libraries，按 barePath 去重（保留先出现）；relativePath 统一带 "libraries/" 前缀（对齐 §4.3.1）。 */
    private fun parseTargets(profile: JsonObject, version: JsonObject?): List<DownloadTarget> {
        val byPath = LinkedHashMap<String, DownloadTarget>()
        addLibraries(profile, byPath)
        addLibraries(version, byPath)
        return byPath.values.toList()
    }

    private fun addLibraries(obj: JsonObject?, byPath: MutableMap<String, DownloadTarget>) {
        if (obj == null) return
        val arr = obj.get("libraries")
        if (arr == null || !arr.isJsonArray) return
        for (lib in arr.asJsonArray) {
            addLibrary(lib, byPath)
        }
    }

    private fun addLibrary(lib: JsonElement, byPath: MutableMap<String, DownloadTarget>) {
        if (!lib.isJsonObject) return
        val obj = lib.asJsonObject
        val name = obj.get("name")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        val artifact = obj.get("downloads")?.takeIf { it.isJsonObject }?.asJsonObject
            ?.get("artifact")?.takeIf { it.isJsonObject }?.asJsonObject

        // barePath：downloads.artifact.path 非空用它，否则 mavenPath(name)（去重与 URL 兜底均用 barePath）
        val pathFromArtifact = artifact?.get("path")
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString && it.asString.isNotEmpty() }?.asString
        val barePath = pathFromArtifact ?: (name?.let { mavenPath(it) } ?: return)
        if (byPath.containsKey("libraries/$barePath")) return  // 去重：保留先出现

        // url：artifact.url → 库级 url → 默认库地址兜底（URL 不含 libraries/ 前缀，libraries.minecraft.net 即库根）
        val urlFromArtifact = artifact?.get("url")
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString && it.asString.isNotEmpty() }?.asString
        val urlFromLib = obj.get("url")
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString && it.asString.isNotEmpty() }?.asString
        val url = urlFromArtifact ?: urlFromLib ?: (DEFAULT_LIBRARY_URL + barePath)

        val sha1 = artifact?.get("sha1")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        val size = artifact?.get("size")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong

        byPath["libraries/$barePath"] = DownloadTarget("libraries/$barePath", url, sha1, size)
    }
}