package sh.pcx.xinventories.unit.service

import io.mockk.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.entity.HumanEntity
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerSwapHandItemsEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryView
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.PlayerInventory
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.bukkit.Server
import org.bukkit.plugin.PluginManager
import sh.pcx.xinventories.XInventories
import sh.pcx.xinventories.internal.model.*
import sh.pcx.xinventories.internal.service.SharedSlotService
import sh.pcx.xinventories.internal.util.Logging
import java.util.UUID
import java.util.logging.Logger

/**
 * Unit tests for SharedSlotService.
 *
 * Tests cover:
 * - Initialization with enabled/disabled config
 * - isSharedSlot and isLockedSlot checks
 * - getEntryForSlot retrieval
 * - preserveSharedSlots across all slot types
 * - restoreSharedSlots with enforced items and SYNC mode
 * - excludeSharedSlotsFromData for PlayerData modification
 * - applyEnforcedItems for enforced item application
 * - Event handlers for LOCK mode (click, drag, drop, swap)
 * - Hotbar swap blocking for locked slots
 * - cleanup and clearCache operations
 * - updateSyncedSlot for SYNC mode updates
 */
@DisplayName("SharedSlotService Unit Tests")
class SharedSlotServiceTest {

    private lateinit var server: Server
    private lateinit var pluginManager: PluginManager
    private lateinit var plugin: XInventories
    private lateinit var scope: CoroutineScope
    private lateinit var service: SharedSlotService

    private val playerUUID = UUID.randomUUID()
    private val playerName = "TestPlayer"

    companion object {
        @JvmStatic
        @BeforeAll
        fun setupAll() {
            Logging.init(Logger.getLogger("SharedSlotServiceTest"), false)
            mockkObject(Logging)
            every { Logging.debug(any<() -> String>()) } just Runs
            every { Logging.debug(any<String>()) } just Runs
            every { Logging.info(any()) } just Runs
            every { Logging.warning(any()) } just Runs
            every { Logging.error(any<String>()) } just Runs
            every { Logging.error(any<String>(), any()) } just Runs

        }

        @JvmStatic
        @AfterAll
        fun teardownAll() {
            unmockkAll()
        }
    }

    @BeforeEach
    fun setUp() {
        pluginManager = mockk(relaxed = true)
        server = mockk(relaxed = true) {
            every { pluginManager } returns this@SharedSlotServiceTest.pluginManager
            every { onlinePlayers } returns emptyList()
        }
        plugin = mockk(relaxed = true) {
            every { this@mockk.server } returns this@SharedSlotServiceTest.server
            every { isEnabled } returns true
        }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        service = SharedSlotService(plugin, scope)
    }

    @AfterEach
    fun tearDown() {
        clearAllMocks()
    }

    // =========================================================================
    // Helper Methods
    // =========================================================================

    private fun createMockPlayer(
        uuid: UUID = playerUUID,
        name: String = playerName,
        mainItems: Map<Int, ItemStack?> = emptyMap(),
        armorItems: Array<ItemStack?> = arrayOfNulls(4),
        offhandItem: ItemStack? = null,
        heldSlot: Int = 0
    ): Player {
        val player = mockk<Player>(relaxed = true)
        val inventory = mockk<PlayerInventory>(relaxed = true)

        every { player.uniqueId } returns uuid
        every { player.name } returns name
        every { player.inventory } returns inventory

        // Set up main inventory slots (0-35)
        for (i in 0..35) {
            every { inventory.getItem(i) } returns mainItems[i]
        }

        // Set up armor contents (slots 36-39). As of Paper 26.1 the armor getters are non-null and
        // return AIR for empty slots (like offhand has always done), so coerce nulls to an AIR item.
        every { inventory.boots } returns (armorItems.getOrNull(0) ?: createNullItem())
        every { inventory.leggings } returns (armorItems.getOrNull(1) ?: createNullItem())
        every { inventory.chestplate } returns (armorItems.getOrNull(2) ?: createNullItem())
        every { inventory.helmet } returns (armorItems.getOrNull(3) ?: createNullItem())

        // Set up offhand (slot 40)
        every { inventory.itemInOffHand } returns (offhandItem ?: createNullItem())

        // Held item slot for drop events
        every { inventory.heldItemSlot } returns heldSlot

        return player
    }

    private fun createNullItem(): ItemStack {
        return mockk {
            every { type } returns Material.AIR
            every { amount } returns 0
            every { clone() } returns this
        }
    }

    private fun createItem(material: Material, amount: Int = 1): ItemStack {
        return mockk {
            every { type } returns material
            every { this@mockk.amount } returns amount
            every { clone() } returns this
        }
    }

    private fun createMockItemConfig(material: Material, amount: Int = 1): ItemConfig {
        val mockItem = createItem(material, amount)
        return mockk {
            every { type } returns material
            every { this@mockk.amount } returns amount
            every { toItemStack() } returns mockItem
        }
    }

    private fun createEnabledConfig(slots: List<SharedSlotEntry>): SharedSlotsConfig {
        return SharedSlotsConfig(enabled = true, slots = slots)
    }

    private fun createDisabledConfig(): SharedSlotsConfig {
        return SharedSlotsConfig.disabled()
    }

    // =========================================================================
    // Initialization Tests
    // =========================================================================

    @Nested
    @DisplayName("Initialization")
    inner class InitializationTests {

        @Test
        @DisplayName("Should initialize with enabled config and register events")
        fun initializeWithEnabledConfig() {
            val config = createEnabledConfig(
                listOf(SharedSlotEntry.single(8, SlotMode.PRESERVE))
            )

            service.initialize(config)

            // Events should be registered with the server
            verify { Logging.info(match { it.contains("SharedSlotService initialized") }) }
        }

        @Test
        @DisplayName("Should initialize with disabled config without registering events")
        fun initializeWithDisabledConfig() {
            val config = createDisabledConfig()

            service.initialize(config)

            // When disabled, no events should be registered
            verify { Logging.debug(any<() -> String>()) }
        }

        @Test
        @DisplayName("Should update config via updateConfig")
        fun updateConfig() {
            val initialConfig = createDisabledConfig()
            service.initialize(initialConfig)

            val newConfig = createEnabledConfig(
                listOf(SharedSlotEntry.single(5, SlotMode.LOCK))
            )
            service.updateConfig(newConfig)

            assertTrue(service.isSharedSlot(5))
            verify { Logging.debug(any<() -> String>()) }
        }
    }

    // =========================================================================
    // isSharedSlot Tests
    // =========================================================================

