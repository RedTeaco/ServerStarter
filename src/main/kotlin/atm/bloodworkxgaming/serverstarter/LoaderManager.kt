package atm.bloodworkxgaming.serverstarter


import atm.bloodworkxgaming.serverstarter.ServerStarter.Companion.LOGGER
import atm.bloodworkxgaming.serverstarter.ServerStarter.Companion.lockFile
import atm.bloodworkxgaming.serverstarter.config.ConfigFile
import atm.bloodworkxgaming.serverstarter.mirror.core.InstallerZip
import atm.bloodworkxgaming.serverstarter.mirror.core.LibraryDownloadTask
import atm.bloodworkxgaming.serverstarter.mirror.download.DownloadProviders
import atm.bloodworkxgaming.serverstarter.mirror.installer.*
import atm.bloodworkxgaming.serverstarter.util.ByteProgressReporter
import atm.bloodworkxgaming.serverstarter.util.LoaderDefaults
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.apache.commons.io.FileUtils
import org.apache.commons.io.FilenameUtils
import org.fusesource.jansi.Ansi.ansi
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.*
import kotlin.concurrent.thread
import kotlin.math.max

class DownloadLoaderException(message: String, exception: Exception) : IOException(message, exception)
class LoaderManager(private val configFile: ConfigFile, private val internetManager: InternetManager) {
    private val runningProcesses = mutableListOf<Process>()
    init {
        setupShutdownHook()
    }

    private fun setupShutdownHook() {
        Runtime.getRuntime().addShutdownHook(thread(start = false) {
            LOGGER.warn("Shutdown Hook called, trying to stop any running subprocess.")
            for (runningProcess in runningProcesses) {
                runningProcess.destroy()
            }
        })
    }

    fun handleServer() {
        val startTimes = ArrayList<LocalDateTime>()
        val timerString = configFile.launch.crashTimer
        val crashTimer =
            try {
                when {
                    timerString.endsWith("h") -> java.lang.Long.parseLong(
                        timerString.dropLast(1)
                    ) * 60 * 60
                    timerString.endsWith("min") -> java.lang.Long.parseLong(
                        timerString.dropLast(3)
                    ) * 60
                    timerString.endsWith("s") -> java.lang.Long.parseLong(
                        timerString.dropLast(1)
                    )
                    else -> java.lang.Long.parseLong(timerString)
                }
            } catch (e: NumberFormatException) {
                LOGGER.error("Invalid crash time format given", e)
                -1L
            }

        var shouldRestart: Boolean
        do {
            val now = LocalDateTime.now()
            startTimes.removeIf { start -> start.until(now, ChronoUnit.SECONDS) > crashTimer }

            startServer()
            startTimes.add(now)

            LOGGER.info("Server has been stopped, it has started " + startTimes.size + " times in " + configFile.launch.crashTimer)


            shouldRestart = configFile.launch.autoRestart && startTimes.size <= configFile.launch.crashLimit
            if (shouldRestart) {
                LOGGER.info("Restarting server in 10 seconds, press ctrl+c to stop")
                try {
                    Thread.sleep(10_000)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    LOGGER.error("Interrupted while waiting to restart the server", e)
                }
            }

        } while (shouldRestart)
    }

    private fun checkEULA(basepath: String) {
        try {
            val eulaFile = File(basepath + "eula.txt")


            val lines: MutableList<String>
            if (eulaFile.exists()) {
                lines = FileUtils.readLines(eulaFile, "utf-8")
            } else {
                lines = ArrayList()
                Collections.addAll(
                    lines,
                    "#By changing the setting below to TRUE you are indicating your agreement to our EULA (https://account.mojang.com/documents/minecraft_eula).",
                    "#" + ZonedDateTime.now()
                        .format(DateTimeFormatter.ofPattern("E MMM d HH:mm:ss O y", Locale.ENGLISH)),
                    "eula=false"
                )
            }


            if (lines.size > 2 && !lines[2].contains("true")) {
                LOGGER.info(ansi().fgCyan().a("You have not accepted the eula yet."))
                LOGGER.info(
                    ansi().fgCyan().a("By typing TRUE you are indicating your agreement to the EULA of Mojang.")
                )
                LOGGER.info(
                    ansi().fgCyan()
                        .a("Read it at https://account.mojang.com/documents/minecraft_eula before accepting it.")
                )

                val answer = readln()
                if (answer.trim().equals("true", ignoreCase = true)) {
                    LOGGER.info("You have accepted the EULA.")
                    lines[2] = "eula=true\n"
                    eulaFile.writeText(
                        lines.joinToString(System.lineSeparator(), postfix = System.lineSeparator()),
                        Charsets.UTF_8
                    )
                }
            }

        } catch (e: IOException) {
            LOGGER.error("Error while checking EULA", e)
        }

    }

