package sh.pcx.xinventories.unit.model

import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import org.mockbukkit.mockbukkit.entity.PlayerMock
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.attribute.Attribute
import org.bukkit.inventory.ItemStack
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assertions
import sh.pcx.xinventories.api.model.GroupSettings
import sh.pcx.xinventories.api.model.InventoryContents
import sh.pcx.xinventories.api.model.PlayerInventorySnapshot
import sh.pcx.xinventories.internal.model.PlayerData
import java.time.Instant
import java.util.UUID

@DisplayName("PlayerData Tests")
class PlayerDataTest {

    private lateinit var server: ServerMock
    private lateinit var player: PlayerMock

    @BeforeEach
    fun setUp() {
        server = MockBukkit.mock()
        player = server.addPlayer()
    }

    @AfterEach
    fun tearDown() {
        MockBukkit.unmock()
    }

    // ============================================================
    // Creation Tests
    // ============================================================

    @Nested
    @DisplayName("Create Empty PlayerData")
    inner class CreateEmptyTests {

        @Test
        @DisplayName("should create empty PlayerData with default values")
        fun createEmptyPlayerData() {
            val uuid = UUID.randomUUID()
            val playerName = "TestPlayer"
            val group = "default"
            val gameMode = GameMode.SURVIVAL

            val playerData = PlayerData.empty(uuid, playerName, group, gameMode)

            assertEquals(uuid, playerData.uuid)
            assertEquals(playerName, playerData.playerName)
            assertEquals(group, playerData.group)
            assertEquals(gameMode, playerData.gameMode)

            // Inventory should be empty
            assertTrue(playerData.mainInventory.isEmpty())
            assertTrue(playerData.armorInventory.isEmpty())
            Assertions.assertNull(playerData.offhand)
            assertTrue(playerData.enderChest.isEmpty())

            // State should be at defaults
            assertEquals(20.0, playerData.health)
            assertEquals(20.0, playerData.maxHealth)
            assertEquals(20, playerData.foodLevel)
            assertEquals(5.0f, playerData.saturation)
            assertEquals(0.0f, playerData.exhaustion)
            assertEquals(0.0f, playerData.experience)
            assertEquals(0, playerData.level)
            assertEquals(0, playerData.totalExperience)
            assertTrue(playerData.potionEffects.isEmpty())

            // Metadata
            Assertions.assertNotNull(playerData.timestamp)
            assertFalse(playerData.dirty)
        }

        @Test
        @DisplayName("should create PlayerData with different game modes")
        fun createEmptyWithDifferentGameModes() {
            val uuid = UUID.randomUUID()

            val survivalData = PlayerData.empty(uuid, "Player", "default", GameMode.SURVIVAL)
            val creativeData = PlayerData.empty(uuid, "Player", "default", GameMode.CREATIVE)
            val adventureData = PlayerData.empty(uuid, "Player", "default", GameMode.ADVENTURE)
            val spectatorData = PlayerData.empty(uuid, "Player", "default", GameMode.SPECTATOR)

            assertEquals(GameMode.SURVIVAL, survivalData.gameMode)
            assertEquals(GameMode.CREATIVE, creativeData.gameMode)
            assertEquals(GameMode.ADVENTURE, adventureData.gameMode)
            assertEquals(GameMode.SPECTATOR, spectatorData.gameMode)
        }
    }

    // ============================================================
    // Load From Player Tests
    // ============================================================

    @Nested
    @DisplayName("Load PlayerData From Player")
    inner class LoadFromPlayerTests {

        @Test
        @DisplayName("should load basic player info")
        fun loadBasicPlayerInfo() {
            player.gameMode = GameMode.CREATIVE

            val playerData = PlayerData.fromPlayer(player, "testgroup")

            assertEquals(player.uniqueId, playerData.uuid)
            assertEquals(player.name, playerData.playerName)
            assertEquals("testgroup", playerData.group)
            assertEquals(GameMode.CREATIVE, playerData.gameMode)
        }

        @Test
        @DisplayName("should load main inventory items")
        fun loadMainInventory() {
            val diamond = ItemStack(Material.DIAMOND, 64)
            val sword = ItemStack(Material.DIAMOND_SWORD)

            player.inventory.setItem(0, diamond)
            player.inventory.setItem(8, sword)

            val playerData = PlayerData.fromPlayer(player, "default")

            assertEquals(2, playerData.mainInventory.size)
            assertEquals(Material.DIAMOND, playerData.mainInventory[0]?.type)
            assertEquals(64, playerData.mainInventory[0]?.amount)
            assertEquals(Material.DIAMOND_SWORD, playerData.mainInventory[8]?.type)
        }

        @Test
        @DisplayName("should load armor inventory")
        fun loadArmorInventory() {
            val helmet = ItemStack(Material.DIAMOND_HELMET)
            val chestplate = ItemStack(Material.DIAMOND_CHESTPLATE)
            val leggings = ItemStack(Material.DIAMOND_LEGGINGS)
            val boots = ItemStack(Material.DIAMOND_BOOTS)

            player.inventory.setHelmet(helmet)
            player.inventory.setChestplate(chestplate)
            player.inventory.setLeggings(leggings)
            player.inventory.setBoots(boots)

            val playerData = PlayerData.fromPlayer(player, "default")

            assertEquals(4, playerData.armorInventory.size)
            // Armor order: 0=boots, 1=leggings, 2=chestplate, 3=helmet
            assertEquals(Material.DIAMOND_BOOTS, playerData.armorInventory[0]?.type)
            assertEquals(Material.DIAMOND_LEGGINGS, playerData.armorInventory[1]?.type)
            assertEquals(Material.DIAMOND_CHESTPLATE, playerData.armorInventory[2]?.type)
            assertEquals(Material.DIAMOND_HELMET, playerData.armorInventory[3]?.type)
        }

        @Test
        @DisplayName("should load offhand item")
        fun loadOffhand() {
            val shield = ItemStack(Material.SHIELD)
            player.inventory.setItemInOffHand(shield)

            val playerData = PlayerData.fromPlayer(player, "default")

            Assertions.assertNotNull(playerData.offhand)
            assertEquals(Material.SHIELD, playerData.offhand?.type)
        }

        @Test
        @DisplayName("should load ender chest contents")
        fun loadEnderChest() {
            val enderPearl = ItemStack(Material.ENDER_PEARL, 16)
            val diamond = ItemStack(Material.DIAMOND, 32)

            player.enderChest.setItem(0, enderPearl)
            player.enderChest.setItem(26, diamond)

            val playerData = PlayerData.fromPlayer(player, "default")

            assertEquals(2, playerData.enderChest.size)
            assertEquals(Material.ENDER_PEARL, playerData.enderChest[0]?.type)
            assertEquals(16, playerData.enderChest[0]?.amount)
            assertEquals(Material.DIAMOND, playerData.enderChest[26]?.type)
        }

        @Test
        @DisplayName("should load health and hunger values")
        fun loadHealthAndHunger() {
            player.health = 15.0
            player.foodLevel = 10
            player.saturation = 3.0f
            player.exhaustion = 2.5f

            val playerData = PlayerData.fromPlayer(player, "default")

            assertEquals(15.0, playerData.health)
            assertEquals(10, playerData.foodLevel)
            assertEquals(3.0f, playerData.saturation)
            assertEquals(2.5f, playerData.exhaustion)
        }

        @Test
        @DisplayName("should load experience values")
        fun loadExperience() {
            player.level = 30
            player.exp = 0.5f
            player.totalExperience = 1395

            val playerData = PlayerData.fromPlayer(player, "default")

            assertEquals(30, playerData.level)
            assertEquals(0.5f, playerData.experience)
            assertEquals(1395, playerData.totalExperience)
        }

        @Test
        @DisplayName("should load potion effects")
        fun loadPotionEffects() {
            val speed = PotionEffect(PotionEffectType.SPEED, 600, 1, true, true)
            val strength = PotionEffect(PotionEffectType.STRENGTH, 1200, 2, false, false)

            player.addPotionEffect(speed)
            player.addPotionEffect(strength)

            val playerData = PlayerData.fromPlayer(player, "default")

            assertEquals(2, playerData.potionEffects.size)
            assertTrue(playerData.potionEffects.any { it.type == PotionEffectType.SPEED })
            assertTrue(playerData.potionEffects.any { it.type == PotionEffectType.STRENGTH })
        }

        @Test
        @DisplayName("should not include air items in inventory")
        fun ignoreAirItems() {
            player.inventory.setItem(0, ItemStack(Material.AIR))
            player.inventory.setItem(1, ItemStack(Material.DIAMOND))

            val playerData = PlayerData.fromPlayer(player, "default")

            assertEquals(1, playerData.mainInventory.size)
            assertFalse(playerData.mainInventory.containsKey(0))
            assertTrue(playerData.mainInventory.containsKey(1))
        }

        @Test
        @DisplayName("should clone items to prevent mutation")
        fun cloneItems() {
            val originalItem = ItemStack(Material.DIAMOND, 32)
            player.inventory.setItem(0, originalItem)

            val playerData = PlayerData.fromPlayer(player, "default")

            // Modify original
            originalItem.amount = 1

            // PlayerData should have the original amount
            assertEquals(32, playerData.mainInventory[0]?.amount)
        }

        @Test
        @DisplayName("should set dirty flag to false after loading")
        fun dirtyFlagAfterLoad() {
            val playerData = PlayerData.fromPlayer(player, "default")

            assertFalse(playerData.dirty)
        }
    }

