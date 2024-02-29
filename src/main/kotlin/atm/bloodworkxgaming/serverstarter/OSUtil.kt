package atm.bloodworkxgaming.serverstarter

import java.util.*

object OSUtil {
    val osName: String by lazy {
        try {
            System.getProperty("os.name")
        } catch (e: Exception) {
            ""
        }
    }

    val isLinux: Boolean = osName.lowercase(Locale.getDefault()).startsWith("linux")
    val isWindows: Boolean = osName.lowercase(Locale.getDefault()).startsWith("win")
}