    fun installLoader(basePath: String, loaderVersion: String, mcVersion: String, loaderName: String? = null): Boolean {
        // installerUrl 留空时按 manifest 给出的 loader 名与版本推导（见 LoaderDefaults），用户不必再手改
        val loader = LoaderDefaults.resolveLoader(loaderName, loaderVersion)
        if (configFile.install.installerUrl.isBlank() && loader == null) {
            throw InitException(
                    "installerUrl is empty and the loader could not be derived from the pack " +
                            "(loader name '$loaderName', version '$loaderVersion'); " +
                            "please set install.installerUrl in the config, or set loaderVersion to something " +
                            "like 'neoforge-21.1.249' so it can be derived")
        }

        val autoDetect = configFile.install.installerUrl.isBlank()
        val derivedUrl = if (autoDetect) LoaderDefaults.installerUrl(loader!!, mcVersion) else null
        if (autoDetect && derivedUrl == null) {
            throw InitException(
                    "Could not derive an installer url for loader '${loader!!.name}', please set install.installerUrl in the config")
        }

        val url = (derivedUrl ?: configFile.install.installerUrl)
                .replace("{{@loaderversion@}}", loaderVersion)
                .replace("{{@mcversion@}}", mcVersion)
        if (autoDetect) {
            LOGGER.info("installerUrl is empty, derived from the pack loader '${loader!!.name}-${loader.version}': $url")
        }
        val installerArguments =
            if (autoDetect) LoaderDefaults.installerArguments(loader!!) else configFile.install.installerArguments

        var installerPath = File(basePath + "installer.jar")
        val result: Boolean =
            if (url.contains("fabric")) {
                installerPath = File(basePath + "fabric-server-launch.jar")
                installFabric(url, installerPath)
            } else if (configFile.install.downloadSource == "bmclapi") {
                LOGGER.info("You're using BMCLAPI")
                try {
                    mirrorInstall(basePath, url, loaderVersion, mcVersion)
                    true
                } catch (e: Exception) {
                    LOGGER.warn("镜像安装失败，回退 --installServer: ${e.message}")
                    installForge(basePath, url, installerPath, installerArguments)
                }
            } else {
                // 官方源失败时自动再走一次镜像（bmclapi），与上面「镜像失败 → 官方」对称。
                // 安装器以非 0 退出码结束（常见：mojmaps 等下载被墙）同样算失败，不能当成装好了。
                var officialError: Exception? = null
                val officialSucceeded = try {
                    installForge(basePath, url, installerPath, installerArguments)
                } catch (e: Exception) {
                    officialError = e
                    false
                }
                if (officialSucceeded) {
                    true
                } else {
                    LOGGER.warn("Failed to install the loader from the official source " +
                            "(${officialError?.message ?: "installer exit code != 0"}), retrying through the BMCLAPI mirror")
                    try {
                        mirrorInstall(basePath, url, loaderVersion, mcVersion)
                        true
                    } catch (mirrorError: Exception) {
                        LOGGER.error("Mirror fallback failed as well (${mirrorError.message}), giving up")
                        throw officialError ?: InitException(
                                "Loader installation failed: the official installer did not finish successfully and the BMCLAPI mirror failed too (${mirrorError.message})",
                                mirrorError)
                    }
                }
            }

        if (!result) {
            // 不写 lock、不写 EULA、直接中止：装失败却标成已安装，下次启动会跳过安装并去启动一个不存在的服务器
            throw InitException("Loader installation failed (see the installer output above); " +
                    "the server was NOT marked as installed and will not be started")
        }
        lockFile.loaderInstalled = true
        lockFile.loaderVersion = loaderVersion
        lockFile.mcVersion = mcVersion
        ServerStarter.saveLockFile(lockFile)
        checkEULA(basePath)
        return result
    }

