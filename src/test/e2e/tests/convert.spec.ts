import * as fs from 'node:fs';
import * as path from 'node:path';
import { DatabaseSync } from 'node:sqlite';
import { afterAll, describe, expect, sleep, test, type TestContext } from '@plugwright/runner';
import { LOCAL_ONLY } from './support/environments.js';
import { NETHER_GROUP, OVERWORLD_GROUP, spawnPos, toNether, toOverworld } from './support/worlds.js';
import { countItem, listFiles, offlineUuid, reloadXinv, runFile, waitFor, writeRunFile, xinvPlayerFile } from './support/run-dir.js';

// /xinv convert between YAML and SQLite on the 'local' server, then running on the converted
// backend: storage.type is edited in the run directory's config.yml and applied with /xinv reload.
const ENV = 'local';
const PLAYERS_DIR = runFile(ENV, 'plugins', 'xInventories', 'data', 'players');
const YAML_BACKUP_DIR = runFile(ENV, 'plugins', 'xInventories', 'data', 'players-before-sqlite');
const SQLITE_FILE = runFile(ENV, 'plugins', 'xInventories', 'data', 'inventories.db');
const CONFIG = 'plugins/xInventories/config.yml';

type Player = TestContext['player'];

function setStorageType(type: 'yaml' | 'sqlite'): void {
    const file = runFile(ENV, CONFIG);
    const text = fs.readFileSync(file, 'utf8');
    const updated = text.replace(/^(\s+type:\s*)\w+/m, `$1${type}`);
    expect(updated).toMatch(new RegExp(`^\\s+type:\\s*${type}$`, 'm'));
    writeRunFile(ENV, CONFIG, updated);
}

/** YAML storage on disk: player folders that hold data, and the number of entries (files). */
function yamlStorageCounts(): { players: number; entries: number } {
    const files = listFiles(PLAYERS_DIR).filter(f => f.endsWith('.yml'));
    return { players: new Set(files.map(f => f.split(path.sep)[0])).size, entries: files.length };
}

function sqliteRows(uuid?: string): Array<{ uuid: string; group_name: string; gamemode: string }> {
    const db = new DatabaseSync(SQLITE_FILE, { readOnly: true });
    try {
        const sql = 'SELECT uuid, group_name, gamemode FROM xinventories_player_data' + (uuid ? ' WHERE uuid = ?' : '');
        const stmt = db.prepare(sql);
        return (uuid ? stmt.all(uuid) : stmt.all()) as Array<{ uuid: string; group_name: string; gamemode: string }>;
    } finally {
        db.close();
    }
}

/** The `inventory:` section of a YAML storage file (items only, no timestamps/versions). */
function inventorySection(file: string): string {
    const text = fs.readFileSync(file, 'utf8');
    const match = text.match(/^inventory:\n(?:[ ].*\n?)*/m);
    expect(match).not.toBeNull();
    return match![0];
}

async function loadInto(admin: Player, target: Player, server: TestContext['server'], group: string): Promise<void> {
    await server.execute(`clear ${target.username}`);
    const marker = admin.getMessageBufferIndex();
    admin.chat(`/xinv load ${target.username} ${group}`);
    await expect(admin).toHaveReceivedMessage(`Inventory loaded for ${target.username}.`, { since: marker, timeout: 10000 });
    await sleep(500);
}

async function convert(admin: Player, from: string, to: string): Promise<void> {
    const marker = admin.getMessageBufferIndex();
    admin.chat(`/xinv convert ${from} ${to}`);
    await expect(admin).toHaveReceivedMessage(`Starting conversion from ${from.toUpperCase()}...`, { since: marker, timeout: 10000 });
    await expect(admin).toHaveReceivedMessage(/Conversion complete! \d+ players migrated\./, { since: marker, timeout: 30000 });
    await expect(admin).not.toHaveReceivedMessage(/errors occurred during migration|Conversion failed/, { since: marker, timeout: 500 });
}

