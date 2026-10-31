package atm.bloodworkxgaming.serverstarter

import atm.bloodworkxgaming.serverstarter.config.ConfigFile
import atm.bloodworkxgaming.serverstarter.yaml.CustomConstructor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.representer.Representer
import java.io.File

/**
 * 仓库里随包发布的示例配置必须能被加载，且不能带会让运行期炸掉的空列表项。
 *
 * 背景：示例里 `startCommand` 下曾有一个孤立的 `-`，YAML 解析成 `[..., null]`，
 * 启动阶段 `startCommand.map(::replacePlaceholders)` 会在非空参数上抛 NPE，
 * 也就是「装完了、一启动就崩」。这类问题单测最容易兜住。
 */
class ConfigTemplateTest {

    private fun loadTemplate(): ConfigFile {
        val template = File("server-setup-config.yaml")
        assertTrue("example config not found at ${template.absolutePath}", template.isFile)
        val dumperOptions = DumperOptions()
        val yaml = Yaml(CustomConstructor(ConfigFile::class.java), Representer(dumperOptions), dumperOptions)
        return yaml.load(template.readText(Charsets.UTF_8))
    }

    @Test
    fun `example config loads`() {
        val config = loadTemplate()
        assertNotNull(config.launch)
        assertEquals("curse", config.install.modpackFormat)
    }

    @Test
    fun `example startCommand has no null or blank entries`() {
        val config = loadTemplate()
        @Suppress("UNCHECKED_CAST")
        val raw = config.launch.startCommand as List<Any?>
        assertTrue("startCommand must not be empty: $raw", raw.isNotEmpty())
        assertTrue("startCommand contains a null entry (stray '-' in yaml): $raw", raw.none { it == null })
        assertTrue("startCommand contains a blank entry: $raw", raw.none { it is String && it.isBlank() })
    }

    @Test
    fun `example formatSpecific lists have no null entries`() {
        val config = loadTemplate()
        @Suppress("UNCHECKED_CAST")
        val ignoreProject = config.install.formatSpecific["ignoreProject"] as? List<Any?>
        if (ignoreProject != null) {
            assertTrue("ignoreProject contains a null entry (stray '-' in yaml): $ignoreProject",
                    ignoreProject.none { it == null })
        }
    }
}
