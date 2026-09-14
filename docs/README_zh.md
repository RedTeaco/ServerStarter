<div align="center">
<h1>Server Starter</h1>
<a href="https://github.com/RedTeaco/ServerStarter/releases"><img src="https://img.shields.io/github/v/release/RedTeaco/ServerStarter" alt="Release"></a>
<a href="https://github.com/RedTeaco/ServerStarter/blob/master/LICENSE"><img src="https://img.shields.io/github/license/RedTeaco/ServerStarter" alt="License"></a>
<a href="https://github.com/RedTeaco/ServerStarter/releases"><img src="https://img.shields.io/github/downloads/RedTeaco/ServerStarter/total" alt="Downloads"></a>
<a href="https://github.com/RedTeaco/ServerStarter/issues"><img src="https://img.shields.io/github/issues/RedTeaco/ServerStarter" alt="Issues"></a>
<a href="https://github.com/RedTeaco/ServerStarter/stargazers"><img src="https://img.shields.io/github/stars/RedTeaco/ServerStarter" alt="Stars"></a>
</div>

---
[**English**](../README.md)|[**中文**](README_zh.md)
## 简介

**Server Starter** 是一款用 Kotlin 编写的 Minecraft 模组服务器**一键安装与启动工具**。包含下载安装服务端、过滤客户端专用模组(非100%准确)、分配内存、启动服务器等步骤的自动化处理。

## 特点

- **多加载器支持**：Forge、NeoForge、Fabric 均可安装与启动，`installerUrl` 支持占位符模板，一套配置适配多种加载器。
- **多整合包格式**：支持 `curseforge`、`modrinth`、`zip` 等格式；整合包既可以从在线 URL 下载，也可以通过 `file://` 使用本地文件。
- **客户端专用模组自动过滤**：安装时依据 CurseForge 的客户端/服务端标记、Modrinth 平台环境信息以及 模组文件中的TOML文件，自动剔除仅客户端模组（如Sodium）。
- **服务器进程管理**：崩溃自动重启、重启次数限制（防无限崩溃循环）、Linux RAMDisk 支持、EULA 自动处理、按 `supportedJavaVersions` 自动在 PATH 中查找合适的 JVM。
- **BMCLAPI 镜像支持**：设置 `install.downloadSource: bmclapi` 后，NeoForge / Forge 的整个安装过程及原版相关资源的下载可通过 BMCLAPI 镜像站进程内完成，官方源慢或不可达的网络环境下尤其有用；镜像安装失败会自动回退官方流程。
- **跨平台启动脚本**：随 Release 分发 `startserver.bat`（Windows）与 `startserver.sh`（Linux），脚本会自动下载对应版本的主程序并执行，无需手动准备 jar。

## 环境要求