    /**
     * Fabric's installer version is not conclude in the metadata files
     * Using api to get the latest installer version.
     */
    fun getFabricInstallerVersion(): String {
        val installerUrl = "https://meta.fabricmc.net/v2/versions/installer"
        try {
            val sourceInstaller = internetManager.get(installerUrl)
            val turnsType = object : TypeToken<List<InstallerInfo>>() {}.type
            val installerJson = Gson().fromJson<ArrayList<InstallerInfo>>(sourceInstaller, turnsType)

            return installerJson?.firstOrNull()?.version
                ?: throw DownloadLoaderException(
                    "Fabric installer metadata from $installerUrl is empty",
                    IllegalStateException("No installer version returned by $installerUrl")
                )
        } catch (e: IOException) {
            LOGGER.error("Problem while installing Loader from $installerUrl", e)
            throw DownloadLoaderException("Problem while installing Loader from $installerUrl", e)
        }
    }

    /**
     * joint url to get the fabric-server-launcher.jar
     * @param url: the installerUrl from config file.
     * @param installerPath: the file "fabric-server-launcher.jar"
     */
    fun installFabric(url: String,installerPath: File): Boolean{
        // get Installer Version:
        val fabricVersion = getFabricInstallerVersion()
        var fabricUrl = ""
        try {
            fabricUrl = url+"/${fabricVersion}/server/jar"
            LOGGER.info("Attempting to download fabric from $fabricUrl")
            internetManager.downloadToFile(fabricUrl, installerPath, ByteProgressReporter())

            LOGGER.info("Done installing loader!")
        }catch (e:IOException){
            LOGGER.error("Problem while installing Loader from $fabricUrl",e)
            throw DownloadLoaderException("Problem while installing Loader from $fabricUrl", e)
        }catch (e:InterruptedException){
            LOGGER.error("Problem while installing Loader from $fabricUrl", e)
            throw DownloadLoaderException("Problem while installing Loader from $fabricUrl", e)
        }

        return true
    }

    fun installForge(basePath: String, url: String, installerPath: File, installerArguments: List<String>): Boolean {
        try {
            LOGGER.info("Attempting to download installer from $url")
            internetManager.downloadToFile(url, installerPath, ByteProgressReporter())

            LOGGER.info("Starting installation of Loader, installer output incoming")
            LOGGER.info("Check log for installer for more information", true)
            val java =
                if (configFile.launch.forcedJavaPath.isEmpty()) "java" else configFile.launch.processedForcedJavaPath
            val installer = ProcessBuilder(
                java,
                "-jar",
                installerPath.absolutePath,
                *installerArguments.toTypedArray()
            )
                .inheritIO()
                .directory(File(basePath))
                .start()


            val exitCode = installer.waitFor()
            if (exitCode != 0) {
                // 老代码不看退出码：安装器失败（例如 DOWNLOAD_MOJMAPS 被墙）也会返回 true，
                // 于是 lock 里被标成 loaderInstalled 并直接启动一个缺文件的服务器
                LOGGER.error("The loader installer exited with code $exitCode, the loader is NOT installed")
                return false
            }
            LOGGER.info("Done installing loader, deleting installer!")
            installerPath.delete()
        } catch (e: IOException) {
            LOGGER.error("Problem while installing Loader from $url", e)
            throw DownloadLoaderException("Problem while installing Loader from $url", e)
        } catch (e: InterruptedException) {
            LOGGER.error("Problem while installing Loader from $url", e)
            throw DownloadLoaderException("Problem while installing Loader from $url", e)
        }

        return true
    }

    /**
     * 镜像站进程内安装（§4.3.1）：下载安装器 → 解析计划 → 版本校验 → 下载 targets → 静态抽取 → processors。
     * 任一步异常向上抛，由 installLoader 兜底回退 --installServer。
     */
    private fun mirrorInstall(basePath: String, installerUrl: String, loaderVersion: String, mcVersion: String) {
        val provider = DownloadProviders.create(configFile.install.downloadSource, configFile.install.mirrorUrl)
        val installRoot = File(basePath)
            LOGGER.info("install dir: ${installRoot.absolutePath}")
        val installerFile = File.createTempFile("serverstarter-installer", ".jar")
        try {
            LOGGER.info("mirror: downloading installer $installerUrl(candidate: ${provider.injectURL(installerUrl)})")
            LibraryDownloadTask(internetManager.httpClient, provider.getConcurrency())
                .downloadToFile(installerUrl, installerFile, provider, progress = ByteProgressReporter())
            InstallerZip.openAndVerify(installerFile).close()   // 无 sha1 元数据 → 仅 zip 完整性
            val plan = InstallerJsonExtractor.parsePlan(installerFile)
            if (plan.mcVersion != mcVersion) {
                throw VersionMismatchException("install plan MC version ${plan.mcVersion} is not consistent with $mcVersion")
            }
            if (plan.versionType == 0) {
                throw UnsupportedOperationException("老式 fat installer 不支持镜像安装（versionType=0）")
            }
            val java = getEffectiveJavaPath()
            val installer: ModLoaderInstaller =
                if (plan.isNeoForge) NeoForgeInstaller(internetManager.httpClient, java)
                else ForgeInstaller(internetManager.httpClient, java)
            installer.install(plan, installRoot, installerFile, provider)
        } finally {
            // installerFile.delete()
        }
    }

