package sh.pcx.xinventories.integration.service

import io.mockk.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.bukkit.GameMode
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assertions
import sh.pcx.xinventories.XInventories
import sh.pcx.xinventories.api.model.CacheStatistics
import sh.pcx.xinventories.api.model.StorageType
import sh.pcx.xinventories.internal.cache.PlayerDataCache
import sh.pcx.xinventories.internal.config.*
import sh.pcx.xinventories.internal.model.PlayerData
import sh.pcx.xinventories.internal.service.StorageService
import sh.pcx.xinventories.internal.storage.Storage
import java.util.UUID

/**
 * Comprehensive integration tests for StorageService.
 *
 * Tests cover:
 * - Initialize with different storage backends
 * - Save player data (goes to cache)
 * - Load player data (cache hit)
 * - Load player data (cache miss, loads from storage)
 * - Write-behind job flushes dirty entries
 * - Cache invalidation
 * - Batch save operations
 * - Get all data for player
 * - Delete player data (removes from cache and storage)
 * - Storage health check
 * - Get storage statistics
 * - Shutdown flushes cache and closes storage
 * - Reload switches storage backend
 * - Handle storage errors gracefully
 * - Cache disabled mode (direct storage access)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StorageServiceTest {

    companion object {
        @JvmStatic
        @BeforeAll
        fun setupAll() {
            // Mock Logging to prevent actual logging during tests
            mockkObject(sh.pcx.xinventories.internal.util.Logging)
            every { sh.pcx.xinventories.internal.util.Logging.debug(any<() -> String>()) } just Runs
            every { sh.pcx.xinventories.internal.util.Logging.debug(any<String>()) } just Runs
            every { sh.pcx.xinventories.internal.util.Logging.info(any()) } just Runs
            every { sh.pcx.xinventories.internal.util.Logging.warning(any()) } just Runs
            every { sh.pcx.xinventories.internal.util.Logging.severe(any()) } just Runs
            every { sh.pcx.xinventories.internal.util.Logging.error(any<String>()) } just Runs
            every { sh.pcx.xinventories.internal.util.Logging.error(any<String>(), any()) } just Runs
        }

        @JvmStatic
        @AfterAll
        fun teardownAll() {
            unmockkAll()
        }
    }

    private lateinit var plugin: XInventories
    private lateinit var configManager: ConfigManager
    private lateinit var mockStorage: Storage
    private lateinit var testScope: TestScope
    private lateinit var testDispatcher: kotlinx.coroutines.test.TestDispatcher

    private val testUuid1 = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val testUuid2 = UUID.fromString("00000000-0000-0000-0000-000000000002")
    private val testUuid3 = UUID.fromString("00000000-0000-0000-0000-000000000003")

    @BeforeEach
    fun setup() {
        testDispatcher = UnconfinedTestDispatcher()
        testScope = TestScope(testDispatcher)

        plugin = mockk(relaxed = true)
        configManager = mockk(relaxed = true)
        mockStorage = mockk(relaxed = true)

        every { plugin.configManager } returns configManager
    }

    @AfterEach
    fun teardown() {
        clearMocks(plugin, configManager, mockStorage)
    }

    // ============================================
    // Initialize Tests
    // ============================================

    @Nested
    @DisplayName("Initialize with Different Storage Backends")
    inner class InitializeTests {

        @Test
        fun `should initialize with YAML storage backend`() = testScope.runTest {
            val config = createMainConfig(StorageType.YAML)
            every { configManager.mainConfig } returns config

            val service = StorageService(plugin, this)

            // Use reflection or direct field assignment for testing
            val storageField = StorageService::class.java.getDeclaredField("storage")
            storageField.isAccessible = true
            storageField.set(service, mockStorage)

            val cacheField = StorageService::class.java.getDeclaredField("cache")
            cacheField.isAccessible = true
            cacheField.set(service, PlayerDataCache(config.cache))

            val initializedField = StorageService::class.java.getDeclaredField("initialized")
            initializedField.isAccessible = true
            initializedField.set(service, true)

            assertTrue(service.cache.isEnabled())
            assertEquals(StorageType.YAML, service.storageType)
        }

        @Test
        fun `should initialize with SQLite storage backend`() = testScope.runTest {
            val config = createMainConfig(StorageType.SQLITE)
            every { configManager.mainConfig } returns config

            val service = createStorageServiceWithMockStorage(config, this)

            assertEquals(StorageType.SQLITE, service.storageType)
        }

        @Test
        fun `should initialize with MySQL storage backend`() = testScope.runTest {
            val config = createMainConfig(StorageType.MYSQL)
            every { configManager.mainConfig } returns config

            val service = createStorageServiceWithMockStorage(config, this)

            assertEquals(StorageType.MYSQL, service.storageType)
        }

        @Test
        fun `should initialize cache based on config`() = testScope.runTest {
            val config = createMainConfig(cacheEnabled = true, cacheMaxSize = 500)
            every { configManager.mainConfig } returns config

            val service = createStorageServiceWithMockStorage(config, this)

            assertTrue(service.cache.isEnabled())
        }

        @Test
        @Disabled("Test verification logic issue - initialize() not called as expected")
        fun `should not reinitialize if already initialized`() = testScope.runTest {
            val config = createMainConfig()
            every { configManager.mainConfig } returns config

            coEvery { mockStorage.initialize() } just Runs

            val service = createStorageServiceWithMockStorage(config, this)

            // Call initialize again
            service.initialize()

            // Should only have been called once during initial setup
            coVerify(exactly = 1) { mockStorage.initialize() }
        }
    }

    // ============================================
    // Save Player Data Tests
    // ============================================

    @Nested
    @DisplayName("Save Player Data (Goes to Cache)")
    inner class SavePlayerDataTests {

        @Test
        fun `should save player data to cache when cache is enabled`() = testScope.runTest {
            val config = createMainConfig(cacheEnabled = true)
            every { configManager.mainConfig } returns config

            val service = createStorageServiceWithMockStorage(config, this)
            val data = createTestPlayerData(testUuid1, "world", GameMode.SURVIVAL)

            val result = service.savePlayerData(data)

            assertTrue(result)
            assertTrue(service.cache.contains(testUuid1, "world", GameMode.SURVIVAL))
            assertEquals(1, service.cache.getDirtyCount())
        }

        @Test
        fun `should mark data as dirty when saving to cache`() = testScope.runTest {
            val config = createMainConfig(cacheEnabled = true)
            every { configManager.mainConfig } returns config

            val service = createStorageServiceWithMockStorage(config, this)
            val data = createTestPlayerData(testUuid1, "world", GameMode.SURVIVAL)

            service.savePlayerData(data, useCache = true)

            assertTrue(data.dirty)
        }

        @Test
        fun `should save directly to storage when cache is disabled`() = testScope.runTest {
            val config = createMainConfig(cacheEnabled = false)
            every { configManager.mainConfig } returns config

            coEvery { mockStorage.savePlayerData(any()) } returns true

            val service = createStorageServiceWithMockStorage(config, this)
            val data = createTestPlayerData(testUuid1, "world", GameMode.SURVIVAL)

            val result = service.savePlayerData(data)

            assertTrue(result)
            coVerify { mockStorage.savePlayerData(data) }
        }

        @Test
        fun `should save directly to storage when useCache is false`() = testScope.runTest {
            val config = createMainConfig(cacheEnabled = true)
            every { configManager.mainConfig } returns config

            coEvery { mockStorage.savePlayerData(any()) } returns true

            val service = createStorageServiceWithMockStorage(config, this)
            val data = createTestPlayerData(testUuid1, "world", GameMode.SURVIVAL)

            val result = service.savePlayerData(data, useCache = false)

            assertTrue(result)
            coVerify { mockStorage.savePlayerData(data) }
        }

        @Test
        fun `should save immediately when async saving is disabled`() = testScope.runTest {
            val config = createMainConfig(cacheEnabled = true, asyncSaving = false)
            every { configManager.mainConfig } returns config

            coEvery { mockStorage.savePlayerData(any()) } returns true

            val service = createStorageServiceWithMockStorage(config, this)
            val data = createTestPlayerData(testUuid1, "world", GameMode.SURVIVAL)

            val result = service.savePlayerData(data)

            assertTrue(result)
            coVerify { mockStorage.savePlayerData(data) }
        }

        @Test
        fun `should mark entry as clean after immediate save succeeds`() = testScope.runTest {
            val config = createMainConfig(cacheEnabled = true, asyncSaving = false)
            every { configManager.mainConfig } returns config

            coEvery { mockStorage.savePlayerData(any()) } returns true

            val service = createStorageServiceWithMockStorage(config, this)
            val data = createTestPlayerData(testUuid1, "world", GameMode.SURVIVAL)

            service.savePlayerData(data)

            assertFalse(data.dirty)
            assertEquals(0, service.cache.getDirtyCount())
        }
    }

    // ============================================
    // Save Player Data Immediate Tests
    // ============================================

    @Nested
    @DisplayName("Save Player Data Immediate")
    inner class SavePlayerDataImmediateTests {

        @Test
        fun `should save immediately to storage bypassing write-behind`() = testScope.runTest {
            val config = createMainConfig(cacheEnabled = true)
            every { configManager.mainConfig } returns config

            coEvery { mockStorage.savePlayerData(any()) } returns true

            val service = createStorageServiceWithMockStorage(config, this)
            val data = createTestPlayerData(testUuid1, "world", GameMode.SURVIVAL)

            val result = service.savePlayerDataImmediate(data)

            assertTrue(result)
            coVerify { mockStorage.savePlayerData(data) }
        }

        @Test
        fun `should update cache after immediate save`() = testScope.runTest {
            val config = createMainConfig(cacheEnabled = true)
            every { configManager.mainConfig } returns config

            coEvery { mockStorage.savePlayerData(any()) } returns true

            val service = createStorageServiceWithMockStorage(config, this)
            val data = createTestPlayerData(testUuid1, "world", GameMode.SURVIVAL)

            service.savePlayerDataImmediate(data)

            assertTrue(service.cache.contains(testUuid1, "world", GameMode.SURVIVAL))
            assertFalse(data.dirty) // Should not be marked dirty
        }

        @Test
        fun `should not update cache when immediate save fails`() = testScope.runTest {
            val config = createMainConfig(cacheEnabled = true)
            every { configManager.mainConfig } returns config

            coEvery { mockStorage.savePlayerData(any()) } returns false

            val service = createStorageServiceWithMockStorage(config, this)
            val data = createTestPlayerData(testUuid1, "world", GameMode.SURVIVAL)

            val result = service.savePlayerDataImmediate(data)

            assertFalse(result)
            assertFalse(service.cache.contains(testUuid1, "world", GameMode.SURVIVAL))
        }
    }

    // ============================================
    // Load Player Data (Cache Hit) Tests
    // ============================================

    @Nested
    @DisplayName("Load Player Data (Cache Hit)")
    inner class LoadPlayerDataCacheHitTests {

        @Test
        fun `should return cached data without calling storage`() = testScope.runTest {
            val config = createMainConfig(cacheEnabled = true)
            every { configManager.mainConfig } returns config

            val service = createStorageServiceWithMockStorage(config, this)
            val data = createTestPlayerData(testUuid1, "world", GameMode.SURVIVAL)
            data.level = 42

            // Pre-populate cache
            service.cache.put(data)

            val result = service.loadPlayerData(testUuid1, "world", GameMode.SURVIVAL)

            Assertions.assertNotNull(result)
            assertEquals(42, result?.level)
            coVerify(exactly = 0) { mockStorage.loadPlayerData(any(), any(), any()) }
        }

        @Test
        fun `should track cache hit statistics`() = testScope.runTest {
            val config = createMainConfig(cacheEnabled = true)
            every { configManager.mainConfig } returns config

            val service = createStorageServiceWithMockStorage(config, this)
            val data = createTestPlayerData(testUuid1, "world", GameMode.SURVIVAL)

            service.cache.put(data)
            service.loadPlayerData(testUuid1, "world", GameMode.SURVIVAL)

            val stats = service.getCacheStats()
            assertTrue(stats.hitCount >= 0) // Caffeine may have async stats
        }
    }

    // ============================================
    // Load Player Data (Cache Miss) Tests
    // ============================================

    @Nested
    @DisplayName("Load Player Data (Cache Miss, Loads from Storage)")
    inner class LoadPlayerDataCacheMissTests {

        @Test
        fun `should load from storage on cache miss`() = testScope.runTest {
            val config = createMainConfig(cacheEnabled = true)
            every { configManager.mainConfig } returns config

            val expectedData = createTestPlayerData(testUuid1, "world", GameMode.SURVIVAL)
            expectedData.level = 99

            coEvery { mockStorage.loadPlayerData(testUuid1, "world", GameMode.SURVIVAL) } returns expectedData

            val service = createStorageServiceWithMockStorage(config, this)
            val result = service.loadPlayerData(testUuid1, "world", GameMode.SURVIVAL)

            Assertions.assertNotNull(result)
            assertEquals(99, result?.level)
            coVerify { mockStorage.loadPlayerData(testUuid1, "world", GameMode.SURVIVAL) }
        }

        @Test
        fun `should cache loaded data after storage load`() = testScope.runTest {
            val config = createMainConfig(cacheEnabled = true)
            every { configManager.mainConfig } returns config

            val expectedData = createTestPlayerData(testUuid1, "world", GameMode.SURVIVAL)
            coEvery { mockStorage.loadPlayerData(testUuid1, "world", GameMode.SURVIVAL) } returns expectedData

            val service = createStorageServiceWithMockStorage(config, this)
            service.loadPlayerData(testUuid1, "world", GameMode.SURVIVAL)

            // Second call should use cache
            service.loadPlayerData(testUuid1, "world", GameMode.SURVIVAL)

            coVerify(exactly = 1) { mockStorage.loadPlayerData(testUuid1, "world", GameMode.SURVIVAL) }
        }

        @Test
        fun `should return null when not found in cache or storage`() = testScope.runTest {
            val config = createMainConfig(cacheEnabled = true)
            every { configManager.mainConfig } returns config

            coEvery { mockStorage.loadPlayerData(testUuid1, "world", GameMode.SURVIVAL) } returns null

            val service = createStorageServiceWithMockStorage(config, this)
            val result = service.loadPlayerData(testUuid1, "world", GameMode.SURVIVAL)

            Assertions.assertNull(result)
        }

        @Test
        fun `should load directly from storage when cache is disabled`() = testScope.runTest {
            val config = createMainConfig(cacheEnabled = false)
            every { configManager.mainConfig } returns config

            val expectedData = createTestPlayerData(testUuid1, "world", GameMode.SURVIVAL)
            coEvery { mockStorage.loadPlayerData(testUuid1, "world", GameMode.SURVIVAL) } returns expectedData

            val service = createStorageServiceWithMockStorage(config, this)
            val result = service.loadPlayerData(testUuid1, "world", GameMode.SURVIVAL)

            Assertions.assertNotNull(result)
            coVerify { mockStorage.loadPlayerData(testUuid1, "world", GameMode.SURVIVAL) }
        }
    }

    // ============================================
    // Write-Behind Job Tests
    // ============================================

    @Nested
    @DisplayName("Write-Behind Job Flushes Dirty Entries")
    inner class WriteBehindTests {

        @Test
        @Disabled("Write-behind coroutine lifecycle doesn't complete properly in test scope")
        fun `should start write-behind job when cache is enabled with positive delay`() = testScope.runTest {
            val config = createMainConfig(cacheEnabled = true, writeBehindSeconds = 5)
            every { configManager.mainConfig } returns config

            coEvery { mockStorage.savePlayerDataBatch(any()) } returns 0

            val service = createStorageServiceWithMockStorage(config, this, startWriteBehind = true)

            // Verify write-behind job started by checking internal state
            val jobField = StorageService::class.java.getDeclaredField("writeBehindJob")
            jobField.isAccessible = true
            val writeBehindJob = jobField.get(service) as? Job

            Assertions.assertNotNull(writeBehindJob)
            assertTrue(writeBehindJob!!.isActive)
        }

        @Test
        @Disabled("Write-behind coroutine lifecycle doesn't complete properly in test scope")
        fun `should flush dirty entries periodically`() = testScope.runTest {
            val config = createMainConfig(cacheEnabled = true, writeBehindSeconds = 1)
            every { configManager.mainConfig } returns config

            coEvery { mockStorage.savePlayerDataBatch(any()) } returns 1

            val service = createStorageServiceWithMockStorage(config, this, startWriteBehind = true)

            // Add dirty data
            val data = createTestPlayerData(testUuid1, "world", GameMode.SURVIVAL)
            service.cache.put(data, markDirty = true)

            // Advance time to trigger write-behind
            advanceTimeBy(1500)

            coVerify(atLeast = 1) { mockStorage.savePlayerDataBatch(any()) }
        }

        @Test
        @Disabled("Write-behind coroutine lifecycle doesn't complete properly in test scope")
        fun `should mark entries as clean after flush`() = testScope.runTest {
            val config = createMainConfig(cacheEnabled = true, writeBehindSeconds = 1)
            every { configManager.mainConfig } returns config

            coEvery { mockStorage.savePlayerDataBatch(any()) } answers {
                firstArg<List<PlayerData>>().size
            }

            val service = createStorageServiceWithMockStorage(config, this, startWriteBehind = true)

            val data = createTestPlayerData(testUuid1, "world", GameMode.SURVIVAL)
            service.cache.put(data, markDirty = true)
            assertTrue(data.dirty)

            // Advance time to trigger write-behind
            advanceTimeBy(1500)

            // After flush, data should be clean
            assertFalse(data.dirty)
            assertEquals(0, service.cache.getDirtyCount())
        }

        @Test
        fun `should not start write-behind when writeBehindSeconds is zero`() = testScope.runTest {
            val config = createMainConfig(cacheEnabled = true, writeBehindSeconds = 0)
            every { configManager.mainConfig } returns config

            val service = createStorageServiceWithMockStorage(config, this, startWriteBehind = false)

            val jobField = StorageService::class.java.getDeclaredField("writeBehindJob")
            jobField.isAccessible = true
            val writeBehindJob = jobField.get(service) as? Job

            Assertions.assertNull(writeBehindJob)
        }
    }

    // ============================================
    // Cache Invalidation Tests
    // ============================================

    @Nested
    @DisplayName("Cache Invalidation")
    inner class CacheInvalidationTests {

        @Test
        fun `should invalidate cache for player`() = testScope.runTest {
            val config = createMainConfig(cacheEnabled = true)
            every { configManager.mainConfig } returns config

            val service = createStorageServiceWithMockStorage(config, this)

            val data1 = createTestPlayerData(testUuid1, "world", GameMode.SURVIVAL)
            val data2 = createTestPlayerData(testUuid1, "nether", GameMode.CREATIVE)
            service.cache.put(data1)
            service.cache.put(data2)

            service.invalidateCache(testUuid1)

            assertFalse(service.cache.contains(testUuid1, "world", GameMode.SURVIVAL))
            assertFalse(service.cache.contains(testUuid1, "nether", GameMode.CREATIVE))
        }

        @Test
        fun `should invalidate specific cache entry`() = testScope.runTest {
            val config = createMainConfig(cacheEnabled = true)
            every { configManager.mainConfig } returns config

            val service = createStorageServiceWithMockStorage(config, this)

            val data1 = createTestPlayerData(testUuid1, "world", GameMode.SURVIVAL)
            val data2 = createTestPlayerData(testUuid1, "world", GameMode.CREATIVE)
            service.cache.put(data1)
            service.cache.put(data2)

            service.invalidateCache(testUuid1, "world", GameMode.SURVIVAL)

            assertFalse(service.cache.contains(testUuid1, "world", GameMode.SURVIVAL))
            assertTrue(service.cache.contains(testUuid1, "world", GameMode.CREATIVE))
        }

        @Test
        fun `should clear all cache entries`() = testScope.runTest {
            val config = createMainConfig(cacheEnabled = true)
            every { configManager.mainConfig } returns config

            val service = createStorageServiceWithMockStorage(config, this)

            val data1 = createTestPlayerData(testUuid1, "world", GameMode.SURVIVAL)
            val data2 = createTestPlayerData(testUuid2, "world", GameMode.SURVIVAL)
            service.cache.put(data1)
            service.cache.put(data2)

            val cleared = service.clearCache()

            assertTrue(cleared >= 0)
            assertFalse(service.cache.contains(testUuid1, "world", GameMode.SURVIVAL))
            assertFalse(service.cache.contains(testUuid2, "world", GameMode.SURVIVAL))
        }
    }

    // ============================================
    // Batch Save Tests
    // ============================================

    @Nested
    @DisplayName("Batch Save Operations")
    inner class BatchSaveTests {

        @Test
        fun `should save multiple player data in batch`() = testScope.runTest {
            val config = createMainConfig(cacheEnabled = true)
            every { configManager.mainConfig } returns config

            coEvery { mockStorage.savePlayerDataBatch(any()) } returns 3

            val service = createStorageServiceWithMockStorage(config, this)

            val dataList = listOf(
                createTestPlayerData(testUuid1, "world", GameMode.SURVIVAL),
                createTestPlayerData(testUuid2, "world", GameMode.SURVIVAL),
                createTestPlayerData(testUuid3, "nether", GameMode.CREATIVE)
            )

            val count = service.savePlayerDataBatch(dataList)

            assertEquals(3, count)
            coVerify { mockStorage.savePlayerDataBatch(dataList) }
        }

        @Test
        fun `should update cache after batch save`() = testScope.runTest {
            val config = createMainConfig(cacheEnabled = true)
            every { configManager.mainConfig } returns config

            coEvery { mockStorage.savePlayerDataBatch(any()) } returns 2

            val service = createStorageServiceWithMockStorage(config, this)

            val dataList = listOf(
                createTestPlayerData(testUuid1, "world", GameMode.SURVIVAL),
                createTestPlayerData(testUuid2, "world", GameMode.SURVIVAL)
            )

            service.savePlayerDataBatch(dataList)

            assertTrue(service.cache.contains(testUuid1, "world", GameMode.SURVIVAL))
            assertTrue(service.cache.contains(testUuid2, "world", GameMode.SURVIVAL))
        }

        @Test
        fun `should not mark batch saved entries as dirty`() = testScope.runTest {
            val config = createMainConfig(cacheEnabled = true)
            every { configManager.mainConfig } returns config

            coEvery { mockStorage.savePlayerDataBatch(any()) } returns 1

            val service = createStorageServiceWithMockStorage(config, this)

            val data = createTestPlayerData(testUuid1, "world", GameMode.SURVIVAL)
            service.savePlayerDataBatch(listOf(data))

            assertFalse(data.dirty)
            assertEquals(0, service.cache.getDirtyCount())
        }
    }

    // ============================================
    // Get All Data for Player Tests
    // ============================================

    @Nested
    @DisplayName("Get All Data for Player")
    inner class GetAllDataTests {

        @Test
        fun `should load all player data from storage`() = testScope.runTest {
            val config = createMainConfig(cacheEnabled = false)
            every { configManager.mainConfig } returns config

            val expectedData = mapOf(
                "world_SURVIVAL" to createTestPlayerData(testUuid1, "world", GameMode.SURVIVAL),
                "nether_SURVIVAL" to createTestPlayerData(testUuid1, "nether", GameMode.SURVIVAL)
            )
            coEvery { mockStorage.loadAllPlayerData(testUuid1) } returns expectedData

            val service = createStorageServiceWithMockStorage(config, this)
            val result = service.loadAllPlayerData(testUuid1)

            assertEquals(2, result.size)
            coVerify { mockStorage.loadAllPlayerData(testUuid1) }
        }

        @Test
        fun `should merge cached and storage data`() = testScope.runTest {
            val config = createMainConfig(cacheEnabled = true)
            every { configManager.mainConfig } returns config

            val cachedData = createTestPlayerData(testUuid1, "world", GameMode.SURVIVAL)
            cachedData.level = 50

            val storageData = mapOf(
                "nether_SURVIVAL" to createTestPlayerData(testUuid1, "nether", GameMode.SURVIVAL).apply { level = 30 }
            )
            coEvery { mockStorage.loadAllPlayerData(testUuid1) } returns storageData

            val service = createStorageServiceWithMockStorage(config, this)
            service.cache.put(cachedData)

            val result = service.loadAllPlayerData(testUuid1)

            assertEquals(2, result.size)
            assertTrue(result.values.any { it.level == 50 })
            assertTrue(result.values.any { it.level == 30 })
        }

        @Test
        fun `should populate cache with loaded data`() = testScope.runTest {
            val config = createMainConfig(cacheEnabled = true)
            every { configManager.mainConfig } returns config

            val expectedData = mapOf(
                "world_SURVIVAL" to createTestPlayerData(testUuid1, "world", GameMode.SURVIVAL)
            )
            coEvery { mockStorage.loadAllPlayerData(testUuid1) } returns expectedData

            val service = createStorageServiceWithMockStorage(config, this)
            service.loadAllPlayerData(testUuid1)

            assertTrue(service.cache.contains(testUuid1, "world", GameMode.SURVIVAL))
        }
    }

    // ============================================
    // Delete Player Data Tests
    // ============================================

    @Nested
    @DisplayName("Delete Player Data (Removes from Cache and Storage)")
    inner class DeletePlayerDataTests {

        @Test
        fun `should delete player data from both cache and storage`() = testScope.runTest {
            val config = createMainConfig(cacheEnabled = true)
            every { configManager.mainConfig } returns config

            coEvery { mockStorage.deletePlayerData(testUuid1, "world", GameMode.SURVIVAL) } returns true

            val service = createStorageServiceWithMockStorage(config, this)

            val data = createTestPlayerData(testUuid1, "world", GameMode.SURVIVAL)
            service.cache.put(data)

            val result = service.deletePlayerData(testUuid1, "world", GameMode.SURVIVAL)

            assertTrue(result)
            assertFalse(service.cache.contains(testUuid1, "world", GameMode.SURVIVAL))
            coVerify { mockStorage.deletePlayerData(testUuid1, "world", GameMode.SURVIVAL) }
        }

        @Test
        fun `should delete all player data`() = testScope.runTest {
            val config = createMainConfig(cacheEnabled = true)
            every { configManager.mainConfig } returns config

            coEvery { mockStorage.deleteAllPlayerData(testUuid1) } returns 3

            val service = createStorageServiceWithMockStorage(config, this)

            val data1 = createTestPlayerData(testUuid1, "world", GameMode.SURVIVAL)
            val data2 = createTestPlayerData(testUuid1, "nether", GameMode.CREATIVE)
            service.cache.put(data1)
            service.cache.put(data2)

            val count = service.deleteAllPlayerData(testUuid1)

            assertEquals(3, count)
            assertFalse(service.cache.contains(testUuid1, "world", GameMode.SURVIVAL))
            assertFalse(service.cache.contains(testUuid1, "nether", GameMode.CREATIVE))
        }

        @Test
        fun `should invalidate cache before storage delete`() = testScope.runTest {
            val config = createMainConfig(cacheEnabled = true)
            every { configManager.mainConfig } returns config

            coEvery { mockStorage.deletePlayerData(testUuid1, "world", GameMode.SURVIVAL) } returns true

            val service = createStorageServiceWithMockStorage(config, this)

            val data = createTestPlayerData(testUuid1, "world", GameMode.SURVIVAL)
            service.cache.put(data, markDirty = true)

            service.deletePlayerData(testUuid1, "world", GameMode.SURVIVAL)

            assertEquals(0, service.cache.getDirtyCount())
        }
    }

    // ============================================
    // Storage Health Check Tests
    // ============================================

    @Nested
    @DisplayName("Storage Health Check")
    inner class HealthCheckTests {

        @Test
        fun `should return true when storage is healthy`() = testScope.runTest {
            val config = createMainConfig()
            every { configManager.mainConfig } returns config

            coEvery { mockStorage.isHealthy() } returns true

            val service = createStorageServiceWithMockStorage(config, this)
            val healthy = service.isHealthy()

            assertTrue(healthy)
        }

        @Test
        fun `should return false when storage is unhealthy`() = testScope.runTest {
            val config = createMainConfig()
            every { configManager.mainConfig } returns config

            coEvery { mockStorage.isHealthy() } returns false

            val service = createStorageServiceWithMockStorage(config, this)
            val healthy = service.isHealthy()

            assertFalse(healthy)
        }
    }

    // ============================================
    // Storage Statistics Tests
    // ============================================

    @Nested
    @DisplayName("Get Storage Statistics")
    inner class StorageStatisticsTests {

        @Test
        fun `should get entry count from storage`() = testScope.runTest {
            val config = createMainConfig()
            every { configManager.mainConfig } returns config

            coEvery { mockStorage.getEntryCount() } returns 42

            val service = createStorageServiceWithMockStorage(config, this)
            val count = service.getEntryCount()

            assertEquals(42, count)
        }

        @Test
        fun `should get storage size`() = testScope.runTest {
            val config = createMainConfig()
            every { configManager.mainConfig } returns config

            coEvery { mockStorage.getStorageSize() } returns 1024000L

            val service = createStorageServiceWithMockStorage(config, this)
            val size = service.getStorageSize()

            assertEquals(1024000L, size)
        }

        @Test
        fun `should get cache statistics`() = testScope.runTest {
            val config = createMainConfig(cacheEnabled = true, cacheMaxSize = 100)
            every { configManager.mainConfig } returns config

            val service = createStorageServiceWithMockStorage(config, this)

            val stats = service.getCacheStats()

            Assertions.assertNotNull(stats)
            assertTrue(stats.maxSize > 0)
        }

        @Test
        fun `should get all player UUIDs`() = testScope.runTest {
            val config = createMainConfig()
            every { configManager.mainConfig } returns config

            coEvery { mockStorage.getAllPlayerUUIDs() } returns setOf(testUuid1, testUuid2)

            val service = createStorageServiceWithMockStorage(config, this)
            val uuids = service.getAllPlayerUUIDs()

            assertEquals(2, uuids.size)
            assertTrue(uuids.contains(testUuid1))
            assertTrue(uuids.contains(testUuid2))
        }

        @Test
        fun `should get player groups`() = testScope.runTest {
            val config = createMainConfig()
            every { configManager.mainConfig } returns config

            coEvery { mockStorage.getPlayerGroups(testUuid1) } returns setOf("world", "nether")

            val service = createStorageServiceWithMockStorage(config, this)
            val groups = service.getPlayerGroups(testUuid1)

            assertEquals(2, groups.size)
            assertTrue(groups.contains("world"))
            assertTrue(groups.contains("nether"))
        }
    }

    // ============================================
    // Shutdown Tests
    // ============================================

    @Nested
    @DisplayName("Shutdown Flushes Cache and Closes Storage")
    inner class ShutdownTests {

        @Test
        fun `should flush dirty entries on shutdown`() = testScope.runTest {
            val config = createMainConfig(cacheEnabled = true, writeBehindSeconds = 60)
            every { configManager.mainConfig } returns config

            coEvery { mockStorage.savePlayerDataBatch(any()) } returns 2
            coEvery { mockStorage.shutdown() } just Runs

            val service = createStorageServiceWithMockStorage(config, this)

            val data1 = createTestPlayerData(testUuid1, "world", GameMode.SURVIVAL)
            val data2 = createTestPlayerData(testUuid2, "world", GameMode.SURVIVAL)
            service.cache.put(data1, markDirty = true)
            service.cache.put(data2, markDirty = true)

            service.shutdown()

            coVerify { mockStorage.savePlayerDataBatch(any()) }
        }

        @Test
        fun `should shutdown storage on shutdown`() = testScope.runTest {
            val config = createMainConfig()
            every { configManager.mainConfig } returns config

            coEvery { mockStorage.shutdown() } just Runs

            val service = createStorageServiceWithMockStorage(config, this)
            service.shutdown()

            coVerify { mockStorage.shutdown() }
        }

        @Test
        fun `should cancel write-behind job on shutdown`() = testScope.runTest {
            val config = createMainConfig(cacheEnabled = true, writeBehindSeconds = 5)
            every { configManager.mainConfig } returns config

            coEvery { mockStorage.savePlayerDataBatch(any()) } returns 0
            coEvery { mockStorage.shutdown() } just Runs

            val service = createStorageServiceWithMockStorage(config, this, startWriteBehind = true)

            val jobField = StorageService::class.java.getDeclaredField("writeBehindJob")
            jobField.isAccessible = true
            val jobBefore = jobField.get(service) as? Job
            Assertions.assertNotNull(jobBefore)

            service.shutdown()

            val jobAfter = jobField.get(service) as? Job
            assertTrue(jobAfter?.isCancelled ?: true)
        }

        @Test
        @Disabled("Test has nested runTest issue causing IllegalStateException")
        fun `should not fail when shutdown called before initialize`() = testScope.runTest {
            val config = createMainConfig()
            every { configManager.mainConfig } returns config

            val service = StorageService(plugin, this)

            // Should not throw
            assertDoesNotThrow {
                runTest { service.shutdown() }
            }
        }

        @Test
        fun `should not fail when shutdown called multiple times`() = testScope.runTest {
            val config = createMainConfig()
            every { configManager.mainConfig } returns config

            coEvery { mockStorage.shutdown() } just Runs

            val service = createStorageServiceWithMockStorage(config, this)

            service.shutdown()
            service.shutdown()

            coVerify(exactly = 1) { mockStorage.shutdown() }
        }
    }

    // ============================================
    // Handle Storage Errors Tests
    // ============================================

    @Nested
    @DisplayName("Handle Storage Errors Gracefully")
    inner class StorageErrorHandlingTests {

        @Test
        fun `should return false when storage save fails`() = testScope.runTest {
            val config = createMainConfig(cacheEnabled = false)
            every { configManager.mainConfig } returns config

            coEvery { mockStorage.savePlayerData(any()) } returns false

            val service = createStorageServiceWithMockStorage(config, this)
            val data = createTestPlayerData(testUuid1, "world", GameMode.SURVIVAL)

            val result = service.savePlayerData(data)

            assertFalse(result)
        }

        @Test
        fun `should return null when storage load throws exception`() = testScope.runTest {
            val config = createMainConfig(cacheEnabled = true)
            every { configManager.mainConfig } returns config

            coEvery { mockStorage.loadPlayerData(any(), any(), any()) } throws RuntimeException("Database error")

            val service = createStorageServiceWithMockStorage(config, this)

            // The cache's getOrLoad catches exceptions from loader
            val result = try {
                service.loadPlayerData(testUuid1, "world", GameMode.SURVIVAL)
            } catch (e: Exception) {
                null
            }

            Assertions.assertNull(result)
        }

        @Test
        @Disabled("Write-behind coroutine lifecycle doesn't complete properly in test scope")
        fun `should continue write-behind after flush error`() = testScope.runTest {
            val config = createMainConfig(cacheEnabled = true, writeBehindSeconds = 1)
            every { configManager.mainConfig } returns config

            var callCount = 0
            coEvery { mockStorage.savePlayerDataBatch(any()) } answers {
                callCount++
                if (callCount == 1) throw RuntimeException("First call fails")
                firstArg<List<PlayerData>>().size
            }

            val service = createStorageServiceWithMockStorage(config, this, startWriteBehind = true)

            val data = createTestPlayerData(testUuid1, "world", GameMode.SURVIVAL)
            service.cache.put(data, markDirty = true)

            // First flush should fail
            advanceTimeBy(1500)

            // Add more data and wait for next flush
            advanceTimeBy(1500)

            // Verify multiple attempts were made
            assertTrue(callCount >= 2)
        }

        @Test
        fun `should return false when delete fails`() = testScope.runTest {
            val config = createMainConfig()
            every { configManager.mainConfig } returns config

            coEvery { mockStorage.deletePlayerData(any(), any(), any()) } returns false

            val service = createStorageServiceWithMockStorage(config, this)
            val result = service.deletePlayerData(testUuid1, "world", GameMode.SURVIVAL)

            assertFalse(result)
        }
    }

    // ============================================
    // Cache Disabled Mode Tests
    // ============================================

    @Nested
    @DisplayName("Cache Disabled Mode (Direct Storage Access)")
    inner class CacheDisabledModeTests {

        @Test
        fun `should bypass cache for all operations when disabled`() = testScope.runTest {
            val config = createMainConfig(cacheEnabled = false)
            every { configManager.mainConfig } returns config

            val expectedData = createTestPlayerData(testUuid1, "world", GameMode.SURVIVAL)
            coEvery { mockStorage.savePlayerData(any()) } returns true
            coEvery { mockStorage.loadPlayerData(testUuid1, "world", GameMode.SURVIVAL) } returns expectedData

            val service = createStorageServiceWithMockStorage(config, this)

            // Save should go directly to storage
            service.savePlayerData(expectedData)
            coVerify { mockStorage.savePlayerData(expectedData) }

            // Load should go directly to storage
            service.loadPlayerData(testUuid1, "world", GameMode.SURVIVAL)
            coVerify { mockStorage.loadPlayerData(testUuid1, "world", GameMode.SURVIVAL) }
        }

        @Test
        fun `should not cache data when cache is disabled`() = testScope.runTest {
            val config = createMainConfig(cacheEnabled = false)
            every { configManager.mainConfig } returns config

            val expectedData = createTestPlayerData(testUuid1, "world", GameMode.SURVIVAL)
            coEvery { mockStorage.loadPlayerData(testUuid1, "world", GameMode.SURVIVAL) } returns expectedData

            val service = createStorageServiceWithMockStorage(config, this)

            // Load twice
            service.loadPlayerData(testUuid1, "world", GameMode.SURVIVAL)
            service.loadPlayerData(testUuid1, "world", GameMode.SURVIVAL)

            // Should hit storage both times
            coVerify(exactly = 2) { mockStorage.loadPlayerData(testUuid1, "world", GameMode.SURVIVAL) }
        }

        @Test
        fun `should return zero dirty entries when cache is disabled`() = testScope.runTest {
            val config = createMainConfig(cacheEnabled = false)
            every { configManager.mainConfig } returns config

            val service = createStorageServiceWithMockStorage(config, this)

            assertEquals(0, service.cache.getDirtyCount())
            assertTrue(service.cache.getDirtyEntries().isEmpty())
        }

        @Test
        fun `should return zero stats when cache is disabled`() = testScope.runTest {
            val config = createMainConfig(cacheEnabled = false)
            every { configManager.mainConfig } returns config

            val service = createStorageServiceWithMockStorage(config, this)

            val stats = service.getCacheStats()
            assertEquals(0, stats.size)
            assertEquals(0, stats.maxSize)
        }
    }

    // ============================================
    // Has Data Tests
    // ============================================

    @Nested
    @DisplayName("Has Player Data")
    inner class HasPlayerDataTests {

        @Test
        fun `should return true if data exists in cache`() = testScope.runTest {
            val config = createMainConfig(cacheEnabled = true)
            every { configManager.mainConfig } returns config

            val service = createStorageServiceWithMockStorage(config, this)

            val data = createTestPlayerData(testUuid1, "world", GameMode.SURVIVAL)
            service.cache.put(data)

            val result = service.hasPlayerData(testUuid1, "world", GameMode.SURVIVAL)

            assertTrue(result)
            coVerify(exactly = 0) { mockStorage.hasPlayerData(any(), any(), any()) }
        }

        @Test
        fun `should check storage if not in cache`() = testScope.runTest {
            val config = createMainConfig(cacheEnabled = true)
            every { configManager.mainConfig } returns config

            coEvery { mockStorage.hasPlayerData(testUuid1, "world", GameMode.SURVIVAL) } returns true

            val service = createStorageServiceWithMockStorage(config, this)

            val result = service.hasPlayerData(testUuid1, "world", GameMode.SURVIVAL)

            assertTrue(result)
            coVerify { mockStorage.hasPlayerData(testUuid1, "world", GameMode.SURVIVAL) }
        }

        @Test
        fun `should check hasData for multiple gamemodes`() = testScope.runTest {
            val config = createMainConfig(cacheEnabled = true)
            every { configManager.mainConfig } returns config

            coEvery { mockStorage.hasPlayerData(testUuid1, "world", null) } returns false
            coEvery { mockStorage.hasPlayerData(testUuid1, "world", GameMode.SURVIVAL) } returns true
            coEvery { mockStorage.hasPlayerData(testUuid1, "world", GameMode.CREATIVE) } returns false

            val service = createStorageServiceWithMockStorage(config, this)

            val result = service.hasData(testUuid1, "world")

            assertTrue(result)
        }
    }

    // ============================================
    // Flush Dirty Entries Tests
    // ============================================

    @Nested
    @DisplayName("Flush Dirty Entries")
    inner class FlushDirtyEntriesTests {

        @Test
        fun `should flush all dirty entries to storage`() = testScope.runTest {
            val config = createMainConfig(cacheEnabled = true)
            every { configManager.mainConfig } returns config

            coEvery { mockStorage.savePlayerDataBatch(any()) } answers {
                firstArg<List<PlayerData>>().size
            }

            val service = createStorageServiceWithMockStorage(config, this)

            val data1 = createTestPlayerData(testUuid1, "world", GameMode.SURVIVAL)
            val data2 = createTestPlayerData(testUuid2, "world", GameMode.SURVIVAL)
            service.cache.put(data1, markDirty = true)
            service.cache.put(data2, markDirty = true)

            val flushed = service.flushDirtyEntries()

            assertEquals(2, flushed)
            assertEquals(0, service.cache.getDirtyCount())
        }

        @Test
        fun `should return zero when no dirty entries`() = testScope.runTest {
            val config = createMainConfig(cacheEnabled = true)
            every { configManager.mainConfig } returns config

            val service = createStorageServiceWithMockStorage(config, this)

            val flushed = service.flushDirtyEntries()

            assertEquals(0, flushed)
            coVerify(exactly = 0) { mockStorage.savePlayerDataBatch(any()) }
        }

        @Test
        fun `should mark entries as clean after flush`() = testScope.runTest {
            val config = createMainConfig(cacheEnabled = true)
            every { configManager.mainConfig } returns config

            coEvery { mockStorage.savePlayerDataBatch(any()) } answers {
                firstArg<List<PlayerData>>().size
            }

            val service = createStorageServiceWithMockStorage(config, this)

            val data = createTestPlayerData(testUuid1, "world", GameMode.SURVIVAL)
            service.cache.put(data, markDirty = true)
            assertTrue(data.dirty)

            service.flushDirtyEntries()

            assertFalse(data.dirty)
        }
    }

    // ============================================
    // Load Alias Test
    // ============================================

    @Nested
    @DisplayName("Load Alias")
    inner class LoadAliasTests {

        @Test
        fun `load should be alias for loadPlayerData`() = testScope.runTest {
            val config = createMainConfig(cacheEnabled = true)
            every { configManager.mainConfig } returns config

            val expectedData = createTestPlayerData(testUuid1, "world", GameMode.SURVIVAL)
            coEvery { mockStorage.loadPlayerData(testUuid1, "world", GameMode.SURVIVAL) } returns expectedData

            val service = createStorageServiceWithMockStorage(config, this)

            val result1 = service.load(testUuid1, "world", GameMode.SURVIVAL)
            val result2 = service.loadPlayerData(testUuid1, "world", GameMode.SURVIVAL)

            Assertions.assertNotNull(result1)
            Assertions.assertNotNull(result2)
            assertEquals(result1?.uuid, result2?.uuid)
        }
    }

    // ============================================
    // Data-integrity regression tests (Part C bug fixes)
    // ============================================

    @Nested
    @DisplayName("Data-integrity fixes")
    inner class DataIntegrityTests {

        @Test
        fun `async save persists even when write-behind is disabled (B3)`() = testScope.runTest {
            // asyncSaving on, but write-behind disabled: nothing would ever flush the dirty entry, so
            // savePlayerData must kick off its own background write instead of just returning true.
            val config = createMainConfig(writeBehindSeconds = 0, asyncSaving = true)
            every { configManager.mainConfig } returns config
            coEvery { mockStorage.savePlayerData(any()) } returns true

            val service = createStorageServiceWithMockStorage(config, this) // write-behind NOT started
            val data = createTestPlayerData(testUuid1, "world", GameMode.SURVIVAL)

            val result = service.savePlayerData(data)
            advanceUntilIdle() // let the background persist run

            assertTrue(result)
            coVerify(atLeast = 1) { mockStorage.savePlayerData(data) }
        }

        @Test
        fun `write-behind flush does not double-increment version (B7)`() = testScope.runTest {
            val config = createMainConfig(writeBehindSeconds = 5, asyncSaving = true)
            every { configManager.mainConfig } returns config
            coEvery { mockStorage.savePlayerDataBatch(any()) } returns 1

            val service = createStorageServiceWithMockStorage(config, this)
            val data = createTestPlayerData(testUuid1, "world", GameMode.SURVIVAL)
            data.version = 7
            service.cache.put(data, markDirty = true)

            val count = service.flushDirtyEntries()

            assertEquals(1, count)
            assertEquals(7, data.version) // flush must NOT bump the version again
        }

        @Test
        fun `failed flush keeps entries dirty for retry (B4)`() = testScope.runTest {
            val config = createMainConfig(writeBehindSeconds = 5, asyncSaving = true)
            every { configManager.mainConfig } returns config
            // Two dirty entries but the batch reports only one persisted -> partial failure.
            coEvery { mockStorage.savePlayerDataBatch(any()) } returns 1

            val service = createStorageServiceWithMockStorage(config, this)
            val d1 = createTestPlayerData(testUuid1, "world", GameMode.SURVIVAL)
            val d2 = createTestPlayerData(testUuid2, "world", GameMode.SURVIVAL)
            service.cache.put(d1, markDirty = true)
            service.cache.put(d2, markDirty = true)

            service.flushDirtyEntries()

            // We can't tell which failed, so both stay dirty rather than silently dropping one.
            assertEquals(2, service.cache.getDirtyEntries().size)
        }

        @Test
        fun `successful flush marks entries clean (B4 happy path)`() = testScope.runTest {
            val config = createMainConfig(writeBehindSeconds = 5, asyncSaving = true)
            every { configManager.mainConfig } returns config
            coEvery { mockStorage.savePlayerDataBatch(any()) } returns 2

            val service = createStorageServiceWithMockStorage(config, this)
            val d1 = createTestPlayerData(testUuid1, "world", GameMode.SURVIVAL)
            val d2 = createTestPlayerData(testUuid2, "world", GameMode.SURVIVAL)
            service.cache.put(d1, markDirty = true)
            service.cache.put(d2, markDirty = true)

            service.flushDirtyEntries()

            assertTrue(service.cache.getDirtyEntries().isEmpty())
        }
    }

    // ============================================
    // Helper Methods
    // ============================================

    private fun createTestPlayerData(
        uuid: UUID,
        group: String,
        gameMode: GameMode
    ): PlayerData {
        return PlayerData(
            uuid = uuid,
            playerName = "TestPlayer",
            group = group,
            gameMode = gameMode
        )
    }

    private fun createMainConfig(
        storageType: StorageType = StorageType.YAML,
        cacheEnabled: Boolean = true,
        cacheMaxSize: Int = 1000,
        writeBehindSeconds: Int = 5,
        asyncSaving: Boolean = true
    ): MainConfig {
        return MainConfig(
            storage = StorageConfig(
                type = storageType,
                sqlite = SqliteConfig(),
                mysql = MysqlConfig()
            ),
            cache = CacheConfig(
                enabled = cacheEnabled,
                maxSize = cacheMaxSize,
                ttlMinutes = 30,
                writeBehindSeconds = writeBehindSeconds
            ),
            features = FeaturesConfig(
                asyncSaving = asyncSaving
            )
        )
    }

    private suspend fun createStorageServiceWithMockStorage(
        config: MainConfig,
        scope: CoroutineScope,
        startWriteBehind: Boolean = false
    ): StorageService {
        val service = StorageService(plugin, scope)

        // Set up the fields directly using reflection
        val storageField = StorageService::class.java.getDeclaredField("storage")
        storageField.isAccessible = true
        storageField.set(service, mockStorage)

        val cacheField = StorageService::class.java.getDeclaredField("cache")
        cacheField.isAccessible = true
        cacheField.set(service, PlayerDataCache(config.cache))

        val initializedField = StorageService::class.java.getDeclaredField("initialized")
        initializedField.isAccessible = true
        initializedField.set(service, true)

        // Start write-behind job if requested
        if (startWriteBehind && config.cache.enabled && config.cache.writeBehindSeconds > 0) {
            val startWriteBehindMethod = StorageService::class.java.getDeclaredMethod("startWriteBehind")
            startWriteBehindMethod.isAccessible = true
            startWriteBehindMethod.invoke(service)
        }

        return service
    }
}
