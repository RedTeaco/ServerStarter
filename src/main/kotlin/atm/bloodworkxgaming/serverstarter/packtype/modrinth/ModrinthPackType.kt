package atm.bloodworkxgaming.serverstarter.packtype.modrinth

import atm.bloodworkxgaming.serverstarter.InternetManager
import atm.bloodworkxgaming.serverstarter.ServerStarter.Companion.LOGGER
import atm.bloodworkxgaming.serverstarter.config.ConfigFile
import atm.bloodworkxgaming.serverstarter.packtype.AbstractZipbasedPackType
import atm.bloodworkxgaming.serverstarter.packtype.writeToFile
import com.google.gson.JsonParser
import org.apache.commons.io.FileUtils
import org.apache.commons.io.FilenameUtils
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStreamReader
import java.net.URISyntaxException
import java.nio.file.PathMatcher
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import kotlin.collections.ArrayList

open class ModrinthPackType (private val configFile: ConfigFile, internetManager: InternetManager) : AbstractZipbasedPackType(configFile, internetManager) {
    private var loaderVersion: String = configFile.install.loaderVersion
    private var mcVersion: String = configFile.install.mcVersion
    private val oldFiles = File(basePath + "OLD_TO_DELETE/")

    override fun cleanUrl(url: String): String {
        return url
    }

    override fun getLoaderVersion(): String {
        return loaderVersion
    }

    override fun getMCVersion(): String {
        return mcVersion
    }

    @Throws(IOException::class)
    override fun handleZip(file: File, pathMatchers: List<PathMatcher>) {
        // delete old installer folder
        FileUtils.deleteDirectory(oldFiles)

        // start with deleting the mods folder as it is not guaranteed to have override mods
        val modsFolder = File(basePath+"mods/")

        if (modsFolder.exists())
            FileUtils.moveDirectory(modsFolder, File(oldFiles,"mods"))
        LOGGER.info("Moved the mods folder")

        LOGGER.info("Starting to unzip files.")
        //unzip start
        try{
            ZipInputStream(FileInputStream(file)).use{
                    zis -> var entry: ZipEntry? = zis.nextEntry

                loop@ while (entry != null){
                    LOGGER.info("Entry in mrpack: $entry", true)
                    val name = entry.name

                    if (name == "modrinth.index.json")
                        zis.writeToFile(File(basePath + "modrinth.index.json"))

                    //overrides
                    if (name.startsWith("overrides/")){
                        val path = entry.name.substring(10)

                        when {
                            pathMatchers.any{ it.matches(Paths.get(path))} ->
                                LOGGER.info("Skipping $path as it is on the ignore list.", true)


                            !name.endsWith("/") -> {
                                val outfile = File(basePath + path)
                                LOGGER.info("Copying entry to = $outfile", true)


                                outfile.parentFile?.mkdirs()

                                zis.writeToFile(outfile)
                            }

                            name != "overrides/" -> {
                                val newFolder = File(basePath + path)
                                if (newFolder.exists())
                                    FileUtils.moveDirectory(newFolder, File(oldFiles, path))

                                LOGGER.info("Folder moved:"+ newFolder.absolutePath, true)
                            }
                        }
                    }
                    entry = zis.nextEntry
                }

                zis.closeEntry()
            }
        } catch (e: IOException){
            LOGGER.error("Could not unzip files",e)
            throw e
        }

        LOGGER.info("Done unzipping the files.")
    }

    @Throws(IOException::class)
    override fun postProcessing() {

        val mods_url = ArrayList<String>()

        InputStreamReader(FileInputStream(File(basePath + "modrinth.index.json")), "utf-8").use { reader ->
            val json = JsonParser.parseReader(reader).asJsonObject
            LOGGER.info("mainfest JSON Object: $json", true)
            val mcObj = json.getAsJsonObject("dependencies")

            if (mcVersion.isEmpty()){
                mcVersion = mcObj.getAsJsonArray("minecraft").asString
            }

            //gets the loader version
            if (loaderVersion.isEmpty()){
                loaderVersion = mcObj.getAsJsonPrimitive(mcObj.keySet().last()).asString // modrinth 中 dependencies 的第二个键值对即为modloader的版本
            }

            // gets all the mods
            for (jsonElement in json.getAsJsonArray("files")) {
                val obj = jsonElement.asJsonObject
                // env server: unsupported 可以自动排除ResourcePack and ShaderPack
                if (obj.getAsJsonObject("env").getAsJsonPrimitive("server").asString.equals("unsupported")){
                    continue
                }
                else{
                    mods_url.add(obj.getAsJsonArray("downloads").get(0).asString)
                }
            }
        }
        downloadMods(mods_url)

    }


    /**
     * Downloads the mods specified in the modrinth.index.json
     *
     * @param mods List of the mods from the json file
     */
    private fun downloadMods(mods: List<String>){

        LOGGER.info("Start Download Mods.")
        val count = AtomicInteger(0)
        val totalCount = mods.size
        val fallbackList = ArrayList<String>()

        mods.stream().parallel().forEach {s -> processSingleMod(s, count, totalCount, fallbackList)}
        val secondFail = ArrayList<String>()
        fallbackList.forEach { s -> processSingleMod(s, count, totalCount, secondFail)}

        if (secondFail.isNotEmpty()) {
            LOGGER.warn("Failed to download (a) mod(s):")
            for (s in secondFail) {
                LOGGER.warn("\t" + s)
            }
        }
    }

    private fun processSingleMod(mod: String, counter: AtomicInteger, totalCount: Int, fallbackList: MutableList<String>){
        try {
            val modName = FilenameUtils.getName(mod)

            internetManager.downloadToFile(mod, File(basePath+"mods/"+modName))
            LOGGER.info("["+String.format("% 3d", counter.incrementAndGet()) + "/" + totalCount + "]Downloaded mod: " + modName)
        }catch (e: IOException) {
            LOGGER.error("Failed to download mod", e)
            fallbackList.add(mod)
        } catch (e: URISyntaxException) {
            LOGGER.error("Invalid url for $mod", e)
        }
    }
}