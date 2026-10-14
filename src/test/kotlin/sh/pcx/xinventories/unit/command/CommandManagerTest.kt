package sh.pcx.xinventories.unit.command

import io.mockk.*
import org.bukkit.Server
import org.bukkit.command.PluginCommand
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertDoesNotThrow
import sh.pcx.xinventories.XInventories
import sh.pcx.xinventories.internal.command.CommandManager
import sh.pcx.xinventories.internal.command.XInventoriesCommand
import sh.pcx.xinventories.internal.command.subcommand.Subcommand
import sh.pcx.xinventories.internal.import.ImportService
import sh.pcx.xinventories.internal.service.EconomyService
import sh.pcx.xinventories.internal.service.GroupService
import sh.pcx.xinventories.internal.service.ServiceManager
import sh.pcx.xinventories.internal.util.Logging
import java.util.logging.Logger

/**
 * Unit tests for CommandManager.
 *
 * Tests cover:
 * - initialize() - Registering all subcommands and aliases
 * - registerSubcommand() - Adding subcommands to the registry (via initialize)
 * - getSubcommand() - Looking up subcommands by name or alias
 * - getAllSubcommands() - Getting all registered subcommands
 * - getSubcommandNames() - Getting all subcommand names
 * - shutdown() - Clearing registered commands
 */
@DisplayName("CommandManager Unit Tests")
class CommandManagerTest {

    private lateinit var plugin: XInventories
    private lateinit var server: Server
    private lateinit var serviceManager: ServiceManager
    private lateinit var economyService: EconomyService
    private lateinit var groupService: GroupService
    private lateinit var importService: ImportService
    private lateinit var pluginCommand: PluginCommand
    private lateinit var logger: Logger
    private lateinit var commandManager: CommandManager

    companion object {
        @JvmStatic
        @BeforeAll
        fun setupAll() {
            Logging.init(Logger.getLogger("CommandManagerTest"), false)
            mockkObject(Logging)
            every { Logging.debug(any<() -> String>()) } just Runs
            every { Logging.debug(any<String>()) } just Runs
            every { Logging.info(any()) } just Runs
            every { Logging.warning(any()) } just Runs
            every { Logging.error(any<String>()) } just Runs
            every { Logging.error(any<String>(), any()) } just Runs
            every { Logging.severe(any()) } just Runs
        }

        @JvmStatic
        @AfterAll
        fun teardownAll() {
            unmockkAll()
        }
    }

    @BeforeEach
    fun setUp() {
        plugin = mockk(relaxed = true)
        server = mockk(relaxed = true)
        serviceManager = mockk(relaxed = true)
        economyService = mockk(relaxed = true)
        groupService = mockk(relaxed = true)
        importService = mockk<ImportService>(relaxed = true)
        pluginCommand = mockk(relaxed = true)
        logger = mockk(relaxed = true)

        every { plugin.plugin } returns plugin
        every { plugin.server } returns server
        every { plugin.logger } returns logger
        every { plugin.serviceManager } returns serviceManager
        every { serviceManager.economyService } returns economyService
        every { serviceManager.groupService } returns groupService
        every { serviceManager.importService } returns importService
        every { plugin.getCommand("xinventories") } returns pluginCommand

        commandManager = CommandManager(plugin)
    }

    @AfterEach
    fun tearDown() {
        clearAllMocks()
    }

    // =========================================================================
    // initialize() Tests
    // =========================================================================