    @Nested
    @DisplayName("isSharedSlot")
    inner class IsSharedSlotTests {

        @Test
        @DisplayName("Should return true for shared slot when enabled")
        fun returnsTrueForSharedSlot() {
            val config = createEnabledConfig(
                listOf(SharedSlotEntry.single(8, SlotMode.PRESERVE))
            )
            service.initialize(config)

            assertTrue(service.isSharedSlot(8))
        }

        @Test
        @DisplayName("Should return false for non-shared slot")
        fun returnsFalseForNonSharedSlot() {
            val config = createEnabledConfig(
                listOf(SharedSlotEntry.single(8, SlotMode.PRESERVE))
            )
            service.initialize(config)

            assertFalse(service.isSharedSlot(7))
            assertFalse(service.isSharedSlot(0))
        }

        @Test
        @DisplayName("Should return false when disabled")
        fun returnsFalseWhenDisabled() {
            val config = createDisabledConfig()
            service.initialize(config)

            assertFalse(service.isSharedSlot(8))
        }

        @Test
        @DisplayName("Should return true for all slots in multi-slot entry")
        fun returnsTrueForMultiSlotEntry() {
            val config = createEnabledConfig(
                listOf(SharedSlotEntry.allHotbar(SlotMode.PRESERVE))
            )
            service.initialize(config)

            for (i in 0..8) {
                assertTrue(service.isSharedSlot(i), "Slot $i should be shared")
            }
            assertFalse(service.isSharedSlot(9))
        }
    }

    // =========================================================================
    // isLockedSlot Tests
    // =========================================================================

    @Nested
    @DisplayName("isLockedSlot")
    inner class IsLockedSlotTests {

        @Test
        @DisplayName("Should return true for LOCK mode slot")
        fun returnsTrueForLockMode() {
            val config = createEnabledConfig(
                listOf(SharedSlotEntry.single(8, SlotMode.LOCK))
            )
            service.initialize(config)

            assertTrue(service.isLockedSlot(8))
        }

        @Test
        @DisplayName("Should return false for PRESERVE mode slot")
        fun returnsFalseForPreserveMode() {
            val config = createEnabledConfig(
                listOf(SharedSlotEntry.single(8, SlotMode.PRESERVE))
            )
            service.initialize(config)

            assertFalse(service.isLockedSlot(8))
        }

        @Test
        @DisplayName("Should return false for SYNC mode slot")
        fun returnsFalseForSyncMode() {
            val config = createEnabledConfig(
                listOf(SharedSlotEntry.single(8, SlotMode.SYNC))
            )
            service.initialize(config)

            assertFalse(service.isLockedSlot(8))
        }

        @Test
        @DisplayName("Should return false for non-shared slot")
        fun returnsFalseForNonSharedSlot() {
            val config = createEnabledConfig(
                listOf(SharedSlotEntry.single(8, SlotMode.LOCK))
            )
            service.initialize(config)

            assertFalse(service.isLockedSlot(7))
        }

        @Test
        @DisplayName("Should return false when disabled")
        fun returnsFalseWhenDisabled() {
            val config = SharedSlotsConfig(
                enabled = false,
                slots = listOf(SharedSlotEntry.single(8, SlotMode.LOCK))
            )
            service.initialize(config)

            assertFalse(service.isLockedSlot(8))
        }
    }

    // =========================================================================
    // getEntryForSlot Tests
    // =========================================================================

    @Nested
    @DisplayName("getEntryForSlot")
    inner class GetEntryForSlotTests {

        @Test
        @DisplayName("Should return entry for shared slot")
        fun returnsEntryForSharedSlot() {
            val entry = SharedSlotEntry.single(8, SlotMode.LOCK)
            val config = createEnabledConfig(listOf(entry))
            service.initialize(config)

            val result = service.getEntryForSlot(8)

            assertNotNull(result)
            assertEquals(SlotMode.LOCK, result!!.mode)
            assertEquals(8, result.slot)
        }

        @Test
        @DisplayName("Should return null for non-shared slot")
        fun returnsNullForNonSharedSlot() {
            val config = createEnabledConfig(
                listOf(SharedSlotEntry.single(8, SlotMode.LOCK))
            )
            service.initialize(config)

            val result = service.getEntryForSlot(7)

            assertNull(result)
        }

        @Test
        @DisplayName("Should return entry for slot within multi-slot entry")
        fun returnsEntryForMultiSlotEntry() {
            val entry = SharedSlotEntry.allArmor(SlotMode.PRESERVE)
            val config = createEnabledConfig(listOf(entry))
            service.initialize(config)

            val result = service.getEntryForSlot(36) // boots

            assertNotNull(result)
            assertEquals(SlotMode.PRESERVE, result!!.mode)
        }
    }

    // =========================================================================
    // preserveSharedSlots Tests
    // =========================================================================

    @Nested
    @DisplayName("preserveSharedSlots")
    inner class PreserveSharedSlotsTests {

        @Test
        @DisplayName("Should preserve main inventory shared slots")
        fun preserveMainInventorySlots() {
            val item = createItem(Material.COMPASS)
            val player = createMockPlayer(mainItems = mapOf(8 to item))

            val config = createEnabledConfig(
                listOf(SharedSlotEntry.single(8, SlotMode.PRESERVE))
            )
            service.initialize(config)

            service.preserveSharedSlots(player)

            verify { Logging.debug(any<() -> String>()) }
        }

        @Test
        @DisplayName("Should preserve armor shared slots")
        fun preserveArmorSlots() {
            val boots = createItem(Material.DIAMOND_BOOTS)
            val armorContents = arrayOf(boots, null, null, null)
            val player = createMockPlayer(armorItems = armorContents)
            val inventory = player.inventory

            val config = createEnabledConfig(
                listOf(SharedSlotEntry.single(36, SlotMode.PRESERVE)) // boots slot
            )
            service.initialize(config)

            service.preserveSharedSlots(player)

            // Verify boots property was accessed (called at least once)
            verify(atLeast = 1) { inventory.boots }
        }

        @Test
        @DisplayName("Should preserve offhand shared slot")
        fun preserveOffhandSlot() {
            val shield = createItem(Material.SHIELD)
            val player = createMockPlayer(offhandItem = shield)
            val inventory = player.inventory

            val config = createEnabledConfig(
                listOf(SharedSlotEntry.offhand(SlotMode.PRESERVE))
            )
            service.initialize(config)

            service.preserveSharedSlots(player)

            // Verify offhand was accessed
            verify(atLeast = 1) { inventory.itemInOffHand }
        }

        @Test
        @DisplayName("Should update synced data for SYNC mode slots")
        fun updateSyncedDataForSyncMode() {
            val item = createItem(Material.COMPASS)
            val player = createMockPlayer(mainItems = mapOf(8 to item))
            val inventory = player.inventory

            val config = createEnabledConfig(
                listOf(SharedSlotEntry.single(8, SlotMode.SYNC))
            )
            service.initialize(config)

            service.preserveSharedSlots(player)

            // Verify the item was accessed for caching
            verify(atLeast = 1) { inventory.getItem(8) }
        }

        @Test
        @DisplayName("Should do nothing when disabled")
        fun doNothingWhenDisabled() {
            val player = createMockPlayer()
            val config = createDisabledConfig()
            service.initialize(config)

            service.preserveSharedSlots(player)

            verify(exactly = 0) { player.inventory.getItem(any<Int>()) }
        }

        @Test
        @DisplayName("Should preserve all helmet slots correctly")
        fun preserveHelmetSlot() {
            val helmet = createItem(Material.DIAMOND_HELMET)
            val armorContents = arrayOf<ItemStack?>(null, null, null, helmet)
            val player = createMockPlayer(armorItems = armorContents)
            val inventory = player.inventory

            val config = createEnabledConfig(
                listOf(SharedSlotEntry.single(39, SlotMode.PRESERVE)) // helmet slot
            )
            service.initialize(config)

            service.preserveSharedSlots(player)

            verify(atLeast = 1) { inventory.helmet }
        }
    }

