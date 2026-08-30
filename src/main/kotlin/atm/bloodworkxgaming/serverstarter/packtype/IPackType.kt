package atm.bloodworkxgaming.serverstarter.packtype

import atm.bloodworkxgaming.serverstarter.InternetManager
import atm.bloodworkxgaming.serverstarter.config.ConfigFile
import atm.bloodworkxgaming.serverstarter.packtype.curse.CurseIDPackType
import atm.bloodworkxgaming.serverstarter.packtype.curse.CursePackType
import atm.bloodworkxgaming.serverstarter.packtype.modrinth.ModrinthPackType
import atm.bloodworkxgaming.serverstarter.packtype.zip.ZipFilePackType
import atm.bloodworkxgaming.serverstarter.util.ApiVerdict
import java.io.File

/**
 * 最终生效版本（Q3：整合包 manifest 优先、yaml 兜底）。
 */
data class PackVersions(val mcVersion: String, val loaderVersion: String)

/**
 * 从整合包 manifest 解析出的原始版本，字段为 null 表示 manifest 未提供对应版本。
 */
data class ManifestVersions(val mcVersion: String?, val loaderVersion: String?)

interface IPackType {
    companion object {
        private val packtype = mutableMapOf<String, (ConfigFile, InternetManager) -> IPackType>(
                Pair("curse", ::CursePackType),
                Pair("curseforge", ::CursePackType),
                Pair("modrinth", ::ModrinthPackType),
                Pair("curseid", ::CurseIDPackType),
                Pair("zip", ::ZipFilePackType),
                Pair("zipfile", ::ZipFilePackType)
        )

        fun createPackType(packTypeName: String, configFile: ConfigFile, internetManager: InternetManager): IPackType? {
            return packtype[packTypeName]?.invoke(configFile, internetManager)
        }
    }

    /**
     * ① 下载/定位整合包 zip（Q2 第一步；Q5 本地 zip 也在此定位）。
     */
    fun obtainPack(): File

    /**
     * ② 从 zip 解析最终生效版本（Q2 第二步；Q3：manifest 优先、yaml 兜底），不落盘。
     */
    fun resolveVersions(zip: File): PackVersions

    /**
     * ④ 解压 overrides + 下载模组（Q2 四步中的最后一步）。
     */
    fun installPack(zip: File)

    /**
     * 平台 API 下载阶段对每个下载文件的 CF 三态判定（文件名 → ApiVerdict）。
     * 参与安装后 jar 扫描的综合决策（与 TOML 规则冲突时提示用户仲裁）；
     * 仅 curse 子类在下载时填充（缺省/双端），modrinth / zip 包型无 API 数据保持空。
     */
    val apiVerdictsByFile: Map<String, ApiVerdict>
        get() = emptyMap()
}
