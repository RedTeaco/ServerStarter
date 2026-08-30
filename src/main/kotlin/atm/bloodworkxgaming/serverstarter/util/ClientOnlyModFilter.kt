package atm.bloodworkxgaming.serverstarter.util

import atm.bloodworkxgaming.serverstarter.ServerStarter.Companion.LOGGER
import com.google.gson.JsonParser
import org.fusesource.jansi.Ansi.ansi
import java.io.File
import java.io.IOException
import java.util.jar.JarFile

/**
 * CF（平台 API）下载阶段对单个文件的三态判定。
 *
 * - [DEFAULT]：gameVersions 中既无 client 也无 server（缺省）→ 保留
 * - [CLIENT_ONLY]：有 client 无 server（仅客户端）→ 删除（下载阶段直接过滤）
 * - [DUAL]：client、server 都有（双端）→ 保留
 *
 * [display] 用于冲突提示文案（统一英文，避免中文乱码）。
 */
enum class CfVerdict(val display: String) {
    DEFAULT("default"),
    CLIENT_ONLY("CLIENT"),
    DUAL("BOTH")
}

/**
 * 客户端模组检测与清理（安装末尾扫描 mods/ 目录）。
 *
 * 判定依据（仅 jar 内容 + 平台 API 下载阶段判定，不依赖运行时环境）：
 * - Fabric（独立规则）：根目录 fabric.mod.json 的顶层 environment == "client"（大小写不敏感）
 *   → 无条件删除，不与 CF / TOML 做冲突比较、不弹提示；其余情况无条件保留（不再看 TOML）。
 * - Forge/NeoForge：META-INF/neoforge.mods.toml（优先）或 META-INF/mods.toml，
 *   用**正则文本解析**提取 [[...]] 块（不引入 TOML 解析器）：
 *   - 存在 modId == "minecraft"（忽略大小写）的块 → 以该块 side 为最终判定；
 *     该块无 side → 视为无判定（保留），不回退。
 *   - 无 minecraft 块 → 取首个带 side 字段的块的 side。
 *   - 最终 side == "CLIENT"（忽略大小写）→ 客户端专用；否则保留。
 *   - 读不到任何块（坏 TOML）→ UNKNOWN（保留，fail-safe）。
 * - 综合决策：无 CF 判定（modrinth/zip/本地）→ 仅按 TOML 规则；CF 与 TOML 不一致
 *   （CF=缺省/双端 × TOML=CLIENT）→ LOGGER 提示用户 Y/N 仲裁（EULA 同款青色）；
 *   EOF/非法输入按 fail-safe 保留或重问。
 *
 * 处置：仅删除判定为客户端专用的 jar；无元数据 / 解析失败一律保留（宁漏勿误）。
 */
object ClientOnlyModFilter {

    enum class Verdict { CLIENT_ONLY, NOT_CLIENT_ONLY, UNKNOWN }

    private const val FABRIC_JSON = "fabric.mod.json"
    private const val NEOFORGE_TOML = "META-INF/neoforge.mods.toml"
    private const val FORGE_TOML = "META-INF/mods.toml"

    private val BLOCK_HEADER = Regex("""^\s*\[\[([^\]]+)\]\]""", RegexOption.MULTILINE)
    private val MOD_ID_FIELD = Regex("""^\s*modId\s*=\s*["'](.*?)["']""", RegexOption.MULTILINE)
    private val SIDE_FIELD = Regex("""^\s*side\s*=\s*["'](.*?)["']""", RegexOption.MULTILINE)

    private data class VerdictResult(val verdict: Verdict, val detail: String? = null)

    /** jar 元数据来源；Fabric 为独立规则，TOML 参与 CF 冲突比较，None 无元数据。 */
    private sealed class JarMetadata {
        data class Fabric(val verdict: VerdictResult) : JarMetadata()
        data class Toml(val verdict: VerdictResult) : JarMetadata()
        object None : JarMetadata()
    }

    private data class TomlBlock(val name: String, val text: String)

    /** fabric.mod.json 内容判定：environment == "client"（大小写不敏感）；解析失败视为 false */
    fun fabricEnvironmentIsClient(content: String): Boolean = fabricVerdict(content).verdict == Verdict.CLIENT_ONLY