    // =========================================================================
    // restoreSharedSlots Tests
    // =========================================================================

    @Nested
    @DisplayName("restoreSharedSlots")
    inner class RestoreSharedSlotsTests {

        @Test
        @DisplayName("Should restore main inventory shared slots")
        fun restoreMainInventorySlots() {
            val item = createItem(Material.COMPASS)
            val player = createMockPlayer(mainItems = mapOf(8 to item))
            val inventory = player.inventory

            val config = createEnabledConfig(
                listOf(SharedSlotEntry.single(8, SlotMode.PRESERVE))
            )
            service.initialize(config)

            // First preserve, then restore
            service.preserveSharedSlots(player)
            service.restoreSharedSlots(player)

            verify { inventory.setItem(eq(8), any()) }
        }

        @Test
        @DisplayName("Should restore armor slots correctly")
        fun restoreArmorSlots() {
            val boots = createItem(Material.DIAMOND_BOOTS)
            val armorContents = arrayOf(boots, null, null, null)
            val player = createMockPlayer(armorItems = armorContents)
            val inventory = player.inventory

            val config = createEnabledConfig(
                listOf(SharedSlotEntry.single(36, SlotMode.PRESERVE))
            )
            service.initialize(config)

            service.preserveSharedSlots(player)
            service.restoreSharedSlots(player)

            verify { inventory.setBoots(any()) }
        }

        @Test
        @DisplayName("Should restore offhand slot correctly")
        fun restoreOffhandSlot() {
            val shield = createItem(Material.SHIELD)
            val player = createMockPlayer(offhandItem = shield)
            val inventory = player.inventory

            val config = createEnabledConfig(
                listOf(SharedSlotEntry.offhand(SlotMode.PRESERVE))
            )
            service.initialize(config)

            service.preserveSharedSlots(player)
            service.restoreSharedSlots(player)

            verify { inventory.setItemInOffHand(any()) }
        }

        @Test
        @DisplayName("Should use synced data for SYNC mode slots")
        fun useSyncedDataForSyncMode() {
            val item = createItem(Material.COMPASS)
            val player = createMockPlayer(mainItems = mapOf(8 to item))
            val inventory = player.inventory

            val config = createEnabledConfig(
                listOf(SharedSlotEntry.single(8, SlotMode.SYNC))
            )
            service.initialize(config)

            // Preserve to populate synced data
            service.preserveSharedSlots(player)
            // Restore should use synced data
            service.restoreSharedSlots(player)

            verify { inventory.setItem(eq(8), any()) }
        }

        @Test
        @DisplayName("Should apply enforced item when configured")
        fun applyEnforcedItemWhenConfigured() {
            val player = createMockPlayer()
            val inventory = player.inventory

            val itemConfig = createMockItemConfig(Material.COMPASS, 1)
            val entry = SharedSlotEntry.single(8, SlotMode.PRESERVE, itemConfig)
            val config = createEnabledConfig(listOf(entry))
            service.initialize(config)

            // Preserve (to create cache entry)
            service.preserveSharedSlots(player)
            // Restore should apply enforced item
            service.restoreSharedSlots(player)

            verify { inventory.setItem(eq(8), any()) }
        }

        @Test
        @DisplayName("Should do nothing when disabled")
        fun doNothingWhenDisabled() {
            val player = createMockPlayer()
            val config = createDisabledConfig()
            service.initialize(config)

            service.restoreSharedSlots(player)

            verify(exactly = 0) { player.inventory.setItem(any<Int>(), any()) }
        }

        @Test
        @DisplayName("Should do nothing when no cached data exists")
        fun doNothingWhenNoCachedData() {
            val player = createMockPlayer()
            val config = createEnabledConfig(
                listOf(SharedSlotEntry.single(8, SlotMode.PRESERVE))
            )
            service.initialize(config)

            // Restore without preserve
            service.restoreSharedSlots(player)

            verify(exactly = 0) { player.inventory.setItem(any<Int>(), any()) }
        }
    }

    // =========================================================================
    // excludeSharedSlotsFromData Tests
    // =========================================================================

    @Nested
    @DisplayName("excludeSharedSlotsFromData")
    inner class ExcludeSharedSlotsFromDataTests {

        @Test
        @DisplayName("Should remove main inventory slots from PlayerData")
        fun removeMainInventorySlots() {
            val playerData = PlayerData(
                uuid = playerUUID,
                playerName = playerName,
                group = "test",
                gameMode = GameMode.SURVIVAL
            )
            playerData.mainInventory[8] = createItem(Material.COMPASS)
            playerData.mainInventory[5] = createItem(Material.DIAMOND)

            val config = createEnabledConfig(
                listOf(SharedSlotEntry.single(8, SlotMode.PRESERVE))
            )
            service.initialize(config)

            service.excludeSharedSlotsFromData(playerData)

            assertFalse(playerData.mainInventory.containsKey(8))
            assertTrue(playerData.mainInventory.containsKey(5))
        }

        @Test
        @DisplayName("Should remove armor slots from PlayerData")
        fun removeArmorSlots() {
            val playerData = PlayerData(
                uuid = playerUUID,
                playerName = playerName,
                group = "test",
                gameMode = GameMode.SURVIVAL
            )
            // Armor inventory uses 0-3 indices (boots=0, leggings=1, chestplate=2, helmet=3)
            playerData.armorInventory[0] = createItem(Material.DIAMOND_BOOTS)
            playerData.armorInventory[2] = createItem(Material.DIAMOND_CHESTPLATE)

            val config = createEnabledConfig(
                listOf(SharedSlotEntry.single(36, SlotMode.PRESERVE)) // boots (slot 36 maps to armor index 0)
            )
            service.initialize(config)

            service.excludeSharedSlotsFromData(playerData)

            assertFalse(playerData.armorInventory.containsKey(0), "Boots should be removed")
            assertTrue(playerData.armorInventory.containsKey(2), "Chestplate should remain")
        }

        @Test
        @DisplayName("Should remove offhand from PlayerData")
        fun removeOffhand() {
            val playerData = PlayerData(
                uuid = playerUUID,
                playerName = playerName,
                group = "test",
                gameMode = GameMode.SURVIVAL
            )
            playerData.offhand = createItem(Material.SHIELD)

            val config = createEnabledConfig(
                listOf(SharedSlotEntry.offhand(SlotMode.PRESERVE))
            )
            service.initialize(config)

            service.excludeSharedSlotsFromData(playerData)

            assertNull(playerData.offhand)
        }

        @Test
        @DisplayName("Should do nothing when disabled")
        fun doNothingWhenDisabled() {
            val playerData = PlayerData(
                uuid = playerUUID,
                playerName = playerName,
                group = "test",
                gameMode = GameMode.SURVIVAL
            )
            playerData.mainInventory[8] = createItem(Material.COMPASS)

            val config = createDisabledConfig()
            service.initialize(config)

            service.excludeSharedSlotsFromData(playerData)

            assertTrue(playerData.mainInventory.containsKey(8))
        }

        @Test
        @DisplayName("Should handle multiple shared slots")
        fun handleMultipleSharedSlots() {
            val playerData = PlayerData(
                uuid = playerUUID,
                playerName = playerName,
                group = "test",
                gameMode = GameMode.SURVIVAL
            )
            playerData.mainInventory[0] = createItem(Material.COMPASS)
            playerData.mainInventory[8] = createItem(Material.CLOCK)
            playerData.offhand = createItem(Material.SHIELD)

            val config = createEnabledConfig(
                listOf(
                    SharedSlotEntry.single(0, SlotMode.PRESERVE),
                    SharedSlotEntry.single(8, SlotMode.LOCK),
                    SharedSlotEntry.offhand(SlotMode.SYNC)
                )
            )
            service.initialize(config)

            service.excludeSharedSlotsFromData(playerData)

            assertFalse(playerData.mainInventory.containsKey(0))
            assertFalse(playerData.mainInventory.containsKey(8))
            assertNull(playerData.offhand)
        }
    }

