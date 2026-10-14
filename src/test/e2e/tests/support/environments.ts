/**
 * Every LocalMode environment in build.gradle runs every spec file. These options pin a test (or a
 * describe.serial block) to the server it was written for:
 * - local:    plain server, xInventories with server-files/groups.yml (survival + nether)
 * - imports:  + Multiverse-Core/Multiverse-Inventories and a PerWorldInventory data folder
 * - myworlds: + BKCommonLib/MyWorlds and a Multiverse-Inventories data folder (no plugin)
 */
export const LOCAL_ONLY = { environments: ['local'] };
export const IMPORTS_ONLY = { environments: ['imports'] };
export const MYWORLDS_ONLY = { environments: ['myworlds'] };