    // ============================================================
    // Apply To Player Tests
    // ============================================================

    @Nested
    @DisplayName("Apply PlayerData To Player")
    inner class ApplyToPlayerTests {

        private lateinit var playerData: PlayerData
        private lateinit var defaultSettings: GroupSettings

        @BeforeEach
        fun setUpPlayerData() {
            playerData = PlayerData.empty(player.uniqueId, player.name, "default", GameMode.SURVIVAL)
            defaultSettings = GroupSettings()
        }

        @Test
        @DisplayName("should apply main inventory to player")
        fun applyMainInventory() {
            playerData.mainInventory[0] = ItemStack(Material.DIAMOND, 64)
            playerData.mainInventory[8] = ItemStack(Material.IRON_INGOT, 32)

            playerData.applyToPlayer(player, defaultSettings)

            assertEquals(Material.DIAMOND, player.inventory.getItem(0)?.type)
            assertEquals(64, player.inventory.getItem(0)?.amount)
            assertEquals(Material.IRON_INGOT, player.inventory.getItem(8)?.type)
        }

        @Test
        @DisplayName("should apply armor to player")
        fun applyArmor() {
            playerData.armorInventory[0] = ItemStack(Material.IRON_BOOTS)
            playerData.armorInventory[1] = ItemStack(Material.IRON_LEGGINGS)
            playerData.armorInventory[2] = ItemStack(Material.IRON_CHESTPLATE)
            playerData.armorInventory[3] = ItemStack(Material.IRON_HELMET)

            playerData.applyToPlayer(player, defaultSettings)

            assertEquals(Material.IRON_BOOTS, player.inventory.boots?.type)
            assertEquals(Material.IRON_LEGGINGS, player.inventory.leggings?.type)
            assertEquals(Material.IRON_CHESTPLATE, player.inventory.chestplate?.type)
            assertEquals(Material.IRON_HELMET, player.inventory.helmet?.type)
        }

        @Test
        @DisplayName("should apply offhand to player")
        fun applyOffhand() {
            playerData.offhand = ItemStack(Material.SHIELD)

            playerData.applyToPlayer(player, defaultSettings)

            assertEquals(Material.SHIELD, player.inventory.itemInOffHand.type)
        }

        @Test
        @DisplayName("should apply ender chest when enabled")
        fun applyEnderChestWhenEnabled() {
            playerData.enderChest[0] = ItemStack(Material.ENDER_PEARL, 16)
            playerData.enderChest[5] = ItemStack(Material.DIAMOND, 64)

            val settings = GroupSettings(saveEnderChest = true)
            playerData.applyToPlayer(player, settings)

            assertEquals(Material.ENDER_PEARL, player.enderChest.getItem(0)?.type)
            assertEquals(Material.DIAMOND, player.enderChest.getItem(5)?.type)
        }

        @Test
        @DisplayName("should not apply ender chest when disabled")
        fun notApplyEnderChestWhenDisabled() {
            // Set up existing ender chest content
            player.enderChest.setItem(0, ItemStack(Material.GOLD_INGOT))

            playerData.enderChest[0] = ItemStack(Material.ENDER_PEARL, 16)

            val settings = GroupSettings(saveEnderChest = false)
            playerData.applyToPlayer(player, settings)

            // Should still have original content
            assertEquals(Material.GOLD_INGOT, player.enderChest.getItem(0)?.type)
        }

        @Test
        @DisplayName("should clear player inventory before applying")
        fun clearInventoryBeforeApply() {
            // Give player some items
            player.inventory.setItem(5, ItemStack(Material.DIRT, 64))
            player.inventory.setItem(10, ItemStack(Material.COBBLESTONE, 64))

            // PlayerData only has item in slot 0
            playerData.mainInventory[0] = ItemStack(Material.DIAMOND)

            playerData.applyToPlayer(player, defaultSettings)

            // Old items should be cleared
            Assertions.assertNull(player.inventory.getItem(5))
            Assertions.assertNull(player.inventory.getItem(10))
        }
    }

    // ============================================================
    // Apply With Group Settings Tests
    // ============================================================