describe.serial('storage conversion YAML -> SQLite -> YAML', LOCAL_ONLY, () => {
    let home = { x: 0.5, y: 80, z: 0.5 };
    let a = '';
    let b = '';

    afterAll(async ({ server }) => {
        // Leave the shared 'local' server on YAML for the spec files that run after this one.
        if (fs.readFileSync(runFile(ENV, CONFIG), 'utf8').match(/^\s+type:\s*sqlite$/m)) {
            setStorageType('yaml');
            await server.execute('xinv reload');
            await sleep(2000);
        }
    });

    test('two players fill overworld and nether inventories on YAML', async ({ player, server, createPlayer }) => {
        const bob = await createPlayer({ as: 'bob' });
        await sleep(1000);
        await player.makeOp();
        a = offlineUuid(player.username);
        b = offlineUuid(bob.username);
        home = spawnPos({ player });

        await player.giveItem('diamond', 3);
        await bob.giveItem('emerald', 4);
        await expect(player).toContainItem('diamond');
        await expect(bob).toContainItem('emerald');

        await toNether({ player, server });
        await toNether({ player: bob, server });
        await expect(player).toHaveReceivedMessage(`Inventory switched: ${OVERWORLD_GROUP} → ${NETHER_GROUP}`, { timeout: 10000 });
        await expect(bob).toHaveReceivedMessage(`Inventory switched: ${OVERWORLD_GROUP} → ${NETHER_GROUP}`, { timeout: 10000 });
        await player.giveItem('quartz', 2);
        await bob.giveItem('netherrack', 5);
        await expect(player).toContainItem('quartz');
        await expect(bob).toContainItem('netherrack');

        await sleep(600);
        await toOverworld({ player, server }, home);
        await toOverworld({ player: bob, server }, home);
        await expect(player).toHaveReceivedMessage(`Inventory switched: ${NETHER_GROUP} → ${OVERWORLD_GROUP}`, { timeout: 10000 });
        await expect(bob).toHaveReceivedMessage(`Inventory switched: ${NETHER_GROUP} → ${OVERWORLD_GROUP}`, { timeout: 10000 });
        await expect(player).toContainItem('diamond', { timeout: 10000 });
        await expect(bob).toContainItem('emerald', { timeout: 10000 });

        player.chat('/xinv save');
        await expect(player).toHaveReceivedMessage('Your inventory has been saved.', { timeout: 10000 });
    });

    test('/xinv convert yaml sqlite copies every player and entry', async ({ player, server }) => {
        // Straight after the saves: anything still waiting in the write-behind cache must be
        // part of the conversion too.
        const marker = player.getMessageBufferIndex();
        await convert(player, 'yaml', 'sqlite');

        const counts = yamlStorageCounts();
        expect(counts.players).toBeGreaterThanOrEqual(2);
        await expect(server).toHaveReceivedMessage(
            `[xInventories] Migration complete: ${counts.players} players, ${counts.entries} entries`, { timeout: 10000 });
        await expect(player).toHaveReceivedMessage(`Conversion complete! ${counts.players} players migrated.`, { since: marker });
        await expect(server).not.toHaveReceivedMessage(/Failed to migrate data for/, { timeout: 500 });

        expect(fs.existsSync(SQLITE_FILE)).toBe(true);
        const rows = sqliteRows();
        expect(new Set(rows.map(r => r.uuid)).size).toBe(counts.players);
        expect(rows.length).toBe(counts.entries);
        for (const uuid of [a, b]) {
            const groups = sqliteRows(uuid).filter(r => r.gamemode === 'SURVIVAL').map(r => r.group_name).sort();
            expect(groups).toEqual([NETHER_GROUP, OVERWORLD_GROUP].sort());
        }
    });

    test('storage.type sqlite + /xinv reload switches the running backend', async ({ player, server }) => {
        // Set the YAML files aside: from here on everything must come from SQLite.
        fs.renameSync(PLAYERS_DIR, YAML_BACKUP_DIR);
        setStorageType('sqlite');
        const marker = player.getMessageBufferIndex();
        await reloadXinv(player);
        await expect(player).toHaveReceivedMessage('Storage backend switched: YAML -> SQLITE', { since: marker, timeout: 10000 });
        await expect(server).toHaveReceivedMessage('[xInventories] Storage backend switched from YAML to SQLITE', { timeout: 10000 });
    });

    test('/xinv load restores both players\' items from SQLite', async ({ player, server, createPlayer }) => {
        const bob = await createPlayer({ as: 'bob' });

        await loadInto(player, player, server, NETHER_GROUP);
        expect(countItem(player, 'quartz')).toBe(2);
        expect(countItem(player, 'diamond')).toBe(0);
        await loadInto(player, bob, server, NETHER_GROUP);
        expect(countItem(bob, 'netherrack')).toBe(5);
        expect(countItem(bob, 'emerald')).toBe(0);

        await loadInto(player, player, server, OVERWORLD_GROUP);
        expect(countItem(player, 'diamond')).toBe(3);
        expect(countItem(player, 'quartz')).toBe(0);
        await loadInto(player, bob, server, OVERWORLD_GROUP);
        expect(countItem(bob, 'emerald')).toBe(4);
        expect(countItem(bob, 'netherrack')).toBe(0);
    });

    test('whoami and group switching work on SQLite', async ({ player, server }) => {
        let marker = player.getMessageBufferIndex();
        player.chat('/xinv whoami');
        await expect(player).toHaveReceivedMessage(`Group: ${OVERWORLD_GROUP}`, { since: marker, strict: true });

        await sleep(600);
        await toNether({ player, server });
        await expect(player).toHaveReceivedMessage(`Inventory switched: ${OVERWORLD_GROUP} → ${NETHER_GROUP}`, { timeout: 10000 });
        await expect(player).toContainItem('quartz', { timeout: 10000 });
        expect(countItem(player, 'diamond')).toBe(0);
        await player.giveItem('gold_ingot', 1);
        await expect(player).toContainItem('gold_ingot');

        marker = player.getMessageBufferIndex();
        player.chat('/xinv whoami');
        await expect(player).toHaveReceivedMessage(`Group: ${NETHER_GROUP}`, { since: marker, strict: true });

        await sleep(600);
        await toOverworld({ player, server }, home);
        await expect(player).toHaveReceivedMessage(`Inventory switched: ${NETHER_GROUP} → ${OVERWORLD_GROUP}`, { timeout: 10000 });
        await expect(player).toContainItem('diamond', { timeout: 10000 });
        expect(countItem(player, 'gold_ingot')).toBe(0);

        // Saved to SQLite, not to the YAML folder that was set aside.
        await waitFor(() => sqliteRows(a).length >= 2, 'SQLite rows for the op');
        await sleep(6000); // past the 5 s write-behind delay
        expect(fs.existsSync(path.join(PLAYERS_DIR, a))).toBe(false);
    });

    test('/xinv convert sqlite yaml writes the YAML files back', async ({ player }) => {
        await convert(player, 'sqlite', 'yaml');
        for (const uuid of [a, b]) {
            for (const group of [OVERWORLD_GROUP, NETHER_GROUP]) {
                expect(fs.existsSync(xinvPlayerFile(ENV, uuid, group))).toBe(true);
            }
        }
        // The second player was not touched on SQLite: identical inventories after the round trip.
        for (const group of [OVERWORLD_GROUP, NETHER_GROUP]) {
            const before = path.join(YAML_BACKUP_DIR, b, `${group}_SURVIVAL.yml`);
            expect(inventorySection(xinvPlayerFile(ENV, b, group))).toBe(inventorySection(before));
        }
        // The op's nether inventory carries what was gained while running on SQLite.
        const nether = inventorySection(xinvPlayerFile(ENV, a, NETHER_GROUP));
        expect(nether).toMatch(/id: minecraft:quartz\n\s+count: 2/);
        expect(nether).toMatch(/id: minecraft:gold_ingot/);
        expect(inventorySection(xinvPlayerFile(ENV, a, OVERWORLD_GROUP))).toMatch(/id: minecraft:diamond\n\s+count: 3/);
    });

    test('back on YAML the converted files are what players load', async ({ player, server }) => {
        setStorageType('yaml');
        const marker = player.getMessageBufferIndex();
        await reloadXinv(player);
        await expect(player).toHaveReceivedMessage('Storage backend switched: SQLITE -> YAML', { since: marker, timeout: 10000 });

        await loadInto(player, player, server, NETHER_GROUP);
        expect(countItem(player, 'quartz')).toBe(2);
        expect(countItem(player, 'gold_ingot')).toBe(1);
        await loadInto(player, player, server, OVERWORLD_GROUP);
        expect(countItem(player, 'diamond')).toBe(3);
    });
});