    fun installSpongeBootstrapper(basePath: String): String {
        val filename = FilenameUtils.getName(configFile.install.spongeBootstrapper)
        val downloadFile = File(basePath + filename)

        try {
            internetManager.downloadToFile(configFile.install.spongeBootstrapper, downloadFile, ByteProgressReporter())
        } catch (e: IOException) {
            LOGGER.error("Error while downloading bootstrapper", e)
            throw e
        }

        return filename
    }

    private fun startServer() {

        try {
            val levelName = try {
                val props = Properties()
                File("server.properties").inputStream().use {
                    props.load(it)
                }

                props["level-name"] as String?
            } catch (e: FileNotFoundException) {
                null
            }

            val filename =
                if (configFile.launch.spongefix) {
                    lockFile.spongeBootstrapper
                } else {
                    replacePlaceholders(configFile.launch.startFile)
                }

            val launchJar = File(configFile.install.normalizedInstallPath + filename)
            val arguments = mutableListOf<String>()
            val ramPreArguments = mutableListOf<String>()
            val ramPostArguments = mutableListOf<String>()

            if (configFile.launch.ramDisk)
                if (levelName == null) {
                     LOGGER.error("The level-name in the server.properties is empty, therefore we can't create a ramdisk")
                }else if (OSUtil.isLinux) {
                    ramPreArguments.addAll(arrayOf("rsync", "-aAXv", "${levelName}_backup/", levelName))
                } else {
                    LOGGER.warn("Windows does not support RAMDisk yet!")
                }

            if (!configFile.launch.preJavaArgs.isEmpty()) {
                arguments.addAll(configFile.launch.preJavaArgs.trim().split(' ').dropWhile { it.isEmpty() })
            }


            val java = getEffectiveJavaPath()

            val processedArguments = configFile.launch.javaArgs.map(::replacePlaceholders)

            arguments.add(java)
            arguments.addAll(processedArguments)
            arguments.add("-Xmx${configFile.launch.maxRam}")

            if (configFile.launch.minRam.isNotBlank()) {
                arguments.add("-Xms${configFile.launch.minRam}")
            } else if (configFile.launch.javaArgs.none { it.trim().startsWith("-Xms") }) {
                try {
                    val xmx =
                        Integer.parseInt(configFile.launch.maxRam.substring(0, configFile.launch.maxRam.length - 1))
                    val xms = max(1, xmx / 2)
                    val ending = configFile.launch.maxRam.substring(configFile.launch.maxRam.length - 1)
                    arguments.add("-Xms$xms$ending")

                } catch (e: NumberFormatException) {
                    LOGGER.error("Problem while calculating XMS", e)
                }
            }

            // Construct the actual launch command
            // For <= 1.16 it has to launch the jar
            // for >= 1.17 it has to add the classes to the classpath
            arguments.addAll(
                configFile.launch.startCommand
                    // 旧示例配置在 startCommand 下留过一个空列表项（"-"），YAML 会解析成 null；
                    // 直接 map 会在 replacePlaceholders 的非空参数上抛 NPE，这里先过滤掉
                    .filterNotNull()
                    .map(::replacePlaceholders)
                    .map { it.replace("{{@startFile@}}", launchJar.absolutePath) }
            )

            if (configFile.launch.ramDisk)
                when (OSUtil.isLinux) {
                    true -> {
                        ramPostArguments.addAll(arrayOf("rsync", "-aAXv", "$levelName/", "${levelName}_backup"))
                    }
                    false -> {
                        LOGGER.warn("Windows does not support RAMDisk yet!")
                    }
                }

            warnAboutMissingArgsFiles(arguments)
            LOGGER.info("Using arguments: $arguments", true)
            LOGGER.info("Starting Loader, output incoming")
            LOGGER.info("For output of this check the server log", true)
            if (configFile.launch.ramDisk)
                startAndWaitForProcess(ramPreArguments)

            startAndWaitForProcess(arguments)

            if (configFile.launch.ramDisk)
                startAndWaitForProcess(ramPostArguments)

        } catch (e: IOException) {
            LOGGER.error("Error while starting the server", e)
        } catch (e: InterruptedException) {
            LOGGER.error("Error while starting the server", e)
        }

    }

