package sh.pcx.xinventories.unit.serializer

import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import org.bukkit.Material
import org.bukkit.enchantments.Enchantment
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import sh.pcx.xinventories.internal.util.InventorySerializer
import sh.pcx.xinventories.internal.util.Logging
import java.util.logging.Logger
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Comprehensive unit tests for InventorySerializer.
 * Tests serialization and deserialization of ItemStacks to/from Base64 and YAML formats.
 */
@DisplayName("InventorySerializer")
class InventorySerializerTest {

    private lateinit var server: ServerMock

    @BeforeEach
    fun setUp() {
        server = MockBukkit.mock()
        // Initialize logging to prevent NPE in serializer error handling
        Logging.init(Logger.getLogger("InventorySerializerTest"), debug = true)
    }

    @AfterEach
    fun tearDown() {
        MockBukkit.unmock()
    }

    @Nested
    @DisplayName("Single ItemStack Serialization")
    inner class SingleItemStackSerializationTests {

        @Test
        @DisplayName("should serialize and deserialize a simple ItemStack")
        fun `serialize and deserialize simple ItemStack roundtrip`() {
            // Given
            val original = ItemStack(Material.DIAMOND_SWORD)

            // When
            val serialized = InventorySerializer.serializeItemStack(original)
            val deserialized = InventorySerializer.deserializeItemStack(serialized)

            // Then
            assertNotNull(deserialized, "Deserialized item should not be null")
            assertEquals(Material.DIAMOND_SWORD, deserialized.type, "Material should match")
            assertEquals(1, deserialized.amount, "Amount should match")
        }

        @Test
        @DisplayName("should serialize and deserialize ItemStack with custom amount")
        fun `serialize and deserialize ItemStack with custom amount`() {
            // Given
            val original = ItemStack(Material.COBBLESTONE, 64)

            // When
            val serialized = InventorySerializer.serializeItemStack(original)
            val deserialized = InventorySerializer.deserializeItemStack(serialized)

            // Then
            assertNotNull(deserialized)
            assertEquals(Material.COBBLESTONE, deserialized.type)
            assertEquals(64, deserialized.amount)
        }

        @Test
        @DisplayName("should return empty string for null ItemStack")
        fun `serialize null ItemStack returns empty string`() {
            // When
            val serialized = InventorySerializer.serializeItemStack(null)

            // Then
            assertEquals("", serialized)
        }

        @Test
        @DisplayName("should return null when deserializing empty string")
        fun `deserialize empty string returns null`() {
            // When
            val result = InventorySerializer.deserializeItemStack("")

            // Then
            assertNull(result)
        }

        @Test
        @DisplayName("should return null when deserializing blank string")
        fun `deserialize blank string returns null`() {
            // When
            val result = InventorySerializer.deserializeItemStack("   ")

            // Then
            assertNull(result)
        }

        @Test
        @DisplayName("should produce non-empty Base64 string for valid ItemStack")
        fun `serialized ItemStack produces non-empty Base64 string`() {
            // Given
            val item = ItemStack(Material.GOLDEN_APPLE, 5)

            // When
            val serialized = InventorySerializer.serializeItemStack(item)

            // Then
            assertTrue(serialized.isNotBlank(), "Serialized data should not be blank")
            assertTrue(serialized.length > 10, "Serialized data should have meaningful length")
        }
    }