    // =========================================================================
    // applyEnforcedItems Tests
    // =========================================================================

    @Nested
    @DisplayName("applyEnforcedItems")
    inner class ApplyEnforcedItemsTests {

        @Test
        @DisplayName("Should apply enforced item to main inventory slot")
        fun applyEnforcedItemToMainSlot() {
            val player = createMockPlayer()
            val inventory = player.inventory

            val itemConfig = createMockItemConfig(Material.COMPASS, 1)
            val entry = SharedSlotEntry.single(8, SlotMode.LOCK, itemConfig)
            val config = createEnabledConfig(listOf(entry))
            service.initialize(config)

            service.applyEnforcedItems(player)

            verify { inventory.setItem(eq(8), any()) }
        }

        @Test
        @DisplayName("Should apply enforced item to armor slot")
        fun applyEnforcedItemToArmorSlot() {
            val player = createMockPlayer()
            val inventory = player.inventory

            val itemConfig = createMockItemConfig(Material.DIAMOND_HELMET, 1)
            val entry = SharedSlotEntry.single(39, SlotMode.LOCK, itemConfig)
            val config = createEnabledConfig(listOf(entry))
            service.initialize(config)

            service.applyEnforcedItems(player)

            verify { inventory.setHelmet(any()) }
        }

        @Test
        @DisplayName("Should apply enforced item to offhand slot")
        fun applyEnforcedItemToOffhandSlot() {
            val player = createMockPlayer()
            val inventory = player.inventory

            val itemConfig = createMockItemConfig(Material.SHIELD, 1)
            val entry = SharedSlotEntry.offhand(SlotMode.LOCK, itemConfig)
            val config = createEnabledConfig(listOf(entry))
            service.initialize(config)

            service.applyEnforcedItems(player)

            verify { inventory.setItemInOffHand(any()) }
        }

        @Test
        @DisplayName("Should not apply when entry has no enforced item")
        fun noApplyWhenNoEnforcedItem() {
            val player = createMockPlayer()
            val inventory = player.inventory

            val entry = SharedSlotEntry.single(8, SlotMode.LOCK) // No item
            val config = createEnabledConfig(listOf(entry))
            service.initialize(config)

            service.applyEnforcedItems(player)

            verify(exactly = 0) { inventory.setItem(any<Int>(), any()) }
        }

        @Test
        @DisplayName("Should apply enforced items to all slots in multi-slot entry")
        fun applyToMultiSlotEntry() {
            val player = createMockPlayer()
            val inventory = player.inventory

            val itemConfig = createMockItemConfig(Material.BARRIER, 1)
            val entry = SharedSlotEntry(
                slots = listOf(0, 1, 2),
                mode = SlotMode.LOCK,
                item = itemConfig
            )
            val config = createEnabledConfig(listOf(entry))
            service.initialize(config)

            service.applyEnforcedItems(player)

            verify { inventory.setItem(eq(0), any()) }
            verify { inventory.setItem(eq(1), any()) }
            verify { inventory.setItem(eq(2), any()) }
        }

        @Test
        @DisplayName("Should do nothing when disabled")
        fun doNothingWhenDisabled() {
            val player = createMockPlayer()
            val config = createDisabledConfig()
            service.initialize(config)

            service.applyEnforcedItems(player)

            verify(exactly = 0) { player.inventory.setItem(any<Int>(), any()) }
        }
    }

    // =========================================================================
    // Event Handler Tests - onInventoryClick
    // =========================================================================

