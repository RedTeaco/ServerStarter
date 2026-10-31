package atm.bloodworkxgaming.serverstarter

import atm.bloodworkxgaming.serverstarter.config.ConfigFile
import atm.bloodworkxgaming.serverstarter.config.LockFile
import atm.bloodworkxgaming.serverstarter.logger.PrimitiveLogger
import atm.bloodworkxgaming.serverstarter.packtype.AbstractZipbasedPackType
import atm.bloodworkxgaming.serverstarter.packtype.IPackType
import atm.bloodworkxgaming.serverstarter.util.AppVersion
import atm.bloodworkxgaming.serverstarter.util.ClientOnlyModFilter
import atm.bloodworkxgaming.serverstarter.util.ConsoleOutput
import atm.bloodworkxgaming.serverstarter.util.ConfigKeyValidator
import atm.bloodworkxgaming.serverstarter.util.PackFormatDetector
import atm.bloodworkxgaming.serverstarter.util.PackObtainer
import atm.bloodworkxgaming.serverstarter.yaml.CustomConstructor
import org.apache.commons.io.FileUtils
import org.fusesource.jansi.Ansi.ansi
import org.fusesource.jansi.AnsiConsole
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.Constructor
import org.yaml.snakeyaml.nodes.Tag
import org.yaml.snakeyaml.representer.Representer
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import kotlin.system.exitProcess


