# ResourceWorldResetter

[![BSD-3-Clause License](https://img.shields.io/badge/license-BSD--3--Clause-blue.svg)](LICENSE)

## Overview

**ResourceWorldResetter** automates the resetting of resource worlds on your Minecraft server.

It integrates with **Multiverse-Core** to manage world regeneration without requiring full server restarts and provides a GUI for easier configuration.

This repository is based on the original **ResourceWorldResetter** project by **Lozaine**.

## Features

- Automated world resets: daily, weekly, or monthly
- GUI-based configuration
- Multiverse-Core support
- Safe teleportation before resets
- Configurable reset warnings
- Configurable world gamerules on create/reset, such as `keep_inventory: true`
- bStats integration for analytics
- Supports Minecraft/Paper 1.21.11+

## Installation

1. Download the latest release from GitHub Releases.
2. Place `ResourceWorldResetter.jar` into your server’s `plugins/` folder.
3. Make sure **Multiverse-Core** is installed.
4. Restart your server.
5. Use `/rwrgui` to configure the plugin.

## Commands & Permissions

| Command | Description | Permission |
|---|---|---|
| `/rwrgui` | Open the GUI | `resourceworldresetter.admin` |
| `/reloadrwr` | Reload plugin configuration | `resourceworldresetter.admin` |
| `/resetworld` | Manually reset the resource world | `resourceworldresetter.admin` |

## More Information

For full documentation, including detailed config settings, GUI breakdown, and advanced usage, visit the Wiki if available.

## Credits

Original project: `TamaWish/ResourceWorldResetter`  
Original author: [Lozaine](https://github.com/Lozaine)

This repository contains modified source code based on the original project.

## License

This project is licensed under the **BSD-3-Clause License**.

Original copyright and license notices from the upstream project must be preserved.