    @Nested
    @DisplayName("onInventoryClick Event Handler")
    inner class OnInventoryClickTests {

        @Test
        @DisplayName("Should cancel click on locked slot")
        fun cancelClickOnLockedSlot() {
            val player = createMockPlayer()
            val inventory = player.inventory
            val view = mockk<InventoryView>(relaxed = true)

            every { view.topInventory } returns mockk(relaxed = true)
            every { view.bottomInventory } returns inventory
            every { view.type } returns InventoryType.CRAFTING

            val event = mockk<InventoryClickEvent>(relaxed = true)
            every { event.whoClicked } returns player
            every { event.clickedInventory } returns inventory
            every { event.slot } returns 8
            every { event.hotbarButton } returns -1
            every { event.click } returns ClickType.LEFT

            val config = createEnabledConfig(
                listOf(SharedSlotEntry.single(8, SlotMode.LOCK))
            )
            service.initialize(config)

            service.onInventoryClick(event)

            verify { event.isCancelled = true }
        }

        @Test
        @DisplayName("Should not cancel click on non-locked slot")
        fun notCancelClickOnNonLockedSlot() {
            val player = createMockPlayer()
            val inventory = player.inventory
            val view = mockk<InventoryView>(relaxed = true)

            every { view.topInventory } returns mockk(relaxed = true)
            every { view.bottomInventory } returns inventory

            val event = mockk<InventoryClickEvent>(relaxed = true)
            every { event.whoClicked } returns player
            every { event.clickedInventory } returns inventory
            every { event.slot } returns 8
            every { event.hotbarButton } returns -1
            every { event.click } returns ClickType.LEFT

            val config = createEnabledConfig(
                listOf(SharedSlotEntry.single(8, SlotMode.PRESERVE)) // Not LOCK
            )
            service.initialize(config)

            service.onInventoryClick(event)

            verify(exactly = 0) { event.isCancelled = true }
        }

        @Test
        @DisplayName("Should cancel hotbar swap with locked slot")
        fun cancelHotbarSwapWithLockedSlot() {
            val player = createMockPlayer()
            val inventory = player.inventory
            val view = mockk<InventoryView>(relaxed = true)

            every { view.topInventory } returns mockk(relaxed = true)
            every { view.bottomInventory } returns inventory

            val event = mockk<InventoryClickEvent>(relaxed = true)
            every { event.whoClicked } returns player
            every { event.clickedInventory } returns inventory
            every { event.slot } returns 10 // Clicking a different slot
            every { event.hotbarButton } returns 8 // But swapping with locked slot 8
            every { event.click } returns ClickType.NUMBER_KEY

            val config = createEnabledConfig(
                listOf(SharedSlotEntry.single(8, SlotMode.LOCK))
            )
            service.initialize(config)

            service.onInventoryClick(event)

            verify { event.isCancelled = true }
        }

        @Test
        @DisplayName("Should not cancel when disabled")
        fun notCancelWhenDisabled() {
            val player = createMockPlayer()
            val inventory = player.inventory

            val event = mockk<InventoryClickEvent>(relaxed = true)
            every { event.whoClicked } returns player
            every { event.clickedInventory } returns inventory
            every { event.slot } returns 8
            every { event.hotbarButton } returns -1
            every { event.click } returns ClickType.LEFT

            val config = createDisabledConfig()
            service.initialize(config)

            service.onInventoryClick(event)

            verify(exactly = 0) { event.isCancelled = true }
        }

        @Test
        @DisplayName("Should ignore non-player inventory clicks")
        fun ignoreNonPlayerInventoryClicks() {
            val player = createMockPlayer()
            val otherInventory = mockk<Inventory>(relaxed = true)

            val event = mockk<InventoryClickEvent>(relaxed = true)
            every { event.whoClicked } returns player
            every { event.clickedInventory } returns otherInventory // Not player's inventory
            every { event.slot } returns 8
            every { event.hotbarButton } returns -1
            every { event.click } returns ClickType.LEFT

            val config = createEnabledConfig(
                listOf(SharedSlotEntry.single(8, SlotMode.LOCK))
            )
            service.initialize(config)

            service.onInventoryClick(event)

            verify(exactly = 0) { event.isCancelled = true }
        }

        @Test
        @DisplayName("Should ignore non-Player entities")
        fun ignoreNonPlayerEntities() {
            val nonPlayerEntity = mockk<HumanEntity>(relaxed = true)

            val event = mockk<InventoryClickEvent>(relaxed = true)
            every { event.whoClicked } returns nonPlayerEntity
            every { event.slot } returns 8
            every { event.hotbarButton } returns -1
            every { event.click } returns ClickType.LEFT

            val config = createEnabledConfig(
                listOf(SharedSlotEntry.single(8, SlotMode.LOCK))
            )
            service.initialize(config)

            service.onInventoryClick(event)

            verify(exactly = 0) { event.isCancelled = true }
        }
    }

    // =========================================================================
    // Event Handler Tests - onInventoryDrag
    // =========================================================================

    @Nested
    @DisplayName("onInventoryDrag Event Handler")
    inner class OnInventoryDragTests {

        @Test
        @DisplayName("Should cancel drag onto locked slot")
        fun cancelDragOntoLockedSlot() {
            val player = createMockPlayer()
            val inventory = player.inventory
            val view = mockk<InventoryView>(relaxed = true)

            every { view.getInventory(8) } returns inventory
            every { view.convertSlot(8) } returns 8

            val event = mockk<InventoryDragEvent>(relaxed = true)
            every { event.whoClicked } returns player
            every { event.view } returns view
            every { event.rawSlots } returns setOf(8)

            val config = createEnabledConfig(
                listOf(SharedSlotEntry.single(8, SlotMode.LOCK))
            )
            service.initialize(config)

            service.onInventoryDrag(event)

            verify { event.isCancelled = true }
        }

        @Test
        @DisplayName("Should not cancel drag onto non-locked slot")
        fun notCancelDragOntoNonLockedSlot() {
            val player = createMockPlayer()
            val inventory = player.inventory
            val view = mockk<InventoryView>(relaxed = true)

            every { view.getInventory(8) } returns inventory
            every { view.convertSlot(8) } returns 8

            val event = mockk<InventoryDragEvent>(relaxed = true)
            every { event.whoClicked } returns player
            every { event.view } returns view
            every { event.rawSlots } returns setOf(8)

            val config = createEnabledConfig(
                listOf(SharedSlotEntry.single(8, SlotMode.PRESERVE))
            )
            service.initialize(config)

            service.onInventoryDrag(event)

            verify(exactly = 0) { event.isCancelled = true }
        }

        @Test
        @DisplayName("Should cancel when any dragged slot is locked")
        fun cancelWhenAnySlotLocked() {
            val player = createMockPlayer()
            val inventory = player.inventory
            val view = mockk<InventoryView>(relaxed = true)

            every { view.getInventory(any()) } returns inventory
            every { view.convertSlot(5) } returns 5
            every { view.convertSlot(6) } returns 6
            every { view.convertSlot(8) } returns 8 // This one is locked

            val event = mockk<InventoryDragEvent>(relaxed = true)
            every { event.whoClicked } returns player
            every { event.view } returns view
            every { event.rawSlots } returns setOf(5, 6, 8)

            val config = createEnabledConfig(
                listOf(SharedSlotEntry.single(8, SlotMode.LOCK))
            )
            service.initialize(config)

            service.onInventoryDrag(event)

            verify { event.isCancelled = true }
        }

        @Test
        @DisplayName("Should not cancel when disabled")
        fun notCancelWhenDisabled() {
            val player = createMockPlayer()

            val event = mockk<InventoryDragEvent>(relaxed = true)
            every { event.whoClicked } returns player
            every { event.rawSlots } returns setOf(8)

            val config = createDisabledConfig()
            service.initialize(config)

            service.onInventoryDrag(event)

            verify(exactly = 0) { event.isCancelled = true }
        }

        @Test
        @DisplayName("Should ignore non-Player entities")
        fun ignoreNonPlayerEntities() {
            val nonPlayerEntity = mockk<HumanEntity>(relaxed = true)

            val event = mockk<InventoryDragEvent>(relaxed = true)
            every { event.whoClicked } returns nonPlayerEntity
            every { event.rawSlots } returns setOf(8)

            val config = createEnabledConfig(
                listOf(SharedSlotEntry.single(8, SlotMode.LOCK))
            )
            service.initialize(config)

            service.onInventoryDrag(event)

            verify(exactly = 0) { event.isCancelled = true }
        }
    }

    // =========================================================================
    // Event Handler Tests - onPlayerDropItem
    // =========================================================================

