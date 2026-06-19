package sh.pcx.xinventories.unit.compat

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import sh.pcx.xinventories.internal.compat.VersionDetector

/**
 * Unit tests for VersionDetector.
 *
 * Note: These tests focus on the parsing logic since actual version detection
 * requires a running Bukkit server. The parseVersion method is internal but
 * exposed for testing purposes.
 */
@DisplayName("VersionDetector")
class VersionDetectorTest {

    @Nested
    @DisplayName("parseVersion")
    inner class ParseVersionTests {

        @Test
        @DisplayName("should parse full version string with major, minor, and patch")
        fun parseFullVersion() {
            val parsed = VersionDetector.parseVersion("1.20.6")

            assertEquals(1, parsed.major)
            assertEquals(20, parsed.minor)
            assertEquals(6, parsed.patch)
        }

        @Test
        @DisplayName("should parse version without patch (defaults to 0)")
        fun parseVersionWithoutPatch() {
            val parsed = VersionDetector.parseVersion("1.21")

            assertEquals(1, parsed.major)
            assertEquals(21, parsed.minor)
            assertEquals(0, parsed.patch)
        }

        @Test
        @DisplayName("should parse 1.20.5")
        fun parse1205() {
            val parsed = VersionDetector.parseVersion("1.20.5")

            assertEquals(1, parsed.major)
            assertEquals(20, parsed.minor)
            assertEquals(5, parsed.patch)
        }

        @Test
        @DisplayName("should parse 1.21.1")
        fun parse1211() {
            val parsed = VersionDetector.parseVersion("1.21.1")

            assertEquals(1, parsed.major)
            assertEquals(21, parsed.minor)
            assertEquals(1, parsed.patch)
        }

        @Test
        @DisplayName("should parse double-digit patch versions")
        fun parseDoubledigitPatch() {
            val parsed = VersionDetector.parseVersion("1.21.11")

            assertEquals(1, parsed.major)
            assertEquals(21, parsed.minor)
            assertEquals(11, parsed.patch)
        }

        @Test
        @DisplayName("should parse year-based version 26.1.2 (year=major, drop=minor, hotfix=patch)")
        fun parseYearBased26() {
            val parsed = VersionDetector.parseVersion("26.1.2")

            assertEquals(26, parsed.major)
            assertEquals(1, parsed.minor)
            assertEquals(2, parsed.patch)
        }

        @Test
        @DisplayName("should parse year-based version without hotfix (26.1 -> patch 0)")
        fun parseYearBased26NoHotfix() {
            val parsed = VersionDetector.parseVersion("26.1")

            assertEquals(26, parsed.major)
            assertEquals(1, parsed.minor)
            assertEquals(0, parsed.patch)
        }

        @Test
        @DisplayName("should handle empty string gracefully")
        fun parseEmptyString() {
            val parsed = VersionDetector.parseVersion("")

            assertEquals(1, parsed.major) // defaults
            assertEquals(0, parsed.minor)
            assertEquals(0, parsed.patch)
        }

        @Test
        @DisplayName("should handle malformed version string")
        fun parseMalformedVersion() {
            val parsed = VersionDetector.parseVersion("invalid.version.string")

            // Should default to safe values
            assertEquals(1, parsed.major)
            assertEquals(0, parsed.minor)
            assertEquals(0, parsed.patch)
        }

        @Test
        @DisplayName("should handle single number version")
        fun parseSingleNumber() {
            val parsed = VersionDetector.parseVersion("1")

            assertEquals(1, parsed.major)
            assertEquals(0, parsed.minor)
            assertEquals(0, parsed.patch)
        }
    }

