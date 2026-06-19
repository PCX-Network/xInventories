package sh.pcx.xinventories.internal.util

import org.bukkit.configuration.ConfigurationSection
import org.bukkit.inventory.ItemStack
import org.bukkit.util.io.BukkitObjectInputStream
import org.bukkit.util.io.BukkitObjectOutputStream
import org.yaml.snakeyaml.external.biz.base64Coder.Base64Coder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Serializes and deserializes ItemStack data for storage.
 */
object InventorySerializer {

    /**
     * Serializes an ItemStack array to a Base64 string.
     */
    fun serializeItemStacks(items: Array<ItemStack?>): String {
        return try {
            ByteArrayOutputStream().use { outputStream ->
                BukkitObjectOutputStream(outputStream).use { dataOutput ->
                    dataOutput.writeInt(items.size)
                    for (item in items) {
                        dataOutput.writeObject(item)
                    }
                }
                Base64Coder.encodeLines(outputStream.toByteArray())
            }
        } catch (e: Exception) {
            Logging.error("Failed to serialize items", e)
            ""
        }
    }

    /**
     * Deserializes an ItemStack array from a Base64 string.
     */
    fun deserializeItemStacks(data: String): Array<ItemStack?> {
        if (data.isBlank()) return emptyArray()

        return try {
            val bytes = Base64Coder.decodeLines(data)
            ByteArrayInputStream(bytes).use { inputStream ->
                BukkitObjectInputStream(inputStream).use { dataInput ->
                    val size = dataInput.readInt()
                    var failures = 0
                    val result = Array<ItemStack?>(size) { index ->
                        try {
                            dataInput.readObject() as? ItemStack
                        } catch (e: Exception) {
                            // A single bad item (e.g. an unknown type from a newer version) must not
                            // wipe the entire inventory. Note: a failed readObject can desync the
                            // Bukkit object stream, so trailing items may also be lost - but we keep
                            // everything read so far instead of returning nothing.
                            failures++
                            Logging.error("Failed to deserialize item at index $index (slot dropped)", e)
                            null
                        }
                    }
                    if (failures > 0) {
                        Logging.warning("Dropped $failures of $size item(s) during inventory deserialization")
                    }
                    result
                }
            }
        } catch (e: Exception) {
            Logging.error("Failed to deserialize items", e)
            emptyArray()
        }
    }

    /**
     * Serializes a single ItemStack to a Base64 string.
     */
    fun serializeItemStack(item: ItemStack?): String {
        if (item == null) return ""

        return try {
            ByteArrayOutputStream().use { outputStream ->
                BukkitObjectOutputStream(outputStream).use { dataOutput ->
                    dataOutput.writeObject(item)
                }
                Base64Coder.encodeLines(outputStream.toByteArray())
            }
        } catch (e: Exception) {
            Logging.error("Failed to serialize item", e)
            ""
        }
    }

    /**
     * Deserializes a single ItemStack from a Base64 string.
     */
    fun deserializeItemStack(data: String): ItemStack? {
        if (data.isBlank()) return null

        return try {
            val bytes = Base64Coder.decodeLines(data)
            ByteArrayInputStream(bytes).use { inputStream ->
                BukkitObjectInputStream(inputStream).use { dataInput ->
                    dataInput.readObject() as? ItemStack
                }
            }
        } catch (e: Exception) {
            Logging.error("Failed to deserialize item", e)
            null
        }
    }

    /**
     * Serializes an inventory map to a Base64 string.
     */
    fun serializeInventoryMap(items: Map<Int, ItemStack>): String {
        if (items.isEmpty()) return ""

        return try {
            ByteArrayOutputStream().use { outputStream ->
                BukkitObjectOutputStream(outputStream).use { dataOutput ->
                    dataOutput.writeInt(items.size)
                    for ((slot, item) in items) {
                        dataOutput.writeInt(slot)
                        dataOutput.writeObject(item)
                    }
                }
                Base64Coder.encodeLines(outputStream.toByteArray())
            }
        } catch (e: Exception) {
            Logging.error("Failed to serialize inventory map", e)
            ""
        }
    }

    /**
     * Deserializes an inventory map from a Base64 string.
     */
    fun deserializeInventoryMap(data: String): Map<Int, ItemStack> {
        if (data.isBlank()) return emptyMap()

        return try {
            val bytes = Base64Coder.decodeLines(data)
            ByteArrayInputStream(bytes).use { inputStream ->
                BukkitObjectInputStream(inputStream).use { dataInput ->
                    val size = dataInput.readInt()
                    val items = mutableMapOf<Int, ItemStack>()
                    var failures = 0
                    repeat(size) { index ->
                        // Per-entry recovery: one undeserializable entry must not drop the whole map.
                        try {
                            val slot = dataInput.readInt()
                            val item = dataInput.readObject() as? ItemStack
                            if (item != null) {
                                items[slot] = item
                            }
                        } catch (e: Exception) {
                            failures++
                            Logging.error("Failed to deserialize inventory-map entry #$index (dropped)", e)
                        }
                    }
                    if (failures > 0) {
                        Logging.warning("Dropped $failures of $size inventory-map entr(ies) during deserialization")
                    }
                    items
                }
            }
        } catch (e: Exception) {
            Logging.error("Failed to deserialize inventory map", e)
            emptyMap()
        }
    }

    /**
     * Converts an ItemStack to a map for YAML serialization.
     */
    fun itemStackToMap(item: ItemStack?): Map<String, Any>? {
        return item?.serialize()
    }

    /**
     * Converts a map back to an ItemStack.
     * Handles MemorySection objects that may be present when reading from YAML.
     */
    fun mapToItemStack(map: Map<String, Any>?): ItemStack? {
        if (map == null) return null
        val cleanedMap = convertToPlainMap(map)
        return ItemStack.deserialize(cleanedMap)
    }

    /**
     * Recursively converts a map containing ConfigurationSection objects to plain Maps.
     * This is needed because YAML configuration returns MemorySection objects for nested data.
     */
    @Suppress("UNCHECKED_CAST")
    private fun convertToPlainMap(map: Map<String, Any>): Map<String, Any> {
        return map.mapValues { (_, value) ->
            when (value) {
                is ConfigurationSection -> convertToPlainMap(value.getValues(false))
                is Map<*, *> -> convertToPlainMap(value as Map<String, Any>)
                else -> value
            }
        }
    }

    /**
     * Serializes inventory contents to a map for YAML storage.
     */
    fun inventoryToYamlMap(items: Map<Int, ItemStack>): Map<String, Map<String, Any>> {
        return items.mapKeys { it.key.toString() }
            .mapValues { it.value.serialize() }
    }

    /**
     * Deserializes inventory contents from a YAML map.
     * Handles both plain Maps and ConfigurationSection objects.
     */
    @Suppress("UNCHECKED_CAST")
    fun yamlMapToInventory(map: Map<String, Any>?): Map<Int, ItemStack> {
        if (map == null) return emptyMap()

        return try {
            map.mapNotNull { (key, value) ->
                val slot = key.toIntOrNull() ?: return@mapNotNull null
                val itemMap = when (value) {
                    is ConfigurationSection -> convertToPlainMap(value.getValues(false))
                    is Map<*, *> -> convertToPlainMap(value as Map<String, Any>)
                    else -> return@mapNotNull null
                }
                val item = ItemStack.deserialize(itemMap)
                slot to item
            }.toMap()
        } catch (e: Exception) {
            Logging.error("Failed to deserialize YAML inventory", e)
            emptyMap()
        }
    }
}