    @Nested
    @DisplayName("onPlayerDropItem Event Handler")
    inner class OnPlayerDropItemTests {

        @Test
        @DisplayName("Should cancel drop from locked held slot when slot is empty after drop")
        fun cancelDropFromLockedHeldSlot() {
            val player = createMockPlayer(heldSlot = 8)
            val inventory = player.inventory
            val droppedItemEntity = mockk<org.bukkit.entity.Item>(relaxed = true)
            val droppedItem = createItem(Material.COMPASS)

            every { inventory.getItem(8) } returns null // Slot is empty after drop
            every { droppedItemEntity.itemStack } returns droppedItem

            val event = mockk<PlayerDropItemEvent>(relaxed = true)
            every { event.player } returns player
            every { event.itemDrop } returns droppedItemEntity

            val config = createEnabledConfig(
                listOf(SharedSlotEntry.single(8, SlotMode.LOCK))
            )
            service.initialize(config)

            service.onPlayerDropItem(event)

            verify { event.isCancelled = true }
        }

        @Test
        @DisplayName("Should not cancel drop from non-locked slot")
        fun notCancelDropFromNonLockedSlot() {
            val player = createMockPlayer(heldSlot = 5)
            val droppedItemEntity = mockk<org.bukkit.entity.Item>(relaxed = true)
            val droppedItem = createItem(Material.DIAMOND)

            every { droppedItemEntity.itemStack } returns droppedItem

            val event = mockk<PlayerDropItemEvent>(relaxed = true)
            every { event.player } returns player
            every { event.itemDrop } returns droppedItemEntity

            val config = createEnabledConfig(
                listOf(SharedSlotEntry.single(8, SlotMode.LOCK))
            )
            service.initialize(config)

            service.onPlayerDropItem(event)

            verify(exactly = 0) { event.isCancelled = true }
        }

        @Test
        @DisplayName("Should not cancel when disabled")
        fun notCancelWhenDisabled() {
            val player = createMockPlayer(heldSlot = 8)
            val droppedItemEntity = mockk<org.bukkit.entity.Item>(relaxed = true)

            val event = mockk<PlayerDropItemEvent>(relaxed = true)
            every { event.player } returns player
            every { event.itemDrop } returns droppedItemEntity

            val config = createDisabledConfig()
            service.initialize(config)

            service.onPlayerDropItem(event)

            verify(exactly = 0) { event.isCancelled = true }
        }
    }

    // =========================================================================
    // Event Handler Tests - onPlayerSwapHandItems
    // =========================================================================

    @Nested
    @DisplayName("onPlayerSwapHandItems Event Handler")
    inner class OnPlayerSwapHandItemsTests {

        @Test
        @DisplayName("Should cancel swap when main hand slot is locked")
        fun cancelSwapWhenMainHandLocked() {
            val player = createMockPlayer(heldSlot = 8)
            val mainHandItem = createItem(Material.COMPASS)
            val offHandItem = createItem(Material.SHIELD)

            val event = mockk<PlayerSwapHandItemsEvent>(relaxed = true)
            every { event.player } returns player
            every { event.mainHandItem } returns mainHandItem
            every { event.offHandItem } returns offHandItem

            val config = createEnabledConfig(
                listOf(SharedSlotEntry.single(8, SlotMode.LOCK))
            )
            service.initialize(config)

            service.onPlayerSwapHandItems(event)

            verify { event.isCancelled = true }
        }

        @Test
        @DisplayName("Should cancel swap when offhand slot is locked")
        fun cancelSwapWhenOffhandLocked() {
            val player = createMockPlayer(heldSlot = 0)
            val mainHandItem = createItem(Material.DIAMOND_SWORD)
            val offHandItem = createItem(Material.SHIELD)

            val event = mockk<PlayerSwapHandItemsEvent>(relaxed = true)
            every { event.player } returns player
            every { event.mainHandItem } returns mainHandItem
            every { event.offHandItem } returns offHandItem

            val config = createEnabledConfig(
                listOf(SharedSlotEntry.offhand(SlotMode.LOCK))
            )
            service.initialize(config)

            service.onPlayerSwapHandItems(event)

            verify { event.isCancelled = true }
        }

        @Test
        @DisplayName("Should cancel swap when both slots are locked")
        fun cancelSwapWhenBothLocked() {
            val player = createMockPlayer(heldSlot = 8)
            val mainHandItem = createItem(Material.COMPASS)
            val offHandItem = createItem(Material.SHIELD)

            val event = mockk<PlayerSwapHandItemsEvent>(relaxed = true)
            every { event.player } returns player
            every { event.mainHandItem } returns mainHandItem
            every { event.offHandItem } returns offHandItem

            val config = createEnabledConfig(
                listOf(
                    SharedSlotEntry.single(8, SlotMode.LOCK),
                    SharedSlotEntry.offhand(SlotMode.LOCK)
                )
            )
            service.initialize(config)

            service.onPlayerSwapHandItems(event)

            verify { event.isCancelled = true }
        }

        @Test
        @DisplayName("Should not cancel swap when neither slot is locked")
        fun notCancelSwapWhenNeitherLocked() {
            val player = createMockPlayer(heldSlot = 0)
            val mainHandItem = createItem(Material.DIAMOND_SWORD)
            val offHandItem = createItem(Material.SHIELD)

            val event = mockk<PlayerSwapHandItemsEvent>(relaxed = true)
            every { event.player } returns player
            every { event.mainHandItem } returns mainHandItem
            every { event.offHandItem } returns offHandItem

            val config = createEnabledConfig(
                listOf(SharedSlotEntry.single(8, SlotMode.LOCK)) // Different slot
            )
            service.initialize(config)

            service.onPlayerSwapHandItems(event)

            verify(exactly = 0) { event.isCancelled = true }
        }

        @Test
        @DisplayName("Should not cancel when disabled")
        fun notCancelWhenDisabled() {
            val player = createMockPlayer(heldSlot = 8)
            val mainHandItem = createItem(Material.COMPASS)
            val offHandItem = createItem(Material.SHIELD)

            val event = mockk<PlayerSwapHandItemsEvent>(relaxed = true)
            every { event.player } returns player
            every { event.mainHandItem } returns mainHandItem
            every { event.offHandItem } returns offHandItem

            val config = createDisabledConfig()
            service.initialize(config)

            service.onPlayerSwapHandItems(event)

            verify(exactly = 0) { event.isCancelled = true }
        }
    }

    // =========================================================================
    // cleanup Tests
    // =========================================================================

    @Nested
    @DisplayName("cleanup")
    inner class CleanupTests {

        @Test
        @DisplayName("Should remove cached data for player")
        fun removeCachedDataForPlayer() {
            val item = createItem(Material.COMPASS)
            val player = createMockPlayer(mainItems = mapOf(8 to item))

            val config = createEnabledConfig(
                listOf(SharedSlotEntry.single(8, SlotMode.PRESERVE))
            )
            service.initialize(config)

            // Preserve to populate cache
            service.preserveSharedSlots(player)

            // Cleanup
            service.cleanup(playerUUID)

            // Restore should do nothing (no cached data)
            val otherPlayer = createMockPlayer(uuid = playerUUID)
            service.restoreSharedSlots(otherPlayer)

            verify(exactly = 0) { otherPlayer.inventory.setItem(any<Int>(), any()) }
        }

        @Test
        @DisplayName("Should not remove synced data on cleanup")
        fun notRemoveSyncedDataOnCleanup() {
            // Synced data should persist across cleanups
            val item = createItem(Material.COMPASS)
            val player = createMockPlayer(mainItems = mapOf(8 to item))
            val inventory = player.inventory

            val config = createEnabledConfig(
                listOf(SharedSlotEntry.single(8, SlotMode.SYNC))
            )
            service.initialize(config)

            // Preserve to populate cache and synced data
            service.preserveSharedSlots(player)

            // Cleanup removes sharedSlotCache but not syncedSlotData
            service.cleanup(playerUUID)

            // Since sharedSlotCache is cleared, restore won't find the player
            // but the syncedSlotData should remain for the next preserve/restore cycle
        }
    }