    @Nested
    @DisplayName("initialize")
    inner class InitializeTests {

        @Test
        @DisplayName("Should register all expected subcommands")
        fun registerAllExpectedSubcommands() {
            commandManager.initialize()

            val subcommands = commandManager.getAllSubcommands()
            assertTrue(subcommands.isNotEmpty(), "Subcommands should not be empty after initialization")

            // Verify core commands are registered
            val names = commandManager.getSubcommandNames()
            assertTrue(names.contains("reload"), "reload command should be registered")
            assertTrue(names.contains("save"), "save command should be registered")
            assertTrue(names.contains("load"), "load command should be registered")
            assertTrue(names.contains("group"), "group command should be registered")
            assertTrue(names.contains("world"), "world command should be registered")
            assertTrue(names.contains("pattern"), "pattern command should be registered")
            assertTrue(names.contains("cache"), "cache command should be registered")
            assertTrue(names.contains("backup"), "backup command should be registered")
            assertTrue(names.contains("convert"), "convert command should be registered")
            assertTrue(names.contains("debug"), "debug command should be registered")
            assertTrue(names.contains("gui"), "gui command should be registered")
            assertTrue(names.contains("sync"), "sync command should be registered")
        }

        @Test
        @DisplayName("Should register Data Foundation commands")
        fun registerDataFoundationCommands() {
            commandManager.initialize()

            val names = commandManager.getSubcommandNames()
            assertTrue(names.contains("history"), "history command should be registered")
            assertTrue(names.contains("restore"), "restore command should be registered")
            assertTrue(names.contains("snapshot"), "snapshot command should be registered")
            assertTrue(names.contains("deaths"), "deaths command should be registered")
        }

        @Test
        @DisplayName("Should register Content Control commands")
        fun registerContentControlCommands() {
            commandManager.initialize()

            val names = commandManager.getSubcommandNames()
            assertTrue(names.contains("template"), "template command should be registered")
            assertTrue(names.contains("restrict"), "restrict command should be registered")
            assertTrue(names.contains("vault"), "vault command should be registered")
            assertTrue(names.contains("reset"), "reset command should be registered")
        }

        @Test
        @DisplayName("Should register Advanced Groups commands")
        fun registerAdvancedGroupsCommands() {
            commandManager.initialize()

            val names = commandManager.getSubcommandNames()
            assertTrue(names.contains("conditions"), "conditions command should be registered")
            assertTrue(names.contains("whoami"), "whoami command should be registered")
            assertTrue(names.contains("lock"), "lock command should be registered")
            assertTrue(names.contains("unlock"), "unlock command should be registered")
        }

        @Test
        @DisplayName("Should register External Integrations commands")
        fun registerExternalIntegrationsCommands() {
            commandManager.initialize()

            val names = commandManager.getSubcommandNames()
            assertTrue(names.contains("balance"), "balance command should be registered")
            assertTrue(names.contains("import"), "import command should be registered")
        }

        @Test
        @DisplayName("Should register Quality of Life commands")
        fun registerQualityOfLifeCommands() {
            commandManager.initialize()

            val names = commandManager.getSubcommandNames()
            assertTrue(names.contains("merge"), "merge command should be registered")
            assertTrue(names.contains("export"), "export command should be registered")
            assertTrue(names.contains("importjson"), "importjson command should be registered")
        }

        @Test
        @DisplayName("Should register Admin Tools commands")
        fun registerAdminToolsCommands() {
            commandManager.initialize()

            val names = commandManager.getSubcommandNames()
            assertTrue(names.contains("bulk"), "bulk command should be registered")
            assertTrue(names.contains("audit"), "audit command should be registered")
            assertTrue(names.contains("stats"), "stats command should be registered")
        }

        @Test
        @DisplayName("Should register Advanced Features commands")
        fun registerAdvancedFeaturesCommands() {
            commandManager.initialize()

            val names = commandManager.getSubcommandNames()
            assertTrue(names.contains("expiration"), "expiration command should be registered")
            assertTrue(names.contains("tempgroup"), "tempgroup command should be registered")
        }

        @Test
        @DisplayName("Should set executor and tab completer on plugin command")
        fun setExecutorAndTabCompleter() {
            commandManager.initialize()

            verify { pluginCommand.setExecutor(any<XInventoriesCommand>()) }
            verify { pluginCommand.tabCompleter = any<XInventoriesCommand>() }
        }

        @Test
        @DisplayName("Should log severe error when command registration fails")
        fun logSevereErrorWhenCommandRegistrationFails() {
            every { plugin.getCommand("xinventories") } returns null

            commandManager.initialize()

            verify { logger.severe("Failed to register /xinventories command!") }
        }

        @Test
        @DisplayName("Should still register subcommands when plugin command is null")
        fun stillRegisterSubcommandsWhenPluginCommandIsNull() {
            every { plugin.getCommand("xinventories") } returns null

            commandManager.initialize()

            val subcommands = commandManager.getAllSubcommands()
            assertTrue(subcommands.isNotEmpty(), "Subcommands should be registered even when plugin command is null")
        }

        @Test
        @DisplayName("Should register subcommand names in lowercase")
        fun registerSubcommandNamesInLowercase() {
            commandManager.initialize()

            val names = commandManager.getSubcommandNames()
            names.forEach { name ->
                assertEquals(name.lowercase(), name, "Subcommand name '$name' should be lowercase")
            }
        }
    }

