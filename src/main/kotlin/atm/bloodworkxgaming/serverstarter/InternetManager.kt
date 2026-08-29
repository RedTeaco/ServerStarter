package atm.bloodworkxgaming.serverstarter

import atm.bloodworkxgaming.serverstarter.ServerStarter.Companion.LOGGER
import atm.bloodworkxgaming.serverstarter.config.ConfigFile
import atm.bloodworkxgaming.serverstarter.util.AppVersion
import atm.bloodworkxgaming.serverstarter.util.ByteProgressReporter
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.buffer
import okio.sink
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class InternetManager(private val configFile: ConfigFile) {
    companion object {
        /**
         * 下载请求 User-Agent。部分镜像/CDN（实测 BMCLAPI→USTC 重定向目标）会拒绝 OkHttp 默认
         * "okhttp/x.y.z" UA（403），必须使用自有 UA（HMCL 同款风格）。
         */
        val USER_AGENT = "ServerStarter/${AppVersion.version}"

        /**
         * GET 并读 body 文本；非 2xx / 无 body → IOException。
         * 供无 InternetManager 实例的调用方使用（如 VersionManifestClient）。
         */
        @Throws(IOException::class)
        fun get(client: OkHttpClient, url: String): String {
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("HTTP error code: ${response.code} for $url")
                }
                val body = response.body ?: throw IOException("Message body was null for $url")
                return body.string()
            }
        }
    }

    val httpClient = OkHttpClient.Builder()
        .connectTimeout(configFile.install.connectTimeout, TimeUnit.SECONDS)
        .readTimeout(configFile.install.readTimeout, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("User-Agent", USER_AGENT)
                    .build()
            )
        }
        .build()


    // Checking for connections seems to be broken on linux without root priviliges
    // Therefore we use HTTP get requests on linux to check for a valid connection
    fun checkConnection(): Boolean {
        var reached = 0

        val urls = configFile.launch.checkUrls
        for (url in urls) {
            try {
                LOGGER.info("Testing $url.")

                val req = Request.Builder()
                    .url(url)
                    .get()
                    .build()

                val r = try {
                    httpClient.newCall(req).execute().use {
                        it.isSuccessful
                        true
                    }
                } catch (ex: IOException) {
                    false
                }

                LOGGER.info("Reached $url: $r")
                if (r) reached++
            } catch (e: IOException) {
                LOGGER.error("Error while attempting to reach a remote server", e)
            }
        }

        LOGGER.info("Reached $reached out of ${urls.size} IPs.")
        if (reached != urls.size) {
            LOGGER.error("Not every host could be reached. There could be a problem with your internet connection!!!!")
            return false
        }

        return true
    }


    /** GET 并读 body 文本；非 2xx / 无 body → IOException。 */
    @Throws(IOException::class)
    fun get(url: String): String = get(httpClient, url)

    /**
     * POST JSON；Content-Type: application/json。传入 headers 追加（不覆盖 Content-Type）。
     * 非 2xx / 无 body → IOException。
     */
    @Throws(IOException::class)
    fun postJson(url: String, jsonBody: String, headers: Map<String, String> = emptyMap()): String {
        val builder = Request.Builder()
            .url(url)
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
        for ((key, value) in headers) {
            if (!key.equals("Content-Type", ignoreCase = true)) {
                builder.header(key, value)
            }
        }
        httpClient.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP error code: ${response.code} for $url")
            }
            val body = response.body ?: throw IOException("Message body was null for $url")
            return body.string()
        }
    }

    @Throws(IOException::class)
    fun downloadToFile(url: String, dest: File, progress: ByteProgressReporter? = null) {
        val req = Request.Builder()
            .url(url)
            .get()
            .build()

        val res = httpClient.newCall(req).execute()
        if (!res.isSuccessful) throw IOException("HTTP error code: ${res.code} for $url")

        val source = res.body?.source()
        source ?: throw IOException("Message body or source from $url was null")

        progress?.onStart(res.body?.contentLength()?.takeIf { it >= 0 })
        source.use {
            dest.parentFile?.mkdirs()
            dest.sink().buffer().use {
                if (progress != null) {
                    val buf = ByteArray(8192)
                    while (true) {
                        val n = source.read(buf, 0, buf.size)
                        if (n < 0) break
                        it.write(buf, 0, n)
                        progress.addBytes(n.toLong())
                    }
                } else {
                    it.writeAll(source)
                }
            }
        }
        progress?.finish()
    }
}