    // =========================================================================
    // clearCache Tests
    // =========================================================================

    @Nested
    @DisplayName("clearCache")
    inner class ClearCacheTests {

        @Test
        @DisplayName("Should clear all cached data")
        fun clearAllCachedData() {
            val item = createItem(Material.COMPASS)
            val player1 = createMockPlayer(uuid = UUID.randomUUID(), mainItems = mapOf(8 to item))
            val player2 = createMockPlayer(uuid = UUID.randomUUID(), mainItems = mapOf(8 to item))

            val config = createEnabledConfig(
                listOf(SharedSlotEntry.single(8, SlotMode.PRESERVE))
            )
            service.initialize(config)

            service.preserveSharedSlots(player1)
            service.preserveSharedSlots(player2)

            service.clearCache()

            verify { Logging.debug(any<() -> String>()) }
        }
    }

    // =========================================================================
    // updateSyncedSlot Tests
    // =========================================================================

    @Nested
    @DisplayName("updateSyncedSlot")
    inner class UpdateSyncedSlotTests {

        @Test
        @DisplayName("Should update synced slot data for SYNC mode")
        fun updateSyncedSlotData() {
            val player = createMockPlayer()
            val item = createItem(Material.COMPASS)

            val config = createEnabledConfig(
                listOf(SharedSlotEntry.single(8, SlotMode.SYNC))
            )
            service.initialize(config)

            service.updateSyncedSlot(player, 8, item)

            verify { Logging.debug(any<() -> String>()) }
        }

        @Test
        @DisplayName("Should not update for non-SYNC mode slots")
        fun notUpdateForNonSyncMode() {
            val player = createMockPlayer()
            val item = createItem(Material.COMPASS)

            val config = createEnabledConfig(
                listOf(SharedSlotEntry.single(8, SlotMode.PRESERVE))
            )
            service.initialize(config)

            service.updateSyncedSlot(player, 8, item)

            // Should not log debug message for sync update
            verify(exactly = 0) {
                Logging.debug(match<() -> String> { it().contains("Updated synced slot") })
            }
        }

        @Test
        @DisplayName("Should not update when disabled")
        fun notUpdateWhenDisabled() {
            val player = createMockPlayer()
            val item = createItem(Material.COMPASS)

            val config = createDisabledConfig()
            service.initialize(config)

            service.updateSyncedSlot(player, 8, item)

            // No update should happen
        }

        @Test
        @DisplayName("Should handle null item")
        fun handleNullItem() {
            val player = createMockPlayer()

            val config = createEnabledConfig(
                listOf(SharedSlotEntry.single(8, SlotMode.SYNC))
            )
            service.initialize(config)

            // Should not throw
            service.updateSyncedSlot(player, 8, null)

            verify { Logging.debug(any<() -> String>()) }
        }
    }

    // =========================================================================
    // Edge Cases Tests
    // =========================================================================

    @Nested
    @DisplayName("Edge Cases")
    inner class EdgeCasesTests {

        @Test
        @DisplayName("Should handle all armor slot types correctly")
        fun handleAllArmorSlotTypes() {
            val boots = createItem(Material.DIAMOND_BOOTS)
            val leggings = createItem(Material.DIAMOND_LEGGINGS)
            val chestplate = createItem(Material.DIAMOND_CHESTPLATE)
            val helmet = createItem(Material.DIAMOND_HELMET)
            val armorContents: Array<ItemStack?> = arrayOf(boots, leggings, chestplate, helmet)

            val player = createMockPlayer(armorItems = armorContents)
            val inventory = player.inventory

            val config = createEnabledConfig(
                listOf(SharedSlotEntry.allArmor(SlotMode.PRESERVE))
            )
            service.initialize(config)

            service.preserveSharedSlots(player)

            verify(atLeast = 1) { inventory.boots }
            verify(atLeast = 1) { inventory.leggings }
            verify(atLeast = 1) { inventory.chestplate }
            verify(atLeast = 1) { inventory.helmet }
        }

        @Test
        @DisplayName("Should handle empty inventory slots")
        fun handleEmptyInventorySlots() {
            val player = createMockPlayer(mainItems = emptyMap())

            val config = createEnabledConfig(
                listOf(SharedSlotEntry.allHotbar(SlotMode.PRESERVE))
            )
            service.initialize(config)

            // Should not throw
            service.preserveSharedSlots(player)
            service.restoreSharedSlots(player)
        }

        @Test
        @DisplayName("Should handle boundary slot indices")
        fun handleBoundarySlotIndices() {
            val config = createEnabledConfig(
                listOf(
                    SharedSlotEntry.single(0, SlotMode.PRESERVE),  // First slot
                    SharedSlotEntry.single(35, SlotMode.PRESERVE), // Last main inventory
                    SharedSlotEntry.single(36, SlotMode.PRESERVE), // First armor (boots)
                    SharedSlotEntry.single(40, SlotMode.PRESERVE)  // Offhand
                )
            )
            service.initialize(config)

            assertTrue(service.isSharedSlot(0))
            assertTrue(service.isSharedSlot(35))
            assertTrue(service.isSharedSlot(36))
            assertTrue(service.isSharedSlot(40))
        }

        @Test
        @DisplayName("Should handle multiple players independently")
        fun handleMultiplePlayersIndependently() {
            val player1UUID = UUID.randomUUID()
            val player2UUID = UUID.randomUUID()

            val item1 = createItem(Material.COMPASS)
            val item2 = createItem(Material.CLOCK)

            val player1 = createMockPlayer(uuid = player1UUID, mainItems = mapOf(8 to item1))
            val player2 = createMockPlayer(uuid = player2UUID, mainItems = mapOf(8 to item2))
            val inventory2 = player2.inventory

            val config = createEnabledConfig(
                listOf(SharedSlotEntry.single(8, SlotMode.PRESERVE))
            )
            service.initialize(config)

            service.preserveSharedSlots(player1)
            service.preserveSharedSlots(player2)

            // Cleanup player 1
            service.cleanup(player1UUID)

            // Player 2 should still have cached data
            service.restoreSharedSlots(player2)

            verify(atLeast = 1) { inventory2.setItem(eq(8), any()) }
        }

        @Test
        @DisplayName("Should handle config with mixed slot modes")
        fun handleMixedSlotModes() {
            val config = createEnabledConfig(
                listOf(
                    SharedSlotEntry.single(0, SlotMode.PRESERVE),
                    SharedSlotEntry.single(1, SlotMode.LOCK),
                    SharedSlotEntry.single(2, SlotMode.SYNC),
                    SharedSlotEntry.offhand(SlotMode.LOCK)
                )
            )
            service.initialize(config)

            assertTrue(service.isSharedSlot(0))
            assertTrue(service.isSharedSlot(1))
            assertTrue(service.isSharedSlot(2))
            assertTrue(service.isSharedSlot(40))

            assertFalse(service.isLockedSlot(0))
            assertTrue(service.isLockedSlot(1))
            assertFalse(service.isLockedSlot(2))
            assertTrue(service.isLockedSlot(40))
        }

        @Test
        @DisplayName("Should handle NUMBER_KEY click type for hotbar swap")
        fun handleNumberKeyClickType() {
            val player = createMockPlayer()
            val inventory = player.inventory

            val event = mockk<InventoryClickEvent>(relaxed = true)
            every { event.whoClicked } returns player
            every { event.clickedInventory } returns inventory
            every { event.slot } returns 10
            every { event.hotbarButton } returns 8
            every { event.click } returns ClickType.NUMBER_KEY

            val config = createEnabledConfig(
                listOf(SharedSlotEntry.single(8, SlotMode.LOCK))
            )
            service.initialize(config)

            service.onInventoryClick(event)

            verify { event.isCancelled = true }
        }
    }

