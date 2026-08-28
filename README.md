# Minecraft Server File Specification

## What is this?
This is the specification for a File that is supposed to be distributed together or seperated from the modpack.
It is supposed to be used by server launchers (_like this one_) to know what it is supposed to do.

## Why?
You might ask, why not just throw the client files next to a forge installer and then call it a day?
You are correct, you can do this if you set it up on your local server, but that is a lot of manual labor.

But it allows for more:
* Reduced size of the server files when download and uploading to a server.
* As it is not launching the server directly but a subprocess it allows for specifying java args easily, 
    which, might not always be possible on some hosting providers.
* This file format is not bound to any program, modpack, or even programming language!  
    A parser could be written for any other utility program to take care of the special problems specified in the file.      
* With the use of wildcard options and regex selectors it could be made to even work across modpack versions.

## Format
See `server-setup-config.yaml` for a example file how this file should be layouted.

## Mirror-based Server Installation (BMCLAPI)

For NeoForge / Forge servers (modern thin installer), the launcher can perform the whole server installation **in-process through a mirror site** (BMCLAPI, default `https://bmclapi2.bangbang93.com`, or any OpenBMCLAPI node via `install.mirrorUrl`) without touching the official sources at all — especially useful on networks where the official endpoints are slow or unreachable.

**Enable it** by setting:

```yaml
install:
  downloadSource: bmclapi   # mojang (default) | bmclapi
  mirrorUrl: ~              # optional: override the BMCLAPI apiRoot, e.g. an OpenBMCLAPI node
```

**How it works** (short version): the installer is downloaded and its `install_profile.json` / `version.json` are parsed into an installation plan; all libraries and the vanilla `server.jar` are then downloaded through the mirror (SHA-1 verified, with retries, per-file mirror→official candidate fallback, and a default concurrency of 4); the startup scripts / args are extracted statically; and the server-side processors (MCP_DATA, merge mappings, jarsplitter, binarypatcher, …) are executed in-process with SHA-1 idempotency on their outputs. The result is a directory layout identical to an official installation (`libraries/` plus `run.sh` / `run.bat` / `user_jvm_args.txt`).

**Reliability:** per-file candidate fallback from the mirror to the official source; on an overall failure the launcher automatically falls back to the original `--installServer` subprocess; downloads are SHA-1 checked, retried, and resumable (an already-present file with a matching SHA-1 is skipped).

**Notes:**
- The `installerUrl` template keeps its current semantics — the URL is automatically injected through the mirror when `downloadSource: bmclapi` is set.
- `installerArguments` is only used by the fallback `--installServer` path.
- Fabric still uses the original flow in this release.

See `docs/TECHNICAL-DESIGN-mirror-install.md` for the detailed design, and `docs/MIRROR-INSTALL.md` for a quick usage guide.