    @Nested
    @DisplayName("Apply With Group Settings")
    inner class ApplyWithGroupSettingsTests {

        private lateinit var playerData: PlayerData

        @BeforeEach
        fun setUpPlayerData() {
            playerData = PlayerData.empty(player.uniqueId, player.name, "default", GameMode.CREATIVE)
            playerData.health = 10.0
            playerData.foodLevel = 5
            playerData.saturation = 2.0f
            playerData.exhaustion = 3.0f
            playerData.level = 50
            playerData.experience = 0.75f
            playerData.totalExperience = 5345
            playerData.potionEffects.add(PotionEffect(PotionEffectType.SPEED, 1000, 2))
        }

        @Test
        @DisplayName("should apply health when saveHealth is true")
        fun applyHealthWhenEnabled() {
            val settings = GroupSettings(saveHealth = true)
            playerData.applyToPlayer(player, settings)

            assertEquals(10.0, player.health)
        }

        @Test
        @DisplayName("should not apply health when saveHealth is false")
        fun notApplyHealthWhenDisabled() {
            player.health = 20.0

            val settings = GroupSettings(saveHealth = false)
            playerData.applyToPlayer(player, settings)

            assertEquals(20.0, player.health)
        }

        @Test
        @DisplayName("should apply hunger when saveHunger is true")
        fun applyHungerWhenEnabled() {
            val settings = GroupSettings(saveHunger = true)
            playerData.applyToPlayer(player, settings)

            assertEquals(5, player.foodLevel)
        }

        @Test
        @DisplayName("should not apply hunger when saveHunger is false")
        fun notApplyHungerWhenDisabled() {
            player.foodLevel = 20

            val settings = GroupSettings(saveHunger = false)
            playerData.applyToPlayer(player, settings)

            assertEquals(20, player.foodLevel)
        }

        @Test
        @DisplayName("should apply saturation when saveSaturation is true")
        fun applySaturationWhenEnabled() {
            val settings = GroupSettings(saveSaturation = true)
            playerData.applyToPlayer(player, settings)

            assertEquals(2.0f, player.saturation)
        }

        @Test
        @DisplayName("should not apply saturation when saveSaturation is false")
        fun notApplySaturationWhenDisabled() {
            player.saturation = 5.0f

            val settings = GroupSettings(saveSaturation = false)
            playerData.applyToPlayer(player, settings)

            assertEquals(5.0f, player.saturation)
        }

        @Test
        @DisplayName("should apply exhaustion when saveExhaustion is true")
        fun applyExhaustionWhenEnabled() {
            val settings = GroupSettings(saveExhaustion = true)
            playerData.applyToPlayer(player, settings)

            assertEquals(3.0f, player.exhaustion)
        }

        @Test
        @DisplayName("should not apply exhaustion when saveExhaustion is false")
        fun notApplyExhaustionWhenDisabled() {
            player.exhaustion = 0.0f

            val settings = GroupSettings(saveExhaustion = false)
            playerData.applyToPlayer(player, settings)

            assertEquals(0.0f, player.exhaustion)
        }

        @Test
        @DisplayName("should apply experience when saveExperience is true")
        fun applyExperienceWhenEnabled() {
            val settings = GroupSettings(saveExperience = true)
            playerData.applyToPlayer(player, settings)

            assertEquals(50, player.level)
            assertEquals(0.75f, player.exp)
            assertEquals(5345, player.totalExperience)
        }

        @Test
        @DisplayName("should not apply experience when saveExperience is false")
        fun notApplyExperienceWhenDisabled() {
            player.level = 0
            player.exp = 0f
            player.totalExperience = 0

            val settings = GroupSettings(saveExperience = false)
            playerData.applyToPlayer(player, settings)

            assertEquals(0, player.level)
        }

        @Test
        @DisplayName("should apply potion effects when savePotionEffects is true")
        fun applyPotionEffectsWhenEnabled() {
            val settings = GroupSettings(savePotionEffects = true)
            playerData.applyToPlayer(player, settings)

            assertTrue(player.hasPotionEffect(PotionEffectType.SPEED))
        }

        @Test
        @DisplayName("should not apply potion effects when savePotionEffects is false")
        fun notApplyPotionEffectsWhenDisabled() {
            val settings = GroupSettings(savePotionEffects = false)
            playerData.applyToPlayer(player, settings)

            assertFalse(player.hasPotionEffect(PotionEffectType.SPEED))
        }

        @Test
        @DisplayName("should clear existing potion effects when applying new ones")
        fun clearExistingEffectsBeforeApply() {
            player.addPotionEffect(PotionEffect(PotionEffectType.REGENERATION, 1000, 1))

            val settings = GroupSettings(savePotionEffects = true)
            playerData.applyToPlayer(player, settings)

            assertFalse(player.hasPotionEffect(PotionEffectType.REGENERATION))
            assertTrue(player.hasPotionEffect(PotionEffectType.SPEED))
        }

        @Test
        @DisplayName("should apply game mode when saveGameMode is true")
        fun applyGameModeWhenEnabled() {
            player.gameMode = GameMode.SURVIVAL

            val settings = GroupSettings(saveGameMode = true)
            playerData.applyToPlayer(player, settings)

            assertEquals(GameMode.CREATIVE, player.gameMode)
        }

        @Test
        @DisplayName("should not apply game mode when saveGameMode is false")
        fun notApplyGameModeWhenDisabled() {
            player.gameMode = GameMode.SURVIVAL

            val settings = GroupSettings(saveGameMode = false)
            playerData.applyToPlayer(player, settings)

            assertEquals(GameMode.SURVIVAL, player.gameMode)
        }

        @Test
        @DisplayName("should respect all settings when all are disabled")
        fun respectAllDisabledSettings() {
            player.health = 20.0
            player.foodLevel = 20
            player.saturation = 5.0f
            player.exhaustion = 0.0f
            player.level = 0
            player.gameMode = GameMode.SURVIVAL

            val settings = GroupSettings(
                saveHealth = false,
                saveHunger = false,
                saveSaturation = false,
                saveExhaustion = false,
                saveExperience = false,
                savePotionEffects = false,
                saveEnderChest = false,
                saveGameMode = false
            )
            playerData.applyToPlayer(player, settings)

            assertEquals(20.0, player.health)
            assertEquals(20, player.foodLevel)
            assertEquals(5.0f, player.saturation)
            assertEquals(0.0f, player.exhaustion)
            assertEquals(0, player.level)
            assertEquals(GameMode.SURVIVAL, player.gameMode)
        }

        @Test
        @DisplayName("should clamp health to max health")
        fun clampHealthToMaxHealth() {
            playerData.health = 100.0  // Exceeds max health
            playerData.maxHealth = 20.0

            val settings = GroupSettings(saveHealth = true)
            playerData.applyToPlayer(player, settings)

            // Health should be clamped to max
            assertTrue(player.health <= 20.0)
        }
    }

    // ============================================================
    // Snapshot Conversion Tests
    // ============================================================

    @Nested
    @DisplayName("Convert To PlayerInventorySnapshot")
    inner class ToSnapshotTests {

        @Test
        @DisplayName("should convert all fields to snapshot")
        fun convertAllFieldsToSnapshot() {
            val uuid = UUID.randomUUID()
            val playerData = PlayerData.empty(uuid, "TestPlayer", "world_nether", GameMode.ADVENTURE)

            playerData.mainInventory[0] = ItemStack(Material.DIAMOND, 64)
            playerData.armorInventory[3] = ItemStack(Material.DIAMOND_HELMET)
            playerData.offhand = ItemStack(Material.SHIELD)
            playerData.enderChest[0] = ItemStack(Material.ENDER_PEARL, 16)
            playerData.health = 15.0
            playerData.maxHealth = 40.0
            playerData.foodLevel = 10
            playerData.saturation = 3.0f
            playerData.exhaustion = 1.5f
            playerData.experience = 0.25f
            playerData.level = 25
            playerData.totalExperience = 1000
            playerData.potionEffects.add(PotionEffect(PotionEffectType.SPEED, 600, 1))

            val snapshot = playerData.toSnapshot()

            assertEquals(uuid, snapshot.uuid)
            assertEquals("TestPlayer", snapshot.playerName)
            assertEquals("world_nether", snapshot.group)
            assertEquals(GameMode.ADVENTURE, snapshot.gameMode)

            assertEquals(1, snapshot.contents.main.size)
            assertEquals(Material.DIAMOND, snapshot.contents.main[0]?.type)

            assertEquals(1, snapshot.contents.armor.size)
            assertEquals(Material.DIAMOND_HELMET, snapshot.contents.armor[3]?.type)

            assertEquals(Material.SHIELD, snapshot.contents.offhand?.type)

            assertEquals(1, snapshot.contents.enderChest.size)
            assertEquals(Material.ENDER_PEARL, snapshot.contents.enderChest[0]?.type)

            assertEquals(15.0, snapshot.health)
            assertEquals(40.0, snapshot.maxHealth)
            assertEquals(10, snapshot.foodLevel)
            assertEquals(3.0f, snapshot.saturation)
            assertEquals(1.5f, snapshot.exhaustion)
            assertEquals(0.25f, snapshot.experience)
            assertEquals(25, snapshot.level)
            assertEquals(1000, snapshot.totalExperience)

            assertEquals(1, snapshot.potionEffects.size)
            assertEquals(PotionEffectType.SPEED, snapshot.potionEffects[0].type)
        }

        @Test
        @DisplayName("should clone items in snapshot")
        fun cloneItemsInSnapshot() {
            val playerData = PlayerData.empty(UUID.randomUUID(), "Test", "default", GameMode.SURVIVAL)
            playerData.mainInventory[0] = ItemStack(Material.DIAMOND, 32)

            val snapshot = playerData.toSnapshot()

            // Modify original
            playerData.mainInventory[0]?.amount = 1

            // Snapshot should have original amount
            assertEquals(32, snapshot.contents.main[0]?.amount)
        }

        @Test
        @DisplayName("should preserve timestamp")
        fun preserveTimestamp() {
            val timestamp = Instant.now().minusSeconds(3600)
            val playerData = PlayerData.empty(UUID.randomUUID(), "Test", "default", GameMode.SURVIVAL)
            playerData.timestamp = timestamp

            val snapshot = playerData.toSnapshot()

            assertEquals(timestamp, snapshot.timestamp)
        }
    }