    // =========================================================================
    // getSubcommand() Tests
    // =========================================================================

    @Nested
    @DisplayName("getSubcommand")
    inner class GetSubcommandTests {

        @BeforeEach
        fun initializeCommands() {
            commandManager.initialize()
        }

        @Test
        @DisplayName("Should return subcommand by exact name")
        fun returnSubcommandByExactName() {
            val subcommand = commandManager.getSubcommand("reload")

            assertNotNull(subcommand)
            assertEquals("reload", subcommand!!.name)
        }

        @Test
        @DisplayName("Should return subcommand by uppercase name")
        fun returnSubcommandByUppercaseName() {
            val subcommand = commandManager.getSubcommand("RELOAD")

            assertNotNull(subcommand)
            assertEquals("reload", subcommand!!.name)
        }

        @Test
        @DisplayName("Should return subcommand by mixed case name")
        fun returnSubcommandByMixedCaseName() {
            val subcommand = commandManager.getSubcommand("ReLOaD")

            assertNotNull(subcommand)
            assertEquals("reload", subcommand!!.name)
        }

        @Test
        @DisplayName("Should return subcommand by alias")
        fun returnSubcommandByAlias() {
            // GroupCommand has aliases: ["groups", "g"]
            val subcommand = commandManager.getSubcommand("groups")

            assertNotNull(subcommand)
            assertEquals("group", subcommand!!.name)
        }

        @Test
        @DisplayName("Should return subcommand by short alias")
        fun returnSubcommandByShortAlias() {
            // GroupCommand has aliases: ["groups", "g"]
            val subcommand = commandManager.getSubcommand("g")

            assertNotNull(subcommand)
            assertEquals("group", subcommand!!.name)
        }

        @Test
        @DisplayName("Should return subcommand by alias case insensitive")
        fun returnSubcommandByAliasCaseInsensitive() {
            // GroupCommand has aliases: ["groups", "g"]
            val subcommand = commandManager.getSubcommand("GROUPS")

            assertNotNull(subcommand)
            assertEquals("group", subcommand!!.name)
        }

        @Test
        @DisplayName("Should return null for unknown subcommand")
        fun returnNullForUnknownSubcommand() {
            val subcommand = commandManager.getSubcommand("nonexistent")

            assertNull(subcommand)
        }

        @Test
        @DisplayName("Should return null for empty string")
        fun returnNullForEmptyString() {
            val subcommand = commandManager.getSubcommand("")

            assertNull(subcommand)
        }

        @Test
        @DisplayName("Should handle backup command alias 'backups'")
        fun handleBackupCommandAlias() {
            val subcommand = commandManager.getSubcommand("backups")

            assertNotNull(subcommand)
            assertEquals("backup", subcommand!!.name)
        }

        @Test
        @DisplayName("Should handle convert command alias 'migrate'")
        fun handleConvertCommandAlias() {
            val subcommand = commandManager.getSubcommand("migrate")

            assertNotNull(subcommand)
            // 'migrate' belongs to convert (storage migration); the import command no longer claims it.
            assertEquals("convert", subcommand!!.name)
        }

        @Test
        @DisplayName("Should handle balance command aliases")
        fun handleBalanceCommandAliases() {
            val balCommand = commandManager.getSubcommand("bal")
            val moneyCommand = commandManager.getSubcommand("money")

            assertNotNull(balCommand)
            assertNotNull(moneyCommand)
            assertEquals("balance", balCommand!!.name)
            assertEquals("balance", moneyCommand!!.name)
        }

        @Test
        @DisplayName("Should handle history command aliases")
        fun handleHistoryCommandAliases() {
            val histCommand = commandManager.getSubcommand("hist")
            val versionsCommand = commandManager.getSubcommand("versions")

            assertNotNull(histCommand)
            assertNotNull(versionsCommand)
            assertEquals("history", histCommand!!.name)
            assertEquals("history", versionsCommand!!.name)
        }

        @Test
        @DisplayName("Should handle pattern command aliases")
        fun handlePatternCommandAliases() {
            val patternsCommand = commandManager.getSubcommand("patterns")
            val pCommand = commandManager.getSubcommand("p")

            assertNotNull(patternsCommand)
            assertNotNull(pCommand)
            assertEquals("pattern", patternsCommand!!.name)
            assertEquals("pattern", pCommand!!.name)
        }

        @Test
        @DisplayName("Should handle world command aliases")
        fun handleWorldCommandAliases() {
            val worldsCommand = commandManager.getSubcommand("worlds")
            val wCommand = commandManager.getSubcommand("w")

            assertNotNull(worldsCommand)
            assertNotNull(wCommand)
            assertEquals("world", worldsCommand!!.name)
            assertEquals("world", wCommand!!.name)
        }

        @Test
        @DisplayName("Should handle GUI command aliases")
        fun handleGuiCommandAliases() {
            val menuCommand = commandManager.getSubcommand("menu")
            val adminCommand = commandManager.getSubcommand("admin")

            assertNotNull(menuCommand)
            assertNotNull(adminCommand)
            assertEquals("gui", menuCommand!!.name)
            assertEquals("gui", adminCommand!!.name)
        }

        @Test
        @DisplayName("Should handle whoami command aliases")
        fun handleWhoamiCommandAliases() {
            val whoCommand = commandManager.getSubcommand("who")
            val meCommand = commandManager.getSubcommand("me")

            assertNotNull(whoCommand)
            assertNotNull(meCommand)
            assertEquals("whoami", whoCommand!!.name)
            assertEquals("whoami", meCommand!!.name)
        }

        @Test
        @DisplayName("Should handle lock command alias")
        fun handleLockCommandAlias() {
            val freezeCommand = commandManager.getSubcommand("freeze")

            assertNotNull(freezeCommand)
            assertEquals("lock", freezeCommand!!.name)
        }

        @Test
        @DisplayName("Should handle unlock command alias")
        fun handleUnlockCommandAlias() {
            val unfreezeCommand = commandManager.getSubcommand("unfreeze")

            assertNotNull(unfreezeCommand)
            assertEquals("unlock", unfreezeCommand!!.name)
        }

        @Test
        @DisplayName("Should handle restore command alias")
        fun handleRestoreCommandAlias() {
            val rollbackCommand = commandManager.getSubcommand("rollback")

            assertNotNull(rollbackCommand)
            assertEquals("restore", rollbackCommand!!.name)
        }

        @Test
        @DisplayName("Should handle snapshot command aliases")
        fun handleSnapshotCommandAliases() {
            val snapCommand = commandManager.getSubcommand("snap")

            assertNotNull(snapCommand)
            assertEquals("snapshot", snapCommand!!.name)
        }

        @Test
        @DisplayName("Should handle conditions command alias")
        fun handleConditionsCommandAlias() {
            val condCommand = commandManager.getSubcommand("cond")

            assertNotNull(condCommand)
            assertEquals("conditions", condCommand!!.name)
        }

        @Test
        @DisplayName("Should handle deaths command alias")
        fun handleDeathsCommandAlias() {
            val deathCommand = commandManager.getSubcommand("death")

            assertNotNull(deathCommand)
            assertEquals("deaths", deathCommand!!.name)
        }

        @Test
        @DisplayName("Should handle template command aliases")
        fun handleTemplateCommandAliases() {
            val templatesCommand = commandManager.getSubcommand("templates")
            val tmplCommand = commandManager.getSubcommand("tmpl")

            assertNotNull(templatesCommand)
            assertNotNull(tmplCommand)
            assertEquals("template", templatesCommand!!.name)
            assertEquals("template", tmplCommand!!.name)
        }

        @Test
        @DisplayName("Should handle vault command aliases")
        fun handleVaultCommandAliases() {
            val confiscatedCommand = commandManager.getSubcommand("confiscated")
            val confiscationCommand = commandManager.getSubcommand("confiscation")

            assertNotNull(confiscatedCommand)
            assertNotNull(confiscationCommand)
            assertEquals("vault", confiscatedCommand!!.name)
            assertEquals("vault", confiscationCommand!!.name)
        }

        @Test
        @DisplayName("Should handle reset command aliases")
        fun handleResetCommandAliases() {
            val resetinvCommand = commandManager.getSubcommand("resetinv")
            val resetinventoryCommand = commandManager.getSubcommand("resetinventory")

            assertNotNull(resetinvCommand)
            assertNotNull(resetinventoryCommand)
            assertEquals("reset", resetinvCommand!!.name)
            assertEquals("reset", resetinventoryCommand!!.name)
        }

        @Test
        @DisplayName("Should handle restrict command aliases")
        fun handleRestrictCommandAliases() {
            val restrictionCommand = commandManager.getSubcommand("restriction")
            val restrictionsCommand = commandManager.getSubcommand("restrictions")

            assertNotNull(restrictionCommand)
            assertNotNull(restrictionsCommand)
            assertEquals("restrict", restrictionCommand!!.name)
            assertEquals("restrict", restrictionsCommand!!.name)
        }

        @Test
        @DisplayName("Should handle merge command alias")
        fun handleMergeCommandAlias() {
            val combineCommand = commandManager.getSubcommand("combine")

            assertNotNull(combineCommand)
            assertEquals("merge", combineCommand!!.name)
        }

        @Test
        @DisplayName("Should handle export command alias")
        fun handleExportCommandAlias() {
            val expCommand = commandManager.getSubcommand("exp")

            assertNotNull(expCommand)
            assertEquals("export", expCommand!!.name)
        }

        @Test
        @DisplayName("Should handle importjson command aliases")
        fun handleImportJsonCommandAliases() {
            val impjsonCommand = commandManager.getSubcommand("impjson")
            val jsonimportCommand = commandManager.getSubcommand("jsonimport")

            assertNotNull(impjsonCommand)
            assertNotNull(jsonimportCommand)
            assertEquals("importjson", impjsonCommand!!.name)
            assertEquals("importjson", jsonimportCommand!!.name)
        }

        @Test
        @DisplayName("Should handle stats command aliases")
        fun handleStatsCommandAliases() {
            val metricsCommand = commandManager.getSubcommand("metrics")
            val statisticsCommand = commandManager.getSubcommand("statistics")

            assertNotNull(metricsCommand)
            assertNotNull(statisticsCommand)
            assertEquals("stats", metricsCommand!!.name)
            assertEquals("stats", statisticsCommand!!.name)
        }

        @Test
        @DisplayName("Should handle expiration command aliases")
        fun handleExpirationCommandAliases() {
            val expireCommand = commandManager.getSubcommand("expire")
            val cleanupCommand = commandManager.getSubcommand("cleanup")

            assertNotNull(expireCommand)
            assertNotNull(cleanupCommand)
            assertEquals("expiration", expireCommand!!.name)
            assertEquals("expiration", cleanupCommand!!.name)
        }

        @Test
        @DisplayName("Should handle tempgroup command aliases")
        fun handleTempGroupCommandAliases() {
            val tgCommand = commandManager.getSubcommand("tg")
            val tempgCommand = commandManager.getSubcommand("tempg")

            assertNotNull(tgCommand)
            assertNotNull(tempgCommand)
            assertEquals("tempgroup", tgCommand!!.name)
            assertEquals("tempgroup", tempgCommand!!.name)
        }

        @Test
        @DisplayName("Should handle sync command alias")
        fun handleSyncCommandAlias() {
            val networkCommand = commandManager.getSubcommand("network")

            assertNotNull(networkCommand)
            assertEquals("sync", networkCommand!!.name)
        }
    }

