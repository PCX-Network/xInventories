package sh.pcx.xinventories.internal.import

import sh.pcx.xinventories.PluginContext
import sh.pcx.xinventories.internal.model.*
import sh.pcx.xinventories.internal.util.Logging
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.OfflinePlayer
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import org.bukkit.potion.PotionEffect
import java.io.File
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * Import source for Multiverse-Inventories (MVI).
 *
 * Repository: https://github.com/Multiverse/Multiverse-Inventories
 *
 * On-disk layout (MVI 4.x and 5.x, both written through dumptruckman's JsonConfiguration):
 * - `groups.yml`: `groups.<name>.worlds` / `groups.<name>.shares`
 * - `groups/<group>/<player name>.json` and `worlds/<world>/<player name>.json`: one top-level section
 *   per profile type (`SURVIVAL`, `CREATIVE`, ...) with `inventoryContents`, `armorContents`,
 *   `enderChestContents` (slot -> item), `offHandItem`, `potions` and `stats` (short keys, string values).
 *   Items are ConfigurationSerializable maps (`"==": "org.bukkit.inventory.ItemStack"`) or, with
 *   `use-byte-serialization-for-inventory-data`, Base64 of Paper's `serializeAsBytes`.
 * - `playernames.json` (5.x, uuid -> name) and `players/<uuid>.json` (`playerData.lastKnownName`)
 *   map the name-keyed profile files back to UUIDs.
 *
 * When MVI 5.x is enabled its API (`org.mvplugins.multiverse.inventories.MultiverseInventoriesApi`)
 * is used for groups and profiles; otherwise (MVI removed, or 4.x, which has no API class) the files
 * are read directly.
 */
class MviImportSource(private val plugin: PluginContext) : ImportSource {

    override val name: String = "Multiverse-Inventories"
    override val id: String = "mvi"

    private val dataFolder: File = File(plugin.plugin.dataFolder.parentFile, "Multiverse-Inventories")
    private val groupsFile: File = File(dataFolder, "groups.yml")
    private val worldsFolder: File = File(dataFolder, "worlds")
    private val groupDataFolder: File = File(dataFolder, "groups")
    private val globalFolder: File = File(dataFolder, "players")
    private val playerNamesFile: File = File(dataFolder, "playernames.json")
    private val configFile: File = File(dataFolder, "config.yml")

    override val isAvailable: Boolean
        get() = dataFolder.exists() && dataFolder.isDirectory

    override val hasApiAccess: Boolean
        get() = api() != null

    // ---------------------------------------------------------------- API (MVI 5.x)

    /** Reflective handle on MVI 5.x's API, resolved through MVI's own class loader. */
    private class Api(val loader: ClassLoader, val instance: Any) {
        fun cls(name: String): Class<*> = Class.forName("$PKG.$name", true, loader)

        companion object {
            const val PKG = "org.mvplugins.multiverse.inventories"
        }
    }

    private fun mviPlugin(): Plugin? =
        Bukkit.getPluginManager().getPlugin("Multiverse-Inventories")?.takeIf { it.isEnabled }

    private fun api(): Api? {
        val mvi = mviPlugin() ?: return null
        return try {
            val loader = mvi.javaClass.classLoader
            val apiClass = Class.forName("${Api.PKG}.MultiverseInventoriesApi", true, loader)
            Api(loader, apiClass.getMethod("get").invoke(null))
        } catch (e: Exception) {
            // MVI 4.x (com.onarandombox...) has no API class: fall back to its files.
            Logging.debug { "Multiverse-Inventories API unavailable: ${e.javaClass.simpleName}: ${e.message}" }
            null
        }
    }

    private fun worldGroupManager(api: Api): Any =
        api.cls("MultiverseInventoriesApi").getMethod("getWorldGroupManager").invoke(api.instance)

    override fun getGroups(): List<ImportGroup> {
        api()?.let { api ->
            try {
                return getGroupsFromApi(api)
            } catch (e: Exception) {
                Logging.warning("Failed to get groups from Multiverse-Inventories API, falling back to files: ${e.message}")
            }
        }
        return getGroupsFromFiles()
    }

    private fun getGroupsFromApi(api: Api): List<ImportGroup> {
        val manager = worldGroupManager(api)
        val groupClass = api.cls("profile.group.WorldGroup")
        val groups = api.cls("profile.group.WorldGroupManager").getMethod("getGroups").invoke(manager) as Collection<*>
        return groups.filterNotNull().map { group ->
            @Suppress("UNCHECKED_CAST")
            val worlds = groupClass.getMethod("getConfigWorlds").invoke(group) as Collection<String>
            ImportGroup(
                name = groupClass.getMethod("getName").invoke(group) as String,
                worlds = worlds.toSet(),
                isDefault = false
            )
        }
    }

    private fun getGroupsFromFiles(): List<ImportGroup> {
        if (!groupsFile.exists()) {
            Logging.warning("MVI groups.yml not found at ${groupsFile.absolutePath}")
            return emptyList()
        }

        return try {
            val yaml = YamlConfiguration.loadConfiguration(groupsFile)
            // Every MVI version since 2.x keeps the groups under a top-level "groups" section.
            val root = yaml.getConfigurationSection("groups") ?: return emptyList()
            root.getKeys(false).mapNotNull { groupName ->
                val section = root.getConfigurationSection(groupName) ?: return@mapNotNull null
                ImportGroup(
                    name = groupName,
                    worlds = section.getStringList("worlds").toSet(),
                    isDefault = false
                )
            }
        } catch (e: Exception) {
            Logging.error("Failed to parse MVI groups.yml", e)
            emptyList()
        }
    }

    // ---------------------------------------------------------------- players

    /** Read once per import run (every profile lookup needs it), not once per lookup. */
    @Volatile
    private var namesCache: Pair<Long, Map<UUID, String>>? = null

    /** uuid -> last known name, from playernames.json (5.x) and players/<uuid>.json (4.x and 5.x). */
    private fun knownPlayerNames(): Map<UUID, String> {
        val now = System.currentTimeMillis()
        namesCache?.let { (readAt, names) -> if (now - readAt < NAMES_CACHE_MS) return names }
        return readPlayerNames().also { namesCache = now to it }
    }

    private fun readPlayerNames(): Map<UUID, String> {
        val names = mutableMapOf<UUID, String>()

        globalFolder.listFiles { f -> f.isFile && f.extension == "json" }?.forEach { file ->
            val uuid = parseUuid(file.nameWithoutExtension) ?: return@forEach
            val name = readProfileFile(file)?.getString("playerData.lastKnownName")
            if (!name.isNullOrBlank()) names[uuid] = name
        }

        if (playerNamesFile.exists()) {
            readProfileFile(playerNamesFile)?.let { map ->
                map.getKeys(false).forEach { key ->
                    val uuid = parseUuid(key) ?: return@forEach
                    map.getString(key)?.takeIf { it.isNotBlank() }?.let { names[uuid] = it }
                }
            }
        }

        return names
    }

    /** Profile files are named after the player (or, for some old data, the UUID). */
    private fun resolvePlayerFile(baseName: String, namesToUuid: Map<String, UUID>): UUID? {
        parseUuid(baseName)?.let { return it }
        namesToUuid[baseName.lowercase()]?.let { return it }
        @Suppress("DEPRECATION")
        return Bukkit.getOfflinePlayerIfCached(baseName)?.uniqueId
    }

    private fun profileFiles(): List<File> {
        return listOf(groupDataFolder, worldsFolder).flatMap { container ->
            container.listFiles { f -> f.isDirectory }?.flatMap { dir ->
                dir.listFiles { f -> f.isFile && (f.extension == "json" || f.extension == "yml") }?.toList() ?: emptyList()
            } ?: emptyList()
        }
    }

    override fun getPlayers(): List<UUID> {
        val namesToUuid = knownPlayerNames().entries.associate { (uuid, name) -> name.lowercase() to uuid }
        val players = linkedSetOf<UUID>()
        profileFiles().forEach { file ->
            val uuid = resolvePlayerFile(file.nameWithoutExtension, namesToUuid)
            if (uuid != null) {
                players.add(uuid)
            } else {
                Logging.warning("MVI profile ${file.path}: no UUID known for player '${file.nameWithoutExtension}', skipped")
            }
        }
        return players.toList()
    }

    private fun playerName(uuid: UUID): String? =
        knownPlayerNames()[uuid] ?: Bukkit.getOfflinePlayer(uuid).name

    // ---------------------------------------------------------------- player data

    override fun getPlayerData(uuid: UUID, group: String, gameMode: GameMode?): ImportedPlayerData? {
        api()?.let { api ->
            try {
                return getPlayerDataFromApi(api, uuid, group, gameMode ?: GameMode.SURVIVAL)
            } catch (e: Exception) {
                Logging.warning("Failed to read MVI profile of $uuid in $group through the API, reading files: ${e.message}")
            }
        }
        return getPlayerDataFromFiles(uuid, group, gameMode ?: GameMode.SURVIVAL)
    }

    private fun getPlayerDataFromApi(api: Api, uuid: UUID, group: String, gameMode: GameMode): ImportedPlayerData? {
        val manager = worldGroupManager(api)
        val worldGroup = api.cls("profile.group.WorldGroupManager")
            .getMethod("getGroup", String::class.java).invoke(manager, group) ?: return null
        val container = api.cls("profile.group.WorldGroup").getMethod("getGroupProfileContainer").invoke(worldGroup)

        val profileTypes = api.cls("profile.key.ProfileTypes")
        val profileType = if (gameModeProfilesEnabledApi(api)) {
            profileTypes.getMethod("forGameMode", GameMode::class.java).invoke(null, gameMode)
        } else {
            profileTypes.getMethod("getDefault").invoke(null)
        }

        val offlinePlayer: OfflinePlayer = Bukkit.getOfflinePlayer(uuid)
        if (offlinePlayer.name == null) {
            // MVI keys profiles by player name; without one there is nothing to look up.
            return getPlayerDataFromFiles(uuid, group, gameMode)
        }

        val profile = api.cls("profile.container.ProfileContainer")
            .getMethod("getPlayerProfileNow", api.cls("profile.key.ProfileType"), OfflinePlayer::class.java)
            .invoke(container, profileType, offlinePlayer) ?: return null

        val profileData = api.cls("profile.data.ProfileData")
        if (profileData.getMethod("isEmpty").invoke(profile) as Boolean) return null

        val sharableClass = api.cls("share.Sharable")
        val sharables = api.cls("share.Sharables")
        val get = profileData.getMethod("get", sharableClass)
        fun value(field: String): Any? = try {
            get.invoke(profile, sharables.getField(field).get(null))
        } catch (e: Exception) {
            null
        }

        return ImportedPlayerData(
            uuid = uuid,
            playerName = offlinePlayer.name ?: uuid.toString(),
            sourceGroup = group,
            gameMode = gameMode,
            mainInventory = slotsOf(value("INVENTORY")),
            armorInventory = slotsOf(value("ARMOR")),
            offhand = (value("OFF_HAND") as? ItemStack)?.takeUnless { it.type.isAir }?.clone(),
            enderChest = slotsOf(value("ENDER_CHEST")),
            health = (value("HEALTH") as? Number)?.toDouble() ?: 20.0,
            maxHealth = (value("MAX_HEALTH") as? Number)?.toDouble() ?: 20.0,
            foodLevel = (value("FOOD_LEVEL") as? Number)?.toInt() ?: 20,
            saturation = (value("SATURATION") as? Number)?.toFloat() ?: 5.0f,
            exhaustion = (value("EXHAUSTION") as? Number)?.toFloat() ?: 0.0f,
            experience = (value("EXPERIENCE") as? Number)?.toFloat() ?: 0.0f,
            level = (value("LEVEL") as? Number)?.toInt() ?: 0,
            totalExperience = (value("TOTAL_EXPERIENCE") as? Number)?.toInt() ?: 0,
            potionEffects = (value("POTIONS") as? Array<*>)?.filterIsInstance<PotionEffect>() ?: emptyList(),
            balance = (value("ECONOMY") as? Number)?.toDouble(),
            sourceTimestamp = null,
            sourceId = id,
            fallDistance = (value("FALL_DISTANCE") as? Number)?.toFloat() ?: 0.0f,
            fireTicks = (value("FIRE_TICKS") as? Number)?.toInt() ?: 0,
            maximumAir = (value("MAXIMUM_AIR") as? Number)?.toInt() ?: 300,
            remainingAir = (value("REMAINING_AIR") as? Number)?.toInt() ?: 300
        )
    }

    private fun gameModeProfilesEnabledApi(api: Api): Boolean = try {
        val config = api.cls("MultiverseInventoriesApi").getMethod("getInventoriesConfig").invoke(api.instance)
        api.cls("config.InventoriesConfig").getMethod("getEnableGamemodeShareHandling").invoke(config) as Boolean
    } catch (e: Exception) {
        false
    }

    /** share-handling.enable-gamemode-share-handling (5.x) / settings.use_game_mode_profiles (4.x). */
    private fun gameModeProfilesEnabledFile(): Boolean {
        if (!configFile.exists()) return false
        val config = YamlConfiguration.loadConfiguration(configFile)
        return config.getBoolean("share-handling.enable-gamemode-share-handling",
            config.getBoolean("settings.use_game_mode_profiles", false))
    }

    private fun getPlayerDataFromFiles(uuid: UUID, group: String, gameMode: GameMode): ImportedPlayerData? {
        val folder = File(groupDataFolder, group)
        val name = playerName(uuid)
        val candidates = listOfNotNull(
            name?.let { File(folder, "$it.json") },
            File(folder, "$uuid.json"),
            name?.let { File(folder, "$it.yml") },
            File(folder, "$uuid.yml")
        )
        val file = candidates.firstOrNull { it.exists() } ?: return null
        return parseProfileFile(file, uuid, name ?: uuid.toString(), group, gameMode)
    }

    private fun parseProfileFile(
        file: File,
        uuid: UUID,
        playerName: String,
        group: String,
        gameMode: GameMode
    ): ImportedPlayerData? {
        val root = readProfileFile(file) ?: return null
        val section = root.getConfigurationSection(gameMode.name)
            ?: if (gameMode != GameMode.SURVIVAL && !gameModeProfilesEnabledFile()) {
                // Without per-gamemode profiles MVI uses the SURVIVAL profile for every game mode.
                root.getConfigurationSection(GameMode.SURVIVAL.name)
            } else {
                null
            }
            ?: return null
        if (section.getKeys(false).isEmpty()) return null

        val stats: ConfigurationSection? = when (val raw = section.get("stats")) {
            is ConfigurationSection -> raw
            is String -> parseText(raw) // MVI 2.x stored the stats as an embedded JSON string
            else -> null
        }
        fun stat(key: String): String? = stats?.get(key)?.toString()

        return ImportedPlayerData(
            uuid = uuid,
            playerName = playerName,
            sourceGroup = group,
            gameMode = gameMode,
            mainInventory = slotsOf(section.getConfigurationSection("inventoryContents")),
            armorInventory = slotsOf(section.getConfigurationSection("armorContents")),
            offhand = itemOf(section.get("offHandItem")),
            enderChest = slotsOf(section.getConfigurationSection("enderChestContents")),
            health = stat("hp")?.toDoubleOrNull() ?: 20.0,
            maxHealth = stat("mhp")?.toDoubleOrNull() ?: 20.0,
            foodLevel = stat("fl")?.toIntOrNull() ?: 20,
            saturation = stat("sa")?.toFloatOrNull() ?: 5.0f,
            exhaustion = stat("ex")?.toFloatOrNull() ?: 0.0f,
            experience = stat("xp")?.toFloatOrNull() ?: 0.0f,
            level = stat("el")?.toIntOrNull() ?: 0,
            totalExperience = stat("txp")?.toIntOrNull() ?: 0,
            potionEffects = section.getList("potions")?.filterIsInstance<PotionEffect>() ?: emptyList(),
            balance = section.get("balance")?.toString()?.toDoubleOrNull(),
            sourceTimestamp = Instant.ofEpochMilli(file.lastModified()),
            sourceId = id,
            fallDistance = stat("fd")?.toFloatOrNull() ?: 0.0f,
            fireTicks = stat("ft")?.toIntOrNull() ?: 0,
            maximumAir = stat("ma")?.toIntOrNull() ?: 300,
            remainingAir = stat("ra")?.toIntOrNull() ?: 300
        )
    }

    /**
     * Reads an MVI JSON (or legacy YAML) file. JSON is valid YAML flow syntax, and Bukkit's YAML
     * loader turns the `"=="` maps into ItemStacks / PotionEffects as it goes.
     */
    private fun readProfileFile(file: File): YamlConfiguration? = try {
        parseText(file.readText())
    } catch (e: Exception) {
        Logging.error("Failed to read Multiverse-Inventories file ${file.absolutePath}", e)
        null
    }

    private fun parseText(text: String): YamlConfiguration =
        YamlConfiguration().apply { loadFromString(text.replace("\\/", "/")) }

    private fun slotsOf(value: Any?): Map<Int, ItemStack> {
        val result = mutableMapOf<Int, ItemStack>()
        when (value) {
            is Array<*> -> value.forEachIndexed { slot, item -> itemOf(item)?.let { result[slot] = it } }
            is ConfigurationSection -> value.getKeys(false).forEach { key ->
                val slot = key.toIntOrNull() ?: return@forEach
                itemOf(value.get(key))?.let { result[slot] = it }
            }
        }
        return result
    }

    private fun itemOf(value: Any?): ItemStack? {
        val item = when (value) {
            is ItemStack -> value
            is String -> try {
                ItemStack.deserializeBytes(Base64.getDecoder().decode(value))
            } catch (e: Exception) {
                Logging.warning("Could not decode a byte-serialized MVI item: ${e.message}")
                null
            }
            else -> null
        }
        return item?.takeUnless { it.type == Material.AIR || it.amount <= 0 }?.clone()
    }

    private fun parseUuid(value: String): UUID? = try {
        UUID.fromString(value)
    } catch (e: IllegalArgumentException) {
        null
    }

    private companion object {
        const val NAMES_CACHE_MS = 30_000L
    }

    override fun getAllPlayerData(uuid: UUID): Map<String, ImportedPlayerData> {
        val result = mutableMapOf<String, ImportedPlayerData>()

        getGroups().forEach { group ->
            GameMode.entries.forEach { gameMode ->
                getPlayerData(uuid, group.name, gameMode)?.let { data ->
                    val key = "${group.name}:${gameMode.name}"
                    result[key] = data
                }
            }
        }

        return result
    }

    override fun validate(): ImportValidationResult {
        val issues = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        if (!dataFolder.exists()) {
            issues.add("MVI data folder not found: ${dataFolder.absolutePath}")
            return ImportValidationResult(
                isValid = false,
                issues = issues
            )
        }

        if (!groupsFile.exists()) {
            warnings.add("MVI groups.yml not found - group detection may be limited")
        }

        val groups = getGroups()
        val players = getPlayers()

        if (groups.isEmpty()) {
            warnings.add("No groups detected in MVI configuration")
        }

        if (players.isEmpty()) {
            warnings.add("No player data files found")
        }

        return ImportValidationResult(
            isValid = issues.isEmpty(),
            issues = issues,
            warnings = warnings,
            playerCount = players.size,
            groupCount = groups.size
        )
    }
}
