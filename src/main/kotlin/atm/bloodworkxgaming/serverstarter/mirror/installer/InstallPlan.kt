package atm.bloodworkxgaming.serverstarter.mirror.installer

/** 安装计划数据模型（对齐设计文档 §4.2）。 */
data class InstallPlan(
    val mcVersion: String,
    val loaderVersion: String,        // NeoForge "21.1.241"（去掉 neoforge- 前缀）；Forge "1.20.1-forge-47.4.0"
    val versionType: Int,             // 0=老式 fat installer；1=现代 thin installer（主场景）；2=有 json 但 processors 为空
    val isNeoForge: Boolean,
    val serverJarPath: String,        // 相对 basePath，如 "libraries/net/minecraft/server/1.21.1/server-1.21.1.jar"
    val vanillaServerUrl: String?,    // 可能为 null → 由调用方走版本清单解析
    val vanillaServerSha1: String?,
    val targets: List<DownloadTarget>,
    val processors: List<Processor>,  // 已过滤 server 侧
    val data: Map<String, String>     // 已取 server 侧值的 data token 表
)

/** 单个待下载文件。relativePath 相对安装根目录。 */
data class DownloadTarget(val relativePath: String, val url: String, val sha1: String?, val size: Long?)

/** 单个 processor：jar 为 maven 坐标；classpath 为 maven 坐标列表；outputs 为 输出文件路径(相对 basePath) → sha1。 */
data class Processor(val jar: String, val classpath: List<String>, val args: List<String>, val outputs: Map<String, String>)

/** 镜像安装相关的基础异常（P4/P5 复用）。 */
open class MirrorInstallException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** 安装计划中的 MC 版本与配置期望版本不一致。 */
class VersionMismatchException(message: String) : MirrorInstallException(message)

/** processor 执行失败。 */
class ProcessorExecutionException(message: String, cause: Throwable? = null) : MirrorInstallException(message, cause)

/** 下载失败（含 sha1 校验失败）。 */
class DownloadFailedException(message: String, cause: Throwable? = null) : MirrorInstallException(message, cause)