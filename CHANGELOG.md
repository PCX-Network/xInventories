# Changelog

All notable changes to xInventories will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.2.0] - 2026-06-18

### Added
- **Minecraft 26.1.2 support** - Updated for Mojang's new year-based versioning (`26.1.2` = year 2026, drop 1, hotfix 2). The plugin compiles against paper-api 26.1.2 on JDK 25 but emits Java 21 bytecode, so a single jar runs on both Java 21 servers (1.20.5 - 1.21.x) and Java 25 servers (26.1.x).

### Fixed
- **GUI item leak** - Items could occasionally be moved out of an xInventories menu while the button handler never fired. GUI inventories now carry a dedicated `InventoryHolder`, so clicks are cancelled by the inventory's intrinsic identity rather than a side tracking map that could desync (for example after `/xinv reload` while a menu was open).
- **Inventory loss on logout** - The quit save could read the player a tick after they were removed and persist an empty/partial inventory. The snapshot is now captured synchronously when the player quits, then persisted asynchronously.
- **Async save never persisting** - When `async-saving` was enabled but write-behind was disabled, saves were only cached and never written until shutdown. Such saves now persist via a background write.
- **Write-behind data loss on failure** - A failed batch flush no longer marks entries clean; unsaved entries stay dirty and are retried on the next flush.
- **Stuck cross-server lock** - The distributed transfer lock is now always released on quit, even if the save throws.
- **Inventory version drift** - The version is no longer double-incremented on the write-behind path.
- **Deserialization resilience** - A single corrupt or unknown item no longer discards the entire inventory; bad entries are logged and skipped.

### Changed
- Armor handling updated for the Paper 26.1 API (armor getters are now non-null).
- Building/testing from source now requires JDK 25; CI updated accordingly. The released jar remains Java 21 bytecode and runs on Java 21+ servers.

## [1.1.0] - 2026-01-30

### Added

#### Data Foundation
- **Inventory Versioning** - Automatic version history for player inventories
  - Configurable version retention and triggers (save, world-change, gamemode-change)
  - Commands: `/xinv version list`, `/xinv version restore`, `/xinv version diff`
  - Rollback to any previous version with preview
- **Death Recovery** - Recover inventories lost on death
  - Configurable retention period and max records per player
  - Commands: `/xinv death list`, `/xinv death restore`, `/xinv death preview`
  - Automatic cleanup of old records

#### Player State Separation
- **Statistics Separation** - Per-group player statistics (kills, deaths, blocks mined, etc.)
- **Advancements Separation** - Per-group advancement/achievement progress
- **Recipes Separation** - Per-group unlocked recipe tracking
- **PWI-Style Player Settings** - Extended player state options
  - Flying state (`save-flying`, `save-allow-flight`)
  - Fall distance, fire ticks, air levels
  - Display name preservation
  - Inventory toggle (`save-inventory`) to disable inventory sync per-group
  - Global defaults in `config.yml` with per-group overrides
- All existing state (ender chest, XP, potion effects, health/hunger) now fully per-group

#### Content Control
- **Templates** - Pre-defined inventory templates for groups
  - Apply templates on first join or group entry
  - Commands: `/xinv template list`, `/xinv template apply`, `/xinv template create`
  - Template editor GUI for viewing/editing template contents
  - Support for starter kits, event loadouts, etc.
- **Item Restrictions** - Control which items players can use per group
  - Whitelist or blacklist modes with material patterns
  - Actions: prevent, drop, remove, or move to vault
  - Per-group restriction configurations
- **Confiscation Vault** - Storage for restricted items
  - Items removed with MOVE_TO_VAULT action are stored safely
  - Players can view and reclaim confiscated items via GUI
  - Commands: `/xinv vault`, `/xinv vault claim`, `/xinv vault <player>`
  - Admin access to view/manage other players' vaults
- **NBT Filters** - Advanced item filtering by NBT data
  - Filter by enchantments (with level ranges)
  - Filter by custom model data
  - Filter by display name/lore patterns (regex)
- **Shared Slots** - Share specific inventory slots across groups
  - Configure shared slots, armor slots, or offhand
  - Real-time sync across group transitions

#### Advanced Groups
- **Conditional Groups** - Dynamic group assignment based on conditions
  - Permission-based: `permission: "group.vip"`
  - Schedule-based: Time ranges with timezone support
  - Cron-based: Complex schedules like "weekends only"
  - PlaceholderAPI: Evaluate placeholders for group selection
- **World Group Inheritance** - Groups inherit settings from parent groups
  - Reduces configuration duplication
  - Child groups override parent settings
  - Circular inheritance detection
