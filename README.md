# xInventories

<p align="center">
  <img src="https://raw.githubusercontent.com/PCX-SH/xInventories/main/images/BANNER.png" alt="xInventories Banner">
</p>

<p align="center">
  <a href="https://github.com/PCX-SH/xInventories/actions/workflows/build.yml"><img src="https://github.com/PCX-SH/xInventories/actions/workflows/build.yml/badge.svg" alt="Build Status"></a>
  <a href="https://github.com/PCX-SH/xInventories/actions/workflows/test.yml"><img src="https://github.com/PCX-SH/xInventories/actions/workflows/test.yml/badge.svg" alt="Tests"></a>
  <a href="https://codecov.io/gh/PCX-SH/xInventories"><img src="https://codecov.io/gh/PCX-SH/xInventories/branch/main/graph/badge.svg" alt="Code Coverage"></a>
  <a href="https://github.com/PCX-SH/xInventories/releases/latest"><img src="https://img.shields.io/github/v/release/PCX-SH/xInventories?color=blue&label=stable" alt="Stable Release"></a>
  <a href="https://github.com/PCX-SH/xInventories/releases/tag/nightly"><img src="https://img.shields.io/badge/nightly-latest-purple" alt="Nightly Build"></a>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Paper%20%7C%20Spigot-1.20.5--26.1.2+-blue?logo=data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAA4AAAAOCAYAAAAfSC3RAAAACXBIWXMAAA7EAAAOxAGVKw4bAAAAyklEQVQokZVSQQ7DIAzz0v7/y+wyCEkDdNImTQKJ7TgBPgBuOgEHd19FZO8uT3f3BQDS93VEpGaW2s0bSJJU1cy2A9VVtR4FkjQz28ysxgCApO37IEmttWZmNcaAqjaA1FpLjJE5Z+acyzBCRHoIYbTWPMlxHJZzthgjQwiIMUJV0Xvv7u69d4oIAEBEEGMEEUHvHb13xBjh7mBmAAB3BzNDRNBaw5wTRNQCHMfhAJBSwtgRzjlJKUFVQUSIMWL8JL8HqsN/8n3+AWPvTE9qfPlzAAAAAElFTkSuQmCC" alt="Paper | Spigot">
  <img src="https://img.shields.io/badge/Java-21+-orange?logo=openjdk&logoColor=white" alt="Java 21+">
  <a href="https://github.com/PCX-SH/xInventories/blob/main/LICENSE"><img src="https://img.shields.io/github/license/PCX-SH/xInventories?color=green" alt="License"></a>
  <a href="https://github.com/PCX-SH/xInventories/stargazers"><img src="https://img.shields.io/github/stars/PCX-SH/xInventories?style=flat&color=yellow" alt="Stars"></a>
</p>

<p align="center">
  <a href="#installation">Installation</a> •
  <a href="#features">Features</a> •
  <a href="#commands">Commands</a> •
  <a href="#configuration">Configuration</a> •
  <a href="#developer-api">API</a> •
  <a href="https://pcx-sh.github.io/xInventories/">Documentation</a>
</p>

---

A powerful per-world inventory management plugin for Paper and Spigot servers (1.20.5 through 26.1.2). Separate player inventories, experience, health, and more across different worlds or world groups.

## Features

### Core Features
- **Per-World Inventories** - Separate inventories for different worlds or world groups
- **Per-GameMode Inventories** - Optionally separate inventories by gamemode (Survival, Creative, etc.)
- **Multiple Storage Backends** - YAML (default), SQLite, or MySQL
- **High-Performance Caching** - Caffeine-powered write-behind caching for optimal performance
- **Backup & Restore** - Create and restore backups of all player data
- **Data Migration** - Migrate between storage backends seamlessly
- **Admin GUI** - Visual interface for managing groups, worlds, and viewing player data
- **Regex Patterns** - Match worlds using regex patterns (e.g., `dungeon_.*`)
- **Full API** - Comprehensive developer API with events and subscriptions
- **PlaceholderAPI Support** - Built-in placeholders for displaying inventory info