    /** mods.toml 内容判定：minecraft 块 side == "CLIENT"（大小写不敏感，规则见类注释）；解析失败视为 false */
    fun forgeTomlIsClient(content: String): Boolean = forgeVerdict(content).verdict == Verdict.CLIENT_ONLY

    /**
     * 判定单个 mod jar（打开 zip、自动检测 loader）。打不开抛 IOException 由调用方处理。
     */
    fun determineVerdict(jar: File): Verdict = inspect(jar).verdictOf()

    /**
     * 扫描 mods/ 顶层目录（仅直接子级 *.jar），删除客户端专用模组。
     *
     * @param cfVerdicts 平台 API 下载阶段的 CF 三态判定（文件名 → CfVerdict）；
     *                   仅 curse 来源填充，modrinth/zip/本地文件缺省（按 TOML 规则独断）。
     * @param input 用户输入读取函数（默认 readLine），测试可注入脚本化输入。
     */
    fun removeClientOnlyMods(
            modsDir: File,
            cfVerdicts: Map<String, CfVerdict> = emptyMap(),
            input: () -> String? = ::readLine
    ) {
        val files = modsDir.listFiles()
        if (files == null) {
            LOGGER.info("Mods directory not found, skipping client mod cleanup: " + modsDir.absolutePath)
            return
        }

        val jars = files.filter { it.isFile && it.name.endsWith(".jar", ignoreCase = true) }
        var removed = 0

        for (jar in jars) {
            val metadata = try {
                LOGGER.info("detecting " + jar.name)
                inspect(jar)
            } catch (e: IOException) {
                LOGGER.warn("Unable to read mod JAR, skipping (keeping): " + jar.name + " (" + e.message + ")")
                continue
            }

            val cf = cfVerdicts[jar.name]
            if (metadata.verdictOf() == Verdict.UNKNOWN) {
                LOGGER.info("Unable to determine mod environment, keeping: " + jar.name)
            }

            if (decideToDelete(jar, metadata, cf, input)) {
                if (jar.delete()) {
                    removed++
                    LOGGER.info("Deleting client-only mods: " + jar.name + " (" + metadata.detail() + ")")
                } else {
                    LOGGER.error("Failed to delete client-only mods (file may be in use): " + jar.absolutePath)
                }
            }
        }

        LOGGER.info("Client mod cleanup completed: scanned " + jars.size + " mods, removed " + removed + ".")
    }

    /**
     * 综合决策：返回 true=删除、false=保留。
     *
     * - Fabric 独立规则：CLIENT_ONLY → 删；其余 → 保留（不弹提示）。
     * - TOML：CLIENT_ONLY 时按 CF 判定——无 CF → 删；CF=CLIENT_ONLY → 删（一致，防御分支）；
     *   CF=缺省/双端 → 冲突提示；TOML 非 CLIENT → 保留。
     */
    private fun decideToDelete(jar: File, metadata: JarMetadata, cf: CfVerdict?, input: () -> String?): Boolean {
        return when (metadata) {
            is JarMetadata.Fabric -> metadata.verdict.verdict == Verdict.CLIENT_ONLY
            is JarMetadata.Toml -> when (metadata.verdict.verdict) {
                Verdict.CLIENT_ONLY -> when (cf) {
                    null -> true
                    CfVerdict.CLIENT_ONLY -> true
                    else -> askUserDelete(cf, jar.name, input)
                }
                else -> false
            }
            JarMetadata.None -> false
        }
    }

    /**
     * 冲突仲裁：LOGGER 青色提示（与 EULA 交互一致），Y=删除、N=保留、
     * 其他字符提示非法并重问；EOF（null）→ 默认保留（fail-safe）。
     */
    private fun askUserDelete(cf: CfVerdict, jarName: String, input: () -> String?): Boolean {
        while (true) {
            LOGGER.info(ansi().fgCyan().a("API returns " + cf.display + ", but toml returns CLIENT. Please choose whether to delete this mod [Y/N]: "))
            val answer = input()?.trim()
            when {
                answer == null -> {
                    LOGGER.warn("Unable to read user input (EOF), keeping: " + jarName)
                    return false
                }
                answer.equals("Y", ignoreCase = true) -> return true
                answer.equals("N", ignoreCase = true) -> return false
                else -> LOGGER.info(ansi().fgCyan().a("Invalid input, please enter Y or N: "))
            }
        }
    }