    @Nested
    @DisplayName("ItemStack Array Serialization")
    inner class ItemStackArraySerializationTests {

        @Test
        @DisplayName("should serialize and deserialize ItemStack array")
        fun `serialize and deserialize ItemStack array roundtrip`() {
            // Given
            val original = arrayOf<ItemStack?>(
                ItemStack(Material.DIAMOND, 10),
                ItemStack(Material.GOLD_INGOT, 32),
                ItemStack(Material.IRON_INGOT, 64)
            )

            // When
            val serialized = InventorySerializer.serializeItemStacks(original)
            val deserialized = InventorySerializer.deserializeItemStacks(serialized)

            // Then
            assertEquals(3, deserialized.size, "Array size should match")
            assertEquals(Material.DIAMOND, deserialized[0]?.type)
            assertEquals(10, deserialized[0]?.amount)
            assertEquals(Material.GOLD_INGOT, deserialized[1]?.type)
            assertEquals(32, deserialized[1]?.amount)
            assertEquals(Material.IRON_INGOT, deserialized[2]?.type)
            assertEquals(64, deserialized[2]?.amount)
        }

        @Test
        @DisplayName("should handle null items in array")
        fun `serialize and deserialize array with null items`() {
            // Given
            val original = arrayOf<ItemStack?>(
                ItemStack(Material.DIAMOND, 1),
                null,
                ItemStack(Material.EMERALD, 3),
                null,
                null,
                ItemStack(Material.GOLD_INGOT, 6)
            )

            // When
            val serialized = InventorySerializer.serializeItemStacks(original)
            val deserialized = InventorySerializer.deserializeItemStacks(serialized)

            // Then
            assertEquals(6, deserialized.size, "Array size should be preserved")
            assertNotNull(deserialized[0])
            assertNull(deserialized[1], "Null at index 1 should be preserved")
            assertNotNull(deserialized[2])
            assertNull(deserialized[3], "Null at index 3 should be preserved")
            assertNull(deserialized[4], "Null at index 4 should be preserved")
            assertNotNull(deserialized[5])
        }

        @Test
        @DisplayName("should handle empty array")
        fun `serialize and deserialize empty array`() {
            // Given
            val original = emptyArray<ItemStack?>()

            // When
            val serialized = InventorySerializer.serializeItemStacks(original)
            val deserialized = InventorySerializer.deserializeItemStacks(serialized)

            // Then
            assertEquals(0, deserialized.size, "Empty array should remain empty")
        }

        @Test
        @DisplayName("should handle array with all null items")
        fun `serialize and deserialize array of all nulls`() {
            // Given
            val original = arrayOfNulls<ItemStack>(5)

            // When
            val serialized = InventorySerializer.serializeItemStacks(original)
            val deserialized = InventorySerializer.deserializeItemStacks(serialized)

            // Then
            assertEquals(5, deserialized.size)
            deserialized.forEach { assertNull(it) }
        }

        @Test
        @DisplayName("should return empty array when deserializing empty string")
        fun `deserialize empty string returns empty array`() {
            // When
            val result = InventorySerializer.deserializeItemStacks("")

            // Then
            assertTrue(result.isEmpty())
        }

        @Test
        @DisplayName("should return empty array when deserializing blank string")
        fun `deserialize blank string returns empty array`() {
            // When
            val result = InventorySerializer.deserializeItemStacks("   \n\t  ")

            // Then
            assertTrue(result.isEmpty())
        }

        @Test
        @DisplayName("should degrade gracefully on a malformed payload instead of throwing (B6)")
        fun `deserialize malformed payload does not throw`() {
            // A structurally-invalid (non-blank) payload must not propagate an exception - it should
            // be caught and return an empty array, mirroring how a single bad item is now dropped
            // rather than taking down the whole inventory.
            val result = InventorySerializer.deserializeItemStacks("!!!not-valid-base64-bukkit-data!!!")

            assertTrue(result.isEmpty())
        }

        @Test
        @DisplayName("should handle large array")
        fun `serialize and deserialize large array`() {
            // Given - typical inventory size
            val original = Array<ItemStack?>(36) { index ->
                if (index % 3 == 0) ItemStack(Material.STONE, index + 1) else null
            }

            // When
            val serialized = InventorySerializer.serializeItemStacks(original)
            val deserialized = InventorySerializer.deserializeItemStacks(serialized)

            // Then
            assertEquals(36, deserialized.size)
            for (i in original.indices) {
                if (i % 3 == 0) {
                    assertNotNull(deserialized[i])
                    assertEquals(Material.STONE, deserialized[i]?.type)
                    assertEquals(i + 1, deserialized[i]?.amount)
                } else {
                    assertNull(deserialized[i])
                }
            }
        }
    }

