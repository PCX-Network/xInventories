package sh.pcx.xinventories.internal.import

import sh.pcx.xinventories.PluginContext
import sh.pcx.xinventories.internal.compat.PotionEffectCompat
import sh.pcx.xinventories.internal.model.*
import sh.pcx.xinventories.internal.util.Logging
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import org.bukkit.potion.PotionEffect
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.time.Instant
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Import source for MyWorlds (plugin name `My_Worlds`, needs BKCommonLib).
 *
 * Repository: https://github.com/bergerhealer/MyWorlds
 *
 * MyWorlds keeps one vanilla-format player data file (`<uuid>.dat`, gzipped NBT) per *world
 * inventory*, in the player data folder of the inventory's shared world:
 * - Paper 26.x world layout: `<main world>/players/data` for the main world,
 *   `<dimension folder>/data/my_worlds/playerdata` for every other world;
 * - older layouts: `<world folder>/playerdata`.
 * Worlds merged into one inventory are listed in `plugins/My_Worlds/inventories.yml`
 * (`<name>: {folder: <shared world>, worlds: [...]}`); every other world has its own inventory.
 * With `useWorldInventories: false` (MyWorlds' default) there is a single inventory, stored in the
 * main world's file.
 *
 * Groups are named after the shared world. Items are converted through Paper's
 * `ItemStack.deserializeBytes`, so the server's data converter upgrades old item NBT.
 */
class MyWorldsImportSource(private val plugin: PluginContext) : ImportSource {

    override val name: String = "MyWorlds"
    override val id: String = "myworlds"

    private val myWorldsFolder: File = File(plugin.plugin.dataFolder.parentFile, "My_Worlds")
    private val worldContainer: File get() = Bukkit.getWorldContainer()

    /**
     * Only MyWorlds' own presence counts. Every vanilla server has player data folders, so finding
     * one says nothing about MyWorlds having been used.
     */
    override val isAvailable: Boolean
        get() = Bukkit.getPluginManager().getPlugin("My_Worlds") != null ||
            (myWorldsFolder.exists() && myWorldsFolder.isDirectory)

    override val hasApiAccess: Boolean
        get() = api() != null

    // ---------------------------------------------------------------- MyWorlds API (reflection)

    private class Api(val loader: ClassLoader) {
        fun cls(name: String): Class<*> = Class.forName("com.bergerkiller.bukkit.mw.$name", true, loader)
    }

    private fun api(): Api? {
        val myWorlds: Plugin = Bukkit.getPluginManager().getPlugin("My_Worlds")?.takeIf { it.isEnabled } ?: return null
        return try {
            Api(myWorlds.javaClass.classLoader).also { it.cls("WorldInventory") }
        } catch (e: Exception) {
            Logging.debug { "MyWorlds API unavailable: ${e.message}" }
            null
        }
    }

    /** A MyWorlds world inventory: the shared world holding its data and the worlds using it. */
    private data class Inventory(val sharedWorld: String, val worlds: Set<String>, val playerFolder: File?)

    private fun inventories(): List<Inventory> {
        api()?.let { api ->
            try {
                return inventoriesFromApi(api)
            } catch (e: Exception) {
                Logging.warning("Failed to read MyWorlds inventories through its API, reading files: ${e.message}")
            }
        }
        return inventoriesFromFiles()
    }

    private fun inventoriesFromApi(api: Api): List<Inventory> {
        val worldConfig = api.cls("WorldConfig")
        val getConfig = worldConfig.getMethod("get", String::class.java)
        val playerFolder = worldConfig.getMethod("getPlayerFolder")
        fun folderOf(world: String): File? = try {
            playerFolder.invoke(getConfig.invoke(null, world)) as? File
        } catch (e: Exception) {
            null
        }

        val useWorldInventories = api.cls("MyWorlds").getField("useWorldInventories").getBoolean(null)
        if (!useWorldInventories) {
            val main = worldConfig.getMethod("getInventoryMain").invoke(null)
            val mainName = worldConfig.getField("worldname").get(main) as String
            return listOf(Inventory(mainName, knownWorlds(), playerFolder.invoke(main) as? File))
        }

        val inventoryClass = api.cls("WorldInventory")
        val all = inventoryClass.getMethod("getAll").invoke(null) as Collection<*>
        return all.filterNotNull().mapNotNull { inv ->
            @Suppress("UNCHECKED_CAST")
            val worlds = (inventoryClass.getMethod("getWorlds").invoke(inv) as Collection<String>).toSet()
            val shared = inventoryClass.getMethod("getSharedWorldName").invoke(inv) as? String
            if (worlds.isEmpty() || shared == null) null else Inventory(shared, worlds, folderOf(shared))
        }.distinctBy { it.sharedWorld.lowercase() }
    }

    private fun inventoriesFromFiles(): List<Inventory> {
        val config = File(myWorldsFolder, "config.yml").takeIf { it.exists() }?.let { YamlConfiguration.loadConfiguration(it) }
        val worlds = knownWorlds()
        val vanillaMain = Bukkit.getWorlds().firstOrNull()?.name ?: "world"

        if (config?.getBoolean("useWorldInventories", false) != true) {
            return listOf(Inventory(vanillaMain, worlds, playerFolderFromFiles(vanillaMain, vanillaMain)))
        }

        val result = mutableListOf<Inventory>()
        val covered = mutableSetOf<String>()
        File(myWorldsFolder, "inventories.yml").takeIf { it.exists() }?.let { file ->
            val yaml = YamlConfiguration.loadConfiguration(file)
            yaml.getKeys(false).forEach { key ->
                val node = yaml.getConfigurationSection(key) ?: return@forEach
                val shared = node.getString("folder") ?: return@forEach
                val members = node.getStringList("worlds").toSet()
                if (members.isEmpty()) return@forEach
                result.add(Inventory(shared, members, playerFolderFromFiles(shared, vanillaMain)))
                covered.addAll(members.map { it.lowercase() })
            }
        }
        // Every world not merged into a shared inventory keeps its own.
        worlds.filter { it.lowercase() !in covered }.forEach { world ->
            result.add(Inventory(world, setOf(world), playerFolderFromFiles(world, vanillaMain)))
        }
        return result
    }

    /** Loaded worlds plus the worlds MyWorlds has configured (plugins/My_Worlds/worlds.yml). */
    private fun knownWorlds(): Set<String> {
        val worlds = linkedSetOf<String>()
        Bukkit.getWorlds().forEach { worlds.add(it.name) }
        File(myWorldsFolder, "worlds.yml").takeIf { it.exists() }?.let { file ->
            worlds.addAll(YamlConfiguration.loadConfiguration(file).getKeys(false))
        }
        return worlds
    }

    private fun playerFolderFromFiles(world: String, vanillaMain: String): File? {
        val candidates = mutableListOf<File>()
        Bukkit.getWorld(world)?.worldFolder?.let { folder ->
            if (world == vanillaMain) candidates.add(File(folder, "players/data"))
            candidates.add(File(folder, "data/my_worlds/playerdata"))
            candidates.add(File(folder, "playerdata"))
        }
        val legacyFolder = File(worldContainer, world)
        if (world == vanillaMain) candidates.add(File(legacyFolder, "players/data"))
        candidates.add(File(legacyFolder, "playerdata"))
        return candidates.firstOrNull { it.isDirectory }
    }

    // ---------------------------------------------------------------- ImportSource

    override fun getGroups(): List<ImportGroup> = inventories().map { inv ->
        ImportGroup(
            name = inv.sharedWorld,
            worlds = inv.worlds,
            isDefault = inv.sharedWorld == Bukkit.getWorlds().firstOrNull()?.name,
            metadata = mapOf("type" to "worldinventory")
        )
    }

    private fun dataFiles(folder: File?): List<Pair<UUID, File>> =
        folder?.listFiles { f -> f.isFile && f.extension == "dat" }?.mapNotNull { file ->
            try {
                UUID.fromString(file.nameWithoutExtension) to file
            } catch (e: IllegalArgumentException) {
                null
            }
        } ?: emptyList()

    override fun getPlayers(): List<UUID> =
        inventories().flatMap { dataFiles(it.playerFolder) }.map { it.first }.distinct()

    override fun getPlayerData(uuid: UUID, group: String, gameMode: GameMode?): ImportedPlayerData? {
        val inventory = inventories().firstOrNull { it.sharedWorld.equals(group, ignoreCase = true) } ?: return null
        val file = inventory.playerFolder?.let { File(it, "$uuid.dat") }?.takeIf { it.exists() } ?: return null

        return try {
            val nbt = readNbtFile(file)
            // MyWorlds does not separate game modes: the same inventory applies to all of them.
            toImportedData(nbt, uuid, inventory.sharedWorld, gameMode ?: GameMode.SURVIVAL, file)
        } catch (e: Exception) {
            Logging.error("Failed to parse MyWorlds player data: ${file.absolutePath}", e)
            null
        }
    }

    private fun toImportedData(
        nbt: Map<String, Any>,
        uuid: UUID,
        group: String,
        gameMode: GameMode,
        file: File
    ): ImportedPlayerData {
        val dataVersion = (nbt["DataVersion"] as? Int) ?: 0

        val mainInventory = mutableMapOf<Int, ItemStack>()
        val armor = mutableMapOf<Int, ItemStack>()
        var offhand: ItemStack? = null
        listOf(nbt["Inventory"]).filterIsInstance<NbtList>().flatMap { it.items }.forEach { entry ->
            @Suppress("UNCHECKED_CAST")
            val tag = entry as? Map<String, Any> ?: return@forEach
            val slot = (tag["Slot"] as? Byte)?.toInt() ?: return@forEach
            val item = toItemStack(tag, dataVersion) ?: return@forEach
            when (slot) {
                in 0..35 -> mainInventory[slot] = item
                in 100..103 -> armor[slot - 100] = item // pre-1.21.5: 100 = feet ... 103 = head
                -106 -> offhand = item
            }
        }
        // 1.21.5+: armor and off-hand live in the "equipment" compound.
        (nbt["equipment"] as? Map<*, *>)?.forEach { (key, value) ->
            @Suppress("UNCHECKED_CAST")
            val item = (value as? Map<String, Any>)?.let { toItemStack(it, dataVersion) } ?: return@forEach
            when (key) {
                "feet" -> armor[0] = item
                "legs" -> armor[1] = item
                "chest" -> armor[2] = item
                "head" -> armor[3] = item
                "offhand" -> offhand = item
            }
        }

        val enderChest = mutableMapOf<Int, ItemStack>()
        (nbt["EnderItems"] as? NbtList)?.items?.forEach { entry ->
            @Suppress("UNCHECKED_CAST")
            val tag = entry as? Map<String, Any> ?: return@forEach
            val slot = (tag["Slot"] as? Byte)?.toInt() ?: return@forEach
            toItemStack(tag, dataVersion)?.let { enderChest[slot] = it }
        }

        @Suppress("UNCHECKED_CAST")
        val abilities = nbt["abilities"] as? Map<String, Any>
        val maxHealth = (nbt["attributes"] as? NbtList)?.items?.filterIsInstance<Map<*, *>>()
            ?.firstOrNull { it["id"] == "minecraft:max_health" || it["Name"] == "minecraft:generic.max_health" }
            ?.let { (it["base"] ?: it["Base"]) as? Double } ?: 20.0

        return ImportedPlayerData(
            uuid = uuid,
            playerName = Bukkit.getOfflinePlayer(uuid).name ?: uuid.toString(),
            sourceGroup = group,
            gameMode = gameMode,
            mainInventory = mainInventory,
            armorInventory = armor,
            offhand = offhand,
            enderChest = enderChest,
            health = (nbt["Health"] as? Float)?.toDouble() ?: 20.0,
            maxHealth = maxHealth,
            foodLevel = (nbt["foodLevel"] as? Int) ?: 20,
            saturation = (nbt["foodSaturationLevel"] as? Float) ?: 5.0f,
            exhaustion = (nbt["foodExhaustionLevel"] as? Float) ?: 0.0f,
            experience = (nbt["XpP"] as? Float) ?: 0.0f,
            level = (nbt["XpLevel"] as? Int) ?: 0,
            totalExperience = (nbt["XpTotal"] as? Int) ?: 0,
            potionEffects = potionEffects(nbt),
            balance = null,
            sourceTimestamp = Instant.ofEpochMilli(file.lastModified()),
            sourceId = id,
            isFlying = (abilities?.get("flying") as? Byte)?.toInt() == 1,
            allowFlight = (abilities?.get("mayfly") as? Byte)?.toInt() == 1,
            fallDistance = ((nbt["fall_distance"] as? Double)?.toFloat()) ?: (nbt["FallDistance"] as? Float) ?: 0.0f,
            fireTicks = (nbt["Fire"] as? Short)?.toInt() ?: 0,
            remainingAir = (nbt["Air"] as? Short)?.toInt() ?: 300
        )
    }

    /** 1.20.2+ `active_effects` (`id` = namespaced key) or the older `ActiveEffects`. */
    private fun potionEffects(nbt: Map<String, Any>): List<PotionEffect> {
        val list = (nbt["active_effects"] ?: nbt["ActiveEffects"]) as? NbtList ?: return emptyList()
        return list.items.filterIsInstance<Map<*, *>>().mapNotNull { tag ->
            try {
                val type = (tag["id"] as? String)?.let { PotionEffectCompat.getByName(it) } ?: return@mapNotNull null
                val duration = (tag["duration"] ?: tag["Duration"]) as? Int ?: return@mapNotNull null
                val amplifier = ((tag["amplifier"] ?: tag["Amplifier"]) as? Byte)?.toInt() ?: 0
                PotionEffect(type, duration, amplifier)
            } catch (e: Exception) {
                null
            }
        }
    }

    /**
     * Turns an item compound from a player file into an ItemStack through Paper's
     * `deserializeBytes`, which runs the data converter from the file's DataVersion.
     */
    private fun toItemStack(tag: Map<String, Any>, dataVersion: Int): ItemStack? {
        val item = LinkedHashMap(tag)
        item.remove("Slot")
        item["DataVersion"] = dataVersion
        return try {
            ItemStack.deserializeBytes(writeNbt(item)).takeUnless { it.type == Material.AIR || it.amount <= 0 }
        } catch (e: Throwable) {
            Logging.warning("Could not convert a MyWorlds item (${tag["id"]}): ${e.message}")
            null
        }
    }

    override fun getAllPlayerData(uuid: UUID): Map<String, ImportedPlayerData> {
        val result = mutableMapOf<String, ImportedPlayerData>()

        getGroups().forEach { group ->
            getPlayerData(uuid, group.name, null)?.let { data ->
                result[group.name] = data
            }
        }

        return result
    }

    override fun validate(): ImportValidationResult {
        val issues = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        if (!isAvailable) {
            issues.add("MyWorlds is not installed and plugins/My_Worlds does not exist")
            return ImportValidationResult(
                isValid = false,
                issues = issues
            )
        }

        val inventories = inventories()
        val files = inventories.flatMap { dataFiles(it.playerFolder) }
        val players = files.map { it.first }.distinct()

        if (inventories.isEmpty()) {
            warnings.add("No world inventory groups detected")
        }

        if (players.isEmpty()) {
            warnings.add("No player data files found")
        }

        val corruptedFiles = files.count { (_, file) ->
            try {
                readNbtFile(file)
                false
            } catch (e: Exception) {
                true
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
            groupCount = inventories.size
        )
    }

    // ---------------------------------------------------------------- NBT

    /** An NBT list, keeping its element type so it can be written back unchanged. */
    private class NbtList(val elementType: Int, val items: List<Any>)

    private fun readNbtFile(file: File): Map<String, Any> =
        DataInputStream(GZIPInputStream(FileInputStream(file)).buffered()).use { input ->
            val type = input.readByte().toInt()
            require(type == TAG_COMPOUND) { "Not an NBT compound: ${file.name}" }
            input.readUTF() // root name
            @Suppress("UNCHECKED_CAST")
            readTag(input, TAG_COMPOUND) as Map<String, Any>
        }

    private fun readTag(input: DataInputStream, type: Int): Any = when (type) {
        1 -> input.readByte()
        2 -> input.readShort()
        3 -> input.readInt()
        4 -> input.readLong()
        5 -> input.readFloat()
        6 -> input.readDouble()
        7 -> ByteArray(input.readInt()).also { input.readFully(it) }
        8 -> input.readUTF()
        9 -> {
            val elementType = input.readByte().toInt()
            val size = input.readInt()
            NbtList(elementType, List(size) { readTag(input, elementType) })
        }
        TAG_COMPOUND -> {
            val map = linkedMapOf<String, Any>()
            while (true) {
                val tagType = input.readByte().toInt()
                if (tagType == 0) break
                val key = input.readUTF()
                map[key] = readTag(input, tagType)
            }
            map
        }
        11 -> IntArray(input.readInt()) { input.readInt() }
        12 -> LongArray(input.readInt()) { input.readLong() }
        else -> throw IllegalArgumentException("Unknown NBT tag type $type")
    }

    /** Gzipped NBT of [root], the format Paper's ItemStack.deserializeBytes reads. */
    private fun writeNbt(root: Map<String, Any>): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(GZIPOutputStream(bytes)).use { out ->
            out.writeByte(TAG_COMPOUND)
            out.writeUTF("")
            writePayload(out, root)
        }
        return bytes.toByteArray()
    }

    private fun typeOf(value: Any): Int = when (value) {
        is Byte -> 1
        is Short -> 2
        is Int -> 3
        is Long -> 4
        is Float -> 5
        is Double -> 6
        is ByteArray -> 7
        is String -> 8
        is NbtList -> 9
        is Map<*, *> -> TAG_COMPOUND
        is IntArray -> 11
        is LongArray -> 12
        else -> throw IllegalArgumentException("Not an NBT value: ${value.javaClass}")
    }

    private fun writePayload(out: DataOutputStream, value: Any) {
        when (value) {
            is Byte -> out.writeByte(value.toInt())
            is Short -> out.writeShort(value.toInt())
            is Int -> out.writeInt(value)
            is Long -> out.writeLong(value)
            is Float -> out.writeFloat(value)
            is Double -> out.writeDouble(value)
            is ByteArray -> { out.writeInt(value.size); out.write(value) }
            is String -> out.writeUTF(value)
            is NbtList -> {
                out.writeByte(value.elementType)
                out.writeInt(value.items.size)
                value.items.forEach { writePayload(out, it) }
            }
            is Map<*, *> -> {
                value.forEach { (key, child) ->
                    if (child == null) return@forEach
                    out.writeByte(typeOf(child))
                    out.writeUTF(key.toString())
                    writePayload(out, child)
                }
                out.writeByte(0)
            }
            is IntArray -> { out.writeInt(value.size); value.forEach { out.writeInt(it) } }
            is LongArray -> { out.writeInt(value.size); value.forEach { out.writeLong(it) } }
            else -> throw IllegalArgumentException("Not an NBT value: ${value.javaClass}")
        }
    }

    private companion object {
        const val TAG_COMPOUND = 10
    }
}