    // ============================================================
    // Create From Snapshot Tests
    // ============================================================

    @Nested
    @DisplayName("Create From PlayerInventorySnapshot")
    inner class FromSnapshotTests {

        @Test
        @DisplayName("should create PlayerData from snapshot")
        fun createFromSnapshot() {
            val uuid = UUID.randomUUID()
            val timestamp = Instant.now()

            val contents = InventoryContents(
                main = mapOf(0 to ItemStack(Material.DIAMOND, 64)),
                armor = mapOf(3 to ItemStack(Material.DIAMOND_HELMET)),
                offhand = ItemStack(Material.SHIELD),
                enderChest = mapOf(0 to ItemStack(Material.ENDER_PEARL, 16))
            )

            val snapshot = PlayerInventorySnapshot(
                uuid = uuid,
                playerName = "SnapshotPlayer",
                group = "creative_world",
                gameMode = GameMode.CREATIVE,
                contents = contents,
                health = 18.0,
                maxHealth = 30.0,
                foodLevel = 15,
                saturation = 4.0f,
                exhaustion = 2.0f,
                experience = 0.5f,
                level = 40,
                totalExperience = 2500,
                potionEffects = listOf(PotionEffect(PotionEffectType.STRENGTH, 1200, 2)),
                timestamp = timestamp
            )

            val playerData = PlayerData.fromSnapshot(snapshot)

            assertEquals(uuid, playerData.uuid)
            assertEquals("SnapshotPlayer", playerData.playerName)
            assertEquals("creative_world", playerData.group)
            assertEquals(GameMode.CREATIVE, playerData.gameMode)

            assertEquals(1, playerData.mainInventory.size)
            assertEquals(Material.DIAMOND, playerData.mainInventory[0]?.type)

            assertEquals(1, playerData.armorInventory.size)
            assertEquals(Material.DIAMOND_HELMET, playerData.armorInventory[3]?.type)

            assertEquals(Material.SHIELD, playerData.offhand?.type)

            assertEquals(1, playerData.enderChest.size)
            assertEquals(Material.ENDER_PEARL, playerData.enderChest[0]?.type)

            assertEquals(18.0, playerData.health)
            assertEquals(30.0, playerData.maxHealth)
            assertEquals(15, playerData.foodLevel)
            assertEquals(4.0f, playerData.saturation)
            assertEquals(2.0f, playerData.exhaustion)
            assertEquals(0.5f, playerData.experience)
            assertEquals(40, playerData.level)
            assertEquals(2500, playerData.totalExperience)

            assertEquals(1, playerData.potionEffects.size)
            assertEquals(PotionEffectType.STRENGTH, playerData.potionEffects[0].type)

            assertEquals(timestamp, playerData.timestamp)
            assertFalse(playerData.dirty)
        }

        @Test
        @DisplayName("should clone items from snapshot")
        fun cloneItemsFromSnapshot() {
            val mutableItem = ItemStack(Material.DIAMOND, 32)
            val contents = InventoryContents(
                main = mapOf(0 to mutableItem),
                armor = emptyMap(),
                offhand = null,
                enderChest = emptyMap()
            )

            val snapshot = PlayerInventorySnapshot(
                uuid = UUID.randomUUID(),
                playerName = "Test",
                group = "default",
                gameMode = GameMode.SURVIVAL,
                contents = contents,
                health = 20.0,
                maxHealth = 20.0,
                foodLevel = 20,
                saturation = 5.0f,
                exhaustion = 0.0f,
                experience = 0.0f,
                level = 0,
                totalExperience = 0,
                potionEffects = emptyList(),
                timestamp = Instant.now()
            )

            val playerData = PlayerData.fromSnapshot(snapshot)

            // Modify snapshot item
            mutableItem.amount = 1

            // PlayerData should have original amount
            assertEquals(32, playerData.mainInventory[0]?.amount)
        }

        @Test
        @DisplayName("should round-trip PlayerData through snapshot")
        fun roundTripThroughSnapshot() {
            val original = PlayerData.empty(UUID.randomUUID(), "RoundTrip", "survival", GameMode.SURVIVAL)
            original.mainInventory[5] = ItemStack(Material.GOLDEN_APPLE, 8)
            original.armorInventory[2] = ItemStack(Material.NETHERITE_CHESTPLATE)
            original.offhand = ItemStack(Material.TOTEM_OF_UNDYING)
            original.enderChest[10] = ItemStack(Material.SHULKER_BOX)
            original.health = 12.5
            original.level = 100
            original.potionEffects.add(PotionEffect(PotionEffectType.FIRE_RESISTANCE, 6000, 0))

            val snapshot = original.toSnapshot()
            val restored = PlayerData.fromSnapshot(snapshot)

            assertEquals(original.uuid, restored.uuid)
            assertEquals(original.playerName, restored.playerName)
            assertEquals(original.group, restored.group)
            assertEquals(original.gameMode, restored.gameMode)
            assertEquals(original.health, restored.health)
            assertEquals(original.level, restored.level)

            assertEquals(original.mainInventory.size, restored.mainInventory.size)
            assertEquals(original.mainInventory[5]?.type, restored.mainInventory[5]?.type)

            assertEquals(original.potionEffects.size, restored.potionEffects.size)
        }
    }

    // ============================================================
    // Inventory Contents Management Tests
    // ============================================================

