package sh.pcx.xinventories.internal.import

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import sh.pcx.xinventories.PluginContext
import sh.pcx.xinventories.internal.model.*
import sh.pcx.xinventories.internal.util.Logging
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.configuration.serialization.ConfigurationSerialization
import org.bukkit.inventory.ItemStack
import org.bukkit.potion.PotionEffect
import org.bukkit.util.io.BukkitObjectInputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * Import source for PerWorldInventory (PerWorldInventory-kt 2.x by EbonJaeger, and the Java 1.x
 * plugin by Gnat008 it replaced).
 *
 * Repository: https://github.com/EbonJaeger/perworldinventory-kt (archived; 2.3.2 is the last release).
 * PWI does not run on current servers and has no profile API, so this source always reads PWI's
 * files, which is also what a server that already removed PWI has left:
 *
 * - `plugins/PerWorldInventory/worlds.yml`: `groups.<name>.worlds` (+ `default-gamemode`, `respawnWorld`)
 * - `plugins/PerWorldInventory/data/<uuid>/<group>.json` for survival and
 *   `<group>_creative.json` / `<group>_adventure.json` / `<group>_spectator.json` for the other game
 *   modes (only when `separate-gamemode-inventories` is on; otherwise the survival file is used for all).
 *
 * File formats (FlatFile + PlayerProfile / PlayerSerializer):
 * - data-format 4 (2.2+): ConfigurationSerialization of PlayerProfile: `"=="`, `data-format`,
 *   `inventory.contents[]` / `inventory.armor[]` / `ender-chest[]` as `{index, item}` with the item a
 *   serialized ItemStack map, `stats{...}`, `potion-effects[]` and `balance`.
 * - data-format 1-3 (PWI 1.x / early 2.x): `inventory.inventory[]` / `inventory.armor[]` /
 *   `ender-chest[]`, items as Base64 BukkitObjectOutputStream strings (1, 2) or ItemStack maps (3),
 *   `stats{...}` with `potion-effects[]` inside and `economy.balance`.
 */
class PwiImportSource(private val plugin: PluginContext) : ImportSource {

    override val name: String = "PerWorldInventory"
    override val id: String = "pwi"

    private val configFolder: File = File(plugin.plugin.dataFolder.parentFile, "PerWorldInventory")
    private val dataFolder: File = File(configFolder, "data")
    private val worldsFile: File = File(configFolder, "worlds.yml")
    private val configFile: File = File(configFolder, "config.yml")

    override val isAvailable: Boolean
        get() = dataFolder.exists() && dataFolder.isDirectory

    /** PWI exposes no profile API (and cannot load on current servers): always file-based. */
    override val hasApiAccess: Boolean = false

    override fun getGroups(): List<ImportGroup> {
        if (!worldsFile.exists()) {
            Logging.warning("PWI worlds.yml not found at ${worldsFile.absolutePath}")
            return emptyList()
        }

        val groups = mutableListOf<ImportGroup>()

        try {
            val yaml = YamlConfiguration.loadConfiguration(worldsFile)
            val groupsSection = yaml.getConfigurationSection("groups") ?: return emptyList()

            groupsSection.getKeys(false).forEach { groupName ->
                val groupSection = groupsSection.getConfigurationSection(groupName) ?: return@forEach

                groups.add(ImportGroup(
                    name = groupName,
                    worlds = groupSection.getStringList("worlds").toSet(),
                    isDefault = groupName.equals("default", ignoreCase = true)
                ))
            }
        } catch (e: Exception) {
            Logging.error("Failed to parse PWI worlds.yml", e)
        }

        return groups
    }

    /** Player folders: data/<uuid>/ holding at least one group profile. */
    private fun playerFolders(): List<Pair<UUID, File>> {
        return dataFolder.listFiles { f -> f.isDirectory }?.mapNotNull { dir ->
            val uuid = try {
                UUID.fromString(dir.name)
            } catch (e: IllegalArgumentException) {
                return@mapNotNull null
            }
            if (profileFiles(dir).isEmpty()) null else uuid to dir
        } ?: emptyList()
    }

    private fun profileFiles(dir: File): List<File> =
        dir.listFiles { f -> f.isFile && f.extension == "json" && f.name !in NON_PROFILE_FILES }?.toList() ?: emptyList()

    override fun getPlayers(): List<UUID> = playerFolders().map { it.first }