- **Java**：工具本身以 Java 8 为目标编译，可在 Java 8+ 上运行；服务器所需的 Java 版本取决于 MC 版本——MC ≤ 1.16 使用 Java 8，MC ≥ 1.17 使用 Java 17 / 21。
- **操作系统**：Windows（`startserver.bat`）与 Linux（`startserver.sh`），RAMDisk 功能目前仅支持 Linux。
- **Minecraft 版本**：1.16.5+ 经测试可用；如遇兼容性问题，欢迎[提交 issue](https://github.com/RedTeaco/ServerStarter/issues) 反馈。
- **网络**：默认需要访问互联网以下载整合包、Loader 与模组；官方源不可达时可启用 BMCLAPI 镜像（见[配置](#install-安装配置)）。

## 使用方式

### 快速开始（腐竹请看）

1. 前往 [Releases](https://github.com/RedTeaco/ServerStarter/releases) 下载最新 `serverstarter-<版本>.zip` 并解压到服务器目录。压缩包内含主程序 jar、`startserver.bat` / `startserver.sh` 启动脚本以及 `server-setup-config.yaml` 配置示例。
2. 编辑 `server-setup-config.yaml`，至少需要设置：
   - `install.modpackUrl`：整合包下载地址（或 `file://` 本地路径）;默认的"./.zip" 表示自动使用当前目录下的 `.zip` 文件
   - `install.modpackFormat`：整合包格式（`curse` / `modrinth` / `zip` 等）
   - `install.installerUrl`: Loader 下载地址 根据配置文件的说明配置，默认为Neoforge
   - `launch.startFile`: 若Loader不是forge/neoforge，还需改动该项目
   - `launch.startCommand`: 若Loader对应的mc版本<1.17或不是neoforge,需改动该项目
3. 运行启动脚本：
   - Windows：双击 `startserver.bat`
   - Linux：`./startserver.sh`
   
   脚本会自动下载对应版本的主程序并执行。
4. 首次运行会自动完成：下载整合包 → 安装 Mod Loader → 下载模组 → 过滤客户端专用模组 → 启动服务器。首次启动时按提示接受 Mojang EULA 即可。
5. 安装完成后再次运行脚本只会启动服务器；安装状态记录在自动生成的 `serverstarter.lock` 中（**请勿手动编辑**）；再次运行时若检测到配置未变化，则跳过安装直接启动服务器。
6. 如需强制重装，删除 `serverstarter.lock` 后重新运行（详见[常见问题](#常见问题-faq)）。


### 整合包作者流程

1. **打包整合包**：从 CurseForge / Modrinth 导出整合包，或自行打包为 zip（含 `overrides/` 目录与 manifest）。
2. **编写配置**：新建 `server-setup-config.yaml`，设置 `modpackUrl`、`modpackFormat`，并用 `install.ignoreFiles` 排除客户端专用文件（如 `mods/optifine*.jar`、`kubejs/client_scripts/**`）、用 `install.additionalFiles` 补充服务器需要的额外文件。
3. **本地测试**：先用 `file://` 指向本地整合包 zip 完整跑一遍安装与启动，确认无误。
4. **分发**：将整合包 zip、`server-setup-config.yaml` 与 `startserver.bat` / `startserver.sh` 一起分发（脚本会自动下载主程序，服主无需预先准备 jar）。

### 命令行参数

| 参数 | 行为 |
|---|---|
| （无参数） | 安装（如需要）并启动服务器 |
| `install` | 仅安装，不启动服务器 |

示例：

```bash
java -jar serverstarter-2.5.1.jar
java -jar serverstarter-2.5.1.jar install
```

## 配置

配置文件为 `server-setup-config.yaml`（仓库根目录提供完整示例）。配置分为 `modpack`、`install`、`launch` 三段，支持 `{{@mcversion@}}`、`{{@loaderversion@}}`、`{{@os@}}`、`{{@startFile@}}` 占位符与 `${ENV_VAR}` 环境变量替换。

### modpack 整合包信息

| 字段 | 说明 | 默认值 | 示例 |
|---|---|---|---|
| `name` | 整合包名称，显示在日志等位置 | `""` | `Example Modpack` |
| `description` | 整合包描述 | `""` | `This is an awesome modpack.` |

### install 安装配置

| 字段 | 说明 | 默认值 | 示例 |
|---|---|---|---|
| `mcVersion` | Minecraft 版本；为 `~` / `null` / `""` 时使用整合包 manifest 中的版本 | `~` | `1.20.1` |
| `loaderVersion` | Loader 版本（Forge / NeoForge / Fabric）；为空时使用整合包 manifest 中的版本 | `~` | `47.1.0` |
| `installerUrl` | 安装器下载地址模板，支持 `{{@loaderversion@}}` / `{{@mcversion@}}` 占位符。Forge / Fabric / NeoForge 官方模板见示例文件注释 | 见示例 | `https://maven.neoforged.net/releases/net/neoforged/neoforge/{{@loaderversion@}}/neoforge-{{@loaderversion@}}-installer.jar` |
| `installerArguments` | 传给安装器的参数（Forge 为 `--installServer`，Fabric 无需） | `[]` | `["--installServer"]` |
| `downloadSource` | 下载源：`mojang`（官方直连，走原 `--installServer` 流程）/ `bmclapi`（通过 BMCLAPI 镜像站进程内完成安装，失败自动回退官方流程） | `mojang` | `bmclapi` |
| `mirrorUrl` | 镜像站 apiRoot 覆盖（仅 `downloadSource: bmclapi` 时生效）；为空使用默认 `https://bmclapi2.bangbang93.com`，可填 OpenBMCLAPI 节点 | `~` | `https://bmclapi.example.com` |
| `modpackUrl` | 整合包下载地址；支持 http(s) URL 与 `file://` 本地路径（相对路径亦可）,若使用固定字符`"./.zip"`则自动寻找同级目录下的`.zip`文件 | `""` | `file://./modpacks/pack.zip` |
| `modpackFormat` | 整合包格式：`curse` / `curseforge`、`modrinth`、`curseid`、`zip` / `zipfile` | `""` | `curse` |
| `formatSpecific.ignoreProject` | 按**平台身份**忽略（`curse` 与 `modrinth` 均支持；Modrinth 包型会同时用于「不下载」与「跳过客户端判定」）；可写 Modrinth 项目 ID/slug、CurseForge 项目 ID/文件 ID，详见下方「ignoreProject 写法」。**按文件名忽略请用 `ignoreFiles`** | `[]` | `[263420, AANobbMI]` |
| `baseInstallPath` | 服务器安装基础路径；为空表示当前目录 | `~` | `server/` |
| `ignoreFiles` | 安装时忽略的文件列表，支持 glob（默认）或 `regex:` / `glob:` 前缀强制匹配类型；`mods/` 前缀项在**下载前**生效（文件名匹配），其余项作用于解压阶段的 overrides 相对路径 | `[]` | `mods/optifine*.jar`、`kubejs/client_scripts/**` |
| `additionalFiles` | 附加文件列表（`url` + `destination`），用于补充服务器需要、客户端没有的文件 | `~` | `- url: https://…/spark-forge.jar`<br>`  destination: mods/spark-forge.jar` |
| `localFiles` | 本地文件 / 文件夹复制列表（`from` + `to`） | `[]` | `- from: setup/AOF 2/.minecraft`<br>`  to: setup/.` |
| `checkFolder` | 安装前检查文件夹 | `true` | `false` |
| `installLoader` | 是否安装 Mod Loader；仅想安装整合包而不装 Loader 时可设为 `false` | `true` | `false` |
| `spongeBootstrapper` | Sponge bootstrap jar 下载地址（`launch.spongefix` 启用时需要） | `""` | `https://github.com/simon816/SpongeBootstrap/releases/download/v0.7.1/SpongeBootstrap-0.7.1.jar` |
| `connectTimeout` | 连接任意 Web 服务的超时（秒），网络不佳时可调大 | `30` | `60` |
| `readTimeout` | 读取任意 Web 服务的超时（秒），网络不佳时可调大 | `30` | `60` |

#### ignoreProject 写法（curse / modrinth 通用）

`install.formatSpecific.ignoreProject` 是一个**平台身份**列表，任一身份命中即忽略该文件。前缀大小写不敏感：

| 写法 | 含义 | 需要联网 |
|---|---|---|
| `263420`（纯数字，无前缀） | CurseForge **项目 ID**（历史 curse 语义） | 是（modrinth 包型下用 `POST /v1/mods/files` 反查 fileId → modId） |
| `AANobbMI`（8 位 base62） | Modrinth **项目 ID** | 否（离线，按字面量比对） |
| `curseProject:263420` / `cfProject:263420` | CurseForge 项目 ID（显式写法） | 同上 |
| `modrinth:AANobbMI` / `mr:AANobbMI` | Modrinth 项目 ID（显式写法，8 位 base62） | 否（离线） |

说明：

- Modrinth 包型下，身份来自 `modrinth.index.json` 中该条目的**全部** `downloads` 链接（不只看第一个），
  因此「CurseForge 链接在前、Modrinth 链接在后」的整合包也能正常命中；纯 CurseForge 链接的条目
  可通过 CurseForge 项目 ID 排除（需要联网把文件 ID 反查成项目 ID）。
- CurseForge 项目 ID 需要联网把包内文件 ID 反查成项目 ID（`POST /v1/mods/files`）；没有 `curseForgeApiKey`
  或请求失败只 warn，不影响其它规则，也绝不会误删或中断安装（fail-safe）。
- 同一文件多个下载链接时，下载优先使用 `downloads[0]`，失败后按顺序回退到后续链接；
  落盘文件名始终取第一个链接的名字。
- **只认两个平台的规范项目 ID**：`name:` / `filename:` / `glob:` / `regex:`（文件名）与 `curseFile:` /
  `cfFile:`（文件 ID）都会被 warn 并忽略 —— 前者改用 `install.ignoreFiles`，后者改用 `ignoreFiles`
  的文件名规则或 CurseForge 项目 ID。
- Modrinth **slug 不被解析**（如 `sodium`）：写了只按字面量比对、永远不会命中。项目 ID 可以从整合包的
  `cdn.modrinth.com/data/<projectId>/...` 链接里直接复制。

#### ignoreFiles 写法（文件名唯一入口）

| 写法 | 生效阶段 | 说明 |
|---|---|---|
| `mods/iris*.jar` | 下载前跳过 + 解压过滤 | 无前缀默认按 glob 匹配 |
| `mods/glob:optifine*.jar` | 下载前跳过 + 解压过滤 | 显式指定 glob |
| `mods/regex:.*-client\.jar` | 下载前跳过 + 解压过滤 | 显式指定正则 |
| `kubejs/client_scripts/**` | 仅解压过滤 | 非 `mods/` 前缀项不参与下载阶段（它们是 overrides 里的路径） |

下载阶段（`mods/` 前缀项）匹配的是该文件可能的**全部名字**：每个下载链接的文件名、它们的百分号
解码形式（`%2b` ↔ `+`）以及 manifest `path` 的名字。所以 `mods/CTM-1.21-1.2.1+3.jar` 与
`mods/CTM-1.21-1.2.1%2b3.jar` 都能命中同一个文件。非法表达式只 warn 并忽略该条，不影响安装。

### launch 启动配置

| 字段 | 说明 | 默认值 | 示例 |
|---|---|---|---|
| `spongefix` | 为部分模组应用启动包装，修复 Sponge 兼容性 | `false` | `true` |
| `ramDisk` | 世界文件夹使用 RAMDisk（仅 Linux；**必须先完整运行一次服务器**再开启，否则无法备份/恢复） | `false` | `true` |
| `checkOffline` | 启动前用无关服务器检查网络连通性，断网时给出提示 | `false` | `true` |
| `maxRam` | 服务器最大内存（`-Xmx`） | `""` | `5G` |
| `minRam` | 服务器最小内存（`-Xms`）；留空时自动取 `maxRam` 的一半 | `""` | `2G` |
| `autoRestart` | 服务器崩溃后是否自动重启 | `false` | `true` |
| `crashLimit` | 崩溃计时窗口内允许自动重启的次数上限 | `0` | `10` |
| `crashTimer` | 崩溃计时窗口，语法为 `[数字]h` / `[数字]min` / `[数字]s` | `""` | `60min` |
| `preJavaArgs` | 位于 `java` 命令之前的参数（字符串，如 Linux 的 nice 值） | `~` | `nice -n 5` |
| `startFile` | 启动 jar 文件名，支持 `{{@mcversion@}}` / `{{@loaderversion@}}` 占位符，须与安装器产出的文件名一致 | `""` | `forge-{{@mcversion@}}-{{@loaderversion@}}.jar` |
| `startCommand` | 服务器启动命令（数组，每个参数单独一项），支持 `{{@startFile@}}`、`{{@os@}}` 等占位符。MC < 1.16 用 `-jar` 启动；MC ≥ 1.17 用 `@libraries/…/{{@os@}}_args.txt` | `[]` | 见示例文件 |
| `forcedJavaPath` | 强制指定 Java 可执行文件绝对路径；支持 `${ENV_VAR}` 环境变量替换；为空使用 PATH 中的 `java` | `~` | `"C:/Program Files/Java/jdk-17/bin/java.exe"` |
| `supportedJavaVersions` | 允许的 Java 版本列表，启用后自动在 PATH 中查找匹配的 JVM（找不到时回退 `java`） | `[]` | `[17, 21]` |
| `javaArgs` | 附加 JVM 参数列表（如 Aikar's Flags） | `[]` | `- '-XX:+UseG1GC'` |

## 常见问题 (FAQ)
### 开服卡住？
若控制台字体为蓝色，则表示需要用户确认，请根据提示操作。现在有两种操作需要用户手动确认： 
1. EULA 未接受：在启动时，程序会自动询问是否接受 EULA。若未接受，请输入 `TRUE` 并回车，程序会自动继续。
2. 在检测客户端模组阶段，若从平台获取的环境校验与本地模组中的文件得到的结果不一致，则会询问用户是否保留该模组，`Y`=删除，`N`=保留。

### 如何强制重装？

删除 `serverstarter.lock` 后重新运行启动脚本即可触发完整重装。也可以修改配置中的 `install.loaderVersion` 或 `install.modpackUrl`——锁定文件检测到配置变化后会自动重新安装。注意 `serverstarter.lock` 是自动生成文件，请勿手动编辑。

### EULA 未接受 / 无法交互式确认怎么办？

首次启动时若 `eula.txt` 中未包含 `eula=true`，程序会交互式询问。在无人值守或无法交互的环境中，可提前在服务器目录创建 `eula.txt` 并写入 `eula=true`（请先阅读并同意 [Mojang EULA](https://account.mojang.com/documents/minecraft_eula)）。

### Java 版本不匹配？

MC ≤ 1.16 需要 Java 8，MC ≥ 1.17 需要 Java 17 / 21。可在 `launch.supportedJavaVersions` 中列出允许的版本，程序会从 PATH 中自动查找合适的 JVM；也可用 `launch.forcedJavaPath` 直接指定路径。找不到匹配 JVM 时会回退到 PATH 中的 `java`（可能启动失败）。

### 客户端专用模组被装进服务器 / 被误删？

程序会依据 CurseForge 的客户端/服务端标记、Modrinth 平台环境信息与 TOML 规则过滤客户端专用模组；信息不确定时采用 fail-safe 策略（保留模组）。如需精确控制：
- 用 `install.formatSpecific.ignoreProject` 按平台身份忽略整个项目：curse 包型写 CurseForge 项目 ID，
  modrinth 包型可写 Modrinth 项目 ID/slug 或 CurseForge 项目 ID/文件 ID（见「ignoreProject 写法」）；
- 用 `install.ignoreFiles` 按文件名排除/保留具体文件（见「ignoreFiles 写法」）；
- 若某个模组被误判，欢迎[提交 issue](https://github.com/RedTeaco/ServerStarter/issues) 反馈。

## 构建与贡献

本地构建需要 JDK 8+ 与 Gradle（推荐使用仓库内的 Gradle Wrapper）。

| 命令 | 产物 |
|---|---|
| `./gradlew build` | 主程序 jar（`build/libs/serverstarter-<版本>.jar`） |
| `./gradlew packageDist` | 分发目录（含启动脚本与示例配置，自动替换版本号） |
| `./gradlew zipDist` | 发布压缩包（`build/release/serverstarter-<版本>.zip`） |

贡献流程：Fork 本仓库 → 创建特性分支 → 修改并补充测试（`src/test`）→ 提交 Pull Request。问题与建议请直接[提交 issue](https://github.com/RedTeaco/ServerStarter/issues)。

## 许可证与致谢

本项目基于 **MIT License** 开源，详见 [LICENSE](https://github.com/RedTeaco/ServerStarter/blob/master/LICENSE)。

原版 Server Starter 由 [BloodWorkXGaming](https://github.com/BloodWorkXGaming) 开发（含 Yoosk 等人的贡献），本项目在其基础上由 [RedTeaco](https://github.com/RedTeaco) 继续维护，感谢所有贡献者。