    @Nested
    @DisplayName("Inventory Contents Management")
    inner class InventoryContentsTests {

        private lateinit var playerData: PlayerData

        @BeforeEach
        fun setUpPlayerData() {
            playerData = PlayerData.empty(UUID.randomUUID(), "Test", "default", GameMode.SURVIVAL)
        }

        @Test
        @DisplayName("should manage main inventory slots 0-35")
        fun manageMainInventorySlots() {
            // Add items to various slots
            playerData.mainInventory[0] = ItemStack(Material.DIAMOND, 1)
            playerData.mainInventory[17] = ItemStack(Material.IRON_INGOT, 32)
            playerData.mainInventory[35] = ItemStack(Material.GOLD_INGOT, 64)

            assertEquals(3, playerData.mainInventory.size)
            assertEquals(Material.DIAMOND, playerData.mainInventory[0]?.type)
            assertEquals(Material.IRON_INGOT, playerData.mainInventory[17]?.type)
            assertEquals(Material.GOLD_INGOT, playerData.mainInventory[35]?.type)
        }

        @Test
        @DisplayName("should manage armor slots 0-3")
        fun manageArmorSlots() {
            playerData.armorInventory[0] = ItemStack(Material.LEATHER_BOOTS)
            playerData.armorInventory[1] = ItemStack(Material.LEATHER_LEGGINGS)
            playerData.armorInventory[2] = ItemStack(Material.LEATHER_CHESTPLATE)
            playerData.armorInventory[3] = ItemStack(Material.LEATHER_HELMET)

            assertEquals(4, playerData.armorInventory.size)
            assertEquals(Material.LEATHER_BOOTS, playerData.armorInventory[0]?.type)
            assertEquals(Material.LEATHER_HELMET, playerData.armorInventory[3]?.type)
        }

        @Test
        @DisplayName("should manage offhand item")
        fun manageOffhandItem() {
            Assertions.assertNull(playerData.offhand)

            playerData.offhand = ItemStack(Material.SHIELD)
            assertEquals(Material.SHIELD, playerData.offhand?.type)

            playerData.offhand = ItemStack(Material.MAP)
            assertEquals(Material.MAP, playerData.offhand?.type)

            playerData.offhand = null
            Assertions.assertNull(playerData.offhand)
        }

        @Test
        @DisplayName("should manage ender chest slots 0-26")
        fun manageEnderChestSlots() {
            playerData.enderChest[0] = ItemStack(Material.ENDER_PEARL, 16)
            playerData.enderChest[13] = ItemStack(Material.ENDER_EYE, 8)
            playerData.enderChest[26] = ItemStack(Material.END_CRYSTAL, 4)

            assertEquals(3, playerData.enderChest.size)
            assertEquals(Material.ENDER_PEARL, playerData.enderChest[0]?.type)
            assertEquals(Material.END_CRYSTAL, playerData.enderChest[26]?.type)
        }

        @Test
        @DisplayName("should overwrite existing items in slot")
        fun overwriteExistingItems() {
            playerData.mainInventory[0] = ItemStack(Material.DIRT, 64)
            playerData.mainInventory[0] = ItemStack(Material.DIAMOND, 32)

            assertEquals(Material.DIAMOND, playerData.mainInventory[0]?.type)
            assertEquals(32, playerData.mainInventory[0]?.amount)
        }

        @Test
        @DisplayName("should remove items from slot")
        fun removeItemsFromSlot() {
            playerData.mainInventory[0] = ItemStack(Material.DIAMOND)
            playerData.mainInventory.remove(0)

            assertFalse(playerData.mainInventory.containsKey(0))
        }
    }

    // ============================================================
    // Health, Hunger, Saturation, Exhaustion Tests
    // ============================================================

    @Nested
    @DisplayName("Health, Hunger, Saturation, Exhaustion Fields")
    inner class PlayerStateFieldsTests {

        private lateinit var playerData: PlayerData

        @BeforeEach
        fun setUpPlayerData() {
            playerData = PlayerData.empty(UUID.randomUUID(), "Test", "default", GameMode.SURVIVAL)
        }

        @Test
        @DisplayName("should store and retrieve health values")
        fun healthValues() {
            playerData.health = 15.5
            playerData.maxHealth = 40.0

            assertEquals(15.5, playerData.health)
            assertEquals(40.0, playerData.maxHealth)
        }

        @Test
        @DisplayName("should store and retrieve hunger values")
        fun hungerValues() {
            playerData.foodLevel = 10

            assertEquals(10, playerData.foodLevel)
        }

        @Test
        @DisplayName("should store and retrieve saturation values")
        fun saturationValues() {
            playerData.saturation = 3.5f

            assertEquals(3.5f, playerData.saturation)
        }

        @Test
        @DisplayName("should store and retrieve exhaustion values")
        fun exhaustionValues() {
            playerData.exhaustion = 2.75f

            assertEquals(2.75f, playerData.exhaustion)
        }

        @Test
        @DisplayName("should handle edge case values")
        fun edgeCaseValues() {
            playerData.health = 0.0
            playerData.foodLevel = 0
            playerData.saturation = 0.0f
            playerData.exhaustion = 4.0f  // Max exhaustion

            assertEquals(0.0, playerData.health)
            assertEquals(0, playerData.foodLevel)
            assertEquals(0.0f, playerData.saturation)
            assertEquals(4.0f, playerData.exhaustion)
        }
    }

    // ============================================================
    // Experience Tests
    // ============================================================

    @Nested
    @DisplayName("Experience Fields")
    inner class ExperienceTests {

        private lateinit var playerData: PlayerData

        @BeforeEach
        fun setUpPlayerData() {
            playerData = PlayerData.empty(UUID.randomUUID(), "Test", "default", GameMode.SURVIVAL)
        }

        @Test
        @DisplayName("should store and retrieve level")
        fun levelValues() {
            playerData.level = 100

            assertEquals(100, playerData.level)
        }

        @Test
        @DisplayName("should store and retrieve exp progress")
        fun expProgress() {
            playerData.experience = 0.99f

            assertEquals(0.99f, playerData.experience)
        }

        @Test
        @DisplayName("should store and retrieve total experience")
        fun totalExperience() {
            playerData.totalExperience = 1000000

            assertEquals(1000000, playerData.totalExperience)
        }

        @Test
        @DisplayName("should handle zero experience")
        fun zeroExperience() {
            playerData.level = 0
            playerData.experience = 0.0f
            playerData.totalExperience = 0

            assertEquals(0, playerData.level)
            assertEquals(0.0f, playerData.experience)
            assertEquals(0, playerData.totalExperience)
        }
    }

    // ============================================================
    // Potion Effects Tests
    // ============================================================

    @Nested
    @DisplayName("Potion Effects List")
    inner class PotionEffectsTests {

        private lateinit var playerData: PlayerData

        @BeforeEach
        fun setUpPlayerData() {
            playerData = PlayerData.empty(UUID.randomUUID(), "Test", "default", GameMode.SURVIVAL)
        }

        @Test
        @DisplayName("should add potion effects")
        fun addPotionEffects() {
            playerData.potionEffects.add(PotionEffect(PotionEffectType.SPEED, 600, 1))
            playerData.potionEffects.add(PotionEffect(PotionEffectType.STRENGTH, 1200, 2))

            assertEquals(2, playerData.potionEffects.size)
        }

        @Test
        @DisplayName("should store effect properties correctly")
        fun storeEffectProperties() {
            val effect = PotionEffect(PotionEffectType.INVISIBILITY, 6000, 0, true, false)
            playerData.potionEffects.add(effect)

            val stored = playerData.potionEffects[0]
            assertEquals(PotionEffectType.INVISIBILITY, stored.type)
            assertEquals(6000, stored.duration)
            assertEquals(0, stored.amplifier)
            assertTrue(stored.isAmbient)
            assertFalse(stored.hasParticles())
        }

        @Test
        @DisplayName("should remove potion effects")
        fun removePotionEffects() {
            val effect = PotionEffect(PotionEffectType.SPEED, 600, 1)
            playerData.potionEffects.add(effect)
            playerData.potionEffects.remove(effect)

            assertTrue(playerData.potionEffects.isEmpty())
        }

        @Test
        @DisplayName("should support multiple effects of different types")
        fun multipleEffectTypes() {
            playerData.potionEffects.addAll(
                listOf(
                    PotionEffect(PotionEffectType.SPEED, 100, 0),
                    PotionEffect(PotionEffectType.STRENGTH, 200, 1),
                    PotionEffect(PotionEffectType.REGENERATION, 300, 2),
                    PotionEffect(PotionEffectType.FIRE_RESISTANCE, 400, 0),
                    PotionEffect(PotionEffectType.WATER_BREATHING, 500, 0)
                )
            )

            assertEquals(5, playerData.potionEffects.size)
        }
    }