    private fun inspect(jar: File): JarMetadata {
        JarFile(jar).use { jf ->
            val fabricEntry = jf.getEntry(FABRIC_JSON)
            if (fabricEntry != null) {
                jf.getInputStream(fabricEntry).use { input ->
                    return JarMetadata.Fabric(fabricVerdict(input.reader(Charsets.UTF_8).readText()))
                }
            }

            val forgeEntry = jf.getEntry(NEOFORGE_TOML) ?: jf.getEntry(FORGE_TOML)
            if (forgeEntry != null) {
                jf.getInputStream(forgeEntry).use { input ->
                    return JarMetadata.Toml(forgeVerdict(input.reader(Charsets.UTF_8).readText()))
                }
            }

            return JarMetadata.None
        }
    }

    private fun fabricVerdict(content: String): VerdictResult {
        return try {
            val json = JsonParser.parseString(content).asJsonObject
            val env = json.get("environment")
            if (env != null && env.isJsonPrimitive && env.asString.equals("client", ignoreCase = true)) {
                VerdictResult(Verdict.CLIENT_ONLY, "fabric.mod.json environment=" + env.asString)
            } else {
                VerdictResult(Verdict.NOT_CLIENT_ONLY)
            }
        } catch (e: Exception) {
            LOGGER.warn("Failed to parse fabric.mod.json (keeping): " + e.message)
            VerdictResult(Verdict.UNKNOWN)
        }
    }

    /**
     * 正则文本解析 mods.toml：提取 [[...]] 块，minecraft 块 side 优先，
     * 无 minecraft 块回退首个带 side 的块；最终 side == CLIENT（忽略大小写）→ 客户端专用。
     * 读不到任何块（坏 TOML）→ UNKNOWN（保留）。
     */
    private fun forgeVerdict(content: String): VerdictResult {
        val blocks = extractBlocks(content)
        if (blocks.isEmpty()) {
            LOGGER.warn("Failed to parse mods.toml (no [[...]] blocks found, keeping)")
            return VerdictResult(Verdict.UNKNOWN)
        }

        val minecraftBlock = blocks.firstOrNull { blockModId(it)?.equals("minecraft", ignoreCase = true) == true }
        val side: String? = if (minecraftBlock != null) {
            blockSide(minecraftBlock)
        } else {
            blocks.firstNotNullOfOrNull { blockSide(it) }
        }

        val finalSide = side?.trim()
        return if (finalSide != null && finalSide.equals("CLIENT", ignoreCase = true)) {
            VerdictResult(Verdict.CLIENT_ONLY, "mods.toml side=" + finalSide)
        } else {
            VerdictResult(Verdict.NOT_CLIENT_ONLY)
        }
    }

    /** 按行提取 [[...]] 块；块头行不进入块文本。 */
    private fun extractBlocks(content: String): List<TomlBlock> {
        val blocks = mutableListOf<TomlBlock>()
        var name: String? = null
        val sb = StringBuilder()
        for (line in content.lineSequence()) {
            val m = BLOCK_HEADER.find(line)
            if (m != null) {
                if (name != null) {
                    blocks.add(TomlBlock(name, sb.toString()))
                }
                name = m.groupValues[1].trim()
                sb.setLength(0)
            } else if (name != null) {
                sb.append(line).append('\n')
            }
        }
        if (name != null) {
            blocks.add(TomlBlock(name, sb.toString()))
        }
        return blocks
    }

    private fun blockModId(block: TomlBlock): String? =
            MOD_ID_FIELD.find(block.text)?.groupValues?.get(1)

    private fun blockSide(block: TomlBlock): String? =
            SIDE_FIELD.find(block.text)?.groupValues?.get(1)

    private fun JarMetadata.verdictOf(): Verdict = when (this) {
        is JarMetadata.Fabric -> verdict.verdict
        is JarMetadata.Toml -> verdict.verdict
        JarMetadata.None -> Verdict.UNKNOWN
    }

    private fun JarMetadata.detail(): String? = when (this) {
        is JarMetadata.Fabric -> verdict.detail
        is JarMetadata.Toml -> verdict.detail
        JarMetadata.None -> null
    }
}