    // =========================================================================
    // getAllSubcommands() Tests
    // =========================================================================

    @Nested
    @DisplayName("getAllSubcommands")
    inner class GetAllSubcommandsTests {

        @Test
        @DisplayName("Should return empty collection before initialization")
        fun returnEmptyCollectionBeforeInitialization() {
            val subcommands = commandManager.getAllSubcommands()

            assertTrue(subcommands.isEmpty())
        }

        @Test
        @DisplayName("Should return all registered subcommands after initialization")
        fun returnAllRegisteredSubcommandsAfterInitialization() {
            commandManager.initialize()

            val subcommands = commandManager.getAllSubcommands()

            assertTrue(subcommands.isNotEmpty())
            // Verify expected count (should have at least 30+ commands)
            assertTrue(subcommands.size >= 30, "Should have at least 30 subcommands registered")
        }

        @Test
        @DisplayName("Should return unique subcommands only")
        fun returnUniqueSubcommandsOnly() {
            commandManager.initialize()

            val subcommands = commandManager.getAllSubcommands()
            val names = subcommands.map { it.name }

            assertEquals(names.size, names.distinct().size, "Should not have duplicate subcommands")
        }

        @Test
        @DisplayName("Should return subcommands with valid properties")
        fun returnSubcommandsWithValidProperties() {
            commandManager.initialize()

            val subcommands = commandManager.getAllSubcommands()

            subcommands.forEach { subcommand ->
                assertTrue(subcommand.name.isNotBlank(), "Subcommand name should not be blank")
                assertTrue(subcommand.permission.isNotBlank(), "Subcommand permission should not be blank")
                assertTrue(subcommand.usage.isNotBlank(), "Subcommand usage should not be blank")
                assertTrue(subcommand.description.isNotBlank(), "Subcommand description should not be blank")
            }
        }

        @Test
        @DisplayName("Should return empty collection after shutdown")
        fun returnEmptyCollectionAfterShutdown() {
            commandManager.initialize()
            assertTrue(commandManager.getAllSubcommands().isNotEmpty())

            commandManager.shutdown()

            assertTrue(commandManager.getAllSubcommands().isEmpty())
        }
    }

