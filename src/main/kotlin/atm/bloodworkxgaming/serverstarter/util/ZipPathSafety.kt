package atm.bloodworkxgaming.serverstarter.util

import java.io.File

/**
 * 解压路径安全（防 ZipSlip）：把 zip 条目里的相对路径解析到安装目录之下，
 * 规范化后仍越界（`../` 穿越、绝对路径、盘符、UNC）就返回 null 由调用方跳过。
 *
 * 为什么需要：`ZipExtractor` 直接 `File(basePath, entryName)` 落盘，一个构造过（或损坏）的
 * mrpack / zip 里带 `../../` 的条目就能把文件写到安装目录之外。
 *
 * 判定用 `Path.startsWith`（按路径段比较，不是字符串前缀），并且两侧都做 canonical 化，
 * 因此 `a/../b.txt` 这类仍在目录内的写法照常放行。
 */
object ZipPathSafety {

    /**
     * @param base 安装基础目录（不需要预先存在）
     * @param relPath zip 条目名（已剥离 overrides/ 前缀）
     * @return 规范化后的目标文件；越界返回 null
     */
    fun resolveInside(base: File, relPath: String): File? {
        if (relPath.isBlank()) return null

        val basePath = try {
            base.canonicalFile.toPath()
        } catch (e: Exception) {
            base.absoluteFile.toPath()
        }

        val target = try {
            File(base, relPath).canonicalFile.toPath()
        } catch (e: Exception) {
            return null
        }

        return if (target.startsWith(basePath)) target.toFile() else null
    }
}