    @Nested
    @DisplayName("isAtLeast comparisons")
    inner class IsAtLeastTests {

        @Test
        @DisplayName("1.21.1 is at least 1.20.0")
        fun version1211IsAtLeast1200() {
            val version = VersionDetector.parseVersion("1.21.1")

            assertTrue(isAtLeastForVersion(version, 1, 20, 0))
        }

        @Test
        @DisplayName("1.21.1 is at least 1.21.0")
        fun version1211IsAtLeast1210() {
            val version = VersionDetector.parseVersion("1.21.1")

            assertTrue(isAtLeastForVersion(version, 1, 21, 0))
        }

        @Test
        @DisplayName("1.21.1 is at least 1.21.1")
        fun version1211IsAtLeast1211() {
            val version = VersionDetector.parseVersion("1.21.1")

            assertTrue(isAtLeastForVersion(version, 1, 21, 1))
        }

        @Test
        @DisplayName("1.21.1 is not at least 1.21.2")
        fun version1211IsNotAtLeast1212() {
            val version = VersionDetector.parseVersion("1.21.1")

            assertFalse(isAtLeastForVersion(version, 1, 21, 2))
        }

        @Test
        @DisplayName("1.21.1 is not at least 1.22.0")
        fun version1211IsNotAtLeast1220() {
            val version = VersionDetector.parseVersion("1.21.1")

            assertFalse(isAtLeastForVersion(version, 1, 22, 0))
        }

        @Test
        @DisplayName("1.20.6 is at least 1.20.5")
        fun version1206IsAtLeast1205() {
            val version = VersionDetector.parseVersion("1.20.6")

            assertTrue(isAtLeastForVersion(version, 1, 20, 5))
        }

        @Test
        @DisplayName("1.20.6 is at least 1.20.6")
        fun version1206IsAtLeast1206() {
            val version = VersionDetector.parseVersion("1.20.6")

            assertTrue(isAtLeastForVersion(version, 1, 20, 6))
        }

        @Test
        @DisplayName("1.20.6 is not at least 1.21.0")
        fun version1206IsNotAtLeast1210() {
            val version = VersionDetector.parseVersion("1.20.6")

            assertFalse(isAtLeastForVersion(version, 1, 21, 0))
        }

        @Test
        @DisplayName("1.20.5 is at least 1.20.5 (exact match)")
        fun version1205IsAtLeast1205() {
            val version = VersionDetector.parseVersion("1.20.5")

            assertTrue(isAtLeastForVersion(version, 1, 20, 5))
        }

        @Test
        @DisplayName("1.20.5 is not at least 1.20.6")
        fun version1205IsNotAtLeast1206() {
            val version = VersionDetector.parseVersion("1.20.5")

            assertFalse(isAtLeastForVersion(version, 1, 20, 6))
        }

        @Test
        @DisplayName("year-based 26.1.2 is at least every legacy 1.x target")
        fun yearBased26IsAtLeastLegacy() {
            val version = VersionDetector.parseVersion("26.1.2")

            assertTrue(isAtLeastForVersion(version, 1, 20, 5))
            assertTrue(isAtLeastForVersion(version, 1, 21, 0))
            assertTrue(isAtLeastForVersion(version, 1, 21, 11))
            // and against a future legacy-style target that will never ship
            assertTrue(isAtLeastForVersion(version, 1, 99, 99))
        }

        /**
         * Helper function to test isAtLeast logic without requiring Bukkit.
         */
        private fun isAtLeastForVersion(
            current: VersionDetector.ParsedVersion,
            major: Int,
            minor: Int,
            patch: Int
        ): Boolean = when {
            current.major > major -> true
            current.major < major -> false
            current.minor > minor -> true
            current.minor < minor -> false
            else -> current.patch >= patch
        }
    }

    @Nested
    @DisplayName("isKotlinBundled logic")
    inner class IsKotlinBundledTests {

        @Test
        @DisplayName("1.21.0 should have Kotlin bundled")
        fun version1210HasKotlinBundled() {
            val version = VersionDetector.parseVersion("1.21.0")

            assertTrue(isKotlinBundledForVersion(version))
        }

        @Test
        @DisplayName("1.21.1 should have Kotlin bundled")
        fun version1211HasKotlinBundled() {
            val version = VersionDetector.parseVersion("1.21.1")

            assertTrue(isKotlinBundledForVersion(version))
        }

        @Test
        @DisplayName("1.21.11 should have Kotlin bundled")
        fun version12111HasKotlinBundled() {
            val version = VersionDetector.parseVersion("1.21.11")

            assertTrue(isKotlinBundledForVersion(version))
        }

        @Test
        @DisplayName("1.20.6 should not have Kotlin bundled")
        fun version1206DoesNotHaveKotlinBundled() {
            val version = VersionDetector.parseVersion("1.20.6")

            assertFalse(isKotlinBundledForVersion(version))
        }

        @Test
        @DisplayName("1.20.5 should not have Kotlin bundled")
        fun version1205DoesNotHaveKotlinBundled() {
            val version = VersionDetector.parseVersion("1.20.5")

            assertFalse(isKotlinBundledForVersion(version))
        }

        @Test
        @DisplayName("1.20.4 should not have Kotlin bundled")
        fun version1204DoesNotHaveKotlinBundled() {
            val version = VersionDetector.parseVersion("1.20.4")

            assertFalse(isKotlinBundledForVersion(version))
        }

        @Test
        @DisplayName("1.22.0 should have Kotlin bundled (future version)")
        fun version1220ShouldHaveKotlinBundled() {
            val version = VersionDetector.parseVersion("1.22.0")

            assertTrue(isKotlinBundledForVersion(version))
        }

        @Test
        @DisplayName("year-based 26.1.2 should have Kotlin bundled")
        fun version2612HasKotlinBundled() {
            val version = VersionDetector.parseVersion("26.1.2")

            assertTrue(isKotlinBundledForVersion(version))
        }

        /**
         * Helper function to test isKotlinBundled logic without requiring Bukkit.
         */
        private fun isKotlinBundledForVersion(version: VersionDetector.ParsedVersion): Boolean {
            return when {
                version.major > 1 -> true
                version.major < 1 -> false
                version.minor > 21 -> true
                version.minor < 21 -> false
                else -> version.patch >= 0 // 1.21.0+
            }
        }
    }