- **Temporary Groups** - Time-limited group assignments
  - Assign players to groups for events/minigames
  - Auto-restore original group on expiration
  - Commands: `/xinv tempgroup assign`, `/xinv tempgroup remove`, `/xinv tempgroup list`
  - Events: `TemporaryGroupExpireEvent`, `TemporaryGroupAssignEvent`
- **Inventory Locking** - Temporarily lock player inventories
  - Commands: `/xinv lock`, `/xinv unlock`, `/xinv lock list`
  - Configurable duration, scope (all/group/slots), and bypass permissions
  - Events: `InventoryLockEvent`, `InventoryUnlockEvent`

#### Admin Tools
- **Audit Logging** - Track all inventory modifications
  - Who modified what, when, and what changed
  - Commands: `/xinv audit <player>`, `/xinv audit search`, `/xinv audit export`
  - Configurable retention and action filtering
- **Bulk Operations** - Apply actions to all players in a group
  - Commands: `/xinv bulk clear`, `/xinv bulk apply-template`, `/xinv bulk reset-stats`
  - Progress tracking and cancellation support
- **Anti-Dupe Detection** - Detect potential item duplication exploits
  - Rapid switch detection, item count anomaly detection
  - Configurable sensitivity (LOW, MEDIUM, HIGH)
  - Admin notifications and optional inventory freeze
- **Statistics Dashboard** - Plugin performance metrics
  - Commands: `/xinv stats`, `/xinv stats cache`, `/xinv stats performance`
  - Storage usage, cache hit rates, operation timing
- **Inventory Comparison** - Side-by-side player inventory comparison GUI
- **Inventory Search** - Search across all player inventories for specific items
- **Inventory Expiration** - Auto-delete inactive player data
  - Configurable inactivity threshold
  - Exclusion by permission or UUID
  - Commands: `/xinv expiration status`, `/xinv expiration preview`, `/xinv expiration run`

#### External Integrations
- **Vault Economy** - Per-group economy balances
  - Separate balances per inventory group
  - Transfer money between groups
  - Full Vault provider compatibility
- **LuckPerms Contexts** - Expose current group as LuckPerms context
  - Context key: `xinventories:group`
  - Enables permission tracks based on inventory group
- **Folia Support** - Multi-threaded server compatibility
  - Region-based scheduling for player operations
  - Runtime detection and automatic adaptation
- **PlaceholderAPI Expansion** - Extended placeholders
  - `%xinventories_group%`, `%xinventories_item_count%`, `%xinventories_empty_slots%`
  - `%xinventories_version_count%`, `%xinventories_death_count%`, `%xinventories_balance%`
  - `%xinventories_locked%`, `%xinventories_lock_reason%`, `%xinventories_last_save%`
- **Plugin Import** - Import data from other inventory plugins
  - PerWorldInventory (PWI) support
  - MultiVerse-Inventories (MVI) support
  - MyWorlds support
  - Commands: `/xinv import detect`, `/xinv import <plugin>`

#### Network Sync
- **Cross-Server Sync** - Redis-based synchronization for networks
  - Real-time inventory sync across BungeeCord/Velocity networks
  - Distributed locking to prevent data conflicts
  - Configurable conflict resolution strategies (latest-wins, merge)
  - Server heartbeat monitoring
  - Cache invalidation across servers

#### Backwards Compatibility
- **Spigot Support** - Runtime dependency loading for Spigot servers
  - Automatically downloads required libraries (Kotlin, coroutines, serialization, etc.) from Maven Central
  - SHA-256 checksum verification for all downloaded dependencies
  - Isolated classloader ensures no conflicts with other plugins
  - Works transparently on Spigot 1.20.5+ and older Paper versions
  - On Paper 1.21+, uses server-provided libraries for optimal performance
  - Dependencies cached in `plugins/xInventories/libraries/` folder

#### Quality of Life
- **Inventory Merge** - Merge two groups' inventories
  - Strategies: COMBINE, REPLACE, KEEP_HIGHER, MANUAL
  - Preview and conflict resolution GUI
  - Commands: `/xinv merge`, `/xinv merge confirm`, `/xinv merge cancel`
- **Export/Import JSON** - Export inventories for external tools
  - Commands: `/xinv export`, `/xinv export all`, `/xinv importjson`
  - Full inventory data including effects, XP, statistics
- **Config Versioning** - Automatic config migration on updates
  - Preserves user settings during upgrades
  - Automatic backup before migration
- **Startup Branding** - ASCII art logo and colorized console output
  - Displays version, storage type, groups loaded, sync status

