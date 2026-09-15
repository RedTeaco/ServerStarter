package atm.bloodworkxgaming.serverstarter.config

import java.io.File
import java.util.*

fun processString(s: String): String {
        var str = s
        val regex = Regex("\\\$\\{(.+)}")
        for (matchResult in regex.findAll(str)) {
            val res = matchResult.groupValues.getOrNull(0) ?: continue
            val inner = matchResult.groupValues.getOrNull(1) ?: continue

            str = str.replace(res, System.getenv(inner) ?: throw IllegalStateException("There is no Environment Variable '$inner'"))
        }

        return str

}

data class AdditionalFile(
    var url: String = "",
    var destination: String = ""
)

data class LocalFile(
    var from: String = "",
    var to: String = ""
)

data class ModpackConfig(
    var name: String = "",
    var description: String = ""
)

data class LaunchSettings(
    var spongefix: Boolean = false,
    var ramDisk: Boolean = false,
    var checkOffline: Boolean = false,
    var checkUrls: List<String> = Collections.emptyList(),
    var maxRam: String = "",
    var minRam: String = "",

    var startFile: String = "",
    var startCommand: List<String> = Collections.emptyList(),
    var javaArgs: List<String> = Collections.emptyList(),
    var autoRestart: Boolean = false,
    var crashLimit: Int = 0,
    var crashTimer: String = "",
    var preJavaArgs: String = "",

    var forcedJavaPath: String = "",

    var supportedJavaVersions: List<String> = Collections.emptyList()

    ) {
    val processedForcedJavaPath: String
        get() = processString(forcedJavaPath)
}

data class InstallConfig(
    var curseForgeApiKey: String = "\$2a\$10\$UkS/Xi7AvCSkNKCqqoHE5u49.B3H1TFB.iZZBirxJqeBXNK1tPjMS",

    var mcVersion: String = "",

    var loaderVersion: String = "",
    var installerUrl: String = "",
    var installerArguments: List<String> = Collections.emptyList(),
    var downloadSource: String = "mojang",   // 下载源：mojang（默认，走原 --installServer）| bmclapi（镜像进程内安装）
    var mirrorUrl: String = "",              // 可选 apiRoot 覆盖（OpenBMCLAPI 节点）；空 = https://bmclapi2.bangbang93.com

    var modpackUrl: String = "",
    var modpackFormat: String = "",
    var formatSpecific: Map<String, Any> = Collections.emptyMap(),

    var baseInstallPath: String = "",
    var ignoreFiles: List<String> = Collections.emptyList(),
    var additionalFiles: List<AdditionalFile> = Collections.emptyList(),
    var localFiles: List<LocalFile> = Collections.emptyList(),

    var checkFolder: Boolean = false,
    var installLoader: Boolean = false,

    var spongeBootstrapper: String = "",
    var connectTimeout: Long = 30,
    var readTimeout: Long = 30,
) {


    @Suppress("UNCHECKED_CAST")
    fun <T> getFormatSpecificSettingOrDefault(name: String, fallback: T?): T? {
        return formatSpecific.getOrDefault(name, fallback) as T?
    }

    /**
     * 归一化安装路径：空/空白 → "." + File.separator（当前目录，作为 File(parent, child) 的 parent 时
     * 永不为空——空 parent 在 Windows 上会被解析为当前盘根，导致解压等文件散落到盘根）；
     * 非空 → 确保以结尾分隔符收尾（避免 "basePath + \"mods/\"" 拼出 "...serverStarterTestmods/"）。
     * 所有落盘路径（解压、模组下载、loader 安装、附加文件、启动 jar 等）统一使用该值。
     */
    val normalizedInstallPath: String
        get() {
            val raw = baseInstallPath.trim()
            return when {
                raw.isEmpty() -> "." + File.separator
                raw.endsWith("/") || raw.endsWith("\\") -> raw
                else -> raw + File.separator
            }
        }
}

data class ConfigFile(
    var _specver: Int = 0,
    var modpack: ModpackConfig = ModpackConfig(),
    var install: InstallConfig = InstallConfig(),
    var launch: LaunchSettings = LaunchSettings()
) {
    fun postProcess() {
        install.curseForgeApiKey = processString(install.curseForgeApiKey)
    }
}
