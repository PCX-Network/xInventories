package sh.pcx.xinventories.integration.service

import io.mockk.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.CoroutineContext
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.inventory.ItemStack
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.scheduler.BukkitScheduler
import org.bukkit.scheduler.BukkitTask
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assertions
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import org.mockbukkit.mockbukkit.entity.PlayerMock
import sh.pcx.xinventories.XInventories
import sh.pcx.xinventories.api.event.InventoryLoadEvent
import sh.pcx.xinventories.api.event.InventorySaveEvent
import sh.pcx.xinventories.api.event.InventorySwitchEvent
import sh.pcx.xinventories.api.model.GroupSettings
import sh.pcx.xinventories.api.model.InventoryGroup
import sh.pcx.xinventories.api.model.PlayerInventorySnapshot
import sh.pcx.xinventories.internal.config.*
import sh.pcx.xinventories.internal.model.Group
import sh.pcx.xinventories.internal.model.PlayerData
import sh.pcx.xinventories.internal.service.GroupService
import sh.pcx.xinventories.internal.service.InventoryService
import sh.pcx.xinventories.internal.service.MessageService
import sh.pcx.xinventories.internal.service.StorageService
import sh.pcx.xinventories.internal.service.SyncService
import sh.pcx.xinventories.internal.util.Logging
import java.util.*

/**
 * Comprehensive integration tests for InventoryService.
 *
 * Tests cover:
 * - Handle player join (load inventory)
 * - Handle player quit (save inventory)
 * - Handle world change (save old, load new)
 * - Handle world change same group (no switch)
 * - Handle gamemode change with separation enabled
 * - Handle gamemode change with separation disabled
 * - Check bypass permission
 * - Check runtime bypass
 * - Add/remove runtime bypass
 * - Save inventory manually
 * - Load inventory manually
 * - Switch inventory programmatically
 * - Get active snapshot for player
 * - Clear player inventory
 * - Fire InventoryLoadEvent
 * - Fire InventorySaveEvent
 * - Fire InventorySwitchEvent
 * - Event cancellation prevents action
 * - Notify player on switch (if enabled)
 * - Play sound on switch (if configured)
 */
@DisplayName("InventoryService Integration Tests")
class InventoryServiceTest {

    companion object {
        @JvmStatic
        @BeforeAll
        fun setupAll() {
            // Mock the Logging object to prevent actual logging during tests
            mockkObject(Logging)
            every { Logging.debug(any<() -> String>()) } just Runs
            every { Logging.debug(any<String>()) } just Runs
            every { Logging.info(any()) } just Runs
            every { Logging.warning(any()) } just Runs
            every { Logging.severe(any()) } just Runs
            every { Logging.error(any<String>()) } just Runs
            every { Logging.error(any<String>(), any()) } just Runs
        }

        @JvmStatic
        @AfterAll
        fun teardownAll() {
            unmockkAll()
        }
    }

    private lateinit var server: ServerMock
    private lateinit var plugin: XInventories
    private lateinit var inventoryService: InventoryService
    private lateinit var storageService: StorageService
    private lateinit var groupService: GroupService
    private lateinit var messageService: MessageService
    private lateinit var configManager: ConfigManager
    private lateinit var testDispatcher: CoroutineDispatcher

    private lateinit var testScope: TestScope