#### GUI Enhancements (20+ new screens)
- **Core Management**: Player Lookup, Inventory Editor, Version Browser, Death Recovery Panel
- **Administration**: Template Manager, Group Browser, Bulk Operations Panel, Inventory Search
- **Configuration**: Item Restrictions Editor, Shared Slots Editor, Lock Manager, Conditional Groups Editor
- **Monitoring**: Audit Log Viewer, Statistics Dashboard, Economy Overview
- **Utilities**: Backup Manager, Import Wizard, Export Tool, GUI Customization

#### CI/CD & Code Quality
- **Nightly Builds** - Automatic development builds on every push to main
  - Available at [Nightly Release](https://github.com/PCX-SH/xInventories/releases/tag/nightly)
  - Pre-release builds for testing new features
- **GitHub Actions Workflows**
  - Automated testing on PRs and pushes
  - Code coverage reporting with Codecov
  - Static analysis with Detekt
  - Security scanning with CodeQL
  - Multi-version Paper testing (1.20.5 - 1.21.4)
  - Automatic PR labeling and stale issue cleanup
  - API documentation generation with Dokka
- **Dependabot** - Automated dependency updates

### Changed
- Updated test suite to 3801 tests (up from 991)
- Added `version` field to PlayerData for sync conflict detection
- Added `balances` field to PlayerData for per-group economy
- Added `statistics`, `advancements`, `recipes` fields to PlayerData
- Enhanced CronExpression to properly handle day-of-week wildcards
- Changed `mainThreadDispatcher` property type to `CoroutineDispatcher` for better testability
- **Reduced jar size from 6.6MB to 1.7MB** using Paper's library loader
  - Dependencies are now downloaded automatically at server startup
  - Only bStats is bundled in the jar (relocated to avoid conflicts)
- Added cross-version Paper compatibility (1.20.5 - 1.21.11+)
  - Created `AttributeCompat` utility for runtime attribute detection
  - Handles `GENERIC_MAX_HEALTH` -> `MAX_HEALTH` rename in Paper 1.21.2+
  - Created `VersionDetector` for Minecraft version detection at runtime
  - Updated `api-version` to 1.20 for broader compatibility
- Expanded MainMenuGUI to 45 slots with new feature access

### Fixed
- Fixed CronExpression day matching when only day-of-week is specified
- Fixed SQLite batch save missing `version` and `balances` columns
- Fixed EconomyService mock configuration in tests
- Fixed YAML deserialization compatibility with MockBukkit 4.101.0
  - Added recursive `MemorySection` to `Map` conversion in `InventorySerializer`
- Fixed Paper 1.21.11 API compatibility
  - Updated `GENERIC_MAX_HEALTH` to `MAX_HEALTH` attribute references
- Fixed coroutine context handling in integration tests
- Fixed scheduler timing in gamemode change tests

### Dependencies
- Gradle 9.3.0
- Kotlin 2.1.21
- kotlinx-coroutines 1.9.0
- kotlinx-serialization-json 1.8.1
- Paper API 1.21.11-R0.1-SNAPSHOT
- Shadow plugin 9.3.1
- run-paper plugin 3.0.2
- Caffeine 3.2.3
- HikariCP 6.2.1
- Jedis 5.2.0
- JUnit Jupiter 5.13.4
- MockK 1.14.7
- MockBukkit 4.101.0
- SQLite JDBC 3.51.1.0
- bStats 3.1.0
- Embedded Redis 1.4.3

## [1.0.1] - 2026-01-29

### Added
- Comprehensive test suite with 991 passing tests
  - Unit tests for serializers, models, cache, and configuration
  - Integration tests for storage backends, services, and API
- Test framework dependencies (JUnit 5, MockK, MockBukkit, kotlinx-coroutines-test)
- SQLite JDBC driver for test environment
- Testing documentation section in README

### Fixed
- Fixed null pointer exception in `InventoryService` when using null group for global bypass
  - Changed internal set implementation to support null values for global bypass tracking
- Fixed `WorldPattern` equality comparison
  - Two patterns with the same regex string now correctly compare as equal
  - Added proper `equals()` and `hashCode()` implementations

## [1.0.0] - 2026-01-28

### Added
- Initial release
- Per-world inventory management
- Per-gamemode inventory separation
- Multiple storage backends (YAML, SQLite, MySQL)
- Caffeine-powered write-behind caching
- Backup and restore functionality
- Data migration between storage backends
- Admin GUI for management
- Regex pattern matching for worlds
- Full developer API with events
- PlaceholderAPI integration
- Comprehensive command system
- MiniMessage support for all messages