class ServerStarter(args: Array<String>) {
    companion object {
        private val rep: Representer = Representer(DumperOptions())
        private val options: DumperOptions = DumperOptions()
        private const val CURRENT_SPEC = 2

        val LOGGER = PrimitiveLogger(File("serverstarter.log"))
        var lockFile: LockFile
            private set
        val config: ConfigFile

        init {
            rep.addClassTag(ConfigFile::class.java, Tag.MAP)
            rep.addClassTag(LockFile::class.java, Tag.MAP)
            options.defaultFlowStyle = DumperOptions.FlowStyle.FLOW

            try {
                lockFile = readLockFile()
                config = readConfig()
            } catch (e: Exception) {
                LOGGER.error("Failed to load Yaml", e)
                throw InitException("Failed to load class", e)
            }

            if (config._specver < CURRENT_SPEC) {
                LOGGER.error(ansi().bgRed().fgBlack().a("You are loading with an older Version of the specification!!"))
                LOGGER.error(ansi().bgRed().fgBlack().a("Make sure you have updated your config.yaml file!"))
            }
        }

        /**
         * Reads the config and parses the config
         *
         * @return the configfile object
         */
        @Throws(RuntimeException::class)
        private fun readConfig(): ConfigFile {
            val yaml = Yaml(CustomConstructor(ConfigFile::class.java), rep, options)

            val text: String
            val file: ConfigFile?

            try {
                text = File("server-setup-config.yaml").readText(Charsets.UTF_8)
                // snakeyaml 遇到不认识的键只会抛出 "Unable to find property 'xxx' on class: ..."，
                // 既不说层级也不说该写什么；这里先给出带路径与建议的清单（必须在 load 之前）
                ConfigKeyValidator.warnUnknownKeys(text, ConfigFile::class.java)
                file = yaml.load(text)
            } catch (e: FileNotFoundException) {
                LOGGER.error("There is no config file given.", e)
                throw RuntimeException("No config file given.", e)
            } catch (e: IOException) {
                LOGGER.error("Could not read config file.", e)
                throw RuntimeException("Failed to read config file", e)
            }

            if (file == null)
                throw RuntimeException("Config file was null while reading.")

            file.postProcess()

            return file
        }

        /**
         * Reads the lockfile if present, returns a new if not
         */
        private fun readLockFile(): LockFile {
            val yaml = Yaml(Constructor(LockFile::class.java, LoaderOptions()), rep, options)
            val file = File("serverstarter.lock")

            if (file.exists()) {
                try {
                    FileInputStream(file).use { stream -> return yaml.load(stream) }
                } catch (e: FileNotFoundException) {
                    return LockFile()
                } catch (e: IOException) {
                    LOGGER.error("Error while reading Lock file", e)
                    throw RuntimeException("Error while reading Lock file", e)
                }

            } else {
                return LockFile()
            }
        }

        /**
         * Writes the lockfile to disk
         *
         * @param lockFile lockfile to write
         */
        fun saveLockFile(lockFile: LockFile) {
            val yaml = Yaml(Constructor(LockFile::class.java,LoaderOptions()), rep, options)
            val file = File("serverstarter.lock")
            this.lockFile = lockFile

            val lock = "#Auto genereated file, DO NOT EDIT!\n" + yaml.dump(lockFile)
            try {
                FileUtils.write(file, lock, "utf-8", false)
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
    }

    private val installOnly: Boolean = args.getOrNull(0) == "install"

    fun greet() {
        LOGGER.run {
            info("ConfigFile: $config", true)
            info("LockFile: $lockFile", true)

            info(ansi().fgRed().a(":::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::"))
            info(ansi().fgBrightBlue().a("   Minecraft ServerStarter install/launcher jar"))
            info(ansi().fgBrightBlue().a("   (Created by ").fgGreen().a("BloodWorkXGaming").fgBrightBlue().a(" with the help of ").fgGreen().a("Contributors").fgBrightBlue().a(")"))
            info(ansi().fgBrightBlue().a("   Version ${AppVersion.version}"))
            info(ansi().fgRed().a(":::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::"))
            info("")
            info("   This jar will launch a Minecraft Forge/Fabric Modded server")
            info("")
            info(ansi().a("   Origin Github:    ").fgBrightBlue().a("https://github.com/BloodyMods/ServerStarter"))
            info(ansi().a("   Renew Github:    ").fgBrightBlue().a("https://github.com/RedTeaco/ServerStarter"))
            info("")
            info(ansi().a("You are playing ").fgGreen().a(config.modpack.name))
            info("Starting to install/launch the server, lean back!")
            info(ansi().fgRed().a(":::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::"))
            info("")
        }
    }

    /** 格式解析结果：最终生效的包型名 + 自动识别时已经取到的包文件与识别细节（显式配置时为 null）。 */
    private data class ResolvedPackFormat(
            val format: String,
            val preObtainedPack: File?,
            val inspection: PackFormatDetector.Inspection? = null)

    /**
     * `modpackFormat` 是否交给自动识别：空 / `auto` / `detect`（大小写与空白无关）。
     */
    private fun isAutoPackFormat(format: String): Boolean {
        val normalized = format.trim().lowercase()
        return normalized.isEmpty() || normalized == "auto" || normalized == "detect"
    }

    /**
     * 解析整合包格式：显式配置优先；`modpackFormat` 为空 / `auto` / `detect` 时，
     * 先把包取到手再看内容自动识别（[PackFormatDetector]）。
     *
     * 识别结果会写回 `config.install.modpackFormat`，让后续流程（客户端模组过滤提示、
     * 纯 zip 的版本兜底规则、日志）看到最终格式；已取到的包一并返回，避免二次下载。
     */
    private fun resolvePackFormat(internetManager: InternetManager): ResolvedPackFormat {
        if (!isAutoPackFormat(config.install.modpackFormat) || config.install.modpackUrl.isEmpty()) {
            return ResolvedPackFormat(config.install.modpackFormat, null)
        }

        val zip = PackObtainer.obtain(
                modpackUrl = config.install.modpackUrl,
                basePath = config.install.normalizedInstallPath,
                internetManager = internetManager,
                cleanUrl = PackObtainer::cursePageDownloadUrl,
                preferredFormat = null)
        // 清单可能在子目录里（重新打包多套一层 / 启动器导出的实例目录），inspect 会一并给出路径与前缀
        val inspection = PackFormatDetector.inspect(zip)
        val detected = inspection.format
        LOGGER.info("modpackFormat '${config.install.modpackFormat}' -> auto-detected '$detected' from ${zip.name}")
        if (inspection.instanceExport) {
            LOGGER.warn("检测到启动器导出的实例目录：清单在 ${inspection.manifestEntry}，" +
                    "游戏目录已展开为 ${inspection.overridesPrefix}；将把该目录内容作为 overrides 解压，并按清单下载模组")
        } else if (inspection.nestedManifest) {
            LOGGER.info("清单位于包内子目录 ${inspection.manifestEntry}（重新打包多套了一层），" +
                    "按该路径读取，解压前缀 ${inspection.overridesPrefix}")
        }
        config.install.modpackFormat = detected
        return ResolvedPackFormat(detected, zip, inspection)
    }

    fun startLoading() {

        val internetManager = InternetManager(config)

        if (!internetManager.checkConnection() && config.launch.checkOffline) {
            LOGGER.error("Problems with the Internet connection, please check your connection!\n" +
                    "This could however be a problem with the servers as well.\n" +
                    "Continuing to download, but this may fail!")
        }


        val loaderManager = LoaderManager(config, internetManager)
        if (lockFile.checkShouldInstall(config) || installOnly) {
            val resolved = resolvePackFormat(internetManager)
            val packtype = IPackType.createPackType(resolved.format, config, internetManager)
                    ?: throw InitException("Unknown pack format given in config, shutting down.")
            resolved.preObtainedPack?.let { zip ->
                (packtype as? AbstractZipbasedPackType)?.preObtainedPack = zip
            }
            resolved.inspection?.let { inspection ->
                (packtype as? AbstractZipbasedPackType)?.apply {
                    manifestEntryNameOverride = inspection.manifestEntry
                    overridesPrefixOverride = inspection.overridesPrefix
                }
            }
            if (config.install.modpackFormat == "modrinth"){
                LOGGER.info("Client-only mods are filtered automatically.")
            }
            if (config.install.modpackUrl.isNotEmpty()) {
                val zip = packtype.obtainPack()                    // ① 下载/定位整合包 zip
                val versions = packtype.resolveVersions(zip)       // ② 从 zip 解析最终生效版本（不落盘）
                if (config.install.installLoader) {                // ③ 先装 loader，失败即中止（模组一个都不下载）
                    loaderManager.installLoader(config.install.normalizedInstallPath, versions.loaderVersion, versions.mcVersion, versions.loaderName)
                }
                packtype.installPack(zip)                          // ④ 解压 + 模组
            } else if (config.install.installLoader) {
                loaderManager.installLoader(config.install.normalizedInstallPath, config.install.loaderVersion, config.install.mcVersion)
            }
            // LockFile 写入时机与内容不变（§2.3）：packInstalled/packUrl 无条件写入
            lockFile.packInstalled = true
            lockFile.packUrl = config.install.modpackUrl
            saveLockFile(lockFile)

            if (config.launch.spongefix) {
                lockFile.spongeBootstrapper = loaderManager.installSpongeBootstrapper(config.install.normalizedInstallPath)
                saveLockFile(lockFile)
            }


            val fileManager = FileManager(config, internetManager)
            fileManager.installAdditionalFiles()
            fileManager.installLocalFiles()

            // 客户端模组清理：安装末尾扫描 mods/ 目录，删除客户端专用模组。
            // （curse 下载阶段已有 CF 三态预过滤；此处为最终兜底：TOML 规则 + CF 冲突仲裁）
            ClientOnlyModFilter.removeClientOnlyMods(
                    File(config.install.normalizedInstallPath + "mods/"),
                    packtype.apiVerdictsByFile
            )


        } else {
            LOGGER.info("Server is already installed to correct version, to force install delete the serverstarter.lock File.")
        }

        if (installOnly) {
            LOGGER.info("Install only mod, exiting now.")
            exitProcess(0)
        }

        loaderManager.handleServer()
    }
}

fun main(args: Array<String>) {
    // System.setProperty("jansi.passthrough", "true")
    try {
        AnsiConsole.systemInstall()
    } catch (e: Exception) {
        println("jansi couldn't be installed in this terminal (e.g. due to aarch64 not being supported)\n" +
                "Future terminal messages will have no color.")
    }
    // 必须在任何日志输出之前：按控制台真实编码重建 stdout/stderr，
    // 否则 jansi 会用 JVM 默认字符集(UTF-8) 输出，而中文 cmd.exe 按 GBK 解码 → 中文乱码
    ConsoleOutput.install()

    try {
        val starter = ServerStarter(args)

        starter.greet()
        starter.startLoading()

    } catch (e: InitException) {
        ServerStarter.LOGGER.error(e.message)
    } catch (e: DownloadLoaderException) {
        ServerStarter.LOGGER.error("Stopping the process as downloading the ModLoader failed", e)
    } catch (e: Throwable) {
        ServerStarter.LOGGER.error("Some uncaught error happened.", e)
    }
}

class InitException(s: String, e: Exception? = null) : RuntimeException(s, e)