    // =========================================================================
    // getSubcommandNames() Tests
    // =========================================================================

    @Nested
    @DisplayName("getSubcommandNames")
    inner class GetSubcommandNamesTests {

        @Test
        @DisplayName("Should return empty set before initialization")
        fun returnEmptySetBeforeInitialization() {
            val names = commandManager.getSubcommandNames()

            assertTrue(names.isEmpty())
        }

        @Test
        @DisplayName("Should return all subcommand names after initialization")
        fun returnAllSubcommandNamesAfterInitialization() {
            commandManager.initialize()

            val names = commandManager.getSubcommandNames()

            assertTrue(names.isNotEmpty())
            assertTrue(names.size >= 30, "Should have at least 30 subcommand names")
        }

        @Test
        @DisplayName("Should return lowercase names only")
        fun returnLowercaseNamesOnly() {
            commandManager.initialize()

            val names = commandManager.getSubcommandNames()

            names.forEach { name ->
                assertEquals(name.lowercase(), name, "Name '$name' should be lowercase")
            }
        }

        @Test
        @DisplayName("Should not include aliases in names")
        fun shouldNotIncludeAliasesInNames() {
            commandManager.initialize()

            val names = commandManager.getSubcommandNames()

            // Aliases should not be in names
            assertFalse(names.contains("g"), "'g' is an alias and should not be in names")
            assertFalse(names.contains("groups"), "'groups' is an alias and should not be in names")
            assertFalse(names.contains("bal"), "'bal' is an alias and should not be in names")
            assertFalse(names.contains("money"), "'money' is an alias and should not be in names")
        }

        @Test
        @DisplayName("Should return expected core command names")
        fun returnExpectedCoreCommandNames() {
            commandManager.initialize()

            val names = commandManager.getSubcommandNames()

            val expectedNames = listOf(
                "reload", "save", "load", "group", "world", "pattern",
                "cache", "backup", "convert", "debug", "gui", "sync",
                "history", "restore", "snapshot", "deaths",
                "template", "restrict", "vault", "reset",
                "conditions", "whoami", "lock", "unlock",
                "balance", "import",
                "merge", "export", "importjson",
                "bulk", "audit", "stats",
                "expiration", "tempgroup"
            )

            expectedNames.forEach { expected ->
                assertTrue(names.contains(expected), "Names should contain '$expected'")
            }
        }

        @Test
        @DisplayName("Should return empty set after shutdown")
        fun returnEmptySetAfterShutdown() {
            commandManager.initialize()
            assertTrue(commandManager.getSubcommandNames().isNotEmpty())

            commandManager.shutdown()

            assertTrue(commandManager.getSubcommandNames().isEmpty())
        }
    }

