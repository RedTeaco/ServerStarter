package atm.bloodworkxgaming.serverstarter.util

import java.util.Properties

/**
 * 应用版本单一来源（Q4）。
 *
 * 唯一显式版本字段在 build.gradle 的 project.version，构建期由 processResources
 * 将 version.properties 中的 ${version} 占位符展开为该值。本对象在运行时只读取
 * classpath 资源，保证 jar 被单独分发后仍能拿到版本号。
 *
 * 所有异常/缺失场景一律回退为 "unknown"，绝不抛异常：资源缺失、值缺失/空白、
 * 值仍是未展开的占位符（"@version@" 或 "${version}"，IDE 直跑或未走
 * processResources 时会发生）均视为版本未知，程序照常启动。
 */
object AppVersion {
    private const val RESOURCE_PATH = "/version.properties"
    private const val UNKNOWN = "unknown"

    val version: String by lazy { loadVersion() }

    private fun loadVersion(): String {
        return try {
            val input = AppVersion::class.java.getResourceAsStream(RESOURCE_PATH)
                ?: return UNKNOWN
            input.use { stream ->
                val props = Properties()
                props.load(stream)
                val value = props.getProperty("version")
                if (value.isNullOrBlank() || value == "@version@" || value.startsWith("\${")) UNKNOWN else value
            }
        } catch (e: Exception) {
            UNKNOWN
        }
    }
}