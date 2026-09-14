<div align="center">
<h1>Server Starter</h1>
<a href="https://github.com/RedTeaco/ServerStarter/releases"><img src="https://img.shields.io/github/v/release/RedTeaco/ServerStarter" alt="Release"></a>
<a href="https://github.com/RedTeaco/ServerStarter/blob/master/LICENSE"><img src="https://img.shields.io/github/license/RedTeaco/ServerStarter" alt="License"></a>
<a href="https://github.com/RedTeaco/ServerStarter/releases"><img src="https://img.shields.io/github/downloads/RedTeaco/ServerStarter/total" alt="Downloads"></a>
<a href="https://github.com/RedTeaco/ServerStarter/issues"><img src="https://img.shields.io/github/issues/RedTeaco/ServerStarter" alt="Issues"></a>
<a href="https://github.com/RedTeaco/ServerStarter/stargazers"><img src="https://img.shields.io/github/stars/RedTeaco/ServerStarter" alt="Stars"></a>
</div>

---
[**English**](README.md)|[**中文**](docs/README_zh.md)
## Introduction

**Server Starter** is a one-click **installation and startup tool for Minecraft modded servers**, written in Kotlin. It automates downloading and installing the server, filtering client-only mods (not 100% accurate), allocating memory, and starting the server.

## Features

- **Multi-loader support**: Forge, NeoForge and Fabric can all be installed and launched; `installerUrl` supports placeholder templates, so a single configuration works across multiple loaders.
- **Multiple modpack formats**: Supports `curseforge`, `modrinth`, `zip` and more; modpacks can be downloaded from an online URL or used from a local file via `file://`.
- **Automatic client-only mod filtering**: During installation, client-only mods (e.g. Sodium) are automatically removed based on CurseForge client/server markers, Modrinth platform environment info, and the TOML files inside mod jars.
- **Server process management**: Automatic restart on crash, restart limits (prevents infinite crash loops), Linux RAMDisk support, automatic EULA handling, and automatic lookup of a suitable JVM on PATH according to `supportedJavaVersions`.
- **BMCLAPI mirror support**: With `install.downloadSource: bmclapi`, the entire NeoForge / Forge installation process and downloads of vanilla-related resources are completed in-process through the BMCLAPI mirror, which is especially useful when official sources are slow or unreachable; if the mirror install fails, it automatically falls back to the official process.
- **Cross-platform launcher scripts**: `startserver.bat` (Windows) and `startserver.sh` (Linux) are distributed with each Release. The scripts automatically download the matching version of the main program and run it, so no manual jar preparation is needed.

## Requirements

