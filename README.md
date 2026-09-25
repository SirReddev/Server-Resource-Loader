<p align="center">
  <img src="https://img.shields.io/badge/MINECRAFT-26.3-1D82B6?style=for-the-badge&labelColor=333333" alt="Minecraft 26.3" />
  <img src="https://img.shields.io/badge/WEBSITE-REDDEV.DEV-2E8B57?style=for-the-badge&labelColor=333333" alt="Website reddev.dev" />
  <img src="https://img.shields.io/badge/LOADER-FABRIC-DDA0DD?style=for-the-badge&labelColor=333333" alt="Fabric Loader" />
  <img src="https://img.shields.io/badge/LICENSE-ALL_RIGHTS_RESERVED-E65100?style=for-the-badge&labelColor=333333" alt="License All Rights Reserved" />
</p>

<p align="center">
  <b>A dedicated server-side Fabric mod for managing, syncing from GitHub, and serving resource packs to players seamlessly.</b>
</p>

---

## <img src="assets/icons/pack.mcmeta_file.svg" width="24" height="24" valign="middle" /> Table of Contents

- [Installation](#installation)
- [Commands](#commands)
- [Configuration](#configuration)
- [GitHub Repository Sync](#github-repository-sync)
- [Custom Messages](#custom-messages)
- [Credits](#credits)
- [License](#license)

---

## <img src="assets/icons/datapacks_folder.svg" width="24" height="24" valign="middle" /> Installation

1. Download `resourceloader-0.8.jar` or compile it with `.\gradlew.bat build`.
2. Place the `.jar` file into your server's `mods/` directory.
3. Put your resource pack `.zip` files into `config/resourceloader/packs/`.
4. Start your server.

> **Note:** ResourceLoader is 100% server-side. Players joining your server do not need to install the mod.

---

## <img src="assets/icons/mcfunction_file.svg" width="24" height="24" valign="middle" /> Commands

| Command | Aliases | Description |
| :--- | :--- | :--- |
| `/load [pack]` | `/resourceload` | Load the server resource pack or a specific pack. |
| `/load <pack> <targets>` | - | Send a resource pack to specific player(s) (Admin). |
| `/unload [pack]` | `/resourceunload` | Unload active server resource packs from your client. |
| `/packlist` | `/listpacks`, `/resourcepacks` | List available packs with clickable load buttons. |
| `/autoload <pack\|clear>` | - | Set or clear your preferred pack to load automatically on join. |
| `/syncgithub [pack]` | `/githubsync`, `/rsync` | Check & download updates from configured GitHub repos (Admin). |
| `/syncdatapack [name]` | `/datapacksync`, `/dsync` | Pull and update datapacks from GitHub repositories into your world (Admin). |
| `/mergepack <output> <p1> <p2>...` | `/merge` | Merge two or more packs into a new pack (Admin). |
| `/checkpack <pack>` | `/validatepack` | Validate a resource pack structure (Admin). |
| `/resourcereload` | `/rreload` | Reload configuration, messages, and packs (Admin). |
| `/clearcache` | `/cacheclear` | Clear downloaded pack and hash caches (Admin). |
| `/resourceversion` | `/rversion` | Show mod version information (Admin). |
| `/resourcehelp` | `/rhelp` | Display command help in chat. |

---

## <img src="assets/icons/config_folder.svg" width="24" height="24" valign="middle" /> Configuration

File location: `config/resourceloader/config.json`

```json
{
  "serverPack": "main_pack.zip",
  "resourcePacks": {
    "pvp": "pvp.zip",
    "remote": "https://example.com/pack.zip"
  },
  "githubPacks": {
    "dev_pack": {
      "repo": "your-username/your-resource-pack",
      "branch": "main",
      "path": "resourcepack",
      "autoUpdate": true,
      "checkIntervalMinutes": 10,
      "token": ""
    }
  },
  "datapacks": {
    "enabled": true,
    "customWorldDirectory": "",
    "autoReloadOnUpdate": true,
    "githubDatapacks": {
      "dev_datapack": {
        "repo": "your-username/your-datapack",
        "branch": "main",
        "path": "datapack",
        "autoUpdate": true,
        "checkIntervalMinutes": 10,
        "token": ""
      }
    }
  },
  "storage": {
    "resourcePackDirectory": "",
    "autoDetection": true
  },
  "server": {
    "port": 40021,
    "address": "",
    "localhost": false,
    "useHttps": false
  },
  "compression": {
    "enabled": true,
    "autoSelect": true,
    "defaultLevel": "medium"
  },
  "enforcement": {
    "enabled": false,
    "required": false,
    "prompt": "Please accept the server resource pack.",
    "kickOnDecline": true,
    "kickOnFail": true
  },
  "messages": {
    "enabled": true,
    "showLoadingMessages": true,
    "showSuccessMessages": true,
    "showErrorMessages": true,
    "prefix": "&7[&eResourceLoader&7] &r"
  }
}
```

### Settings Overview

| Setting | Type | Description |
| :--- | :--- | :--- |
| `serverPack` | String | Default pack file or URL sent to joining players. |
| `resourcePacks` | Map | Named aliases mapped to local `.zip` files or download URLs. |
| `githubPacks` | Map | Direct GitHub repository sources for live resource pack development. |
| `datapacks.enabled` | Boolean | Enable GitHub sync support for server datapacks. |
| `datapacks.customWorldDirectory` | String | Optional path to custom world folder (leave empty for active world). |
| `datapacks.autoReloadOnUpdate` | Boolean | Automatically execute server reload when a datapack updates. |
| `datapacks.githubDatapacks` | Map | Direct GitHub repository sources for live datapack development. |
| `server.port` | Integer | Port for the built-in HTTP server (Default: `40021`). |
| `server.address` | String | Your public server IP or domain (leave empty for auto-detection). |
| `compression.enabled` | Boolean | Enable smart compression to reduce download times. |
| `enforcement.enabled` | Boolean | Require players to load the pack on join. |
| `messages.enabled` | Boolean | Master toggle for all chat messages. |
| `messages.prefix` | String | Custom prefix for chat messages. |

---

## <img src="assets/icons/fabric_file.svg" width="24" height="24" valign="middle" /> GitHub Repository Sync

Develop your texture packs and datapacks directly in GitHub repositories and have the server automatically pull changes.

| Option | Type | Description |
| :--- | :--- | :--- |
| `repo` | String | GitHub repository in `Owner/Repo` format or full HTTPS URL. |
| `branch` | String | Git branch to track (e.g. `main` or `master`). |
| `path` | String | Subfolder containing `pack.mcmeta` and `assets/` or `data/` (leave empty if at repo root). |
| `autoUpdate` | Boolean | Automatically poll GitHub for new commits periodically. |
| `checkIntervalMinutes` | Integer | Interval in minutes between background update checks. |
| `token` | String | Optional Personal Access Token for private repositories or rate limits. |

---

## <img src="assets/icons/chat_type_file.svg" width="24" height="24" valign="middle" /> Custom Messages

You can customize messages directly in `config/resourceloader/messages.json` or by adding `messages.customMessages` in `config.json`. Standard color codes (`&a`, `&c`, `&e`, etc.) are supported.

| Message Key | Default Text |
| :--- | :--- |
| `prefix` | `&7[&eResourceLoader&7] &r` |
| `general.reload-success` | `&aConfiguration and textures reloaded successfully!` |
| `general.invalid-pack` | `&cResource pack '%pack%' not found!` |
| `resource-packs.loading` | `&eLoading resource pack %pack%...` |
| `resource-packs.load-success` | `&aResource pack loaded successfully!` |
| `resource-packs.load-failed` | `&cFailed to load resource pack: %error%` |
| `resource-packs.unloaded` | `&aResource pack has been unloaded.` |
| `autoload.set` | `&aSet &6%pack% &aas your preferred resource pack.` |
| `enforcement.declined` | `&cYou must accept the resource pack to play on this server!` |

---

## <img src="assets/icons/license_file.svg" width="24" height="24" valign="middle" /> Credits

- **Datapack Icons**: Visual icon assets sourced from the [FuncFusion/mc-dp-icons](https://github.com/FuncFusion/mc-dp-icons) suite.

---

## <img src="assets/icons/archive_file.svg" width="24" height="24" valign="middle" /> License

Copyright (c) 2026 RedDev. **All Rights Reserved.**

See the [LICENSE](LICENSE) file for details.