    /**
     * Immediate dispatcher that runs blocks synchronously for testing.
     */
    private class ImmediateDispatcher : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            block.run()
        }
    }
    private val testUuid1 = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val testUuid2 = UUID.fromString("00000000-0000-0000-0000-000000000002")

    @BeforeEach
    fun setUp() {
        server = MockBukkit.mock()

        // Create mocks
        plugin = mockk(relaxed = true)
        storageService = mockk(relaxed = true)
        groupService = mockk(relaxed = true)
        messageService = mockk(relaxed = true)
        configManager = mockk(relaxed = true)

        // Setup test coroutine scope and dispatcher
        testScope = TestScope()
        // Use an immediate dispatcher that runs blocks synchronously for testing
        testDispatcher = ImmediateDispatcher()

        // Setup default config
        val mainConfig = MainConfig(
            features = FeaturesConfig(
                saveOnWorldChange = true,
                saveOnGamemodeChange = true,
                asyncSaving = true
            )
        )
        val groupsConfig = GroupsConfig(
            globalSettings = GlobalSettings(
                notifyOnSwitch = true,
                switchSound = "ENTITY_ENDERMAN_TELEPORT",
                switchSoundVolume = 0.5f,
                switchSoundPitch = 1.2f
            )
        )

        every { plugin.configManager } returns configManager
        every { configManager.mainConfig } returns mainConfig
        every { configManager.groupsConfig } returns groupsConfig
        every { plugin.mainThreadDispatcher } returns testDispatcher
        every { plugin.plugin } returns plugin
        every { plugin.server } returns server

        // Use MockBukkit's built-in scheduler - no mocking needed

        // Setup default group
        val survivalGroup = Group(
            name = "survival",
            worlds = setOf("world", "world_nether", "world_the_end"),
            settings = GroupSettings(),
            isDefault = true
        )
        val creativeGroup = Group(
            name = "creative",
            worlds = setOf("creative"),
            settings = GroupSettings(separateGameModeInventories = false)
        )

        every { groupService.getGroupForWorld(any<World>()) } returns survivalGroup
        every { groupService.getGroupForWorld(any<String>()) } returns survivalGroup
        every { groupService.getGroup("survival") } returns survivalGroup
        every { groupService.getGroup("creative") } returns creativeGroup

        // Create the service
        inventoryService = InventoryService(
            plugin = plugin,
            scope = testScope,
            storageService = storageService,
            groupService = groupService,
            messageService = messageService
        )
    }

    @AfterEach
    fun tearDown() {
        MockBukkit.unmock()
        clearAllMocks()
    }

    // ============================================================
    // Handle Player Join Tests
    // ============================================================

    @Nested
    @DisplayName("Handle Player Join")
    inner class HandlePlayerJoinTests {

        @Test
        @DisplayName("should load inventory on player join")
        fun loadInventoryOnJoin() = testScope.runTest {
            val player = server.addPlayer()
            val group = Group(name = "survival", isDefault = true)
            val playerData = PlayerData.fromPlayer(player, "survival")

            every { groupService.getGroupForWorld(player.world) } returns group
            coEvery { storageService.loadPlayerData(player.uniqueId, "survival", any()) } returns playerData

            // Mock event dispatch to run without issues
            // Use MockBukkit's built-in PluginManager


            inventoryService.handlePlayerJoin(player)
            advanceUntilIdle()

            coVerify { storageService.loadPlayerData(player.uniqueId, "survival", any()) }
        }

        @Test
        @DisplayName("should skip inventory load when player has bypass permission")
        fun skipLoadWithBypassPermission() = testScope.runTest {
            val player = mockk<PlayerMock>(relaxed = true)
            every { player.hasPermission("xinventories.bypass") } returns true
            every { player.uniqueId } returns testUuid1
            every { player.name } returns "TestPlayer"

            inventoryService.handlePlayerJoin(player)
            advanceUntilIdle()

            coVerify(exactly = 0) { storageService.loadPlayerData(any(), any(), any()) }
        }

        @Test
        @DisplayName("should skip inventory load when player has runtime bypass")
        fun skipLoadWithRuntimeBypass() = testScope.runTest {
            val player = mockk<PlayerMock>(relaxed = true)
            every { player.hasPermission("xinventories.bypass") } returns false
            every { player.hasPermission("xinventories.bypass.survival") } returns false
            every { player.uniqueId } returns testUuid1
            every { player.name } returns "TestPlayer"

            inventoryService.addBypass(testUuid1)

            inventoryService.handlePlayerJoin(player)
            advanceUntilIdle()

            coVerify(exactly = 0) { storageService.loadPlayerData(any(), any(), any()) }
        }

        @Test
        @DisplayName("should track player group after join")
        fun trackPlayerGroupAfterJoin() = testScope.runTest {
            val player = server.addPlayer()
            val group = Group(name = "survival", isDefault = true)
            val playerData = PlayerData.fromPlayer(player, "survival")

            every { groupService.getGroupForWorld(player.world) } returns group
            coEvery { storageService.loadPlayerData(player.uniqueId, "survival", any()) } returns playerData


            inventoryService.handlePlayerJoin(player)
            advanceUntilIdle()

            assertEquals("survival", inventoryService.getCurrentGroup(player))
        }
    }

    // ============================================================
    // Handle Player Quit Tests
    // ============================================================

    @Nested
    @DisplayName("Handle Player Quit")
    inner class HandlePlayerQuitTests {

        @Test
        @DisplayName("should save inventory on player quit")
        fun saveInventoryOnQuit() = testScope.runTest {
            val player = server.addPlayer()
            val group = Group(name = "survival", isDefault = true)
            val playerData = PlayerData.fromPlayer(player, "survival")

            every { groupService.getGroupForWorld(player.world) } returns group
            every { groupService.getGroup("survival") } returns group
            coEvery { storageService.savePlayerData(any()) } returns true

            // Use MockBukkit's built-in PluginManager

            // Simulate player was tracked
            coEvery { storageService.loadPlayerData(player.uniqueId, "survival", any()) } returns playerData
            inventoryService.handlePlayerJoin(player)
            advanceUntilIdle()

            // Now test quit
            inventoryService.handlePlayerQuit(player)
            advanceUntilIdle()

            coVerify { storageService.savePlayerData(any()) }
        }

        @Test
        @DisplayName("should skip inventory save when player has bypass")
        fun skipSaveWithBypass() = testScope.runTest {
            val player = mockk<PlayerMock>(relaxed = true)
            every { player.hasPermission("xinventories.bypass") } returns true
            every { player.uniqueId } returns testUuid1
            every { player.name } returns "TestPlayer"

            inventoryService.handlePlayerQuit(player)
            advanceUntilIdle()

            coVerify(exactly = 0) { storageService.savePlayerData(any()) }
        }

        @Test
        @DisplayName("should clean up player tracking after quit")
        fun cleanupAfterQuit() = testScope.runTest {
            val player = server.addPlayer()
            val group = Group(name = "survival", isDefault = true)
            val playerData = PlayerData.fromPlayer(player, "survival")

            every { groupService.getGroupForWorld(player.world) } returns group
            every { groupService.getGroup("survival") } returns group
            coEvery { storageService.loadPlayerData(player.uniqueId, "survival", any()) } returns playerData
            coEvery { storageService.savePlayerData(any()) } returns true


            inventoryService.handlePlayerJoin(player)
            advanceUntilIdle()

            inventoryService.handlePlayerQuit(player)
            advanceUntilIdle()

            Assertions.assertNull(inventoryService.getCurrentGroup(player))
            Assertions.assertNull(inventoryService.getActiveSnapshot(player))
        }

        @Test
        @DisplayName("releases the distributed lock even when the save fails (B5)")
        fun releasesLockOnSaveFailure() = testScope.runTest {
            val player = server.addPlayer()
            val group = Group(name = "survival", isDefault = true)
            every { groupService.getGroupForWorld(player.world) } returns group
            every { groupService.getGroup("survival") } returns group

            // Enable sync so the lock path is exercised, and make the save fail.
            val syncService = mockk<SyncService>(relaxed = true)
            every { plugin.serviceManager.syncService } returns syncService
            every { syncService.isEnabled } returns true
            coEvery { storageService.savePlayerData(any()) } throws RuntimeException("db down")

            // The save failure may propagate, but the lock MUST still be released in the finally block
            // - otherwise the player's data stays locked across the network forever.
            runCatching { inventoryService.handlePlayerQuit(player) }
            advanceUntilIdle()

            coVerify { syncService.releaseLock(player.uniqueId) }
        }
    }

    // ============================================================
    // Handle World Change Tests
    // ============================================================

    @Nested
    @DisplayName("Handle World Change")
    inner class HandleWorldChangeTests {

        @Test
        @DisplayName("should switch inventory when changing to different group")
        fun switchInventoryOnWorldChange() = testScope.runTest {
            val player = server.addPlayer()
            val survivalGroup = Group(name = "survival", isDefault = true)
            val creativeGroup = Group(name = "creative")

            every { groupService.getGroupForWorld("world") } returns survivalGroup
            every { groupService.getGroupForWorld("creative") } returns creativeGroup
            every { groupService.getGroup("creative") } returns creativeGroup
            coEvery { storageService.savePlayerData(any()) } returns true
            coEvery { storageService.loadPlayerData(player.uniqueId, "creative", any()) } returns null


            inventoryService.handleWorldChange(player, "world", "creative")
            advanceUntilIdle()

            coVerify { storageService.savePlayerData(any()) }
            coVerify { storageService.loadPlayerData(player.uniqueId, "creative", any()) }
        }

        @Test
        @DisplayName("should save but not switch inventory when staying in same group")
        fun noSwitchSameGroup() = testScope.runTest {
            val player = server.addPlayer()
            val survivalGroup = Group(name = "survival", isDefault = true, worlds = setOf("world", "world_nether"))
            val playerData = PlayerData.fromPlayer(player, "survival")

            every { groupService.getGroupForWorld("world") } returns survivalGroup
            every { groupService.getGroupForWorld("world_nether") } returns survivalGroup
            every { groupService.getGroup("survival") } returns survivalGroup
            coEvery { storageService.savePlayerData(any()) } returns true
            coEvery { storageService.loadPlayerData(player.uniqueId, "survival", any()) } returns playerData

            // Use MockBukkit's built-in PluginManager

            // Simulate player tracked in survival
            inventoryService.handlePlayerJoin(player)
            advanceUntilIdle()

            // Clear verifications from join
            clearMocks(storageService, answers = false)
            coEvery { storageService.savePlayerData(any()) } returns true

            inventoryService.handleWorldChange(player, "world", "world_nether")
            advanceUntilIdle()

            // Should save but not call loadPlayerData for switch
            coVerify { storageService.savePlayerData(any()) }
        }

        @Test
        @DisplayName("should skip world change handling when player has bypass")
        fun skipWorldChangeWithBypass() = testScope.runTest {
            val player = mockk<PlayerMock>(relaxed = true)
            every { player.hasPermission("xinventories.bypass") } returns true
            every { player.uniqueId } returns testUuid1

            inventoryService.handleWorldChange(player, "world", "creative")
            advanceUntilIdle()

            coVerify(exactly = 0) { storageService.savePlayerData(any()) }
            coVerify(exactly = 0) { storageService.loadPlayerData(any(), any(), any()) }
        }
    }

    // ============================================================
    // Handle GameMode Change Tests
    // ============================================================

    @Nested
    @DisplayName("Handle GameMode Change")
    inner class HandleGameModeChangeTests {

        @Test
        @DisplayName("should switch inventory when gamemode changes with separation enabled")
        fun switchOnGameModeChangeWithSeparation() = testScope.runTest {
            val player = server.addPlayer()
            val group = Group(
                name = "survival",
                isDefault = true,
                settings = GroupSettings(separateGameModeInventories = true)
            )
            val playerData = PlayerData.fromPlayer(player, "survival")

            every { groupService.getGroupForWorld(player.world) } returns group
            every { groupService.getGroup("survival") } returns group
            coEvery { storageService.loadPlayerData(player.uniqueId, "survival", any()) } returns playerData
            coEvery { storageService.savePlayerData(any()) } returns true


            // Join to track player
            inventoryService.handlePlayerJoin(player)
            advanceUntilIdle()

            // Clear and setup for gamemode change
            clearMocks(storageService, answers = false)
            coEvery { storageService.loadPlayerData(player.uniqueId, "survival", GameMode.CREATIVE) } returns null
            coEvery { storageService.savePlayerData(any()) } returns true

            inventoryService.handleGameModeChange(player, GameMode.SURVIVAL, GameMode.CREATIVE)
            advanceUntilIdle()

            coVerify { storageService.savePlayerData(any()) }
            coVerify { storageService.loadPlayerData(player.uniqueId, "survival", GameMode.CREATIVE) }
        }

        @Test
        @DisplayName("should not switch inventory when gamemode changes with separation disabled")
        fun noSwitchOnGameModeChangeWithoutSeparation() = testScope.runTest {
            val player = server.addPlayer()
            val group = Group(
                name = "creative",
                settings = GroupSettings(separateGameModeInventories = false)
            )
            val playerData = PlayerData.fromPlayer(player, "creative")

            every { groupService.getGroupForWorld(player.world) } returns group
            every { groupService.getGroup("creative") } returns group
            coEvery { storageService.loadPlayerData(player.uniqueId, "creative", any()) } returns playerData


            // Join to track player
            inventoryService.handlePlayerJoin(player)
            advanceUntilIdle()

            // Clear for gamemode change
            clearMocks(storageService, answers = false)

            inventoryService.handleGameModeChange(player, GameMode.SURVIVAL, GameMode.CREATIVE)
            advanceUntilIdle()

            // Should not save or load for gamemode separation
            coVerify(exactly = 0) { storageService.savePlayerData(any()) }
            coVerify(exactly = 0) { storageService.loadPlayerData(any(), any(), any()) }
        }

        @Test
        @DisplayName("should skip gamemode change handling when player has bypass")
        fun skipGameModeChangeWithBypass() = testScope.runTest {
            val player = mockk<PlayerMock>(relaxed = true)
            every { player.hasPermission("xinventories.bypass") } returns true
            every { player.uniqueId } returns testUuid1

            inventoryService.handleGameModeChange(player, GameMode.SURVIVAL, GameMode.CREATIVE)
            advanceUntilIdle()

            coVerify(exactly = 0) { storageService.savePlayerData(any()) }
            coVerify(exactly = 0) { storageService.loadPlayerData(any(), any(), any()) }
        }

        @Test
        @DisplayName("should clear inventory when no data exists for new gamemode")
        fun clearInventoryForNewGameMode() = testScope.runTest {
            val player = server.addPlayer()
            player.inventory.setItem(0, ItemStack(Material.DIAMOND, 64))

            val group = Group(
                name = "survival",
                isDefault = true,
                settings = GroupSettings(separateGameModeInventories = true)
            )
            val playerData = PlayerData.fromPlayer(player, "survival")

            every { groupService.getGroupForWorld(player.world) } returns group
            every { groupService.getGroup("survival") } returns group
            coEvery { storageService.loadPlayerData(player.uniqueId, "survival", any()) } returns playerData
            coEvery { storageService.savePlayerData(any()) } returns true


            inventoryService.handlePlayerJoin(player)
            advanceUntilIdle()

            clearMocks(storageService, answers = false)
            coEvery { storageService.loadPlayerData(player.uniqueId, "survival", GameMode.CREATIVE) } returns null
            coEvery { storageService.savePlayerData(any()) } returns true

            inventoryService.handleGameModeChange(player, GameMode.SURVIVAL, GameMode.CREATIVE)
            advanceUntilIdle()

            // Execute scheduled tasks (clearPlayerInventory uses scheduler.runTask)
            server.scheduler.performTicks(1)

            // Inventory should be cleared
            Assertions.assertNull(player.inventory.getItem(0))
        }
    }

    // ============================================================
    // Bypass Permission Tests
    // ============================================================

    @Nested
    @DisplayName("Check Bypass Permission")
    inner class BypassPermissionTests {

        @Test
        @DisplayName("should detect global bypass permission")
        fun detectGlobalBypassPermission() {
            val player = mockk<Player>()
            every { player.hasPermission("xinventories.bypass") } returns true
            every { player.hasPermission("xinventories.bypass.survival") } returns false
            every { player.uniqueId } returns testUuid1

            assertTrue(inventoryService.hasBypass(player))
        }

        @Test
        @DisplayName("should detect group-specific bypass permission")
        fun detectGroupBypassPermission() {
            val player = mockk<Player>()
            every { player.hasPermission("xinventories.bypass") } returns false
            every { player.hasPermission("xinventories.bypass.survival") } returns true
            every { player.hasPermission("xinventories.bypass.creative") } returns false
            every { player.uniqueId } returns testUuid1

            assertTrue(inventoryService.hasBypass(player, "survival"))
            assertFalse(inventoryService.hasBypass(player, "creative"))
        }

        @Test
        @DisplayName("should return false when no bypass permission")
        fun noBypassPermission() {
            val player = mockk<Player>()
            every { player.hasPermission("xinventories.bypass") } returns false
            every { player.hasPermission("xinventories.bypass.survival") } returns false
            every { player.uniqueId } returns testUuid1

            assertFalse(inventoryService.hasBypass(player))
            assertFalse(inventoryService.hasBypass(player, "survival"))
        }
    }

    // ============================================================
    // Runtime Bypass Tests
    // ============================================================

    @Nested
    @DisplayName("Runtime Bypass Management")
    inner class RuntimeBypassTests {

        @Test
        @DisplayName("should add global runtime bypass")
        fun addGlobalRuntimeBypass() {
            inventoryService.addBypass(testUuid1)

            assertTrue(inventoryService.hasBypass(testUuid1))
        }

        @Test
        @DisplayName("should add group-specific runtime bypass")
        fun addGroupRuntimeBypass() {
            inventoryService.addBypass(testUuid1, "survival")

            assertTrue(inventoryService.hasBypass(testUuid1, "survival"))
            assertFalse(inventoryService.hasBypass(testUuid1, "creative"))
            assertFalse(inventoryService.hasBypass(testUuid1, null)) // Not global
        }

        @Test
        @DisplayName("should remove runtime bypass")
        fun removeRuntimeBypass() {
            inventoryService.addBypass(testUuid1)
            assertTrue(inventoryService.hasBypass(testUuid1))

            inventoryService.removeBypass(testUuid1)
            assertFalse(inventoryService.hasBypass(testUuid1))
        }

        @Test
        @DisplayName("should remove group-specific bypass")
        fun removeGroupBypass() {
            inventoryService.addBypass(testUuid1, "survival")
            inventoryService.addBypass(testUuid1, "creative")

            inventoryService.removeBypass(testUuid1, "survival")

            assertFalse(inventoryService.hasBypass(testUuid1, "survival"))
            assertTrue(inventoryService.hasBypass(testUuid1, "creative"))
        }

        @Test
        @DisplayName("should get all bypasses")
        fun getAllBypasses() {
            inventoryService.addBypass(testUuid1)
            inventoryService.addBypass(testUuid2, "survival")
            inventoryService.addBypass(testUuid2, "creative")

            val bypasses = inventoryService.getBypasses()

            assertEquals(2, bypasses.size)
            assertTrue(bypasses[testUuid1]?.contains(null) == true)
            assertTrue(bypasses[testUuid2]?.contains("survival") == true)
            assertTrue(bypasses[testUuid2]?.contains("creative") == true)
        }

        @Test
        @DisplayName("should check bypass via Player object with runtime bypass")
        fun checkBypassViaPlayerWithRuntimeBypass() {
            val player = mockk<Player>()
            every { player.hasPermission("xinventories.bypass") } returns false
            every { player.hasPermission("xinventories.bypass.survival") } returns false
            every { player.uniqueId } returns testUuid1

            inventoryService.addBypass(testUuid1)

            assertTrue(inventoryService.hasBypass(player))
        }
    }

    // ============================================================
    // Save Inventory Manually Tests
    // ============================================================

    @Nested
    @DisplayName("Save Inventory Manually")
    inner class SaveInventoryManuallyTests {

        @Test
        @DisplayName("should save inventory with specified group")
        fun saveWithSpecifiedGroup() = testScope.runTest {
            val player = server.addPlayer()
            val group = Group(name = "survival", isDefault = true)

            every { groupService.getGroup("survival") } returns group
            coEvery { storageService.savePlayerData(any()) } returns true

            // Use MockBukkit's built-in PluginManager

            val result = inventoryService.saveInventory(player, "survival")

            assertTrue(result)
            coVerify { storageService.savePlayerData(match { it.group == "survival" }) }
        }

        @Test
        @DisplayName("should save inventory with current group when not specified")
        fun saveWithCurrentGroup() = testScope.runTest {
            val player = server.addPlayer()
            val group = Group(name = "survival", isDefault = true)
            val playerData = PlayerData.fromPlayer(player, "survival")

            every { groupService.getGroupForWorld(player.world) } returns group
            every { groupService.getGroup("survival") } returns group
            coEvery { storageService.loadPlayerData(player.uniqueId, "survival", any()) } returns playerData
            coEvery { storageService.savePlayerData(any()) } returns true


            // Track player
            inventoryService.handlePlayerJoin(player)
            advanceUntilIdle()

            clearMocks(storageService, answers = false)
            coEvery { storageService.savePlayerData(any()) } returns true

            val result = inventoryService.saveInventory(player)

            assertTrue(result)
            coVerify { storageService.savePlayerData(match { it.group == "survival" }) }
        }

        @Test
        @Disabled("MockBukkit's PluginManagerMock.callEvent() cannot be properly mocked with MockK")
        @DisplayName("should fire InventorySaveEvent when saving")
        fun fireInventorySaveEvent() = testScope.runTest {
            val player = server.addPlayer()
            val group = Group(name = "survival", isDefault = true)

            every { groupService.getGroup("survival") } returns group
            coEvery { storageService.savePlayerData(any()) } returns true

            val capturedEvents = mutableListOf<Event>()
            // Use MockBukkit's built-in PluginManager
            every { server.pluginManager.callEvent(any()) } answers {
                capturedEvents.add(firstArg())
                Unit
            }

            inventoryService.saveInventory(player, "survival")

            assertTrue(capturedEvents.any { it is InventorySaveEvent })
        }

        @Test
        @DisplayName("should return false when group not found")
        fun returnFalseWhenGroupNotFound() = testScope.runTest {
            val player = server.addPlayer()

            every { groupService.getGroup("nonexistent") } returns null
            every { groupService.getGroupForWorld(player.world) } returns Group(name = "default")

            val result = inventoryService.saveInventory(player, "nonexistent")

            assertFalse(result)
        }
    }

    // ============================================================
    // Load Inventory Manually Tests
    // ============================================================

    @Nested
    @DisplayName("Load Inventory Manually")
    inner class LoadInventoryManuallyTests {

        @Test
        @DisplayName("should load inventory for specified group")
        fun loadForSpecifiedGroup() = testScope.runTest {
            val player = server.addPlayer()
            val group = InventoryGroup(
                name = "survival",
                worlds = setOf("world"),
                patterns = emptyList(),
                priority = 0,
                parent = null,
                settings = GroupSettings(),
                isDefault = true
            )
            val playerData = PlayerData.fromPlayer(player, "survival")

            coEvery { storageService.loadPlayerData(player.uniqueId, "survival", any()) } returns playerData


            val result = inventoryService.loadInventory(
                player,
                group,
                InventoryLoadEvent.LoadReason.COMMAND
            )

            assertTrue(result)
            coVerify { storageService.loadPlayerData(player.uniqueId, "survival", any()) }
        }

        @Test
        @Disabled("MockBukkit's PluginManagerMock.callEvent() cannot be properly mocked with MockK")
        @DisplayName("should fire InventoryLoadEvent when loading")
        fun fireInventoryLoadEvent() = testScope.runTest {
            val player = server.addPlayer()
            val group = InventoryGroup(
                name = "survival",
                worlds = setOf("world"),
                patterns = emptyList(),
                priority = 0,
                parent = null,
                settings = GroupSettings(),
                isDefault = true
            )
            val playerData = PlayerData.fromPlayer(player, "survival")

            coEvery { storageService.loadPlayerData(player.uniqueId, "survival", any()) } returns playerData

            val capturedEvents = mutableListOf<Event>()
            // Use MockBukkit's built-in PluginManager
            every { server.pluginManager.callEvent(any()) } answers {
                capturedEvents.add(firstArg())
                Unit
            }

            inventoryService.loadInventory(
                player,
                group,
                InventoryLoadEvent.LoadReason.COMMAND
            )

            assertTrue(capturedEvents.any { it is InventoryLoadEvent })
        }

        @Test
        @Disabled("MockBukkit's PluginManagerMock.callEvent() cannot be properly mocked with MockK")
        @DisplayName("should return false when event is cancelled")
        fun returnFalseWhenEventCancelled() = testScope.runTest {
            val player = server.addPlayer()
            val group = InventoryGroup(
                name = "survival",
                worlds = setOf("world"),
                patterns = emptyList(),
                priority = 0,
                parent = null,
                settings = GroupSettings(),
                isDefault = true
            )

            coEvery { storageService.loadPlayerData(player.uniqueId, "survival", any()) } returns null

            // Use MockBukkit's built-in PluginManager
            every { server.pluginManager.callEvent(any()) } answers {
                val event = firstArg<Event>()
                if (event is InventoryLoadEvent) {
                    event.isCancelled = true
                }
                Unit
            }

            val result = inventoryService.loadInventory(
                player,
                group,
                InventoryLoadEvent.LoadReason.API
            )

            assertFalse(result)
        }

        @Test
        @DisplayName("should track active snapshot after load")
        fun trackActiveSnapshotAfterLoad() = testScope.runTest {
            val player = server.addPlayer()
            val group = InventoryGroup(
                name = "survival",
                worlds = setOf("world"),
                patterns = emptyList(),
                priority = 0,
                parent = null,
                settings = GroupSettings(),
                isDefault = true
            )
            val playerData = PlayerData.fromPlayer(player, "survival")

            coEvery { storageService.loadPlayerData(player.uniqueId, "survival", any()) } returns playerData


            inventoryService.loadInventory(
                player,
                group,
                InventoryLoadEvent.LoadReason.COMMAND
            )

            val snapshot = inventoryService.getActiveSnapshot(player)
            Assertions.assertNotNull(snapshot)
            assertEquals(player.uniqueId, snapshot?.uuid)
        }
    }

    // ============================================================
    // Switch Inventory Programmatically Tests
    // ============================================================

    @Nested
    @DisplayName("Switch Inventory Programmatically")
    inner class SwitchInventoryTests {

        @Test
        @DisplayName("should switch inventory between groups")
        fun switchBetweenGroups() = testScope.runTest {
            val player = server.addPlayer()
            val fromGroup = InventoryGroup(
                name = "survival",
                worlds = setOf("world"),
                patterns = emptyList(),
                priority = 0,
                parent = null,
                settings = GroupSettings(),
                isDefault = true
            )
            val toGroup = InventoryGroup(
                name = "creative",
                worlds = setOf("creative"),
                patterns = emptyList(),
                priority = 0,
                parent = null,
                settings = GroupSettings(),
                isDefault = false
            )
            val playerData = PlayerData.fromPlayer(player, "creative")

            coEvery { storageService.savePlayerData(any()) } returns true
            coEvery { storageService.loadPlayerData(player.uniqueId, "creative", any()) } returns playerData


            val result = inventoryService.switchInventory(
                player,
                fromGroup,
                toGroup,
                InventorySwitchEvent.SwitchReason.COMMAND
            )

            assertTrue(result)
            assertEquals("creative", inventoryService.getCurrentGroup(player))
        }

        @Test
        @Disabled("MockBukkit's PluginManagerMock.callEvent() cannot be properly mocked with MockK")
        @DisplayName("should fire InventorySwitchEvent when switching")
        fun fireInventorySwitchEvent() = testScope.runTest {
            val player = server.addPlayer()
            val fromGroup = InventoryGroup(
                name = "survival",
                worlds = setOf("world"),
                patterns = emptyList(),
                priority = 0,
                parent = null,
                settings = GroupSettings(),
                isDefault = true
            )
            val toGroup = InventoryGroup(
                name = "creative",
                worlds = setOf("creative"),
                patterns = emptyList(),
                priority = 0,
                parent = null,
                settings = GroupSettings(),
                isDefault = false
            )

            coEvery { storageService.savePlayerData(any()) } returns true
            coEvery { storageService.loadPlayerData(player.uniqueId, "creative", any()) } returns null

            val capturedEvents = mutableListOf<Event>()
            // Use MockBukkit's built-in PluginManager
            every { server.pluginManager.callEvent(any()) } answers {
                capturedEvents.add(firstArg())
                Unit
            }

            inventoryService.switchInventory(
                player,
                fromGroup,
                toGroup,
                InventorySwitchEvent.SwitchReason.API
            )

            assertTrue(capturedEvents.any { it is InventorySwitchEvent })
        }

        @Test
        @Disabled("MockBukkit's PluginManagerMock.callEvent() cannot be properly mocked with MockK")
        @DisplayName("should return false when switch event is cancelled")
        fun returnFalseWhenSwitchCancelled() = testScope.runTest {
            val player = server.addPlayer()
            val fromGroup = InventoryGroup(
                name = "survival",
                worlds = setOf("world"),
                patterns = emptyList(),
                priority = 0,
                parent = null,
                settings = GroupSettings(),
                isDefault = true
            )
            val toGroup = InventoryGroup(
                name = "creative",
                worlds = setOf("creative"),
                patterns = emptyList(),
                priority = 0,
                parent = null,
                settings = GroupSettings(),
                isDefault = false
            )

            // Use MockBukkit's built-in PluginManager
            every { server.pluginManager.callEvent(any()) } answers {
                val event = firstArg<Event>()
                if (event is InventorySwitchEvent) {
                    event.isCancelled = true
                }
                Unit
            }

            val result = inventoryService.switchInventory(
                player,
                fromGroup,
                toGroup,
                InventorySwitchEvent.SwitchReason.PLUGIN
            )

            assertFalse(result)
            coVerify(exactly = 0) { storageService.savePlayerData(any()) }
        }

        @Test
        @Disabled("MockBukkit's PluginManagerMock.callEvent() cannot be properly mocked with MockK")
        @DisplayName("should use override snapshot when provided in event")
        fun useOverrideSnapshot() = testScope.runTest {
            val player = server.addPlayer()
            val fromGroup = InventoryGroup(
                name = "survival",
                worlds = setOf("world"),
                patterns = emptyList(),
                priority = 0,
                parent = null,
                settings = GroupSettings(),
                isDefault = true
            )
            val toGroup = InventoryGroup(
                name = "creative",
                worlds = setOf("creative"),
                patterns = emptyList(),
                priority = 0,
                parent = null,
                settings = GroupSettings(),
                isDefault = false
            )
            val overrideSnapshot = PlayerInventorySnapshot.empty(
                player.uniqueId,
                player.name,
                "creative",
                GameMode.CREATIVE
            )

            coEvery { storageService.savePlayerData(any()) } returns true

            // Use MockBukkit's built-in PluginManager
            every { server.pluginManager.callEvent(any()) } answers {
                val event = firstArg<Event>()
                if (event is InventorySwitchEvent) {
                    event.overrideSnapshot = overrideSnapshot
                }
                Unit
            }

            inventoryService.switchInventory(
                player,
                fromGroup,
                toGroup,
                InventorySwitchEvent.SwitchReason.COMMAND
            )

            // Should not call loadPlayerData when override is provided
            coVerify(exactly = 0) { storageService.loadPlayerData(any(), any(), any()) }
        }
    }

    // ============================================================
    // Get Active Snapshot Tests
    // ============================================================

    @Nested
    @DisplayName("Get Active Snapshot")
    inner class GetActiveSnapshotTests {

        @Test
        @DisplayName("should return null for untracked player")
        fun returnNullForUntrackedPlayer() {
            val player = server.addPlayer()

            val snapshot = inventoryService.getActiveSnapshot(player)

            Assertions.assertNull(snapshot)
        }

        @Test
        @DisplayName("should return snapshot for tracked player")
        fun returnSnapshotForTrackedPlayer() = testScope.runTest {
            val player = server.addPlayer()
            val group = InventoryGroup(
                name = "survival",
                worlds = setOf("world"),
                patterns = emptyList(),
                priority = 0,
                parent = null,
                settings = GroupSettings(),
                isDefault = true
            )
            val playerData = PlayerData.fromPlayer(player, "survival")

            coEvery { storageService.loadPlayerData(player.uniqueId, "survival", any()) } returns playerData


            inventoryService.loadInventory(player, group, InventoryLoadEvent.LoadReason.JOIN)

            val snapshot = inventoryService.getActiveSnapshot(player)
            Assertions.assertNotNull(snapshot)
            assertEquals(player.uniqueId, snapshot?.uuid)
            assertEquals("survival", snapshot?.group)
        }
    }

    // ============================================================
    // Clear Player Data Tests
    // ============================================================

    @Nested
    @DisplayName("Clear Player Data")
    inner class ClearPlayerDataTests {

        @Test
        @DisplayName("should delete player data for group")
        fun deletePlayerDataForGroup() = testScope.runTest {
            coEvery { storageService.deletePlayerData(testUuid1, "survival", null) } returns true

            val result = inventoryService.clearPlayerData(testUuid1, "survival", null)

            assertTrue(result)
            coVerify { storageService.deletePlayerData(testUuid1, "survival", null) }
        }

        @Test
        @DisplayName("should delete player data for group and gamemode")
        fun deletePlayerDataForGroupAndGameMode() = testScope.runTest {
            coEvery { storageService.deletePlayerData(testUuid1, "survival", GameMode.CREATIVE) } returns true

            val result = inventoryService.clearPlayerData(testUuid1, "survival", GameMode.CREATIVE)

            assertTrue(result)
            coVerify { storageService.deletePlayerData(testUuid1, "survival", GameMode.CREATIVE) }
        }
    }

    // ============================================================
    // Event Firing Tests
    // ============================================================

    @Nested
    @DisplayName("Event Firing")
    inner class EventFiringTests {

        @Test
        @Disabled("MockBukkit's PluginManagerMock.callEvent() cannot be properly mocked with MockK")
        @DisplayName("should fire InventoryLoadEvent with correct data")
        fun fireInventoryLoadEventWithCorrectData() = testScope.runTest {
            val player = server.addPlayer()
            val group = InventoryGroup(
                name = "survival",
                worlds = setOf("world"),
                patterns = emptyList(),
                priority = 0,
                parent = null,
                settings = GroupSettings(),
                isDefault = true
            )
            val playerData = PlayerData.fromPlayer(player, "survival")

            coEvery { storageService.loadPlayerData(player.uniqueId, "survival", any()) } returns playerData

            var capturedEvent: InventoryLoadEvent? = null
            // Use MockBukkit's built-in PluginManager
            every { server.pluginManager.callEvent(any()) } answers {
                val event = firstArg<Event>()
                if (event is InventoryLoadEvent) {
                    capturedEvent = event
                }
                Unit
            }

            inventoryService.loadInventory(player, group, InventoryLoadEvent.LoadReason.JOIN)

            Assertions.assertNotNull(capturedEvent)
            assertEquals(player, capturedEvent?.player)
            assertEquals(group, capturedEvent?.group)
            assertEquals(InventoryLoadEvent.LoadReason.JOIN, capturedEvent?.reason)
        }

        @Test
        @Disabled("MockBukkit's PluginManagerMock.callEvent() cannot be properly mocked with MockK")
        @DisplayName("should fire InventorySaveEvent with correct data")
        fun fireInventorySaveEventWithCorrectData() = testScope.runTest {
            val player = server.addPlayer()
            val group = Group(name = "survival", isDefault = true)

            every { groupService.getGroup("survival") } returns group
            coEvery { storageService.savePlayerData(any()) } returns true

            var capturedEvent: InventorySaveEvent? = null
            // Use MockBukkit's built-in PluginManager
            every { server.pluginManager.callEvent(any()) } answers {
                val event = firstArg<Event>()
                if (event is InventorySaveEvent) {
                    capturedEvent = event
                }
                Unit
            }

            inventoryService.saveInventory(player, "survival")

            Assertions.assertNotNull(capturedEvent)
            assertEquals(player, capturedEvent?.player)
            assertEquals("survival", capturedEvent?.group?.name)
        }

        @Test
        @Disabled("MockBukkit's PluginManagerMock.callEvent() cannot be properly mocked with MockK")
        @DisplayName("should fire InventorySwitchEvent with correct data")
        fun fireInventorySwitchEventWithCorrectData() = testScope.runTest {
            val player = server.addPlayer()
            val fromGroup = InventoryGroup(
                name = "survival",
                worlds = setOf("world"),
                patterns = emptyList(),
                priority = 0,
                parent = null,
                settings = GroupSettings(),
                isDefault = true
            )
            val toGroup = InventoryGroup(
                name = "creative",
                worlds = setOf("creative"),
                patterns = emptyList(),
                priority = 0,
                parent = null,
                settings = GroupSettings(),
                isDefault = false
            )

            coEvery { storageService.savePlayerData(any()) } returns true
            coEvery { storageService.loadPlayerData(any(), any(), any()) } returns null

            var capturedEvent: InventorySwitchEvent? = null
            // Use MockBukkit's built-in PluginManager
            every { server.pluginManager.callEvent(any()) } answers {
                val event = firstArg<Event>()
                if (event is InventorySwitchEvent) {
                    capturedEvent = event
                }
                Unit
            }

            inventoryService.switchInventory(
                player,
                fromGroup,
                toGroup,
                InventorySwitchEvent.SwitchReason.WORLD_CHANGE
            )

            Assertions.assertNotNull(capturedEvent)
            assertEquals(player, capturedEvent?.player)
            assertEquals(fromGroup, capturedEvent?.fromGroup)
            assertEquals(toGroup, capturedEvent?.toGroup)
            assertEquals(InventorySwitchEvent.SwitchReason.WORLD_CHANGE, capturedEvent?.reason)
        }
    }

    // ============================================================
    // Notify Player On Switch Tests
    // ============================================================

    @Nested
    @DisplayName("Notify Player On Switch")
    inner class NotifyPlayerOnSwitchTests {

        @Test
        @DisplayName("should send message when notifyOnSwitch is enabled")
        fun sendMessageWhenNotifyEnabled() = testScope.runTest {
            val player = server.addPlayer()
            val fromGroup = InventoryGroup(
                name = "survival",
                worlds = setOf("world"),
                patterns = emptyList(),
                priority = 0,
                parent = null,
                settings = GroupSettings(),
                isDefault = true
            )
            val toGroup = InventoryGroup(
                name = "creative",
                worlds = setOf("creative"),
                patterns = emptyList(),
                priority = 0,
                parent = null,
                settings = GroupSettings(),
                isDefault = false
            )

            val groupsConfig = GroupsConfig(
                globalSettings = GlobalSettings(notifyOnSwitch = true)
            )
            every { configManager.groupsConfig } returns groupsConfig
            coEvery { storageService.savePlayerData(any()) } returns true
            coEvery { storageService.loadPlayerData(any(), any(), any()) } returns null


            inventoryService.switchInventory(
                player,
                fromGroup,
                toGroup,
                InventorySwitchEvent.SwitchReason.WORLD_CHANGE
            )

            verify {
                messageService.send(
                    player,
                    "inventory-switched",
                    "from_group" to "survival",
                    "to_group" to "creative"
                )
            }
        }

        @Test
        @DisplayName("should not send message when notifyOnSwitch is disabled")
        fun noMessageWhenNotifyDisabled() = testScope.runTest {
            val player = server.addPlayer()
            val fromGroup = InventoryGroup(
                name = "survival",
                worlds = setOf("world"),
                patterns = emptyList(),
                priority = 0,
                parent = null,
                settings = GroupSettings(),
                isDefault = true
            )
            val toGroup = InventoryGroup(
                name = "creative",
                worlds = setOf("creative"),
                patterns = emptyList(),
                priority = 0,
                parent = null,
                settings = GroupSettings(),
                isDefault = false
            )

            val groupsConfig = GroupsConfig(
                globalSettings = GlobalSettings(notifyOnSwitch = false)
            )
            every { configManager.groupsConfig } returns groupsConfig
            coEvery { storageService.savePlayerData(any()) } returns true
            coEvery { storageService.loadPlayerData(any(), any(), any()) } returns null


            inventoryService.switchInventory(
                player,
                fromGroup,
                toGroup,
                InventorySwitchEvent.SwitchReason.WORLD_CHANGE
            )

            verify(exactly = 0) { messageService.send(any(), any(), *anyVararg()) }
        }
    }

    // ============================================================
    // Play Sound On Switch Tests
    // ============================================================

    @Nested
    @DisplayName("Play Sound On Switch")
    inner class PlaySoundOnSwitchTests {

        @Test
        @Disabled("Complex coroutine context interaction with MockK causes ClassCastException")
        @DisplayName("should play sound when configured")
        fun playSoundWhenConfigured() = testScope.runTest {
            val player = mockk<PlayerMock>(relaxed = true)
            every { player.uniqueId } returns testUuid1
            every { player.name } returns "TestPlayer"
            every { player.gameMode } returns GameMode.SURVIVAL
            every { player.world } returns mockk(relaxed = true)
            every { player.hasPermission(any<String>()) } returns false

            val fromGroup = InventoryGroup(
                name = "survival",
                worlds = setOf("world"),
                patterns = emptyList(),
                priority = 0,
                parent = null,
                settings = GroupSettings(),
                isDefault = true
            )
            val toGroup = InventoryGroup(
                name = "creative",
                worlds = setOf("creative"),
                patterns = emptyList(),
                priority = 0,
                parent = null,
                settings = GroupSettings(),
                isDefault = false
            )

            val groupsConfig = GroupsConfig(
                globalSettings = GlobalSettings(
                    notifyOnSwitch = false,
                    switchSound = "ENTITY_ENDERMAN_TELEPORT",
                    switchSoundVolume = 0.5f,
                    switchSoundPitch = 1.2f
                )
            )
            every { configManager.groupsConfig } returns groupsConfig
            coEvery { storageService.savePlayerData(any()) } returns true
            coEvery { storageService.loadPlayerData(any(), any(), any()) } returns null


            inventoryService.switchInventory(
                player,
                fromGroup,
                toGroup,
                InventorySwitchEvent.SwitchReason.WORLD_CHANGE
            )

            verify {
                player.playSound(
                    any<org.bukkit.Location>(),
                    Sound.ENTITY_ENDERMAN_TELEPORT,
                    0.5f,
                    1.2f
                )
            }
        }

        @Test
        @Disabled("Complex coroutine context interaction with MockK causes ClassCastException")
        @DisplayName("should not play sound when switchSound is null")
        fun noSoundWhenNull() = testScope.runTest {
            val player = mockk<PlayerMock>(relaxed = true)
            every { player.uniqueId } returns testUuid1
            every { player.name } returns "TestPlayer"
            every { player.gameMode } returns GameMode.SURVIVAL
            every { player.world } returns mockk(relaxed = true)
            every { player.hasPermission(any<String>()) } returns false

            val fromGroup = InventoryGroup(
                name = "survival",
                worlds = setOf("world"),
                patterns = emptyList(),
                priority = 0,
                parent = null,
                settings = GroupSettings(),
                isDefault = true
            )
            val toGroup = InventoryGroup(
                name = "creative",
                worlds = setOf("creative"),
                patterns = emptyList(),
                priority = 0,
                parent = null,
                settings = GroupSettings(),
                isDefault = false
            )

            val groupsConfig = GroupsConfig(
                globalSettings = GlobalSettings(
                    notifyOnSwitch = false,
                    switchSound = null
                )
            )
            every { configManager.groupsConfig } returns groupsConfig
            coEvery { storageService.savePlayerData(any()) } returns true
            coEvery { storageService.loadPlayerData(any(), any(), any()) } returns null


            inventoryService.switchInventory(
                player,
                fromGroup,
                toGroup,
                InventorySwitchEvent.SwitchReason.WORLD_CHANGE
            )

            verify(exactly = 0) { player.playSound(any<org.bukkit.Location>(), any<Sound>(), any<Float>(), any<Float>()) }
        }
    }

    // ============================================================
    // Event Cancellation Prevents Action Tests
    // ============================================================

    @Nested
    @DisplayName("Event Cancellation Prevents Action")
    inner class EventCancellationTests {

        @Test
        @Disabled("MockBukkit's PluginManagerMock.callEvent() cannot be properly mocked with MockK")
        @DisplayName("should not apply inventory when InventoryLoadEvent is cancelled")
        fun noApplyWhenLoadCancelled() = testScope.runTest {
            val player = server.addPlayer()
            player.inventory.setItem(0, ItemStack(Material.DIAMOND, 64))

            val group = InventoryGroup(
                name = "survival",
                worlds = setOf("world"),
                patterns = emptyList(),
                priority = 0,
                parent = null,
                settings = GroupSettings(),
                isDefault = true
            )
            val newData = PlayerData.empty(player.uniqueId, player.name, "survival", GameMode.SURVIVAL)

            coEvery { storageService.loadPlayerData(player.uniqueId, "survival", any()) } returns newData

            // Use MockBukkit's built-in PluginManager
            every { server.pluginManager.callEvent(any()) } answers {
                val event = firstArg<Event>()
                if (event is InventoryLoadEvent) {
                    event.isCancelled = true
                }
                Unit
            }

            inventoryService.loadInventory(player, group, InventoryLoadEvent.LoadReason.COMMAND)

            // Inventory should still have the diamond
            assertEquals(Material.DIAMOND, player.inventory.getItem(0)?.type)
        }

        @Test
        @Disabled("MockBukkit's PluginManagerMock.callEvent() cannot be properly mocked with MockK")
        @DisplayName("should not save when InventorySwitchEvent is cancelled")
        fun noSaveWhenSwitchCancelled() = testScope.runTest {
            val player = server.addPlayer()
            val fromGroup = InventoryGroup(
                name = "survival",
                worlds = setOf("world"),
                patterns = emptyList(),
                priority = 0,
                parent = null,
                settings = GroupSettings(),
                isDefault = true
            )
            val toGroup = InventoryGroup(
                name = "creative",
                worlds = setOf("creative"),
                patterns = emptyList(),
                priority = 0,
                parent = null,
                settings = GroupSettings(),
                isDefault = false
            )

            // Use MockBukkit's built-in PluginManager
            every { server.pluginManager.callEvent(any()) } answers {
                val event = firstArg<Event>()
                if (event is InventorySwitchEvent) {
                    event.isCancelled = true
                }
                Unit
            }

            inventoryService.switchInventory(
                player,
                fromGroup,
                toGroup,
                InventorySwitchEvent.SwitchReason.COMMAND
            )

            coVerify(exactly = 0) { storageService.savePlayerData(any()) }
            coVerify(exactly = 0) { storageService.loadPlayerData(any(), any(), any()) }
        }
    }

    // ============================================================
    // Current Group Tracking Tests
    // ============================================================

    @Nested
    @DisplayName("Current Group Tracking")
    inner class CurrentGroupTrackingTests {

        @Test
        @DisplayName("should return null for untracked player")
        fun returnNullForUntrackedPlayer() {
            val player = server.addPlayer()

            val group = inventoryService.getCurrentGroup(player)

            Assertions.assertNull(group)
        }

        @Test
        @DisplayName("should return current group for tracked player")
        fun returnCurrentGroupForTrackedPlayer() = testScope.runTest {
            val player = server.addPlayer()
            val group = Group(name = "survival", isDefault = true)
            val playerData = PlayerData.fromPlayer(player, "survival")

            every { groupService.getGroupForWorld(player.world) } returns group
            coEvery { storageService.loadPlayerData(player.uniqueId, "survival", any()) } returns playerData


            inventoryService.handlePlayerJoin(player)
            advanceUntilIdle()

            assertEquals("survival", inventoryService.getCurrentGroup(player))
        }

        @Test
        @DisplayName("should update current group after switch")
        fun updateCurrentGroupAfterSwitch() = testScope.runTest {
            val player = server.addPlayer()
            val fromGroup = InventoryGroup(
                name = "survival",
                worlds = setOf("world"),
                patterns = emptyList(),
                priority = 0,
                parent = null,
                settings = GroupSettings(),
                isDefault = true
            )
            val toGroup = InventoryGroup(
                name = "creative",
                worlds = setOf("creative"),
                patterns = emptyList(),
                priority = 0,
                parent = null,
                settings = GroupSettings(),
                isDefault = false
            )

            coEvery { storageService.savePlayerData(any()) } returns true
            coEvery { storageService.loadPlayerData(any(), any(), any()) } returns null


            inventoryService.switchInventory(
                player,
                fromGroup,
                toGroup,
                InventorySwitchEvent.SwitchReason.COMMAND
            )

            assertEquals("creative", inventoryService.getCurrentGroup(player))
        }
    }
}