### v1.1.0 Features

#### Data Foundation
- **Inventory Versioning** - Automatic version history with rollback capability
- **Death Recovery** - Recover inventories lost on death
- **Player State Separation** - Per-group statistics, advancements, and recipes
- **PWI-Style Settings** - Flying, fall distance, fire ticks, air, display name per-group

#### Content Control
- **Templates** - Pre-defined inventory templates for groups (starter kits, loadouts)
- **Item Restrictions** - Whitelist/blacklist items per group with confiscation vault
- **Confiscation Vault** - Store and reclaim restricted items via `/xinv vault`
- **NBT Filters** - Advanced filtering by enchantments, custom model data, name/lore patterns
- **Shared Slots** - Share specific slots across all groups

#### Advanced Groups
- **Conditional Groups** - Dynamic groups based on permissions, time, cron schedules, or PlaceholderAPI
- **World Group Inheritance** - Groups inherit settings from parent groups
- **Temporary Groups** - Time-limited group assignments for events/minigames
- **Inventory Locking** - Temporarily lock inventories with bypass permissions

#### Admin Tools
- **Audit Logging** - Track all inventory modifications with full history
- **Bulk Operations** - Apply actions to all players in a group
- **Anti-Dupe Detection** - Detect potential item duplication exploits
- **Inventory Search** - Search across all player inventories
- **Inventory Comparison** - Side-by-side player inventory comparison
- **Statistics Dashboard** - Plugin performance metrics

#### External Integrations
- **Vault Economy** - Per-group economy balances
- **LuckPerms Contexts** - Current group as LuckPerms context
- **Folia Support** - Multi-threaded server compatibility
- **PlaceholderAPI Expansion** - 15+ placeholders for inventory info
- **Plugin Import** - Import from PerWorldInventory, MultiVerse-Inventories, MyWorlds

#### Network Sync
- **Cross-Server Sync** - Redis-based sync for BungeeCord/Velocity networks
- **Distributed Locking** - Prevent data conflicts across servers

#### Quality of Life
- **Inventory Merge** - Merge two groups' inventories with conflict resolution
- **Export/Import JSON** - Export inventories for external tools or backup
- **Config Versioning** - Automatic config migration on updates
- **20+ GUI Screens** - Comprehensive admin interface for all features

## Requirements

- **Paper 1.20.5+** or **Spigot 1.20.5+** (1.20.5 - 26.1.2+ supported)
- **Java 21+** — the plugin jar targets Java 21 and runs on Java 21 servers (1.20.5 - 1.21.x). Minecraft 26.1+ servers require Java 25 per Mojang; the same jar runs there too.

> **Note:** While both Paper and Spigot are supported, Paper is recommended for optimal performance and access to all features.
>
> Mojang switched to year-based versioning at 26.1 (e.g. `26.1.2` = year 2026, drop 1, hotfix 2).

## Downloads