    // ============================================================
    // GameMode Handling Tests
    // ============================================================

    @Nested
    @DisplayName("GameMode Handling")
    inner class GameModeTests {

        @Test
        @DisplayName("should store SURVIVAL game mode")
        fun survivalMode() {
            val playerData = PlayerData.empty(UUID.randomUUID(), "Test", "default", GameMode.SURVIVAL)
            assertEquals(GameMode.SURVIVAL, playerData.gameMode)
        }

        @Test
        @DisplayName("should store CREATIVE game mode")
        fun creativeMode() {
            val playerData = PlayerData.empty(UUID.randomUUID(), "Test", "default", GameMode.CREATIVE)
            assertEquals(GameMode.CREATIVE, playerData.gameMode)
        }

        @Test
        @DisplayName("should store ADVENTURE game mode")
        fun adventureMode() {
            val playerData = PlayerData.empty(UUID.randomUUID(), "Test", "default", GameMode.ADVENTURE)
            assertEquals(GameMode.ADVENTURE, playerData.gameMode)
        }

        @Test
        @DisplayName("should store SPECTATOR game mode")
        fun spectatorMode() {
            val playerData = PlayerData.empty(UUID.randomUUID(), "Test", "default", GameMode.SPECTATOR)
            assertEquals(GameMode.SPECTATOR, playerData.gameMode)
        }

        @Test
        @DisplayName("should update game mode")
        fun updateGameMode() {
            val playerData = PlayerData.empty(UUID.randomUUID(), "Test", "default", GameMode.SURVIVAL)
            playerData.gameMode = GameMode.CREATIVE

            assertEquals(GameMode.CREATIVE, playerData.gameMode)
        }
    }

    // ============================================================
    // Timestamp Tracking Tests
    // ============================================================

    @Nested
    @DisplayName("Timestamp Tracking")
    inner class TimestampTests {

        @Test
        @DisplayName("should set timestamp on creation")
        fun timestampOnCreation() {
            val before = Instant.now()
            val playerData = PlayerData.empty(UUID.randomUUID(), "Test", "default", GameMode.SURVIVAL)
            val after = Instant.now()

            Assertions.assertNotNull(playerData.timestamp)
            assertTrue(playerData.timestamp >= before)
            assertTrue(playerData.timestamp <= after)
        }

        @Test
        @DisplayName("should update timestamp on load from player")
        fun timestampOnLoadFromPlayer() {
            val before = Instant.now()
            val playerData = PlayerData.fromPlayer(player, "default")
            val after = Instant.now()

            assertTrue(playerData.timestamp >= before)
            assertTrue(playerData.timestamp <= after)
        }

        @Test
        @DisplayName("should allow manual timestamp update")
        fun manualTimestampUpdate() {
            val playerData = PlayerData.empty(UUID.randomUUID(), "Test", "default", GameMode.SURVIVAL)
            val newTimestamp = Instant.now().plusSeconds(3600)

            playerData.timestamp = newTimestamp

            assertEquals(newTimestamp, playerData.timestamp)
        }

        @Test
        @DisplayName("should preserve timestamp from snapshot")
        fun preserveTimestampFromSnapshot() {
            val originalTimestamp = Instant.now().minusSeconds(86400) // 24 hours ago
            val snapshot = PlayerInventorySnapshot(
                uuid = UUID.randomUUID(),
                playerName = "Test",
                group = "default",
                gameMode = GameMode.SURVIVAL,
                contents = InventoryContents.empty(),
                health = 20.0,
                maxHealth = 20.0,
                foodLevel = 20,
                saturation = 5.0f,
                exhaustion = 0.0f,
                experience = 0.0f,
                level = 0,
                totalExperience = 0,
                potionEffects = emptyList(),
                timestamp = originalTimestamp
            )

            val playerData = PlayerData.fromSnapshot(snapshot)

            assertEquals(originalTimestamp, playerData.timestamp)
        }
    }

    // ============================================================
    // Clear/Reset Operations Tests
    // ============================================================

    @Nested
    @DisplayName("Clear/Reset Operations")
    inner class ClearResetTests {

        private lateinit var playerData: PlayerData

        @BeforeEach
        fun setUpPlayerData() {
            playerData = PlayerData.empty(UUID.randomUUID(), "Test", "default", GameMode.SURVIVAL)

            // Populate with data
            playerData.mainInventory[0] = ItemStack(Material.DIAMOND, 64)
            playerData.mainInventory[10] = ItemStack(Material.IRON_INGOT, 32)
            playerData.armorInventory[3] = ItemStack(Material.DIAMOND_HELMET)
            playerData.offhand = ItemStack(Material.SHIELD)
            playerData.enderChest[0] = ItemStack(Material.ENDER_PEARL, 16)
            playerData.health = 10.0
            playerData.foodLevel = 5
            playerData.level = 50
            playerData.potionEffects.add(PotionEffect(PotionEffectType.SPEED, 1000, 1))
        }

        @Test
        @DisplayName("should clear all inventory contents")
        fun clearInventory() {
            playerData.clearInventory()

            assertTrue(playerData.mainInventory.isEmpty())
            assertTrue(playerData.armorInventory.isEmpty())
            Assertions.assertNull(playerData.offhand)
            assertTrue(playerData.enderChest.isEmpty())
        }

        @Test
        @DisplayName("should set dirty flag when clearing inventory")
        fun dirtyFlagOnClearInventory() {
            playerData.dirty = false
            playerData.clearInventory()

            assertTrue(playerData.dirty)
        }

        @Test
        @DisplayName("should not affect other fields when clearing inventory")
        fun preserveOtherFieldsOnClearInventory() {
            playerData.clearInventory()

            assertEquals(10.0, playerData.health)
            assertEquals(5, playerData.foodLevel)
            assertEquals(50, playerData.level)
            assertEquals(1, playerData.potionEffects.size)
        }

        @Test
        @DisplayName("should clear all potion effects")
        fun clearEffects() {
            playerData.clearEffects()

            assertTrue(playerData.potionEffects.isEmpty())
        }

        @Test
        @DisplayName("should set dirty flag when clearing effects")
        fun dirtyFlagOnClearEffects() {
            playerData.dirty = false
            playerData.clearEffects()

            assertTrue(playerData.dirty)
        }

        @Test
        @DisplayName("should reset player state to defaults")
        fun resetState() {
            playerData.resetState()

            assertEquals(20.0, playerData.health)
            assertEquals(20.0, playerData.maxHealth)
            assertEquals(20, playerData.foodLevel)
            assertEquals(5.0f, playerData.saturation)
            assertEquals(0.0f, playerData.exhaustion)
            assertEquals(0.0f, playerData.experience)
            assertEquals(0, playerData.level)
            assertEquals(0, playerData.totalExperience)
            assertTrue(playerData.potionEffects.isEmpty())
        }

        @Test
        @DisplayName("should set dirty flag when resetting state")
        fun dirtyFlagOnResetState() {
            playerData.dirty = false
            playerData.resetState()

            assertTrue(playerData.dirty)
        }

        @Test
        @DisplayName("should not affect inventory when resetting state")
        fun preserveInventoryOnResetState() {
            playerData.resetState()

            assertEquals(2, playerData.mainInventory.size)
            assertEquals(1, playerData.armorInventory.size)
            Assertions.assertNotNull(playerData.offhand)
            assertEquals(1, playerData.enderChest.size)
        }
    }