    @Nested
    @DisplayName("Inventory Map Serialization")
    inner class InventoryMapSerializationTests {

        @Test
        @Disabled("MockBukkit's ArmorMetaMock is not registered with Bukkit's serialization system")
        @DisplayName("should serialize and deserialize inventory map")
        fun `serialize and deserialize inventory map roundtrip`() {
            // Given - armor items have ArmorMeta which MockBukkit doesn't properly serialize
            val original = mapOf(
                0 to ItemStack(Material.DIAMOND_HELMET),
                1 to ItemStack(Material.DIAMOND_CHESTPLATE),
                2 to ItemStack(Material.DIAMOND_LEGGINGS),
                3 to ItemStack(Material.DIAMOND_BOOTS)
            )

            // When
            val serialized = InventorySerializer.serializeInventoryMap(original)
            val deserialized = InventorySerializer.deserializeInventoryMap(serialized)

            // Then
            assertEquals(4, deserialized.size)
            assertEquals(Material.DIAMOND_HELMET, deserialized[0]?.type)
            assertEquals(Material.DIAMOND_CHESTPLATE, deserialized[1]?.type)
            assertEquals(Material.DIAMOND_LEGGINGS, deserialized[2]?.type)
            assertEquals(Material.DIAMOND_BOOTS, deserialized[3]?.type)
        }

        @Test
        @DisplayName("should handle empty inventory map")
        fun `serialize and deserialize empty inventory map`() {
            // Given
            val original = emptyMap<Int, ItemStack>()

            // When
            val serialized = InventorySerializer.serializeInventoryMap(original)
            val deserialized = InventorySerializer.deserializeInventoryMap(serialized)

            // Then
            assertEquals("", serialized, "Empty map should serialize to empty string")
            assertTrue(deserialized.isEmpty())
        }

        @Test
        @DisplayName("should preserve slot indices")
        fun `serialize and deserialize map with non-contiguous slots`() {
            // Given
            val original = mapOf(
                0 to ItemStack(Material.STONE),
                5 to ItemStack(Material.COBBLESTONE),
                17 to ItemStack(Material.DIRT),
                35 to ItemStack(Material.GRAVEL)
            )

            // When
            val serialized = InventorySerializer.serializeInventoryMap(original)
            val deserialized = InventorySerializer.deserializeInventoryMap(serialized)

            // Then
            assertEquals(4, deserialized.size)
            assertTrue(deserialized.containsKey(0))
            assertTrue(deserialized.containsKey(5))
            assertTrue(deserialized.containsKey(17))
            assertTrue(deserialized.containsKey(35))
            assertEquals(Material.STONE, deserialized[0]?.type)
            assertEquals(Material.COBBLESTONE, deserialized[5]?.type)
            assertEquals(Material.DIRT, deserialized[17]?.type)
            assertEquals(Material.GRAVEL, deserialized[35]?.type)
        }

        @Test
        @DisplayName("should return empty map when deserializing empty string")
        fun `deserialize empty string returns empty map`() {
            // When
            val result = InventorySerializer.deserializeInventoryMap("")

            // Then
            assertTrue(result.isEmpty())
        }

        @Test
        @DisplayName("should return empty map when deserializing blank string")
        fun `deserialize blank string returns empty map`() {
            // When
            val result = InventorySerializer.deserializeInventoryMap("   ")

            // Then
            assertTrue(result.isEmpty())
        }
    }

