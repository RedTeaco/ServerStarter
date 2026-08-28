package atm.bloodworkxgaming.serverstarter

import atm.bloodworkxgaming.serverstarter.ServerStarter.Companion.LOGGER
import atm.bloodworkxgaming.serverstarter.config.ConfigFile
import okhttp3.OkHttpClient
import okhttp3.Request
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
        const val USER_AGENT = "ServerStarter/2.4.2"
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


    @Throws(IOException::class)
    fun downloadToFile(url: String, dest: File) {
        val req = Request.Builder()
            .url(url)
            .get()
            .build()

        val res = httpClient.newCall(req).execute()
        if (!res.isSuccessful) throw IOException("HTTP error code: ${res.code} for $url")

        val source = res.body?.source()
        source ?: throw IOException("Message body or source from $url was null")

        source.use {
            dest.parentFile?.mkdirs()
            dest.sink().buffer().use {
                it.writeAll(source)
            }
        }
    }
}