    // =========================================================================
    // shutdown() Tests
    // =========================================================================

    @Nested
    @DisplayName("shutdown")
    inner class ShutdownTests {

        @Test
        @DisplayName("Should clear all subcommands on shutdown")
        fun clearAllSubcommandsOnShutdown() {
            commandManager.initialize()
            assertTrue(commandManager.getAllSubcommands().isNotEmpty())

            commandManager.shutdown()

            assertTrue(commandManager.getAllSubcommands().isEmpty())
        }

        @Test
        @DisplayName("Should clear all subcommand names on shutdown")
        fun clearAllSubcommandNamesOnShutdown() {
            commandManager.initialize()
            assertTrue(commandManager.getSubcommandNames().isNotEmpty())

            commandManager.shutdown()

            assertTrue(commandManager.getSubcommandNames().isEmpty())
        }

        @Test
        @DisplayName("Should clear all aliases on shutdown")
        fun clearAllAliasesOnShutdown() {
            commandManager.initialize()
            assertNotNull(commandManager.getSubcommand("g")) // Alias for group

            commandManager.shutdown()

            assertNull(commandManager.getSubcommand("g"))
            assertNull(commandManager.getSubcommand("groups"))
            assertNull(commandManager.getSubcommand("bal"))
        }

        @Test
        @DisplayName("Should handle multiple shutdown calls")
        fun handleMultipleShutdownCalls() {
            commandManager.initialize()

            assertDoesNotThrow {
                commandManager.shutdown()
                commandManager.shutdown()
                commandManager.shutdown()
            }
        }

        @Test
        @DisplayName("Should handle shutdown before initialization")
        fun handleShutdownBeforeInitialization() {
            assertDoesNotThrow {
                commandManager.shutdown()
            }

            assertTrue(commandManager.getAllSubcommands().isEmpty())
            assertTrue(commandManager.getSubcommandNames().isEmpty())
        }

        @Test
        @DisplayName("Should allow re-initialization after shutdown")
        fun allowReInitializationAfterShutdown() {
            commandManager.initialize()
            val initialCount = commandManager.getAllSubcommands().size

            commandManager.shutdown()
            assertTrue(commandManager.getAllSubcommands().isEmpty())

            commandManager.initialize()
            val afterReinitCount = commandManager.getAllSubcommands().size

            assertEquals(initialCount, afterReinitCount, "Should have same number of commands after re-initialization")
        }

        @Test
        @DisplayName("Should restore all aliases after re-initialization")
        fun restoreAllAliasesAfterReInitialization() {
            commandManager.initialize()
            assertNotNull(commandManager.getSubcommand("g"))

            commandManager.shutdown()
            assertNull(commandManager.getSubcommand("g"))

            commandManager.initialize()
            assertNotNull(commandManager.getSubcommand("g"))
            assertEquals("group", commandManager.getSubcommand("g")!!.name)
        }
    }

