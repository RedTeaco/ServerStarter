package atm.bloodworkxgaming.serverstarter.util

import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream
import java.nio.charset.Charset

/**
 * 控制台输出的编码与颜色适配。
 *
 * 为什么需要它：
 * - jansi 的 `AnsiConsole.systemInstall()` 会用 **JVM 默认字符集**（Java 18+ 为 UTF-8）重建 `System.out`，
 *   而中文版 Windows 的 cmd.exe 仍按控制台代码页（GBK/936）解码 → 「无限计划」变成 `鏃犻檺璁″垝`；
 * - 没开 VT 处理的旧式 cmd.exe 不认 ANSI 转义，`\x1b[0m` 会原样漏成 `[m`。
 *
 * 因此这里统一做两件事：
 * 1. 按**控制台真实编码**（`stdout.encoding`，Java 18+ 提供）重建 `System.out` / `System.err`，中文按控制台
 *    代码页输出，任何 CP 下都能正确显示；
 * 2. 只有当终端确实支持 ANSI 时才保留颜色，否则在打印前剥掉转义序列。
 *
 * 可用 `-Dserverstarter.color=always|never` 或环境变量 `NO_COLOR` 覆盖颜色判断。
 */
object ConsoleOutput {

    private val ANSI_PATTERN = Regex("\u001B\\[[0-9;]*m")

    /** 控制台实际使用的字符集（优先 Java 18+ 的 `stdout.encoding`）。 */
    val charset: Charset = readCharset()

    /** 当前终端是否支持 ANSI 转义序列。 */
    val ansiSupported: Boolean = readAnsiPreference()

    /** 用控制台编码重建标准输出/错误流（在打印任何日志之前调用一次）。 */
    fun install() {
        System.setOut(PrintStream(FileOutputStream(FileDescriptor.out), true, charset.name()))
        System.setErr(PrintStream(FileOutputStream(FileDescriptor.err), true, charset.name()))
    }

    fun stripAnsi(message: String): String = ANSI_PATTERN.replace(message, "")

    private fun readCharset(): Charset {
        val name = System.getProperty("stdout.encoding")
                ?: System.getProperty("sun.stdout.encoding")
                ?: System.getProperty("file.encoding")
        return runCatching { if (name == null) Charsets.UTF_8 else Charset.forName(name) }
                .getOrDefault(Charsets.UTF_8)
    }

    private fun readAnsiPreference(): Boolean {
        val override = System.getProperty("serverstarter.color")?.lowercase()
        if (override == "always" || override == "true") return true
        if (override == "never" || override == "false") return false
        if (System.getenv("NO_COLOR") != null) return false

        val os = System.getProperty("os.name", "").lowercase()
        if (!os.contains("win")) return true

        // Windows：只有能处理 VT 序列的终端才保留颜色
        val env = System.getenv()
        if (env["WT_SESSION"] != null) return true                 // Windows Terminal
        if (env["ANSICON"] != null) return true                    // ANSICON
        if (env["ConEmuANSI"]?.equals("ON", ignoreCase = true) == true) return true
        if (env["TERM_PROGRAM"] != null) return true               // VS Code / iTerm 等
        if (env["MSYSTEM"] != null) return true                    // Git Bash / mintty
        val term = env["TERM"]?.lowercase()
        return term != null && (term.startsWith("xterm") || term.contains("256color"))
    }
}
