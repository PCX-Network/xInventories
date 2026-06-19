package sh.pcx.xinventories.integration.listener

import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.junit.jupiter.api.*
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import sh.pcx.xinventories.XInventories
import sh.pcx.xinventories.internal.listener.ConnectionListener
import sh.pcx.xinventories.internal.service.InventoryService
import sh.pcx.xinventories.internal.service.ServiceManager
import sh.pcx.xinventories.internal.util.Logging
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Comprehensive tests for ConnectionListener.
 *
 * Tests cover:
 * - Player join triggers inventory loading
 * - Player quit triggers inventory saving
 * - Coroutine error handling
 * - Service delegation
 */
@DisplayName("ConnectionListener Tests")
class ConnectionListenerTest {

    companion object {
        @JvmStatic
        @BeforeAll
        fun setupAll() {
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
    private lateinit var serviceManager: ServiceManager
    private lateinit var listener: ConnectionListener

    private val testUuid = UUID.fromString("00000000-0000-0000-0000-000000000001")

    @BeforeEach
    fun setUp() {
        server = MockBukkit.mock()

        // Create mocks
        plugin = mockk(relaxed = true)
        inventoryService = mockk(relaxed = true)
        serviceManager = mockk(relaxed = true)

        // Configure plugin mock
        every { plugin.serviceManager } returns serviceManager
        every { serviceManager.inventoryService } returns inventoryService
        every { plugin.plugin } returns plugin
        every { plugin.server } returns server

        // Make plugin.launch execute blocks immediately using Unconfined dispatcher
        val testJob = SupervisorJob()
        every { plugin.coroutineContext } returns (testJob + Dispatchers.Unconfined)

        // Create listener
        listener = ConnectionListener(plugin)
    }

    @AfterEach
    fun tearDown() {
        MockBukkit.unmock()
        clearAllMocks()
    }

    // ============================================================
    // Player Join Event Tests
    // ============================================================

    @Nested
    @DisplayName("Player Join Event")
    inner class PlayerJoinEventTests {

        @Test
        @DisplayName("should call handlePlayerJoin on inventory service when player joins")
        fun shouldCallHandlePlayerJoinOnJoin() {
            val player = server.addPlayer()
            val event = PlayerJoinEvent(player, Component.text("joined"))

            coEvery { inventoryService.handlePlayerJoin(player) } just Runs

            listener.onPlayerJoin(event)

            coVerify(exactly = 1) { inventoryService.handlePlayerJoin(player) }
        }

        @Test
        @DisplayName("should pass the correct player to inventory service")
        fun shouldPassCorrectPlayerToService() {
            val player = server.addPlayer("TestPlayer")
            val event = PlayerJoinEvent(player, Component.text("joined"))

            val capturedPlayer = slot<Player>()
            coEvery { inventoryService.handlePlayerJoin(capture(capturedPlayer)) } just Runs

            listener.onPlayerJoin(event)

            assertTrue(capturedPlayer.isCaptured)
            assertEquals("TestPlayer", capturedPlayer.captured.name)
        }

        @Test
        @DisplayName("should handle exceptions gracefully during player join")
        fun shouldHandleExceptionsDuringJoin() {
            val player = server.addPlayer()
            val event = PlayerJoinEvent(player, Component.text("joined"))

            coEvery { inventoryService.handlePlayerJoin(any()) } throws RuntimeException("Test exception")

            // Should not throw - exception is caught and logged
            assertDoesNotThrow {
                listener.onPlayerJoin(event)
            }

            verify { Logging.error(match { it.contains("Failed to load inventory") }, any()) }
        }

        @Test
        @DisplayName("should handle multiple concurrent player joins")
        fun shouldHandleMultipleConcurrentJoins() {
            val player1 = server.addPlayer("Player1")
            val player2 = server.addPlayer("Player2")
            val player3 = server.addPlayer("Player3")

            coEvery { inventoryService.handlePlayerJoin(any()) } just Runs

            listener.onPlayerJoin(PlayerJoinEvent(player1, Component.text("joined")))
            listener.onPlayerJoin(PlayerJoinEvent(player2, Component.text("joined")))
            listener.onPlayerJoin(PlayerJoinEvent(player3, Component.text("joined")))

            coVerify(exactly = 1) { inventoryService.handlePlayerJoin(player1) }
            coVerify(exactly = 1) { inventoryService.handlePlayerJoin(player2) }
            coVerify(exactly = 1) { inventoryService.handlePlayerJoin(player3) }
        }

        @Test
        @DisplayName("should log debug message on player join")
        fun shouldLogDebugOnJoin() {
            val player = server.addPlayer("TestPlayer")
            val event = PlayerJoinEvent(player, Component.text("joined"))

            coEvery { inventoryService.handlePlayerJoin(any()) } just Runs

            listener.onPlayerJoin(event)

            verify { Logging.debug(any<() -> String>()) }
        }
    }

    // ============================================================
    // Player Quit Event Tests
    // ============================================================

    @Nested
    @DisplayName("Player Quit Event")
    inner class PlayerQuitEventTests {

        @Test
        @DisplayName("should call handlePlayerQuit on inventory service when player quits")
        fun shouldCallHandlePlayerQuitOnQuit() {
            val player = server.addPlayer()
            val event = PlayerQuitEvent(player, Component.text("quit"), PlayerQuitEvent.QuitReason.DISCONNECTED)

            coEvery { inventoryService.handlePlayerQuit(player, any()) } just Runs

            listener.onPlayerQuit(event)

            coVerify(exactly = 1) { inventoryService.handlePlayerQuit(player, any()) }
        }

        @Test
        @DisplayName("should pass the correct player to inventory service on quit")
        fun shouldPassCorrectPlayerToServiceOnQuit() {
            val player = server.addPlayer("QuittingPlayer")
            val event = PlayerQuitEvent(player, Component.text("quit"), PlayerQuitEvent.QuitReason.DISCONNECTED)

            val capturedPlayer = slot<Player>()
            coEvery { inventoryService.handlePlayerQuit(capture(capturedPlayer), any()) } just Runs

            listener.onPlayerQuit(event)

            assertTrue(capturedPlayer.isCaptured)
            assertEquals("QuittingPlayer", capturedPlayer.captured.name)
        }

        @Test
        @DisplayName("should handle exceptions gracefully during player quit")
        fun shouldHandleExceptionsDuringQuit() {
            val player = server.addPlayer()
            val event = PlayerQuitEvent(player, Component.text("quit"), PlayerQuitEvent.QuitReason.DISCONNECTED)

            coEvery { inventoryService.handlePlayerQuit(any(), any()) } throws RuntimeException("Test exception")

            // Should not throw - exception is caught and logged
            assertDoesNotThrow {
                listener.onPlayerQuit(event)
            }

            verify { Logging.error(match { it.contains("Failed to save inventory") }, any()) }
        }

        @Test
        @DisplayName("should handle multiple concurrent player quits")
        fun shouldHandleMultipleConcurrentQuits() {
            val player1 = server.addPlayer("Player1")
            val player2 = server.addPlayer("Player2")
            val player3 = server.addPlayer("Player3")

            coEvery { inventoryService.handlePlayerQuit(any(), any()) } just Runs

            listener.onPlayerQuit(PlayerQuitEvent(player1, Component.text("quit"), PlayerQuitEvent.QuitReason.DISCONNECTED))
            listener.onPlayerQuit(PlayerQuitEvent(player2, Component.text("quit"), PlayerQuitEvent.QuitReason.KICKED))
            listener.onPlayerQuit(PlayerQuitEvent(player3, Component.text("quit"), PlayerQuitEvent.QuitReason.TIMED_OUT))

            coVerify(exactly = 1) { inventoryService.handlePlayerQuit(player1, any()) }
            coVerify(exactly = 1) { inventoryService.handlePlayerQuit(player2, any()) }
            coVerify(exactly = 1) { inventoryService.handlePlayerQuit(player3, any()) }
        }

        @Test
        @DisplayName("should log debug message on player quit")
        fun shouldLogDebugOnQuit() {
            val player = server.addPlayer("TestPlayer")
            val event = PlayerQuitEvent(player, Component.text("quit"), PlayerQuitEvent.QuitReason.DISCONNECTED)

            coEvery { inventoryService.handlePlayerQuit(any(), any()) } just Runs

            listener.onPlayerQuit(event)

            verify { Logging.debug(any<() -> String>()) }
        }

        @Test
        @DisplayName("should handle all quit reasons")
        fun shouldHandleAllQuitReasons() {
            coEvery { inventoryService.handlePlayerQuit(any(), any()) } just Runs

            PlayerQuitEvent.QuitReason.entries.forEach { reason ->
                val player = server.addPlayer()
                val event = PlayerQuitEvent(player, Component.text("quit"), reason)

                listener.onPlayerQuit(event)

                coVerify { inventoryService.handlePlayerQuit(player, any()) }
            }
        }
    }

    // ============================================================
    // Event Priority Tests
    // ============================================================

    @Nested
    @DisplayName("Event Priority")
    inner class EventPriorityTests {

        @Test
        @DisplayName("join event handler has LOWEST priority annotation")
        fun joinEventHasLowestPriority() {
            val method = ConnectionListener::class.java.getDeclaredMethod(
                "onPlayerJoin",
                PlayerJoinEvent::class.java
            )
            val annotation = method.getAnnotation(org.bukkit.event.EventHandler::class.java)

            assertNotNull(annotation)
            assertEquals(org.bukkit.event.EventPriority.LOWEST, annotation.priority)
        }

        @Test
        @DisplayName("quit event handler has HIGHEST priority annotation")
        fun quitEventHasHighestPriority() {
            val method = ConnectionListener::class.java.getDeclaredMethod(
                "onPlayerQuit",
                PlayerQuitEvent::class.java
            )
            val annotation = method.getAnnotation(org.bukkit.event.EventHandler::class.java)

            assertNotNull(annotation)
            assertEquals(org.bukkit.event.EventPriority.HIGHEST, annotation.priority)
        }
    }

    // ============================================================
    // Integration Tests
    // ============================================================

    @Nested
    @DisplayName("Integration Tests")
    inner class IntegrationTests {

        @Test
        @DisplayName("should handle rapid join/quit cycle")
        fun shouldHandleRapidJoinQuitCycle() {
            val player = server.addPlayer()

            coEvery { inventoryService.handlePlayerJoin(any()) } just Runs
            coEvery { inventoryService.handlePlayerQuit(any(), any()) } just Runs

            // Simulate rapid join/quit
            repeat(5) {
                listener.onPlayerJoin(PlayerJoinEvent(player, Component.text("joined")))
                listener.onPlayerQuit(PlayerQuitEvent(player, Component.text("quit"), PlayerQuitEvent.QuitReason.DISCONNECTED))
            }

            coVerify(exactly = 5) { inventoryService.handlePlayerJoin(player) }
            coVerify(exactly = 5) { inventoryService.handlePlayerQuit(player, any()) }
        }

        @Test
        @DisplayName("should maintain player context in async operations")
        fun shouldMaintainPlayerContextInAsyncOps() {
            val player = server.addPlayer("ContextTestPlayer")
            val event = PlayerJoinEvent(player, Component.text("joined"))

            var capturedUuid: UUID? = null
            coEvery { inventoryService.handlePlayerJoin(any()) } coAnswers {
                capturedUuid = firstArg<Player>().uniqueId
            }

            listener.onPlayerJoin(event)

            assertEquals(player.uniqueId, capturedUuid)
        }
    }
}