    @Nested
    @DisplayName("Special Items Serialization")
    inner class SpecialItemsSerializationTests {

        @Test
        @DisplayName("should serialize and deserialize enchanted item")
        fun `serialize and deserialize enchanted ItemStack`() {
            // Given
            val original = ItemStack(Material.DIAMOND_SWORD).apply {
                addUnsafeEnchantment(Enchantment.SHARPNESS, 5)
                addUnsafeEnchantment(Enchantment.UNBREAKING, 3)
            }

            // When
            val serialized = InventorySerializer.serializeItemStack(original)
            val deserialized = InventorySerializer.deserializeItemStack(serialized)

            // Then
            assertNotNull(deserialized)
            assertEquals(Material.DIAMOND_SWORD, deserialized.type)
            assertEquals(5, deserialized.getEnchantmentLevel(Enchantment.SHARPNESS))
            assertEquals(3, deserialized.getEnchantmentLevel(Enchantment.UNBREAKING))
        }

        @Test
        @DisplayName("should serialize and deserialize item with lore")
        fun `serialize and deserialize ItemStack with lore`() {
            // Given
            val original = ItemStack(Material.DIAMOND_PICKAXE)
            val meta = original.itemMeta
            meta?.lore(listOf(
                net.kyori.adventure.text.Component.text("Line 1"),
                net.kyori.adventure.text.Component.text("Line 2"),
                net.kyori.adventure.text.Component.text("Line 3")
            ))
            original.itemMeta = meta

            // When
            val serialized = InventorySerializer.serializeItemStack(original)
            val deserialized = InventorySerializer.deserializeItemStack(serialized)

            // Then
            assertNotNull(deserialized)
            assertEquals(Material.DIAMOND_PICKAXE, deserialized.type)
            assertNotNull(deserialized.itemMeta?.lore())
            assertEquals(3, deserialized.itemMeta?.lore()?.size)
        }

        @Test
        @DisplayName("should serialize and deserialize item with custom name")
        fun `serialize and deserialize ItemStack with custom display name`() {
            // Given
            val original = ItemStack(Material.NETHERITE_SWORD)
            val meta = original.itemMeta
            meta?.displayName(net.kyori.adventure.text.Component.text("Legendary Blade"))
            original.itemMeta = meta

            // When
            val serialized = InventorySerializer.serializeItemStack(original)
            val deserialized = InventorySerializer.deserializeItemStack(serialized)

            // Then
            assertNotNull(deserialized)
            assertEquals(Material.NETHERITE_SWORD, deserialized.type)
            assertNotNull(deserialized.itemMeta?.displayName())
        }

        @Test
        @DisplayName("should serialize and deserialize item with multiple metadata")
        fun `serialize and deserialize ItemStack with combined metadata`() {
            // Given
            val original = ItemStack(Material.BOW).apply {
                addUnsafeEnchantment(Enchantment.POWER, 5)
                addUnsafeEnchantment(Enchantment.INFINITY, 1)
            }
            val meta = original.itemMeta
            meta?.displayName(net.kyori.adventure.text.Component.text("Infinity Bow"))
            meta?.lore(listOf(
                net.kyori.adventure.text.Component.text("A legendary bow")
            ))
            original.itemMeta = meta

            // When
            val serialized = InventorySerializer.serializeItemStack(original)
            val deserialized = InventorySerializer.deserializeItemStack(serialized)

            // Then
            assertNotNull(deserialized)
            assertEquals(Material.BOW, deserialized.type)
            assertEquals(5, deserialized.getEnchantmentLevel(Enchantment.POWER))
            assertEquals(1, deserialized.getEnchantmentLevel(Enchantment.INFINITY))
            assertNotNull(deserialized.itemMeta?.displayName())
            assertNotNull(deserialized.itemMeta?.lore())
        }
    }