    // ============================================================
    // Clone/Copy Operations Tests
    // ============================================================

    @Nested
    @DisplayName("Clone/Copy Operations")
    inner class CloneCopyTests {

        @Test
        @DisplayName("should clone items when loading from player")
        fun cloneOnLoadFromPlayer() {
            val originalItem = ItemStack(Material.DIAMOND, 64)
            player.inventory.setItem(0, originalItem)

            val playerData = PlayerData.fromPlayer(player, "default")

            // Modify original
            originalItem.amount = 1

            // PlayerData should have original amount
            assertEquals(64, playerData.mainInventory[0]?.amount)
        }

        @Test
        @DisplayName("should clone items when applying to player")
        fun cloneOnApplyToPlayer() {
            val playerData = PlayerData.empty(player.uniqueId, player.name, "default", GameMode.SURVIVAL)
            playerData.mainInventory[0] = ItemStack(Material.DIAMOND, 64)

            playerData.applyToPlayer(player, GroupSettings())

            // Modify playerData
            playerData.mainInventory[0]?.amount = 1

            // Player should have original amount
            assertEquals(64, player.inventory.getItem(0)?.amount)
        }

        @Test
        @DisplayName("should clone items in toSnapshot")
        fun cloneInToSnapshot() {
            val playerData = PlayerData.empty(UUID.randomUUID(), "Test", "default", GameMode.SURVIVAL)
            val item = ItemStack(Material.DIAMOND, 64)
            playerData.mainInventory[0] = item

            val snapshot = playerData.toSnapshot()

            // Modify original
            item.amount = 1

            // Snapshot should have original amount
            assertEquals(64, snapshot.contents.main[0]?.amount)
        }

        @Test
        @DisplayName("should clone items in loadFromSnapshot")
        fun cloneInLoadFromSnapshot() {
            val mutableItem = ItemStack(Material.DIAMOND, 64)
            val contents = InventoryContents(
                main = mapOf(0 to mutableItem),
                armor = emptyMap(),
                offhand = null,
                enderChest = emptyMap()
            )
            val snapshot = PlayerInventorySnapshot(
                uuid = UUID.randomUUID(),
                playerName = "Test",
                group = "default",
                gameMode = GameMode.SURVIVAL,
                contents = contents,
                health = 20.0,
                maxHealth = 20.0,
                foodLevel = 20,
                saturation = 5.0f,
                exhaustion = 0.0f,
                experience = 0.0f,
                level = 0,
                totalExperience = 0,
                potionEffects = emptyList(),
                timestamp = Instant.now()
            )

            val playerData = PlayerData.fromSnapshot(snapshot)

            // Modify original
            mutableItem.amount = 1

            // PlayerData should have original amount
            assertEquals(64, playerData.mainInventory[0]?.amount)
        }

        @Test
        @DisplayName("should create independent copies via snapshot round-trip")
        fun independentCopiesViaSnapshot() {
            val original = PlayerData.empty(UUID.randomUUID(), "Original", "default", GameMode.SURVIVAL)
            original.mainInventory[0] = ItemStack(Material.DIAMOND, 64)
            original.health = 15.0

            val snapshot = original.toSnapshot()
            val copy = PlayerData.fromSnapshot(snapshot)

            // Modify copy
            copy.mainInventory[0]?.amount = 1
            copy.health = 20.0

            // Original should be unchanged
            assertEquals(64, original.mainInventory[0]?.amount)
            assertEquals(15.0, original.health)
        }
    }

    // ============================================================
    // Dirty Flag Tests
    // ============================================================

    @Nested
    @DisplayName("Dirty Flag Tracking")
    inner class DirtyFlagTests {

        @Test
        @DisplayName("should be false on empty creation")
        fun falseOnEmptyCreation() {
            val playerData = PlayerData.empty(UUID.randomUUID(), "Test", "default", GameMode.SURVIVAL)
            assertFalse(playerData.dirty)
        }

        @Test
        @DisplayName("should be false after loading from player")
        fun falseAfterLoadFromPlayer() {
            val playerData = PlayerData.fromPlayer(player, "default")
            assertFalse(playerData.dirty)
        }

        @Test
        @DisplayName("should be false after loading from snapshot")
        fun falseAfterLoadFromSnapshot() {
            val snapshot = PlayerInventorySnapshot.empty(UUID.randomUUID(), "Test", "default", GameMode.SURVIVAL)
            val playerData = PlayerData.fromSnapshot(snapshot)
            assertFalse(playerData.dirty)
        }

        @Test
        @DisplayName("should be true after clearInventory")
        fun trueAfterClearInventory() {
            val playerData = PlayerData.empty(UUID.randomUUID(), "Test", "default", GameMode.SURVIVAL)
            playerData.clearInventory()
            assertTrue(playerData.dirty)
        }

        @Test
        @DisplayName("should be true after clearEffects")
        fun trueAfterClearEffects() {
            val playerData = PlayerData.empty(UUID.randomUUID(), "Test", "default", GameMode.SURVIVAL)
            playerData.clearEffects()
            assertTrue(playerData.dirty)
        }

        @Test
        @DisplayName("should be true after resetState")
        fun trueAfterResetState() {
            val playerData = PlayerData.empty(UUID.randomUUID(), "Test", "default", GameMode.SURVIVAL)
            playerData.resetState()
            assertTrue(playerData.dirty)
        }

        @Test
        @DisplayName("should be settable manually")
        fun manuallySettable() {
            val playerData = PlayerData.empty(UUID.randomUUID(), "Test", "default", GameMode.SURVIVAL)

            playerData.dirty = true
            assertTrue(playerData.dirty)

            playerData.dirty = false
            assertFalse(playerData.dirty)
        }
    }

    // ============================================================
    // Edge Case Tests
    // ============================================================

