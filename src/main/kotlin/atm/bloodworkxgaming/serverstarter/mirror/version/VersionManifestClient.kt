package atm.bloodworkxgaming.serverstarter.mirror.version

import atm.bloodworkxgaming.serverstarter.ServerStarter.Companion.LOGGER
import atm.bloodworkxgaming.serverstarter.mirror.download.DownloadProvider
import atm.bloodworkxgaming.serverstarter.mirror.installer.DownloadFailedException
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** 单个原版版本的原版下载信息（安装期只用 server 侧）。 */
data class VanillaVersionInfo(
    val mcVersion: String,
    val serverUrl: String?,
    val serverSha1: String?,
    val serverSize: Long?,
    val serverMappingsUrl: String?,
    val serverMappingsSha1: String?
)

/**
 * 原版版本清单客户端：version_manifest_v2.json → 单版本 json → downloads.server / downloads.server_mappings。
 * URL 全部经 provider 注入镜像；单次安装内内存缓存（不落盘）。
 */
class VersionManifestClient(private val httpClient: OkHttpClient, private val provider: DownloadProvider) {

    /** 单版本解析结果缓存：同一次安装内多次 resolve 同一版本不重复请求。 */
    private val versionCache = ConcurrentHashMap<String, VanillaVersionInfo>()

    /** 版本清单缓存（单值，双检锁保证并发安全）。 */
    @Volatile
    private var manifestCache: JsonObject? = null
    private val manifestLock = Any()

    /** 解析指定 MC 版本的 vanilla 下载信息。版本不存在 → DownloadFailedException。 */
    fun resolveVanillaVersion(mcVersion: String): VanillaVersionInfo {
        versionCache[mcVersion]?.let { return it }
        val info = fetchVanillaVersion(mcVersion)
        versionCache[mcVersion] = info
        return info
    }

    // ---------- 解析流程 ----------

    private fun fetchVanillaVersion(mcVersion: String): VanillaVersionInfo {
        val entryUrl = findVersionUrl(getManifest(), mcVersion)
        val versionJson = fetchVersionJson(entryUrl)
        return parseDownloads(versionJson, mcVersion)
    }

    /** 版本清单缓存读取（双检锁）。 */
    private fun getManifest(): JsonObject {
        manifestCache?.let { return it }
        synchronized(manifestLock) {
            manifestCache?.let { return it }
            val manifest = fetchManifest()
            manifestCache = manifest
            return manifest
        }
    }

    /**
     * 依次尝试 provider.getVersionListURLs()（OkHttp 默认跟随 301/302，相对 Location 自动解析）；
     * 第一个 2xx 的用 Gson 解析；全部失败 → DownloadFailedException。
     */
    private fun fetchManifest(): JsonObject {
        var lastError: Throwable? = null
        for (url in provider.getVersionListURLs()) {
            try {
                val text = getBody(url)
                val parsed = JsonParser.parseString(text)
                if (parsed.isJsonObject) return parsed.asJsonObject
                throw IOException("版本清单 JSON 不是对象: $url")
            } catch (e: Exception) {
                lastError = e
                LOGGER.warn("获取版本清单失败 $url: ${e.message}")
            }
        }
        throw DownloadFailedException("获取版本清单失败（${lastError?.message ?: "未知错误"}）", lastError)
    }

    /** 在清单 versions 数组中按 id 精确匹配；找不到 → DownloadFailedException。 */
    private fun findVersionUrl(manifest: JsonObject, mcVersion: String): String {
        val versions = manifest.get("versions")
        if (versions != null && versions.isJsonArray) {
            for (elem in versions.asJsonArray) {
                if (!elem.isJsonObject) continue
                val obj = elem.asJsonObject
                val id = obj.get("id")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
                if (id == mcVersion) {
                    val url = obj.get("url")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
                    if (!url.isNullOrEmpty()) return url
                    throw DownloadFailedException("版本 $mcVersion 在清单中但缺少 url")
                }
            }
        }
        throw DownloadFailedException("版本 $mcVersion 不在版本清单中")
    }

    /** 单版本 json：URL 经 provider.injectURLWithCandidates 依次尝试（镜像优先，官方兜底）；全部失败 → DownloadFailedException。 */
    private fun fetchVersionJson(url: String): JsonObject {
        var lastError: Throwable? = null
        for (candidate in provider.injectURLWithCandidates(url)) {
            try {
                val text = getBody(candidate)
                val parsed = JsonParser.parseString(text)
                if (parsed.isJsonObject) return parsed.asJsonObject
                throw IOException("版本信息 JSON 不是对象: $candidate")
            } catch (e: Exception) {
                lastError = e
                LOGGER.warn("获取版本信息失败 $candidate: ${e.message}")
            }
        }
        throw DownloadFailedException("获取版本信息失败 $url（${lastError?.message ?: "未知错误"}）", lastError)
    }

    /**
     * 解析 downloads.server / downloads.server_mappings（字段缺失用 has()/isJson* 判空，不抛 NPE）。
     * 两者都缺失也不抛错，返回对应字段为 null 的 VanillaVersionInfo（上层自行决定）。
     */
    private fun parseDownloads(versionJson: JsonObject, mcVersion: String): VanillaVersionInfo {
        var serverUrl: String? = null
        var serverSha1: String? = null
        var serverSize: Long? = null
        var mappingsUrl: String? = null
        var mappingsSha1: String? = null
        val downloads = versionJson.get("downloads")
        if (downloads != null && downloads.isJsonObject) {
            val server = downloads.asJsonObject.get("server")
            if (server != null && server.isJsonObject) {
                val obj = server.asJsonObject
                serverUrl = obj.get("url")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
                serverSha1 = obj.get("sha1")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
                serverSize = obj.get("size")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong
            }
            val mappings = downloads.asJsonObject.get("server_mappings")
            if (mappings != null && mappings.isJsonObject) {
                val obj = mappings.asJsonObject
                mappingsUrl = obj.get("url")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
                mappingsSha1 = obj.get("sha1")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
            }
        }
        return VanillaVersionInfo(mcVersion, serverUrl, serverSha1, serverSize, mappingsUrl, mappingsSha1)
    }

    // ---------- HTTP ----------

    /** GET 并读 body 文本；非 2xx / 无 body → IOException。不依赖 Content-Type（镜像可能返回 octet-stream）。 */
    private fun getBody(url: String): String {
        val request = Request.Builder().url(url).get().build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP error code: ${response.code} for $url")
            }
            val body = response.body ?: throw IOException("Message body was null for $url")
            return body.string()
        }
    }
}