    /**
     * Forge / NeoForge 的 `@libraries/.../<os>_args.txt` 启动参数：文件不存在时 JVM 只打印一行
     * "Error: Could not find or load main class @..." 就退出，日志里很难看出是路径写错了
     * （最常见的是配置里留着另一个 loader 的 neoforged / minecraftforge 路径）。
     * 这里提前告警，并列出实际存在的 args 文件。
     */
    private fun warnAboutMissingArgsFiles(arguments: List<String>) {
        val installRoot = File(configFile.install.normalizedInstallPath)
        arguments.filter { it.startsWith("@") }.forEach { arg ->
            val target = File(installRoot, arg.substring(1).removePrefix("./"))
            if (target.isFile) return@forEach
            val available = try {
                File(installRoot, "libraries/net").walkTopDown()
                        .filter { it.isFile && it.name.endsWith("_args.txt") }
                        .map { "@" + it.relativeTo(installRoot).invariantSeparatorsPath }
                        .take(6)
                        .toList()
            } catch (e: Exception) {
                emptyList()
            }
            val hint = if (available.isEmpty()) {
                "No *_args.txt exists under libraries/net, the loader install probably did not finish."
            } else {
                "Args files that do exist: " + available.joinToString(", ")
            }
            LOGGER.warn("The start command references '$arg' but ${target.absolutePath} does not exist; " +
                    "the server will exit immediately. $hint")
        }
    }

    /**
     * Finds the correct java path depending on the config, either:
     * 1. Forced java path
     * 2. correct jvm version on the $PATH
     * 3. 'java'
     */
    private fun getEffectiveJavaPath(): String = when {
        configFile.launch.forcedJavaPath.isNotBlank() -> configFile.launch.processedForcedJavaPath
        configFile.launch.supportedJavaVersions.isNotEmpty() -> {
            // Find the best suitable java version
            LOGGER.info("Attempting to find suitable jvm for supported version ${configFile.launch.supportedJavaVersions}")

            val command = if (OSUtil.isWindows) {
                arrayOf("where", "java")
            } else {
                arrayOf("which", "-a", "java")
            }
            try {
                val path = ProcessBuilder(*command).start().let { pathProcess ->
                    val javaPaths = pathProcess.inputStream.bufferedReader().use { it.readLines() }
                    pathProcess.waitFor()
                    pathProcess.outputStream.close()
                    pathProcess.errorStream.close()
                    javaPaths
                }.firstOrNull { path ->
                    val text = ProcessBuilder(path, "-version").start().let { versionProcess ->
                        val versionOutput = versionProcess.errorStream.bufferedReader().use { it.readText() }
                        versionProcess.waitFor()
                        versionProcess.outputStream.close()
                        versionProcess.inputStream.close()
                        versionOutput
                    }
                    configFile.launch.supportedJavaVersions
                        .any { text.contains(Regex("\"(1\\.)?${it}")) }
                }

                if (path == null) {
                    LOGGER.warn("Couldn't find any JVM installation matching the supported versions, falling back to 'java', but this might fail.")
                    "java"
                } else {
                    LOGGER.info("Found suitable JVM at path $path.")
                    path.replace("\\", "/")
                }
            } catch (e: Exception) {
                LOGGER.error("Couldn't find jvm, falling back to 'java'", e)
                "java"
            }
        }
        else -> "java"
    }



    /**
     * Replaces all custom variables in supported strings
     */
    private fun replacePlaceholders(s: String): String = s
        .replace("{{@loaderversion@}}", lockFile.loaderVersion)
        .replace("{{@mcversion@}}", lockFile.mcVersion)
        .replace("{{@os@}}", if (OSUtil.isWindows) "win" else "unix")


    private fun startAndWaitForProcess(args: List<String>) {
        ProcessBuilder(args).apply {
            inheritIO()
            directory(File(configFile.install.normalizedInstallPath))
            start().apply {
                runningProcesses.add(this)

                waitFor()
                outputStream.close()
                errorStream.close()
                inputStream.close()
            }
        }
    }
}

data class InstallerInfo(
    val url: String,
    val maven: String,
    val version: String,
    val stable: Boolean
)