    @Nested
    @DisplayName("Edge Cases")
    inner class EdgeCaseTests {

        @Test
        @DisplayName("should handle empty player inventory")
        fun emptyPlayerInventory() {
            val playerData = PlayerData.fromPlayer(player, "default")

            assertTrue(playerData.mainInventory.isEmpty())
            assertTrue(playerData.armorInventory.isEmpty())
            Assertions.assertNull(playerData.offhand)
        }

        @Test
        @DisplayName("should handle player with only some armor slots filled")
        fun partialArmorSlots() {
            player.inventory.setHelmet(ItemStack(Material.DIAMOND_HELMET))
            player.inventory.setBoots(ItemStack(Material.DIAMOND_BOOTS))

            val playerData = PlayerData.fromPlayer(player, "default")

            assertEquals(2, playerData.armorInventory.size)
            assertTrue(playerData.armorInventory.containsKey(0))  // boots
            assertTrue(playerData.armorInventory.containsKey(3))  // helmet
            assertFalse(playerData.armorInventory.containsKey(1)) // no leggings
            assertFalse(playerData.armorInventory.containsKey(2)) // no chestplate
        }

        @Test
        @DisplayName("should handle empty snapshot")
        fun emptySnapshot() {
            val snapshot = PlayerInventorySnapshot.empty(UUID.randomUUID(), "Test", "default", GameMode.SURVIVAL)
            val playerData = PlayerData.fromSnapshot(snapshot)

            assertTrue(playerData.mainInventory.isEmpty())
            assertTrue(playerData.armorInventory.isEmpty())
            Assertions.assertNull(playerData.offhand)
            assertTrue(playerData.enderChest.isEmpty())
            assertTrue(playerData.potionEffects.isEmpty())
        }

        @Test
        @DisplayName("should handle special characters in player name")
        fun specialCharactersInName() {
            val playerData = PlayerData.empty(
                UUID.randomUUID(),
                "Player_123",
                "world-nether",
                GameMode.SURVIVAL
            )

            assertEquals("Player_123", playerData.playerName)
            assertEquals("world-nether", playerData.group)
        }

        @Test
        @DisplayName("should handle full inventory")
        fun fullInventory() {
            // Fill all 36 main inventory slots
            for (i in 0..35) {
                player.inventory.setItem(i, ItemStack(Material.DIRT, 64))
            }
            // Fill all armor slots
            player.inventory.setHelmet(ItemStack(Material.IRON_HELMET))
            player.inventory.setChestplate(ItemStack(Material.IRON_CHESTPLATE))
            player.inventory.setLeggings(ItemStack(Material.IRON_LEGGINGS))
            player.inventory.setBoots(ItemStack(Material.IRON_BOOTS))
            // Set offhand
            player.inventory.setItemInOffHand(ItemStack(Material.SHIELD))
            // Fill ender chest
            for (i in 0..26) {
                player.enderChest.setItem(i, ItemStack(Material.DIAMOND))
            }

            val playerData = PlayerData.fromPlayer(player, "default")

            assertEquals(36, playerData.mainInventory.size)
            assertEquals(4, playerData.armorInventory.size)
            Assertions.assertNotNull(playerData.offhand)
            assertEquals(27, playerData.enderChest.size)
        }
    }

    // ============================================================
    // Extended Data Tests (Statistics, Advancements, Recipes)
    // ============================================================

    @Nested
    @DisplayName("Extended Data Fields")
    inner class ExtendedDataTests {

        private lateinit var playerData: PlayerData

        @BeforeEach
        fun setUpPlayerData() {
            playerData = PlayerData.empty(UUID.randomUUID(), "Test", "default", GameMode.SURVIVAL)
        }

        @Test
        @DisplayName("should have null statistics by default")
        fun statisticsNullByDefault() {
            Assertions.assertNull(playerData.statistics)
        }

        @Test
        @DisplayName("should have null advancements by default")
        fun advancementsNullByDefault() {
            Assertions.assertNull(playerData.advancements)
        }

        @Test
        @DisplayName("should have null recipes by default")
        fun recipesNullByDefault() {
            Assertions.assertNull(playerData.recipes)
        }

        @Test
        @DisplayName("should store and retrieve statistics")
        fun storeAndRetrieveStatistics() {
            val stats = sh.pcx.xinventories.internal.model.PlayerStatistics(mapOf(
                "DEATHS" to 5,
                "MINE_BLOCK:DIAMOND_ORE" to 100
            ))
            playerData.statistics = stats

            Assertions.assertNotNull(playerData.statistics)
            assertEquals(5, playerData.statistics!!.get("DEATHS"))
            assertEquals(100, playerData.statistics!!.get("MINE_BLOCK:DIAMOND_ORE"))
        }

        @Test
        @DisplayName("should store and retrieve advancements")
        fun storeAndRetrieveAdvancements() {
            val advancements = sh.pcx.xinventories.internal.model.PlayerAdvancements(setOf(
                "minecraft:story/mine_stone",
                "minecraft:nether/return_to_sender"
            ))
            playerData.advancements = advancements

            Assertions.assertNotNull(playerData.advancements)
            assertTrue(playerData.advancements!!.isCompleted("minecraft:story/mine_stone"))
            assertTrue(playerData.advancements!!.isCompleted("minecraft:nether/return_to_sender"))
        }

        @Test
        @DisplayName("should store and retrieve recipes")
        fun storeAndRetrieveRecipes() {
            val recipes = setOf("minecraft:oak_planks", "minecraft:crafting_table")
            playerData.recipes = recipes

            Assertions.assertNotNull(playerData.recipes)
            assertTrue(playerData.recipes!!.contains("minecraft:oak_planks"))
            assertTrue(playerData.recipes!!.contains("minecraft:crafting_table"))
        }

        @Test
        @DisplayName("should reset extended data on resetState")
        fun resetExtendedDataOnResetState() {
            playerData.statistics = sh.pcx.xinventories.internal.model.PlayerStatistics(mapOf("DEATHS" to 5))
            playerData.advancements = sh.pcx.xinventories.internal.model.PlayerAdvancements(setOf("minecraft:story/mine_stone"))
            playerData.recipes = setOf("minecraft:oak_planks")

            playerData.resetState()

            Assertions.assertNull(playerData.statistics)
            Assertions.assertNull(playerData.advancements)
            Assertions.assertNull(playerData.recipes)
        }
    }

    // ============================================================
    // Extended Data with Group Settings Tests
    // ============================================================

    @Nested
    @DisplayName("Extended Data with Group Settings")
    inner class ExtendedDataWithSettingsTests {

        private lateinit var playerData: PlayerData

        @BeforeEach
        fun setUpPlayerData() {
            playerData = PlayerData.empty(player.uniqueId, player.name, "default", GameMode.SURVIVAL)
        }

        @Test
        @DisplayName("should not apply statistics when saveStatistics is false")
        fun notApplyStatisticsWhenDisabled() {
            playerData.statistics = sh.pcx.xinventories.internal.model.PlayerStatistics(mapOf("DEATHS" to 100))

            val settings = GroupSettings(saveStatistics = false)
            playerData.applyToPlayer(player, settings)

            // Statistics should not be applied (player statistics remain unchanged)
            // Note: We can't directly verify player statistics in MockBukkit without
            // setting them first, so we just verify no exception is thrown
        }

        @Test
        @DisplayName("should not apply advancements when saveAdvancements is false")
        fun notApplyAdvancementsWhenDisabled() {
            playerData.advancements = sh.pcx.xinventories.internal.model.PlayerAdvancements(
                setOf("minecraft:story/mine_stone")
            )

            val settings = GroupSettings(saveAdvancements = false)
            playerData.applyToPlayer(player, settings)

            // Advancements should not be applied
            // Verify no exception is thrown
        }

        @Test
        @DisplayName("should not apply recipes when saveRecipes is false")
        fun notApplyRecipesWhenDisabled() {
            playerData.recipes = setOf("minecraft:oak_planks")

            val settings = GroupSettings(saveRecipes = false)
            playerData.applyToPlayer(player, settings)

            // Recipes should not be applied
            // Verify no exception is thrown
        }

        @Test
        @DisplayName("should handle null extended data gracefully when settings enabled")
        fun handleNullExtendedDataGracefully() {
            // Extended data is null
            Assertions.assertNull(playerData.statistics)
            Assertions.assertNull(playerData.advancements)
            Assertions.assertNull(playerData.recipes)

            val settings = GroupSettings(
                saveStatistics = true,
                saveAdvancements = true,
                saveRecipes = true
            )

            // Should not throw exception
            playerData.applyToPlayer(player, settings)
        }
    }
}