    private fun separateGameModes(): Boolean =
        !configFile.exists() || YamlConfiguration.loadConfiguration(configFile).getBoolean("separate-gamemode-inventories", true)

    private fun profileFile(uuid: UUID, group: String, gameMode: GameMode): File? {
        val dir = File(dataFolder, uuid.toString())
        if (!dir.isDirectory) return null
        val effectiveMode = if (separateGameModes()) gameMode else GameMode.SURVIVAL
        val fileName = when (effectiveMode) {
            GameMode.SURVIVAL -> "$group.json"
            else -> "${group}_${effectiveMode.name.lowercase()}.json"
        }
        // PWI writes the group name as configured; match case-insensitively for safety.
        return File(dir, fileName).takeIf { it.exists() }
            ?: dir.listFiles()?.firstOrNull { it.name.equals(fileName, ignoreCase = true) }
    }

    override fun getPlayerData(uuid: UUID, group: String, gameMode: GameMode?): ImportedPlayerData? {
        val targetGameMode = gameMode ?: GameMode.SURVIVAL
        val file = profileFile(uuid, group, targetGameMode) ?: return null

        return try {
            val root = JsonParser.parseString(file.readText()).asJsonObject
            parseProfile(root, uuid, group, targetGameMode, file)
        } catch (e: Exception) {
            Logging.error("Failed to parse PWI player file: ${file.absolutePath}", e)
            null
        }
    }

    private fun parseProfile(
        root: JsonObject,
        uuid: UUID,
        group: String,
        gameMode: GameMode,
        file: File
    ): ImportedPlayerData {
        val format = root.get("data-format")?.asInt ?: 3
        val inventory = root.getAsJsonObject("inventory")
        val contentsKey = if (inventory?.has("contents") == true) "contents" else "inventory"

        val mainInventory = mutableMapOf<Int, ItemStack>()
        var offhand: ItemStack? = null
        readItems(inventory?.getAsJsonArray(contentsKey), format).forEach { (index, item) ->
            when (index) {
                in 0..35 -> mainInventory[index] = item
                // player.inventory.contents also holds the armor (36-39, saved separately as well)
                // and the off-hand (40).
                OFFHAND_INDEX -> offhand = item
            }
        }
        val armor = readItems(inventory?.getAsJsonArray("armor"), format).filterKeys { it in 0..3 }
        val enderChest = readItems(root.getAsJsonArray("ender-chest"), format)

        val stats = root.getAsJsonObject("stats") ?: JsonObject()
        fun stat(key: String): JsonPrimitive? = stats.get(key)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive

        // 2.x keeps the effects at the top level, older formats inside "stats".
        val potions = (root.getAsJsonArray("potion-effects") ?: stats.getAsJsonArray("potion-effects"))
            ?.mapNotNull { effect ->
                try {
                    toBukkit(effect) as? PotionEffect
                } catch (e: Exception) {
                    Logging.warning("Skipping an unreadable PWI potion effect: ${e.message}")
                    null
                }
            } ?: emptyList()

        val balance = root.get("balance")?.takeIf { it.isJsonPrimitive }?.asDouble
            ?: root.getAsJsonObject("economy")?.get("balance")?.asDouble

        val playerName = stat("display-name")?.asString?.takeIf { it.isNotBlank() } ?: uuid.toString()

        return ImportedPlayerData(
            uuid = uuid,
            playerName = playerName,
            sourceGroup = group,
            gameMode = gameMode,
            mainInventory = mainInventory,
            armorInventory = armor,
            offhand = offhand,
            enderChest = enderChest,
            health = stat("health")?.asDouble ?: 20.0,
            maxHealth = stat("max-health")?.asDouble ?: 20.0,
            foodLevel = stat("food")?.asInt ?: 20,
            saturation = stat("saturation")?.asFloat ?: 5.0f,
            exhaustion = stat("exhaustion")?.asFloat ?: 0.0f,
            experience = stat("exp")?.asFloat ?: 0.0f,
            level = stat("level")?.asInt ?: 0,
            totalExperience = 0,
            potionEffects = potions,
            balance = balance,
            sourceTimestamp = Instant.ofEpochMilli(file.lastModified()),
            sourceId = id,
            isFlying = stat("flying")?.asBoolean ?: false,
            allowFlight = stat("can-fly")?.asBoolean ?: false,
            displayName = stat("display-name")?.asString,
            fallDistance = stat("fallDistance")?.asFloat ?: 0.0f,
            fireTicks = stat("fireTicks")?.asInt ?: 0,
            maximumAir = stat("maxAir")?.asInt ?: 300,
            remainingAir = stat("remainingAir")?.asInt ?: 300
        )
    }