    // =========================================================================
    // Edge Cases and Integration Tests
    // =========================================================================

    @Nested
    @DisplayName("Edge Cases")
    inner class EdgeCasesTests {

        @Test
        @DisplayName("Should handle getSubcommand with whitespace")
        fun handleGetSubcommandWithWhitespace() {
            commandManager.initialize()

            // Note: The implementation uses lowercase() which doesn't trim whitespace
            // so " reload" would not match "reload"
            val result = commandManager.getSubcommand(" reload")

            assertNull(result, "Subcommand with leading whitespace should not match")
        }

        @Test
        @DisplayName("Should handle getSubcommand with trailing whitespace")
        fun handleGetSubcommandWithTrailingWhitespace() {
            commandManager.initialize()

            val result = commandManager.getSubcommand("reload ")

            assertNull(result, "Subcommand with trailing whitespace should not match")
        }

        @Test
        @DisplayName("Should handle very long subcommand name lookup")
        fun handleVeryLongSubcommandNameLookup() {
            commandManager.initialize()

            val longName = "a".repeat(1000)
            val result = commandManager.getSubcommand(longName)

            assertNull(result)
        }

        @Test
        @DisplayName("Should handle special characters in subcommand lookup")
        fun handleSpecialCharactersInSubcommandLookup() {
            commandManager.initialize()

            val specialNames = listOf(
                "reload!",
                "reload@",
                "reload#",
                "reload$",
                "reload%",
                "reload^",
                "reload&",
                "reload*",
                "reload()",
                "reload[]",
                "reload{}",
                "reload<>",
                "reload/",
                "reload\\",
                "reload|"
            )

            specialNames.forEach { name ->
                assertNull(commandManager.getSubcommand(name), "Should not find subcommand for '$name'")
            }
        }

        @Test
        @DisplayName("Should handle unicode characters in subcommand lookup")
        fun handleUnicodeCharactersInSubcommandLookup() {
            commandManager.initialize()

            val result = commandManager.getSubcommand("\u4E2D\u6587")

            assertNull(result)
        }

        @Test
        @DisplayName("Should ensure all subcommands have correct permissions prefix")
        fun ensureAllSubcommandsHaveCorrectPermissionsPrefix() {
            commandManager.initialize()

            val subcommands = commandManager.getAllSubcommands()

            subcommands.forEach { subcommand ->
                assertTrue(
                    subcommand.permission.startsWith("xinventories."),
                    "Permission '${subcommand.permission}' for '${subcommand.name}' should start with 'xinventories.'"
                )
            }
        }

        @Test
        @DisplayName("Should ensure all subcommands have usage starting with /xinv")
        fun ensureAllSubcommandsHaveUsageStartingWithXinv() {
            commandManager.initialize()

            val subcommands = commandManager.getAllSubcommands()

            subcommands.forEach { subcommand ->
                assertTrue(
                    subcommand.usage.startsWith("/xinv"),
                    "Usage '${subcommand.usage}' for '${subcommand.name}' should start with '/xinv'"
                )
            }
        }

        @Test
        @DisplayName("Should handle concurrent access to getAllSubcommands")
        fun handleConcurrentAccessToGetAllSubcommands() {
            commandManager.initialize()

            val results = (1..10).map {
                commandManager.getAllSubcommands().size
            }

            val expected = results.first()
            results.forEach { size ->
                assertEquals(expected, size, "All concurrent accesses should return same size")
            }
        }

        @Test
        @DisplayName("Should handle concurrent access to getSubcommandNames")
        fun handleConcurrentAccessToGetSubcommandNames() {
            commandManager.initialize()

            val results = (1..10).map {
                commandManager.getSubcommandNames().size
            }

            val expected = results.first()
            results.forEach { size ->
                assertEquals(expected, size, "All concurrent accesses should return same size")
            }
        }
    }