- **Java**: The tool itself is compiled targeting Java 8 and runs on Java 8+; the Java version required by the server depends on the MC version — Java 8 for MC ≤ 1.16, Java 17 / 21 for MC ≥ 1.17.
- **Operating system**: Windows (`startserver.bat`) and Linux (`startserver.sh`); the RAMDisk feature is currently Linux-only.
- **Minecraft version**: 1.16.5+ is tested and working; if you run into compatibility issues, feel free to [submit an issue](https://github.com/RedTeaco/ServerStarter/issues).
- **Network**: Internet access is required by default to download the modpack, loaders and mods; when official sources are unreachable, enable the BMCLAPI mirror (see [Configuration](#install-install-configuration)).

## Usage

### Quick start (for server owners)

1. Go to [Releases](https://github.com/RedTeaco/ServerStarter/releases), download the latest `serverstarter-<version>.zip` and extract it into your server directory. The archive contains the main program jar, the `startserver.bat` / `startserver.sh` launcher scripts, and a `server-setup-config.yaml` example config.
2. Edit `server-setup-config.yaml`; at minimum you need to set:
   - `install.modpackUrl`: the modpack download URL (or a `file://` local path); the default `"./.zip"` automatically uses a `.zip` file in the current directory
   - `install.modpackFormat`: the modpack format (`curse` / `modrinth` / `zip`, etc.)
   - `install.installerUrl`: the Loader download URL — configure it according to the notes in the config file; defaults to NeoForge
   - `launch.startFile`: if the loader is not forge/neoforge, this value must also be changed
   - `launch.startCommand`: if the loader's MC version is < 1.17 or is not neoforge, this value must be changed
3. Run the launcher script:
   - Windows: double-click `startserver.bat`
   - Linux: `./startserver.sh`

   The script automatically downloads the matching version of the main program and runs it.
4. On first run the following happens automatically: download modpack → install Mod Loader → download mods → filter client-only mods → start the server. Just accept the Mojang EULA when prompted during the first start.
5. After installation, running the script again only starts the server; the installation state is recorded in the auto-generated `serverstarter.lock` (**do not edit it manually**); on subsequent runs, if the config is detected to be unchanged, installation is skipped and the server starts directly.
6. To force a reinstall, delete `serverstarter.lock` and run again (see [FAQ](#faq)).

### Modpack author workflow

1. **Package the modpack**: Export the modpack from CurseForge / Modrinth, or package it yourself as a zip (containing an `overrides/` directory and a manifest).
2. **Write the config**: Create `server-setup-config.yaml`, set `modpackUrl` and `modpackFormat`, and use `install.ignoreFiles` to exclude client-only files (e.g. `mods/optifine*.jar`, `kubejs/client_scripts/**`), and `install.additionalFiles` to add extra files the server needs.
3. **Test locally**: First run the full install and launch flow against your local modpack zip using `file://` to make sure everything works.
4. **Distribute**: Ship the modpack zip together with `server-setup-config.yaml` and `startserver.bat` / `startserver.sh` (the script downloads the main program automatically, so server owners don't need to prepare a jar in advance).

### Command-line arguments

| Argument | Behavior |
|---|---|
| (none) | Install (if needed) and start the server |
| `install` | Install only, do not start the server |

Example:

```bash
java -jar serverstarter-2.5.1.jar
java -jar serverstarter-2.5.1.jar install
```

## Configuration

The config file is `server-setup-config.yaml` (a full example is provided at the repository root). The config is split into three sections — `modpack`, `install`, `launch` — and supports the `{{@mcversion@}}`, `{{@loaderversion@}}`, `{{@os@}}`, `{{@startFile@}}` placeholders as well as `${ENV_VAR}` environment variable substitution.

### modpack — modpack info

| Field | Description | Default | Example |
|---|---|---|---|
| `name` | Modpack name, shown in logs etc. | `""` | `Example Modpack` |
| `description` | Modpack description | `""` | `This is an awesome modpack.` |

### install — installation config

| Field | Description | Default | Example |
|---|---|---|---|
| `mcVersion` | Minecraft version; when `~` / `null` / `""`, uses the version from the modpack manifest | `~` | `1.20.1` |
| `loaderVersion` | Loader version (Forge / NeoForge / Fabric); when empty, uses the version from the modpack manifest | `~` | `47.1.0` |
| `installerUrl` | Installer download URL template, supports the `{{@loaderversion@}}` / `{{@mcversion@}}` placeholders. Official templates for Forge / Fabric / NeoForge are in the example file's comments | see example | `https://maven.neoforged.net/releases/net/neoforged/neoforge/{{@loaderversion@}}/neoforge-{{@loaderversion@}}-installer.jar` |
| `installerArguments` | Arguments passed to the installer (Forge uses `--installServer`, Fabric needs none) | `[]` | `["--installServer"]` |
| `downloadSource` | Download source: `mojang` (official direct connection, uses the original `--installServer` flow) / `bmclapi` (install in-process through the BMCLAPI mirror, falls back to the official flow on failure) | `mojang` | `bmclapi` |
| `mirrorUrl` | Mirror apiRoot override (only effective with `downloadSource: bmclapi`); when empty uses the default `https://bmclapi2.bangbang93.com`; an OpenBMCLAPI node can be used | `~` | `https://bmclapi.example.com` |
| `modpackUrl` | Modpack download URL; supports http(s) URLs and `file://` local paths (relative paths work too); using the fixed value `"./.zip"` automatically finds a `.zip` file in the same directory | `""` | `file://./modpacks/pack.zip` |
| `modpackFormat` | Modpack format: `curse` / `curseforge`, `modrinth`, `curseid`, `zip` / `zipfile` | `""` | `curse` |
| `formatSpecific.ignoreProject` | Ignore list, supported for both `curse` and `modrinth` (the Modrinth pack type also uses it to skip the client-only check); accepts Modrinth project ID/slug, CurseForge project ID/file ID, or file-name rules — see "ignoreProject forms" below | `[]` | `[263420, AANobbMI]` |
| `baseInstallPath` | Server installation base path; empty means the current directory | `~` | `server/` |
| `ignoreFiles` | List of files to ignore during installation; supports glob (default) or a `regex:` / `glob:` prefix to force the match type | `[]` | `mods/optifine*.jar`, `kubejs/client_scripts/**` |
| `additionalFiles` | List of additional files (`url` + `destination`) to add files the server needs but the client doesn't have | `~` | `- url: https://…/spark-forge.jar`<br>`  destination: mods/spark-forge.jar` |
| `localFiles` | List of local files / folders to copy (`from` + `to`) | `[]` | `- from: setup/AOF 2/.minecraft`<br>`  to: setup/.` |
| `checkFolder` | Check the folder before installing | `true` | `false` |
| `installLoader` | Whether to install the Mod Loader; set to `false` to only install the modpack without a loader | `true` | `false` |
| `spongeBootstrapper` | Sponge bootstrap jar download URL (needed when `launch.spongefix` is enabled) | `""` | `https://github.com/simon816/SpongeBootstrap/releases/download/v0.7.1/SpongeBootstrap-0.7.1.jar` |
| `connectTimeout` | Timeout (seconds) for connecting to any web service; increase it on poor networks | `30` | `60` |
| `readTimeout` | Timeout (seconds) for reading from any web service; increase it on poor networks | `30` | `60` |

#### ignoreProject forms (curse / modrinth)

`install.formatSpecific.ignoreProject` is a list; a file is ignored when any of its identities matches. Prefixes are case-insensitive:

| Form | Meaning | Needs network |
|---|---|---|
| `263420` (plain digits, no prefix) | CurseForge **project ID** (legacy curse semantics) | yes (on the Modrinth pack type a `POST /v1/mods/files` lookup maps fileId → modId) |
| `AANobbMI` / `sodium` (any other bare token) | Modrinth **project ID or slug** | only for slugs / non-canonical IDs (`GET /v3/project/{id or slug}`) |
| `curseProject:263420` / `cfProject:263420` | CurseForge project ID (explicit) | same as above |
| `curseFile:8837013` / `cfFile:8837013` | CurseForge **file ID** | no (offline) |
| `modrinth:AANobbMI` | Modrinth project ID or slug (explicit) | same as bare token |
| `name:sodium*.jar` | file-name glob (matches the basename; a leading `mods/` is allowed) | no (offline) |
| `glob:iris*.jar` / `regex:.*-client\.jar` | file-name glob / regex | no (offline) |

Notes:

- On the Modrinth pack type the identity is taken from **all** `downloads` links of an entry, not just the first
  one, so packs whose CurseForge links come first (with Modrinth links later) now match correctly; entries with
  CurseForge links only can still be ignored through CurseForge project ID / file ID / file-name rules.
- File-name rules match the raw URL file name, its percent-decoded form (`%2b` ↔ `+`) and the manifest `path`
  name, so `name:CTM-1.21-1.2.1+3.jar` and `name:CTM-1.21-1.2.1%2b3.jar` both hit the same file.
- Any resolution failure (network down, unknown slug, no `curseForgeApiKey`) only logs a warning and falls back
  to literal comparison — it never deletes extra files or aborts the installation (fail-safe).
- When a file has several download links, the download uses `downloads[0]` first and falls back to the following
  links in order; the file name on disk always comes from the first link.

### launch — launch config

| Field | Description | Default | Example |
|---|---|---|---|
| `spongefix` | Apply a launch wrapper to some mods to fix Sponge compatibility | `false` | `true` |
| `ramDisk` | Use a RAMDisk for the world folder (Linux only; **the server must be fully run once first** before enabling, otherwise backup/restore is impossible) | `false` | `true` |
| `checkOffline` | Check network connectivity against an unrelated server before starting, showing a notice when offline | `false` | `true` |
| `maxRam` | Server maximum memory (`-Xmx`) | `""` | `5G` |
| `minRam` | Server minimum memory (`-Xms`); when left empty, automatically takes half of `maxRam` | `""` | `2G` |
| `autoRestart` | Whether to automatically restart the server after a crash | `false` | `true` |
| `crashLimit` | Maximum number of automatic restarts allowed within the crash timing window | `0` | `10` |
| `crashTimer` | Crash timing window, syntax is `[number]h` / `[number]min` / `[number]s` | `""` | `60min` |
| `preJavaArgs` | Arguments placed before the `java` command (a string, e.g. a nice value on Linux) | `~` | `nice -n 5` |
| `startFile` | The launch jar file name, supports the `{{@mcversion@}}` / `{{@loaderversion@}}` placeholders; must match the file name produced by the installer | `""` | `forge-{{@mcversion@}}-{{@loaderversion@}}.jar` |
| `startCommand` | Server launch command (an array, one entry per argument), supports the `{{@startFile@}}`, `{{@os@}}` and other placeholders. MC < 1.16 launches with `-jar`; MC ≥ 1.17 uses `@libraries/…/{{@os@}}_args.txt` | `[]` | see example file |
| `forcedJavaPath` | Force an absolute path to the Java executable; supports `${ENV_VAR}` environment variable substitution; when empty uses `java` from PATH | `~` | `"C:/Program Files/Java/jdk-17/bin/java.exe"` |
| `supportedJavaVersions` | List of allowed Java versions; when enabled, automatically finds a matching JVM on PATH (falls back to `java` if not found) | `[]` | `[17, 21]` |
| `javaArgs` | List of additional JVM arguments (e.g. Aikar's Flags) | `[]` | `- '-XX:+UseG1GC'` |

## FAQ

### The server start is stuck?

If the console font is blue, user confirmation is required — follow the prompts. There are currently two actions that need manual confirmation:
1. EULA not accepted: on startup, the program automatically asks whether to accept the EULA. If it hasn't been accepted, type `TRUE` and press Enter; the program continues automatically.
2. During the client mod detection stage, if the environment check obtained from the platform doesn't match the result from the local mod files, the program asks whether to keep the mod: `Y` = remove, `N` = keep.

### How do I force a reinstall?

Delete `serverstarter.lock` and run the launcher script again to trigger a full reinstall. You can also change `install.loaderVersion` or `install.modpackUrl` in the config — the lock file detects the config change and reinstalls automatically. Note that `serverstarter.lock` is an auto-generated file; do not edit it manually.

### EULA not accepted / can't confirm interactively?

On first launch, if `eula.txt` doesn't contain `eula=true`, the program asks interactively. In unattended or non-interactive environments, create `eula.txt` in the server directory in advance and write `eula=true` into it (please read and agree to the [Mojang EULA](https://account.mojang.com/documents/minecraft_eula) first).

### Java version mismatch?

MC ≤ 1.16 needs Java 8, MC ≥ 1.17 needs Java 17 / 21. List the allowed versions in `launch.supportedJavaVersions` and the program will automatically find a suitable JVM on PATH; alternatively use `launch.forcedJavaPath` to specify a path directly. If no matching JVM is found, it falls back to `java` on PATH (which may fail to start).

### Client-only mods got installed onto the server / were removed by mistake?

The program filters client-only mods based on CurseForge client/server markers, Modrinth platform environment info, and TOML rules; when information is uncertain, it uses a fail-safe strategy (keeps the mod). For precise control:
- use `install.formatSpecific.ignoreProject` to ignore entire projects by ID: the curse pack type takes
  CurseForge project IDs, while the modrinth pack type also accepts Modrinth project ID/slug, CurseForge
  project ID/file ID, and file-name rules (see "ignoreProject forms");
- use `install.ignoreFiles` to exclude/keep specific files;
- if a mod is misclassified, feel free to [submit an issue](https://github.com/RedTeaco/ServerStarter/issues).

## Building & Contributing

A local build requires JDK 8+ and Gradle (the Gradle Wrapper in the repo is recommended).

| Command | Artifact |
|---|---|
| `./gradlew build` | Main program jar (`build/libs/serverstarter-<version>.jar`) |
| `./gradlew packageDist` | Distribution directory (contains launcher scripts and example config, version number replaced automatically) |
| `./gradlew zipDist` | Release archive (`build/release/serverstarter-<version>.zip`) |

Contribution flow: Fork this repo → create a feature branch → make changes and add tests (`src/test`) → submit a Pull Request. For problems and suggestions, please [submit an issue](https://github.com/RedTeaco/ServerStarter/issues) directly.

## License & Acknowledgements

This project is open source under the **MIT License**; see [LICENSE](https://github.com/RedTeaco/ServerStarter/blob/master/LICENSE).

The original Server Starter was developed by [BloodWorkXGaming](https://github.com/BloodWorkXGaming) (with contributions from Yoosk and others). This project is a continuation maintained by [RedTeaco](https://github.com/RedTeaco), and we thank all contributors.