    /** Reads PWI's `[{index, item}]` arrays into slot -> item, skipping air and unreadable items. */
    private fun readItems(array: JsonArray?, format: Int): Map<Int, ItemStack> {
        val result = mutableMapOf<Int, ItemStack>()
        array?.forEach { element ->
            try {
                val obj = element.asJsonObject
                val index = obj.get("index").asInt
                val item = when (val raw = obj.get("item")) {
                    null -> null
                    is JsonPrimitive -> if (format <= 2) decodeLegacyItem(raw.asString) else null
                    else -> toBukkit(raw) as? ItemStack
                }
                if (item != null && item.type != Material.AIR && item.amount > 0) {
                    result[index] = item
                }
            } catch (e: Exception) {
                Logging.warning("Skipping an unreadable PWI item: ${e.message}")
            }
        }
        return result
    }

    /** Data formats 1 and 2: Base64 of a BukkitObjectOutputStream holding one ItemStack ("AIR" for none). */
    private fun decodeLegacyItem(encoded: String): ItemStack? {
        if (encoded == "AIR") return null
        @Suppress("DEPRECATION")
        return BukkitObjectInputStream(ByteArrayInputStream(Base64.getMimeDecoder().decode(encoded))).use {
            it.readObject() as? ItemStack
        }
    }

    /**
     * Converts PWI's JSON back into what Bukkit serialized: objects carrying `"=="` become their
     * ConfigurationSerializable (ItemStack, ItemMeta, PotionEffect, ...), innermost first, exactly
     * as dumptruckman's SerializationHelper wrote them. Integral numbers stay integers (json-smart
     * USE_INTEGER_STORAGE), which ItemStack/ItemMeta deserialization relies on.
     */
    private fun toBukkit(element: JsonElement): Any? = when {
        element.isJsonNull -> null
        element.isJsonArray -> element.asJsonArray.map { toBukkit(it) }
        element.isJsonObject -> {
            val map = linkedMapOf<String, Any?>()
            element.asJsonObject.entrySet().forEach { (key, value) -> map[key] = toBukkit(value) }
            if (map.containsKey(ConfigurationSerialization.SERIALIZED_TYPE_KEY)) {
                ConfigurationSerialization.deserializeObject(map)
            } else {
                map
            }
        }
        else -> {
            val primitive = element.asJsonPrimitive
            when {
                primitive.isBoolean -> primitive.asBoolean
                primitive.isNumber -> {
                    val text = primitive.asString
                    if (text.contains('.') || text.contains('e') || text.contains('E')) {
                        primitive.asDouble
                    } else {
                        text.toIntOrNull() ?: text.toLongOrNull() ?: primitive.asDouble
                    }
                }
                else -> primitive.asString
            }
        }
    }

    override fun getAllPlayerData(uuid: UUID): Map<String, ImportedPlayerData> {
        val result = mutableMapOf<String, ImportedPlayerData>()

        getGroups().forEach { group ->
            // Try each game mode
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
            issues.add("PWI data folder not found: ${dataFolder.absolutePath}")
            return ImportValidationResult(
                isValid = false,
                issues = issues
            )
        }

        if (!worldsFile.exists()) {
            warnings.add("PWI worlds.yml not found - group detection may be limited")
        }

        val groups = getGroups()
        val players = playerFolders()

        if (groups.isEmpty()) {
            warnings.add("No groups detected in PWI configuration")
        }

        if (players.isEmpty()) {
            warnings.add("No player data files found")
        }

        // Check for corrupted data files
        var corruptedFiles = 0
        players.forEach { (_, dir) ->
            profileFiles(dir).forEach { file ->
                try {
                    JsonParser.parseString(file.readText()).asJsonObject
                } catch (e: Exception) {
                    corruptedFiles++
                }
            }
        }

        if (corruptedFiles > 0) {
            warnings.add("$corruptedFiles corrupted player data files detected")
        }

        return ImportValidationResult(
            isValid = issues.isEmpty(),
            issues = issues,
            warnings = warnings,
            playerCount = players.size,
            groupCount = groups.size
        )
    }

    private companion object {
        /** Other files PWI keeps in a player's data folder. */
        val NON_PROFILE_FILES = setOf("last-logout.json", "last-locations.json")
        const val OFFHAND_INDEX = 40
    }
}