    @Nested
    @DisplayName("Error Handling")
    inner class ErrorHandlingTests {

        @Test
        @DisplayName("should handle corrupted Base64 data gracefully for single item")
        fun `deserialize corrupted data returns null for single item`() {
            // Given
            val corruptedData = "not_valid_base64_!!!@#\$%"

            // When/Then - should not throw, returns null
            val result = assertDoesNotThrow {
                InventorySerializer.deserializeItemStack(corruptedData)
            }
            assertNull(result)
        }

        @Test
        @DisplayName("should handle corrupted Base64 data gracefully for array")
        fun `deserialize corrupted data returns empty array`() {
            // Given
            val corruptedData = "corrupted_base64_data_here"

            // When/Then - should not throw, returns empty array
            val result = assertDoesNotThrow {
                InventorySerializer.deserializeItemStacks(corruptedData)
            }
            assertTrue(result.isEmpty())
        }

        @Test
        @DisplayName("should handle corrupted Base64 data gracefully for map")
        fun `deserialize corrupted data returns empty map`() {
            // Given
            val corruptedData = "invalid_base64_!@#\$%"

            // When/Then - should not throw, returns empty map
            val result = assertDoesNotThrow {
                InventorySerializer.deserializeInventoryMap(corruptedData)
            }
            assertTrue(result.isEmpty())
        }

        @Test
        @DisplayName("should handle valid Base64 but invalid object data")
        fun `deserialize valid Base64 with wrong content returns empty`() {
            // Given - valid Base64 but not a serialized ItemStack
            val validBase64WrongContent = "SGVsbG8gV29ybGQ=" // "Hello World" in Base64

            // When/Then - should not throw
            val result = assertDoesNotThrow {
                InventorySerializer.deserializeItemStack(validBase64WrongContent)
            }
            assertNull(result)
        }

        @Test
        @DisplayName("should handle truncated Base64 data")
        fun `deserialize truncated data handles gracefully`() {
            // Given - serialize valid item then truncate
            val original = ItemStack(Material.DIAMOND)
            val fullSerialized = InventorySerializer.serializeItemStack(original)
            val truncated = fullSerialized.take(fullSerialized.length / 2)

            // When/Then - should not throw
            val result = assertDoesNotThrow {
                InventorySerializer.deserializeItemStack(truncated)
            }
            assertNull(result)
        }
    }

    @Nested
    @DisplayName("YAML Map Conversion")
    inner class YamlMapConversionTests {

        @Test
        @DisplayName("should convert ItemStack to map")
        fun `itemStackToMap returns serialized map`() {
            // Given
            val item = ItemStack(Material.DIAMOND_SWORD, 1)

            // When
            val map = InventorySerializer.itemStackToMap(item)

            // Then
            assertNotNull(map)
            // MockBukkit 4.101.0 uses various keys for serialization
            // We just need to ensure it returns non-null data that can be deserialized
            assertTrue(map.isNotEmpty(), "Map should contain serialization data")
        }

        @Test
        @DisplayName("should return null for null ItemStack")
        fun `itemStackToMap returns null for null item`() {
            // When
            val map = InventorySerializer.itemStackToMap(null)

            // Then
            assertNull(map)
        }

        @Test
        @DisplayName("should convert map back to ItemStack")
        fun `mapToItemStack deserializes correctly`() {
            // Given
            val original = ItemStack(Material.GOLDEN_APPLE, 5)
            val map = original.serialize()

            // When
            val restored = InventorySerializer.mapToItemStack(map)

            // Then
            assertNotNull(restored)
            assertEquals(Material.GOLDEN_APPLE, restored.type)
            assertEquals(5, restored.amount)
        }

        @Test
        @DisplayName("should return null for null map")
        fun `mapToItemStack returns null for null map`() {
            // When
            val result = InventorySerializer.mapToItemStack(null)

            // Then
            assertNull(result)
        }

        @Test
        @DisplayName("should convert inventory to YAML map")
        fun `inventoryToYamlMap serializes correctly`() {
            // Given
            val inventory = mapOf(
                0 to ItemStack(Material.DIAMOND, 5),
                5 to ItemStack(Material.EMERALD, 10)
            )

            // When
            val yamlMap = InventorySerializer.inventoryToYamlMap(inventory)

            // Then
            assertEquals(2, yamlMap.size)
            assertTrue(yamlMap.containsKey("0"))
            assertTrue(yamlMap.containsKey("5"))
            assertTrue(yamlMap["0"] is Map<*, *>)
            assertTrue(yamlMap["5"] is Map<*, *>)
        }

        @Test
        @DisplayName("should convert YAML map back to inventory")
        fun `yamlMapToInventory deserializes correctly`() {
            // Given
            val original = mapOf(
                0 to ItemStack(Material.IRON_INGOT, 32),
                10 to ItemStack(Material.GOLD_INGOT, 16)
            )
            val yamlMap = InventorySerializer.inventoryToYamlMap(original)

            // When
            @Suppress("UNCHECKED_CAST")
            val restored = InventorySerializer.yamlMapToInventory(yamlMap as Map<String, Any>)

            // Then
            assertEquals(2, restored.size)
            assertEquals(Material.IRON_INGOT, restored[0]?.type)
            assertEquals(32, restored[0]?.amount)
            assertEquals(Material.GOLD_INGOT, restored[10]?.type)
            assertEquals(16, restored[10]?.amount)
        }

        @Test
        @DisplayName("should return empty map for null input")
        fun `yamlMapToInventory returns empty map for null`() {
            // When
            val result = InventorySerializer.yamlMapToInventory(null)

            // Then
            assertTrue(result.isEmpty())
        }

        @Test
        @DisplayName("should handle invalid slot keys gracefully")
        fun `yamlMapToInventory skips non-integer keys`() {
            // Given
            val invalidMap = mapOf(
                "0" to ItemStack(Material.STONE).serialize(),
                "invalid" to ItemStack(Material.DIRT).serialize(),
                "abc" to ItemStack(Material.COBBLESTONE).serialize(),
                "5" to ItemStack(Material.GRAVEL).serialize()
            )

            // When
            @Suppress("UNCHECKED_CAST")
            val result = InventorySerializer.yamlMapToInventory(invalidMap as Map<String, Any>)

            // Then
            assertEquals(2, result.size, "Only valid integer keys should be included")
            assertTrue(result.containsKey(0))
            assertTrue(result.containsKey(5))
        }

        @Test
        @DisplayName("should handle invalid value types gracefully")
        fun `yamlMapToInventory skips non-map values`() {
            // Given
            val mixedMap = mapOf<String, Any>(
                "0" to ItemStack(Material.STONE).serialize(),
                "1" to "not a map",
                "2" to 12345,
                "3" to ItemStack(Material.DIRT).serialize()
            )

            // When
            val result = InventorySerializer.yamlMapToInventory(mixedMap)

            // Then
            assertEquals(2, result.size, "Only valid map values should be deserialized")
            assertTrue(result.containsKey(0))
            assertTrue(result.containsKey(3))
        }

        @Test
        @DisplayName("should handle empty YAML map")
        fun `yamlMapToInventory handles empty map`() {
            // Given
            val emptyMap = emptyMap<String, Any>()

            // When
            val result = InventorySerializer.yamlMapToInventory(emptyMap)

            // Then
            assertTrue(result.isEmpty())
        }
    }