| Release | Description | Link |
|---------|-------------|------|
| **Stable** | Recommended for production servers | [Latest Release](https://github.com/PCX-SH/xInventories/releases/latest) |
| **Nightly** | Latest development build (may be unstable) | [Nightly Build](https://github.com/PCX-SH/xInventories/releases/tag/nightly) |

## Installation

1. Download `xInventories.jar` (~1.7MB) from the links above
2. Place in your server's `plugins/` folder
3. Restart your server
4. Configure `plugins/xInventories/config.yml` as needed
5. Set up world groups in `plugins/xInventories/groups.yml`

> **Note:** On Paper 1.21+, dependencies are provided by the server. On Spigot or older Paper versions, xInventories automatically downloads required libraries from Maven Central on first startup (with SHA-256 verification). This keeps the jar small while ensuring broad compatibility.

## Commands

All commands use `/xinventories` (aliases: `/xinv`, `/xi`)

| Command | Description | Permission |
|---------|-------------|------------|
| `/xinv` | Show help menu | `xinventories.command` |
| `/xinv reload` | Reload configuration | `xinventories.command.reload` |
| `/xinv save [player]` | Save player inventory | `xinventories.command.save` |
| `/xinv load <player> [group]` | Load player inventory | `xinventories.command.load` |
| `/xinv gui` | Open admin GUI | `xinventories.gui` |
| `/xinv group <list\|info\|create\|delete\|setdefault> [name]` | Manage groups | `xinventories.command.group` |
| `/xinv world <assign\|unassign\|list> [world] [group]` | Manage world assignments | `xinventories.command.world` |
| `/xinv pattern <add\|remove\|list> <group> [pattern]` | Manage regex patterns | `xinventories.command.pattern` |
| `/xinv cache <stats\|clear> [player]` | Manage cache | `xinventories.command.cache` |
| `/xinv backup <create\|restore\|list\|delete> [name/id]` | Manage backups | `xinventories.command.backup` |
| `/xinv convert <from> <to>` | Migrate storage (yaml/sqlite/mysql) | `xinventories.command.convert` |
| `/xinv debug [info\|player\|storage] [player]` | Debug information | `xinventories.command.debug` |
| `/xinv version <list\|restore\|diff> [player]` | Inventory version history | `xinventories.command.version` |
| `/xinv death <list\|restore\|preview> [player]` | Death recovery | `xinventories.command.death` |
| `/xinv template <list\|apply\|create> [name]` | Inventory templates | `xinventories.command.template` |
| `/xinv restrict <list\|add\|remove> [group]` | Item restrictions | `xinventories.command.restrict` |
| `/xinv vault [player\|claim]` | View/claim confiscated items | `xinventories.command.vault` |
| `/xinv conditions <group>` | View group conditions | `xinventories.command.conditions` |
| `/xinv whoami` | Show current group and match reason | `xinventories.command` |
| `/xinv lock <player> [reason] [duration]` | Lock player inventory | `xinventories.command.lock` |
| `/xinv unlock <player>` | Unlock player inventory | `xinventories.command.lock` |
| `/xinv import <detect\|plugin> [args]` | Import from other plugins | `xinventories.command.import` |
| `/xinv sync <status\|force> [player]` | Network sync status | `xinventories.command.sync` |
| `/xinv bulk <clear\|apply-template\|reset-stats> <group>` | Bulk operations | `xinventories.command.bulk` |
| `/xinv audit <player> [--limit]` | View audit log | `xinventories.command.audit` |
| `/xinv stats [cache\|storage\|performance]` | Plugin statistics | `xinventories.command.stats` |
| `/xinv merge <player> <source> <target> [strategy]` | Merge inventories | `xinventories.command.merge` |
| `/xinv export <player> [group] [file]` | Export to JSON | `xinventories.command.export` |
| `/xinv importjson <player> <file> [group]` | Import from JSON | `xinventories.command.import` |
| `/xinv expiration <status\|preview\|run>` | Data expiration | `xinventories.command.expiration` |
| `/xinv tempgroup <assign\|remove\|list> [player] [group] [duration]` | Temporary groups | `xinventories.command.tempgroup` |

## Permissions

### Admin Permission
| Permission | Description | Default |
|------------|-------------|---------|
| `xinventories.admin` | Full admin access (includes all below) | op |

### Command Permissions
| Permission | Description | Default |
|------------|-------------|---------|
| `xinventories.command` | Use base command | true |
| `xinventories.command.reload` | Reload configuration | op |
| `xinventories.command.save` | Save inventories | op |
| `xinventories.command.save.others` | Save other players | op |
| `xinventories.command.load` | Load inventories | op |
| `xinventories.command.group` | Manage groups | op |
| `xinventories.command.world` | Manage worlds | op |
| `xinventories.command.pattern` | Manage patterns | op |
| `xinventories.command.cache` | Manage cache | op |
| `xinventories.command.backup` | Manage backups | op |
| `xinventories.command.convert` | Migrate storage | op |
| `xinventories.command.debug` | Debug commands | op |
| `xinventories.gui` | Open admin GUI | op |
| `xinventories.command.version` | Version history | op |
| `xinventories.command.death` | Death recovery | op |
| `xinventories.command.template` | Manage templates | op |
| `xinventories.command.restrict` | Manage restrictions | op |
| `xinventories.command.conditions` | View conditions | op |
| `xinventories.command.lock` | Lock/unlock inventories | op |
| `xinventories.command.import` | Import from plugins | op |
| `xinventories.command.sync` | Network sync | op |

### Bypass Permissions
| Permission | Description | Default |
|------------|-------------|---------|
| `xinventories.bypass` | Bypass all inventory switching | false |
| `xinventories.bypass.<group>` | Bypass switching for specific group | false |
| `xinventories.lock.bypass` | Bypass inventory locks | op |
| `xinventories.restrict.bypass` | Bypass item restrictions | op |

## Configuration

### config.yml

```yaml
# Storage settings
storage:
  # Storage type: YAML, SQLITE, or MYSQL
  type: YAML

  # SQLite settings (if type is SQLITE)
  sqlite:
    file: "data/xinventories.db"

  # MySQL settings (if type is MYSQL)
  mysql:
    host: "localhost"
    port: 3306
    database: "xinventories"
    username: "root"
    password: ""
    pool:
      maximumPoolSize: 10
      minimumIdle: 2
      connectionTimeout: 30000
      idleTimeout: 600000
      maxLifetime: 1800000

# Cache settings
cache:
  enabled: true
  maxSize: 1000
  expireAfterAccessMinutes: 30
  expireAfterWriteMinutes: 60
  writeBehindSeconds: 30

# Feature toggles
features:
  asyncSaving: true
  asyncLoading: true
  logInventorySwitches: false
  adminNotifications: true

# Performance settings
performance:
  threadPoolSize: 4

# Backup settings
backup:
  directory: "backups"
  maxBackups: 10
  autoBackup: false
  autoBackupIntervalHours: 24
```

### groups.yml

```yaml
# Default group - catches all worlds not assigned elsewhere
default:
  default: true
  priority: 0
  worlds: []
  patterns: []
  settings:
    saveHealth: true
    saveHunger: true
    saveSaturation: true
    saveExhaustion: true
    saveExperience: true
    savePotionEffects: true
    saveEnderChest: true
    saveGameMode: false
    separateGameModeInventories: true
    clearOnDeath: false
    clearOnJoin: false

# Example: Survival worlds group
survival:
  priority: 10
  worlds:
    - "world"
    - "world_nether"
    - "world_the_end"
  patterns: []
  settings:
    separateGameModeInventories: true
    saveEnderChest: true

# Example: Creative worlds group
creative:
  priority: 10
  worlds:
    - "creative"
    - "plots"
  patterns:
    - "creative_.*"
  settings:
    separateGameModeInventories: false
    saveEnderChest: false

# Example: Minigames using regex pattern
minigames:
  priority: 20
  worlds: []
  patterns:
    - "game_.*"
    - "arena_.*"
  settings:
    saveHealth: false
    saveHunger: false
    saveExperience: false
    savePotionEffects: false
    clearOnJoin: true
```

### Group Settings

| Setting | Description | Default |
|---------|-------------|---------|
| `saveHealth` | Save/restore player health | `true` |
| `saveHunger` | Save/restore food level | `true` |
| `saveSaturation` | Save/restore saturation | `true` |
| `saveExhaustion` | Save/restore exhaustion | `true` |
| `saveExperience` | Save/restore XP and levels | `true` |
| `savePotionEffects` | Save/restore potion effects | `true` |
| `saveEnderChest` | Save/restore ender chest | `true` |
| `saveGameMode` | Restore gamemode on switch | `false` |
| `separateGameModeInventories` | Separate inventories per gamemode | `true` |
| `clearOnDeath` | Clear inventory on death | `false` |
| `clearOnJoin` | Clear inventory when entering group | `false` |

### messages.yml

Customize all plugin messages using MiniMessage format for colors and formatting.

```yaml
prefix: "<gray>[<aqua>xInventories</aqua>]</gray> "

messages:
  no-permission: "<red>You don't have permission to do that."
  player-not-found: "<red>Player <yellow>{player}</yellow> not found."
  reload-success: "<green>Configuration reloaded successfully."
  # ... more messages
```

## Storage Backends

### YAML (Default)
- Stores data in `plugins/xInventories/data/players/`
- One file per player UUID
- Human-readable, easy to edit manually
- Best for small to medium servers

### SQLite
- Single database file
- No external dependencies
- Better performance than YAML for larger datasets
- Good for medium servers

### MySQL
- External database server
- Best performance at scale
- Required for multi-server setups (BungeeCord/Velocity networks)
- Includes HikariCP connection pooling

### Migrating Storage

```
/xinv convert yaml sqlite    # YAML to SQLite
/xinv convert sqlite mysql   # SQLite to MySQL
/xinv convert mysql yaml     # MySQL to YAML
```

## PlaceholderAPI Placeholders

| Placeholder | Description |
|-------------|-------------|
| `%xinventories_group%` | Player's current inventory group |
| `%xinventories_group_display%` | Current group display name |
| `%xinventories_bypass%` | Whether player has bypass enabled |
| `%xinventories_groups_count%` | Total number of configured groups |
| `%xinventories_item_count%` | Total items in inventory |
| `%xinventories_empty_slots%` | Empty inventory slots |
| `%xinventories_armor_count%` | Equipped armor pieces |
| `%xinventories_version_count%` | Number of saved versions |
| `%xinventories_death_count%` | Number of death records |
| `%xinventories_balance%` | Per-group economy balance |
| `%xinventories_last_save%` | Last save timestamp |
| `%xinventories_locked%` | Whether inventory is locked |
| `%xinventories_lock_reason%` | Lock reason if locked |
| `%xinventories_storage_type%` | Current storage backend type |
| `%xinventories_cache_size%` | Current cache entry count |
| `%xinventories_cache_max%` | Maximum cache size |
| `%xinventories_cache_hit_rate%` | Cache hit rate percentage |
| `%xinventories_version%` | Plugin version |

## Developer API

### Getting the API

```kotlin
// Kotlin
val api = XInventoriesProvider.get()

// Java
XInventoriesAPI api = XInventoriesProvider.get();
```

### Example: Get Player's Current Group

```kotlin
val group = api.getGroupForWorld(player.world)
println("Player is in group: ${group.name}")
```

### Example: Save/Load Inventory

```kotlin
// Save current inventory
api.savePlayerData(player).thenAccept { result ->
    result.onSuccess { println("Saved!") }
    result.onFailure { println("Failed: ${it.message}") }
}

// Load from specific group
api.loadPlayerData(player, "creative").thenAccept { result ->
    result.onSuccess { println("Loaded!") }
}
```

### Example: Subscribe to Events

```kotlin
val subscription = api.onInventorySwitch { context ->
    println("${context.player.name} switched from ${context.fromGroup} to ${context.toGroup}")
}

// Later, unsubscribe
subscription.unsubscribe()
```

### Dependency

```kotlin
// build.gradle.kts
compileOnly(files("libs/xInventories-1.1.0.jar"))
```

```xml
<!-- pom.xml -->
<dependency>
    <groupId>sh.pcx</groupId>
    <artifactId>xInventories</artifactId>
    <version>1.1.0</version>
    <scope>provided</scope>
</dependency>
```

Make sure to add `xInventories` as a dependency or soft-dependency in your `plugin.yml`:

```yaml
depend: [xInventories]
# or
softdepend: [xInventories]
```

## Screenshots

<details>
<summary>Click to view screenshots</summary>

### Main Admin GUI
![Main GUI](https://raw.githubusercontent.com/PCX-SH/xInventories/main/images/GUI_MAIN.png)

### Group Management
![Groups GUI](https://raw.githubusercontent.com/PCX-SH/xInventories/main/images/GUI_GROUPS.png)

### Player Inventory Viewer
![Player GUI](https://raw.githubusercontent.com/PCX-SH/xInventories/main/images/GUI_PLAYER.png)

</details>

## Testing

xInventories includes a comprehensive test suite to ensure reliability and prevent regressions.

### Test Coverage

| Category | Tests | Description |
|----------|-------|-------------|
| **Unit Tests** | 2800+ | Serializers, models, cache, configuration, GUI logic, services, commands, hooks |
| **Integration Tests** | 950+ | Storage backends, services, API, economy, templates |
| **Total** | **3880 passing** | 40 skipped (MockBukkit limitations) |

### Running Tests

> **Note:** Building/testing from source requires **JDK 25** (paper-api 26.1.2 is compiled against Java 25). The produced jar is Java 21 bytecode and still runs on Java 21 servers.

```bash
# Run all tests
./gradlew test

# Run with detailed output
./gradlew test --info

# View HTML report
# build/reports/tests/test/index.html
```

### Test Framework

- **JUnit 5** - Test framework
- **MockK** - Kotlin mocking library
- **MockBukkit** - Bukkit API mocking for Paper
- **kotlinx-coroutines-test** - Coroutine testing utilities

### Test Categories

- **Serializers** - ItemStack, PlayerData, PotionEffect serialization/deserialization
- **Models** - Group, WorldPattern, PlayerData data classes
- **Cache** - Caffeine cache operations, dirty tracking, eviction
- **Configuration** - Config loading, validation, defaults
- **Storage** - YAML, SQLite storage operations and roundtrips
- **Services** - GroupService, StorageService, InventoryService, BackupService, MigrationService
- **API** - Public API methods, event handling, subscriptions

## FAQ

**Q: Does this work with Spigot?**
A: Yes! As of v1.1.0, xInventories supports both Paper and Spigot 1.20.5+. On Spigot or older Paper versions, the plugin automatically downloads required libraries (Kotlin, coroutines, etc.) from Maven Central on first startup. Paper 1.21+ is still recommended for best performance as it provides these libraries natively.

**Q: How do I share inventories between servers?**
A: For BungeeCord/Velocity networks, enable Redis sync in config.yml. This provides real-time synchronization with distributed locking to prevent conflicts. Alternatively, use MySQL storage with all servers pointing to the same database.

**Q: Can I have different inventories for Creative and Survival in the same world?**
A: Yes! Enable `separateGameModeInventories: true` in the group settings.

**Q: How do I exclude certain players from inventory switching?**
A: Give them the `xinventories.bypass` permission, or `xinventories.bypass.<groupname>` for specific groups.

**Q: My players lost their inventory!**
A: Check `/xinv debug player <name>` for their data. You can also restore from backup with `/xinv backup restore <id>`.

**Q: How do I match multiple worlds with similar names?**
A: Use regex patterns in groups.yml, e.g., `patterns: ["skyblock_.*"]` matches skyblock_1, skyblock_2, etc.

**Q: Can I edit player inventories through the GUI?**
A: Yes! Use `/xinv gui`, navigate to Player Management, select a player, and you can view and manage their inventories for each group.

**Q: How do I recover a player's inventory after death?**
A: Use `/xinv death list <player>` to see recent deaths, then `/xinv death restore <player> <id>` to restore.

**Q: Can I have different balances per inventory group?**
A: Yes! Enable Vault economy integration in config.yml with `economy.separateByGroup: true`.

**Q: How do I import data from PerWorldInventory?**
A: Run `/xinv import detect` to check for compatible plugins, then `/xinv import pwi` to start the import.

**Q: How do I set up conditional groups?**
A: In groups.yml, add a `conditions` section with permission, schedule, cron, or placeholder requirements.

## Support

- **Issues:** [GitHub Issues](https://github.com/PCX-SH/xInventories/issues)
- **Source:** [GitHub](https://github.com/PCX-SH/xInventories)

## License

MIT License - See [LICENSE](LICENSE) file for details.

## Author

**Reset65**