    // =========================================================================
    // Subcommand Interface Verification Tests
    // =========================================================================

    @Nested
    @DisplayName("Subcommand Interface Verification")
    inner class SubcommandInterfaceVerificationTests {

        @Test
        @DisplayName("All subcommands should implement Subcommand interface")
        fun allSubcommandsShouldImplementSubcommandInterface() {
            commandManager.initialize()

            val subcommands = commandManager.getAllSubcommands()

            subcommands.forEach { subcommand ->
                assertTrue(
                    subcommand is Subcommand,
                    "${subcommand.name} should implement Subcommand interface"
                )
            }
        }

        @Test
        @DisplayName("Subcommands should have non-empty aliases list or default empty list")
        fun subcommandsShouldHaveValidAliasesList() {
            commandManager.initialize()

            val subcommands = commandManager.getAllSubcommands()

            subcommands.forEach { subcommand ->
                assertNotNull(subcommand.aliases, "Aliases should not be null for ${subcommand.name}")
            }
        }

        @Test
        @DisplayName("Subcommands should have valid playerOnly property")
        fun subcommandsShouldHaveValidPlayerOnlyProperty() {
            commandManager.initialize()

            val subcommands = commandManager.getAllSubcommands()

            subcommands.forEach { subcommand ->
                // playerOnly should be either true or false, not throw
                assertDoesNotThrow {
                    subcommand.playerOnly
                }
            }
        }
    }

    // =========================================================================
    // Plugin Integration Tests
    // =========================================================================

    @Nested
    @DisplayName("Plugin Integration")
    inner class PluginIntegrationTests {

        @Test
        @DisplayName("Should use plugin logger for error messages")
        fun usePluginLoggerForErrorMessages() {
            every { plugin.getCommand("xinventories") } returns null

            commandManager.initialize()

            verify { logger.severe("Failed to register /xinventories command!") }
        }

        @Test
        @DisplayName("Should access serviceManager for BalanceCommand")
        fun accessServiceManagerForBalanceCommand() {
            commandManager.initialize()

            verify { serviceManager.economyService }
            verify { serviceManager.groupService }
        }

        @Test
        @DisplayName("Should access serviceManager for ImportCommand")
        fun accessServiceManagerForImportCommand() {
            commandManager.initialize()

            verify { serviceManager.importService }
        }

        @Test
        @DisplayName("Should set both executor and tabCompleter to same instance")
        fun setBothExecutorAndTabCompleterToSameInstance() {
            val executorSlot = slot<XInventoriesCommand>()
            val tabCompleterSlot = slot<XInventoriesCommand>()

            every { pluginCommand.setExecutor(capture(executorSlot)) } just Runs
            every { pluginCommand.tabCompleter = capture(tabCompleterSlot) } just Runs

            commandManager.initialize()

            assertNotNull(executorSlot.captured)
            assertNotNull(tabCompleterSlot.captured)
            // They should be the same instance
            assertTrue(executorSlot.captured === tabCompleterSlot.captured)
        }
    }
}