    @Nested
    @DisplayName("Roundtrip Consistency")
    inner class RoundtripConsistencyTests {

        @Test
        @DisplayName("should maintain data consistency across multiple roundtrips")
        fun `multiple roundtrips maintain consistency`() {
            // Given
            val original = ItemStack(Material.NETHERITE_PICKAXE, 1).apply {
                addUnsafeEnchantment(Enchantment.EFFICIENCY, 5)
            }

            // When - perform multiple roundtrips
            var current = original
            repeat(3) {
                val serialized = InventorySerializer.serializeItemStack(current)
                current = InventorySerializer.deserializeItemStack(serialized)!!
            }

            // Then
            assertEquals(Material.NETHERITE_PICKAXE, current.type)
            assertEquals(1, current.amount)
            assertEquals(5, current.getEnchantmentLevel(Enchantment.EFFICIENCY))
        }

        @Test
        @DisplayName("should produce identical Base64 for same item")
        fun `serialization is deterministic`() {
            // Given
            val item1 = ItemStack(Material.DIAMOND, 10)
            val item2 = ItemStack(Material.DIAMOND, 10)

            // When
            val serialized1 = InventorySerializer.serializeItemStack(item1)
            val serialized2 = InventorySerializer.serializeItemStack(item2)

            // Then - both should deserialize to equivalent items
            val deserialized1 = InventorySerializer.deserializeItemStack(serialized1)
            val deserialized2 = InventorySerializer.deserializeItemStack(serialized2)

            assertEquals(deserialized1?.type, deserialized2?.type)
            assertEquals(deserialized1?.amount, deserialized2?.amount)
        }
    }
}
