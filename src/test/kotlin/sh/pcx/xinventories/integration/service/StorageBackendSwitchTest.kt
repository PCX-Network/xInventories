package sh.pcx.xinventories.integration.service

import io.mockk.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.bukkit.GameMode
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import sh.pcx.xinventories.XInventories
import sh.pcx.xinventories.api.model.StorageType
import sh.pcx.xinventories.internal.config.*
import sh.pcx.xinventories.internal.model.PlayerData
import sh.pcx.xinventories.internal.service.MigrationService
import sh.pcx.xinventories.internal.service.StorageService
import sh.pcx.xinventories.internal.storage.Storage
import sh.pcx.xinventories.internal.util.Logging
import java.util.UUID
import java.util.logging.Logger

/**
 * Storage backend changes after `/xinv convert`: /xinv reload switches the running backend
 * (StorageService.switchToConfiguredBackend), and a conversion includes saves that were still
 * waiting in the write-behind cache (MigrationService flushes them first).
 */
@DisplayName("Storage backend switch and migration of pending saves")
class StorageBackendSwitchTest {

    private lateinit var plugin: XInventories
    private lateinit var configManager: ConfigManager
    private lateinit var scope: CoroutineScope
    private var config = config(StorageType.YAML)

    private val uuid = UUID.fromString("00000000-0000-0000-0000-0000000000aa")

    @BeforeEach
    fun setUp() {
        Logging.init(Logger.getLogger("StorageBackendSwitchTest"), false)
        mockkObject(Logging)
        every { Logging.info(any()) } just Runs
        every { Logging.warning(any()) } just Runs
        every { Logging.debug(any<() -> String>()) } just Runs
        every { Logging.error(any<String>(), any()) } just Runs

        plugin = mockk(relaxed = true)
        configManager = mockk(relaxed = true)
        every { plugin.configManager } returns configManager
        every { configManager.mainConfig } answers { config }
        scope = CoroutineScope(SupervisorJob())
    }

    @AfterEach
    fun tearDown() {
        scope.cancel()
        unmockkAll()
    }

    private fun config(type: StorageType, sqliteFile: String = "data/inventories.db") = MainConfig(
        storage = StorageConfig(type = type, sqlite = SqliteConfig(file = sqliteFile)),
        // Write-behind on and async saving: saves stay dirty in the cache until flushed.
        cache = CacheConfig(enabled = true, maxSize = 100, ttlMinutes = 30, writeBehindSeconds = 300),
        features = FeaturesConfig(asyncSaving = true)
    )

    private fun backend(name: String): Storage = mockk(relaxed = true) {
        every { this@mockk.name } returns name
        coEvery { savePlayerDataBatch(any()) } answers { firstArg<List<PlayerData>>().size }
    }

    private suspend fun service(vararg backends: Storage): StorageService {
        val service = spyk(StorageService(plugin, scope))
        every { service["createStorage"](any<StorageConfig>()) } returnsMany backends.toList()
        service.initialize()
        return service
    }

    private fun data(group: String) = PlayerData(uuid = uuid, playerName = "Converted", group = group, gameMode = GameMode.SURVIVAL)

    @Test
    fun `reload without a storage change keeps the running backend`() = runTest {
        val yaml = backend("YAML")
        val service = service(yaml)

        Assertions.assertNull(service.switchToConfiguredBackend())
        assertSame(yaml, service.storage)
        coVerify(exactly = 0) { yaml.shutdown() }
    }

    @Test
    fun `changing storage type switches to the new backend after flushing pending saves to the old one`() = runTest {
        val yaml = backend("YAML")
        val sqlite = backend("SQLite")
        val service = service(yaml, sqlite)
        val pending = data("survival")
        service.savePlayerData(pending)
        assertEquals(1, service.cache.getDirtyEntries().size)

        config = config(StorageType.SQLITE)
        assertEquals(StorageType.YAML, service.switchToConfiguredBackend())

        assertSame(sqlite, service.storage)
        coVerifyOrder {
            yaml.savePlayerDataBatch(match { it.single().group == "survival" })
            sqlite.initialize()
            yaml.shutdown()
        }
        // The cache held the old backend's view; reads now go to SQLite.
        assertTrue(service.cache.getDirtyEntries().isEmpty())
        coEvery { sqlite.loadPlayerData(uuid, "survival", GameMode.SURVIVAL) } returns data("survival")
        Assertions.assertNotNull(service.loadPlayerData(uuid, "survival", GameMode.SURVIVAL))
        coVerify { sqlite.loadPlayerData(uuid, "survival", GameMode.SURVIVAL) }

        // A second reload with the same configuration changes nothing.
        Assertions.assertNull(service.switchToConfiguredBackend())
    }

    @Test
    fun `moving the SQLite file counts as a backend change`() = runTest {
        config = config(StorageType.SQLITE, "data/a.db")
        val first = backend("SQLite")
        val second = backend("SQLite")
        val service = service(first, second)

        config = config(StorageType.SQLITE, "data/b.db")
        assertEquals(StorageType.SQLITE, service.switchToConfiguredBackend())
        assertSame(second, service.storage)
    }

    @Test
    fun `a backend that fails to start leaves the running one in place`() = runTest {
        val yaml = backend("YAML")
        val broken = backend("MySQL")
        coEvery { broken.initialize() } throws IllegalStateException("connection refused")
        val service = service(yaml, broken)

        config = config(StorageType.MYSQL)
        val error = assertThrows<IllegalStateException> { service.switchToConfiguredBackend() }
        assertEquals("connection refused", error.message)
        assertSame(yaml, service.storage)
        coVerify(exactly = 0) { yaml.shutdown() }
    }

    @Test
    fun `convert includes saves still waiting in the write-behind cache`() = runTest {
        val live = backend("YAML")
        val storageService = service(live)
        storageService.savePlayerData(data("nether"))

        val source = backend("YAML")
        val target = backend("SQLite")
        // The source reads the files: it sees the entry only once the cache was flushed to them.
        var persisted = emptyList<PlayerData>()
        coEvery { live.savePlayerDataBatch(any()) } answers { persisted = firstArg(); persisted.size }
        coEvery { source.getAllPlayerUUIDs() } answers { persisted.map { it.uuid }.toSet() }
        coEvery { source.loadAllPlayerData(uuid) } answers { persisted.associateBy { "${it.group}_${it.gameMode}" } }

        val migration = spyk(MigrationService(plugin, storageService))
        every { migration["createStorage"](any<StorageType>()) } returnsMany listOf(source, target)

        val report = migration.migrate(StorageType.YAML, StorageType.SQLITE).getOrThrow()

        assertEquals(1, report.playersProcessed)
        assertEquals(1, report.entriesMigrated)
        coVerify { target.savePlayerDataBatch(match { it.single().group == "nether" }) }
    }
}
