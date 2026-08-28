package atm.bloodworkxgaming.serverstarter.mirror.core

import atm.bloodworkxgaming.serverstarter.mirror.installer.MirrorInstallException
import java.io.File
import java.util.zip.ZipFile

object InstallerZip {
    const val INSTALL_PROFILE = "install_profile.json"

    /**
     * 校验 zip 完整性并可打开；缺 install_profile.json → MirrorInstallException。
     * 返回 ZipFile（调用方负责 close）。
     */
    fun openAndVerify(zip: File): ZipFile {
        val zipFile = try {
            ZipFile(zip)
        } catch (e: Exception) {
            throw MirrorInstallException("Invalid installer zip: ${zip.absolutePath}", e)
        }
        if (zipFile.getEntry(INSTALL_PROFILE) == null) {
            zipFile.close()
            throw MirrorInstallException("install_profile.json not found in ${zip.absolutePath}")
        }
        return zipFile
    }

    /** 读取 zip 内文本条目；不存在返回 null。zip 无法打开 → MirrorInstallException。测试代码 */
//    fun readEntryText(zip: File, entryName: String): String? {
//        val zipFile = try {
//            ZipFile(zip)
//        } catch (e: Exception) {
//            throw MirrorInstallException("Cannot open zip: ${zip.absolutePath}", e)
//        }
//        zipFile.use {
//            val entry = it.getEntry(entryName) ?: return null
//            return it.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { r -> r.readText() }
//        }
//    }

    /** 抽取 zip 内条目到目标文件（创建父目录）；不存在抛 MirrorInstallException。 */
    fun extractEntry(zip: File, entryName: String, destFile: File) {
        val zipFile = try {
            ZipFile(zip)
        } catch (e: Exception) {
            throw MirrorInstallException("Cannot open zip: ${zip.absolutePath}", e)
        }
        zipFile.use {
            val entry = it.getEntry(entryName)
                ?: throw MirrorInstallException("Entry not found in zip: $entryName")
            destFile.parentFile?.mkdirs()
            it.getInputStream(entry).use { input ->
                destFile.outputStream().use { output -> input.copyTo(output) }
            }
        }
    }

    /** zip 是否含某条目。 */
    fun hasEntry(zip: File, entryName: String): Boolean {
        val zipFile = try {
            ZipFile(zip)
        } catch (e: Exception) {
            throw MirrorInstallException("Cannot open zip: ${zip.absolutePath}", e)
        }
        zipFile.use {
            return it.getEntry(entryName) != null
        }
    }
}