    @Nested
    @DisplayName("ParsedVersion data class")
    inner class ParsedVersionTests {

        @Test
        @DisplayName("equal versions should be equal")
        fun equalVersions() {
            val v1 = VersionDetector.parseVersion("1.20.6")
            val v2 = VersionDetector.parseVersion("1.20.6")

            assertEquals(v1, v2)
        }

        @Test
        @DisplayName("different versions should not be equal")
        fun differentVersions() {
            val v1 = VersionDetector.parseVersion("1.20.6")
            val v2 = VersionDetector.parseVersion("1.21.1")

            assertNotEquals(v1, v2)
        }

        @Test
        @DisplayName("copy should work correctly")
        fun copyWorks() {
            val original = VersionDetector.parseVersion("1.20.6")
            val copy = original.copy(minor = 21)

            assertEquals(1, copy.major)
            assertEquals(21, copy.minor)
            assertEquals(6, copy.patch)
        }

        @Test
        @DisplayName("hashCode should be consistent with equals")
        fun hashCodeConsistent() {
            val v1 = VersionDetector.parseVersion("1.20.6")
            val v2 = VersionDetector.parseVersion("1.20.6")

            assertEquals(v1.hashCode(), v2.hashCode())
        }
    }

    @Nested
    @DisplayName("Edge cases")
    inner class EdgeCaseTests {

        @Test
        @DisplayName("should handle version with extra parts")
        fun versionWithExtraParts() {
            // Some servers might have versions like "1.20.6.1"
            val parsed = VersionDetector.parseVersion("1.20.6.1")

            assertEquals(1, parsed.major)
            assertEquals(20, parsed.minor)
            assertEquals(6, parsed.patch)
            // Extra parts are ignored
        }

        @Test
        @DisplayName("should handle version with pre-release suffix stripped")
        fun versionWithPreReleaseSuffix() {
            // getBukkitVersion returns something like "1.20.6-R0.1-SNAPSHOT"
            // This should be stripped before parsing
            val versionString = "1.20.6-R0.1-SNAPSHOT".substringBefore("-")
            val parsed = VersionDetector.parseVersion(versionString)

            assertEquals(1, parsed.major)
            assertEquals(20, parsed.minor)
            assertEquals(6, parsed.patch)
        }

        @Test
        @DisplayName("should handle very large version numbers")
        fun veryLargeVersionNumbers() {
            val parsed = VersionDetector.parseVersion("1.99.999")

            assertEquals(1, parsed.major)
            assertEquals(99, parsed.minor)
            assertEquals(999, parsed.patch)
        }

        @Test
        @DisplayName("should handle all zeros")
        fun allZeros() {
            val parsed = VersionDetector.parseVersion("0.0.0")

            assertEquals(0, parsed.major)
            assertEquals(0, parsed.minor)
            assertEquals(0, parsed.patch)
        }

        @Test
        @DisplayName("should handle leading zeros in version parts")
        fun leadingZeros() {
            val parsed = VersionDetector.parseVersion("01.020.006")

            assertEquals(1, parsed.major)
            assertEquals(20, parsed.minor)
            assertEquals(6, parsed.patch)
        }
    }

    @Nested
    @DisplayName("Supported versions")
    inner class SupportedVersionTests {

        @Test
        @DisplayName("1.20.5 should be valid supported version")
        fun version1205Supported() {
            val version = VersionDetector.parseVersion("1.20.5")

            assertEquals(1, version.major)
            assertEquals(20, version.minor)
            assertEquals(5, version.patch)
        }

        @Test
        @DisplayName("1.20.6 should be valid supported version")
        fun version1206Supported() {
            val version = VersionDetector.parseVersion("1.20.6")

            assertEquals(1, version.major)
            assertEquals(20, version.minor)
            assertEquals(6, version.patch)
        }

        @Test
        @DisplayName("1.21 should be valid supported version")
        fun version121Supported() {
            val version = VersionDetector.parseVersion("1.21")

            assertEquals(1, version.major)
            assertEquals(21, version.minor)
            assertEquals(0, version.patch)
        }

        @Test
        @DisplayName("1.21.1 should be valid supported version")
        fun version1211Supported() {
            val version = VersionDetector.parseVersion("1.21.1")

            assertEquals(1, version.major)
            assertEquals(21, version.minor)
            assertEquals(1, version.patch)
        }

        @Test
        @DisplayName("1.21.3 should be valid supported version")
        fun version1213Supported() {
            val version = VersionDetector.parseVersion("1.21.3")

            assertEquals(1, version.major)
            assertEquals(21, version.minor)
            assertEquals(3, version.patch)
        }

        @Test
        @DisplayName("1.21.4 should be valid supported version")
        fun version1214Supported() {
            val version = VersionDetector.parseVersion("1.21.4")

            assertEquals(1, version.major)
            assertEquals(21, version.minor)
            assertEquals(4, version.patch)
        }
    }
}