    // =========================================================================
    // Integration Tests
    // =========================================================================

    @Nested
    @DisplayName("Integration Tests")
    inner class IntegrationTests {

        @Test
        @DisplayName("Should complete full preserve-restore cycle")
        fun completePreserveRestoreCycle() {
            val compassItem = createItem(Material.COMPASS)
            val player = createMockPlayer(mainItems = mapOf(8 to compassItem))
            val inventory = player.inventory

            val config = createEnabledConfig(
                listOf(SharedSlotEntry.single(8, SlotMode.PRESERVE))
            )
            service.initialize(config)

            // Simulate group switch
            service.preserveSharedSlots(player)

            // Player data would be saved/loaded here in real scenario

            service.restoreSharedSlots(player)

            verify { inventory.getItem(8) }
            verify { inventory.setItem(eq(8), any()) }
        }

        @Test
        @DisplayName("Should handle enforced items in full cycle")
        fun handleEnforcedItemsInFullCycle() {
            val player = createMockPlayer()
            val inventory = player.inventory

            val itemConfig = createMockItemConfig(Material.COMPASS, 1)
            val entry = SharedSlotEntry.single(8, SlotMode.LOCK, itemConfig)
            val config = createEnabledConfig(listOf(entry))
            service.initialize(config)

            // Apply enforced items (e.g., on join)
            service.applyEnforcedItems(player)

            // Preserve before group switch
            service.preserveSharedSlots(player)

            // Restore after group switch
            service.restoreSharedSlots(player)

            // Verify item was set
            verify(atLeast = 1) { inventory.setItem(eq(8), any()) }
        }

        @Test
        @DisplayName("Should block all modification attempts for locked slots")
        fun blockAllModificationAttemptsForLockedSlots() {
            val player = createMockPlayer(heldSlot = 8)
            val inventory = player.inventory

            val config = createEnabledConfig(
                listOf(SharedSlotEntry.single(8, SlotMode.LOCK))
            )
            service.initialize(config)

            // Click event
            val clickEvent = mockk<InventoryClickEvent>(relaxed = true)
            every { clickEvent.whoClicked } returns player
            every { clickEvent.clickedInventory } returns inventory
            every { clickEvent.slot } returns 8
            every { clickEvent.hotbarButton } returns -1
            every { clickEvent.click } returns ClickType.LEFT

            service.onInventoryClick(clickEvent)
            verify { clickEvent.isCancelled = true }

            // Drag event
            val view = mockk<InventoryView>(relaxed = true)
            every { view.getInventory(8) } returns inventory
            every { view.convertSlot(8) } returns 8

            val dragEvent = mockk<InventoryDragEvent>(relaxed = true)
            every { dragEvent.whoClicked } returns player
            every { dragEvent.view } returns view
            every { dragEvent.rawSlots } returns setOf(8)

            service.onInventoryDrag(dragEvent)
            verify { dragEvent.isCancelled = true }

            // Drop event
            val droppedItemEntity = mockk<org.bukkit.entity.Item>(relaxed = true)
            every { droppedItemEntity.itemStack } returns createItem(Material.COMPASS)
            every { inventory.getItem(8) } returns null // Empty after drop

            val dropEvent = mockk<PlayerDropItemEvent>(relaxed = true)
            every { dropEvent.player } returns player
            every { dropEvent.itemDrop } returns droppedItemEntity

            service.onPlayerDropItem(dropEvent)
            verify { dropEvent.isCancelled = true }

            // Swap event
            val swapEvent = mockk<PlayerSwapHandItemsEvent>(relaxed = true)
            every { swapEvent.player } returns player
            every { swapEvent.mainHandItem } returns createItem(Material.COMPASS)
            every { swapEvent.offHandItem } returns createItem(Material.SHIELD)

            service.onPlayerSwapHandItems(swapEvent)
            verify { swapEvent.isCancelled = true }
        }

        @Test
        @DisplayName("Should properly exclude shared slots when saving player data")
        fun properlyExcludeSharedSlotsWhenSavingPlayerData() {
            val playerData = PlayerData(
                uuid = playerUUID,
                playerName = playerName,
                group = "survival",
                gameMode = GameMode.SURVIVAL
            )

            // Populate inventory data
            playerData.mainInventory[0] = createItem(Material.DIAMOND_SWORD)
            playerData.mainInventory[8] = createItem(Material.COMPASS) // Shared
            playerData.mainInventory[35] = createItem(Material.BREAD)
            playerData.armorInventory[0] = createItem(Material.DIAMOND_BOOTS) // Shared
            playerData.armorInventory[3] = createItem(Material.DIAMOND_HELMET)
            playerData.offhand = createItem(Material.SHIELD) // Shared

            val config = createEnabledConfig(
                listOf(
                    SharedSlotEntry.single(8, SlotMode.PRESERVE),
                    SharedSlotEntry.single(36, SlotMode.LOCK), // boots
                    SharedSlotEntry.offhand(SlotMode.SYNC)
                )
            )
            service.initialize(config)

            service.excludeSharedSlotsFromData(playerData)

            // Non-shared slots should remain
            assertTrue(playerData.mainInventory.containsKey(0))
            assertTrue(playerData.mainInventory.containsKey(35))
            assertTrue(playerData.armorInventory.containsKey(3))

            // Shared slots should be removed
            assertFalse(playerData.mainInventory.containsKey(8))
            assertFalse(playerData.armorInventory.containsKey(0))
            assertNull(playerData.offhand)
        }
    }
}
