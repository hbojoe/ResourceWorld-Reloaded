# ResourceWorldResetter v3.0.2
<p align="center">
  <img src="https://files.catbox.moe/xhfveh.png" alt="project-image">
</p>
## Overview
ResourceWorldResetter automates the resetting of resource worlds on your Minecraft server. It integrates with **Multiverse-Core** to manage world regeneration without requiring restarts and provides an intuitive GUI for configuration.

## Features
- Automated world resets (daily, weekly, or monthly)
- **GUI-based** configuration
- **Multiverse-Core** support
- Safe teleportation before resets
- Configurable reset warnings
- Configurable world gamerules on create/reset (e.g. `keep_inventory: true`)
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
| `/resetworld`| Manually reset the resource world | `resourceworldresetter.admin` |

## More Information
For full documentation, including **detailed config settings, GUI breakdown, and advanced usage**, visit the **[Wiki](https://github.com/hboj/ResourceWorldResetter-v3.0.0/wiki)**.

---

**Author**: hboj  
GitHub: [hboj](https://github.com/hboj)  
Discord: [LozDev Mines](https://discord.gg/Y3UuG7xu9x)  

This project is licensed under the [MIT License](LICENSE).
