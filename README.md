# ResourceWorldResetter v3.0.2


## Overview
ResourceWorldResetter automates the resetting of resource worlds on your Minecraft server. It integrates with **Multiverse-Core** to manage world regeneration without requiring restarts and provides an intuitive GUI for configuration.

## Features
- Automated overworld, Nether, and End resource world resets (daily, every 2/3 days, weekly, or monthly)
- **GUI-based** configuration
- **Multiverse-Core** support
- Safe teleportation before resets
- Configurable reset warnings
- Per-resource-world enable toggles and gamerules on create/reset (e.g. `keep_inventory: true`)
- **bStats integration** for analytics
- Supports **Minecraft/Paper 1.21.11+**

## Installation
1. **Download** the latest release from [GitHub Releases](https://github.com/hboj/ResourceWorldResetter/releases).
2. Place `ResourceWorldResetter.jar` into your `plugins/` folder.
3. Ensure **Multiverse-Core** is installed.
4. Restart your server.
5. Use `/rwrgui` to configure settings.

## Commands & Permissions
| Command       | Description                       | Permission                    |
|--------------|---------------------------------|------------------------------|
| `/rwrgui`    | Open the GUI                     | `resourceworldresetter.admin` |
| `/reloadrwr` | Reload plugin configuration      | `resourceworldresetter.admin` |
| `/resetworld`| Manually reset all enabled resource worlds | `resourceworldresetter.admin` |

## Resource Worlds
All enabled resource worlds reset together using the global `resetType`, `resetDay`, `resetIntervalDays`, and `restartTime` settings.

```yaml
resourceWorlds:
  overworld:
    enabled: true
    name: "Resources"
    gameRules:
      keep_inventory: true

  nether:
    enabled: true
    name: "Resources_nether"
    gameRules:
      keep_inventory: true

  end:
    enabled: true
    name: "Resources_the_end"
    gameRules:
      keep_inventory: true
```

## More Information
For full documentation, including **detailed config settings, GUI breakdown, and advanced usage**, visit the **[Wiki](https://github.com/hboj/ResourceWorldResetter-v3.0.0/wiki)**.

---

**Author**: hboj  
GitHub: [hboj](https://github.com/hboj)  

This project is licensed under the [MIT License](LICENSE).
