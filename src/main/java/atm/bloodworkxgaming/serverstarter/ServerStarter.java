package atm.bloodworkxgaming.serverstarter;

import atm.bloodworkxgaming.serverstarter.config.ConfigFile;
import atm.bloodworkxgaming.serverstarter.config.LockFile;
import atm.bloodworkxgaming.serverstarter.logger.PrimitiveLogger;
import atm.bloodworkxgaming.serverstarter.packtype.IPackType;
import atm.bloodworkxgaming.serverstarter.packtype.TypeFactory;
import org.apache.commons.io.FileUtils;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;
import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.representer.Representer;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URL;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;


public class ServerStarter {
    public static final PrimitiveLogger LOGGER = new PrimitiveLogger(new File("serverstarter.log"));
    public static LockFile lockFile = null;
    private static Representer rep;
    private static DumperOptions options;

    static {
        rep = new Representer();
        options = new DumperOptions();
        rep.addClassTag(ConfigFile.class, Tag.MAP);
        rep.addClassTag(LockFile.class, Tag.MAP);
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.FLOW);
    }

    public static void main(String[] args) {
        ConfigFile config = readConfig();
        lockFile = readLockFile();

        boolean installOnly = args.length > 0 && args[0].equals("install");

        if (config == null || lockFile == null) {
            LOGGER.error("One file is null: config: " + config + " lock: " + lockFile);
            return;
        }

        // normalize the file so it can be used
        if (config.install.baseInstallPath == null) config.install.baseInstallPath = "";


        if (lockFile.checkShouldInstall(config) || installOnly) {
            IPackType packtype = TypeFactory.createPackType(config.install.modpackFormat, config);
            if (packtype == null) {
                LOGGER.error("Unknown pack format given in config");
                return;
            }

            packtype.installPack();
            lockFile.packInstalled = true;
            lockFile.packUrl = config.install.modpackUrl;
            saveLockFile(lockFile);


            String forgeVersion = packtype.getForgeVersion();
            String mcVersion = packtype.getMCVersion();
            installForge(config.install.baseInstallPath, forgeVersion, mcVersion);

            FileManager filemanger = new FileManager(config);
            filemanger.installAdditionalFiles();


        } else {
            LOGGER.info("Server is already installed to correct version, to force install delete the serverstarter.lock File.");
        }

        if (installOnly) {
            LOGGER.info("Install only mod, exiting now.");
            return;
        }

        checkEULA(config.install.baseInstallPath);
        handleServer(config);
    }


    /**
     * Reads the config and parses the config
     *
     * @return the configfile object
     */
    public static ConfigFile readConfig() {
        Yaml yaml = new Yaml(new Constructor(ConfigFile.class), rep, options);
        try {
            return yaml.load(new FileInputStream(new File("server-setup-config.yaml")));
        } catch (FileNotFoundException e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Reads the lockfile if present, returns a new if not
     */
    public static LockFile readLockFile() {
        Yaml yaml = new Yaml(new Constructor(LockFile.class), rep, options);
        File file = new File("serverstarter.lock");

        if (file.exists()) {
            try {
                return yaml.load(new FileInputStream(file));
            } catch (FileNotFoundException e) {
                return new LockFile();
            }
        } else {
            return new LockFile();
        }
    }

    /**
     * Writes the lockfile to disk
     *
     * @param lockFile lockfile to write
     */
    public static void saveLockFile(LockFile lockFile) {
        Yaml yaml = new Yaml(new Constructor(LockFile.class), rep, options);
        File file = new File("serverstarter.lock");
        ServerStarter.lockFile = lockFile;

        String lock = "#Auto genereated file, DO NOT EDIT!\n" + yaml.dump(lockFile);
        try {
            FileUtils.write(file, lock, "utf-8", false);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }


    private static void handleServer(ConfigFile config) {
        List<LocalDateTime> starttimes = new ArrayList<>();
        long _crashTimer = -1;
        String timerString = config.launch.crashTimer;

        try {
            if (timerString.endsWith("h"))
                _crashTimer = Long.valueOf(timerString.substring(0, timerString.length() - 1)) * 60 * 69;
            else if (timerString.endsWith("min"))
                _crashTimer = Long.valueOf(timerString.substring(0, timerString.length() - 3)) * 60;
            else if (timerString.endsWith("s"))
                _crashTimer = Long.valueOf(timerString.substring(0, timerString.length() - 1));
            else
                _crashTimer = Long.valueOf(timerString);
        } catch (NumberFormatException e) {
            LOGGER.error("Invalid crash time format given", e);
        }

        final long crashTimer = _crashTimer;

        boolean shouldRestart = true;
        do {
            LocalDateTime now = LocalDateTime.now();
            starttimes.removeIf(start -> start.until(now, ChronoUnit.SECONDS) > crashTimer);

            startServer(config);
            starttimes.add(now);

            LOGGER.info("Server has been stopped, it has started " + starttimes.size() + " times in " + config.launch.crashTimer);


            shouldRestart = config.launch.autoRestart && starttimes.size() <= config.launch.crashLimit;
            if (shouldRestart) {
                LOGGER.info("Restarting server in 10 seconds, press ctrl+c to stop");
                try {
                    Thread.sleep(10000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }

        } while (shouldRestart);
    }

    private static void checkEULA(String basepath) {
        try {
            File eulaFile = new File(basepath + "eula.txt");


            List<String> lines;
            if (eulaFile.exists()) {
                lines = FileUtils.readLines(eulaFile, "utf-8");
            } else {
                lines = new ArrayList<>();
                Collections.addAll(lines,
                        "#By changing the setting below to TRUE you are indicating your agreement to our EULA (https://account.mojang.com/documents/minecraft_eula).",
                        "#" + ZonedDateTime.now().format(DateTimeFormatter.ofPattern("E MMM d HH:mm:ss O y", Locale.ENGLISH)),
                        "eula=false");
            }



            if (lines.size() > 2 && !lines.get(2).contains("true")) {
                try (Scanner scanner = new Scanner(System.in)) {

                    LOGGER.info("You have not accepted the eula yet.");
                    LOGGER.info("By typing TRUE you are indicating your agreement to the EULA of Mojang.");
                    LOGGER.info("Read it at https://account.mojang.com/documents/minecraft_eula before accepting it.");

                    String answer = scanner.nextLine();
                    if (answer.trim().equalsIgnoreCase("true")) {
                        LOGGER.info("You have accepted the EULA.");
                        lines.set(2, "eula=true\n");
                        FileUtils.writeLines(eulaFile, lines);
                    }
                }
            }

        } catch (IOException e) {
            LOGGER.error("Error while checking EULA", e);
        }
    }

    private static void installForge(String basePath, String forgeVersion, String mcVersion) {
        String temp = mcVersion + "-" + forgeVersion;
        String url = "http://files.minecraftforge.net/maven/net/minecraftforge/forge/" + temp + "/forge-" + temp + "-installer.jar";
        // http://files.minecraftforge.net/maven/net/minecraftforge/forge/1.12.2-14.23.3.2682/forge-1.12.2-14.23.3.2682-installer.jar
        File installerPath = new File(basePath + "forge-" + temp + "-installer.jar");


        try {
            LOGGER.info("Attempting to download forge installer from " + url);
            FileUtils.copyURLToFile(new URL(url), installerPath);

            LOGGER.info("Starting installation of Forge, installer output incoming");
            LOGGER.info("Check log for installer for more information", true);
            Process installer = new ProcessBuilder("java", "-jar", installerPath.getAbsolutePath(), "--installServer")
                    .inheritIO()
                    .directory(new File(basePath + "."))
                    .start();

            installer.waitFor();

            LOGGER.info("Done installing forge, deleting installer!");

            lockFile.forgeInstalled = true;
            lockFile.forgeVersion = forgeVersion;
            lockFile.mcVersion = mcVersion;
            saveLockFile(lockFile);

            //noinspection ResultOfMethodCallIgnored
            installerPath.delete();
        } catch (IOException | InterruptedException e) {
            LOGGER.error("Problem while installing Forge", e);
        }
    }

    private static void startServer(ConfigFile configFile) {

        try {
            String filename = "forge-" + lockFile.mcVersion + "-" + lockFile.forgeVersion + "-universal.jar";
            File forgeUniversal = new File(configFile.install.baseInstallPath + filename);

            List<String> arguments = new ArrayList<>();

            arguments.add("java");
            arguments.addAll(configFile.launch.javaArgs);
            Collections.addAll(arguments, "-jar", forgeUniversal.getAbsolutePath());
            arguments.add("nogui");

            LOGGER.info("Starting Forge, output incoming");
            LOGGER.info("For output of this check the server log", true);
            Process process = new ProcessBuilder(arguments)
                    .inheritIO()
                    .directory(new File(configFile.install.baseInstallPath + "."))
                    .start();


            process.waitFor();

            process.getOutputStream().close();
            process.getErrorStream().close();
            process.getInputStream().close();

        } catch (IOException | InterruptedException e) {
            LOGGER.error("Error while starting the server", e);
        }
    }
}
