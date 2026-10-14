import * as fs from 'node:fs';
import { describe, expect, sleep, test } from '@plugwright/runner';
import { spawnPos, toNether, toOverworld } from './support/worlds.js';
import { countItem, listFiles, offlineUuid, reloadXinv, runFile, useXinvGroups, waitFor, xinvPlayerFile } from './support/run-dir.js';
import { importDetect } from './support/imports.js';

// Real Multiverse-Inventories 5.3.6 (on Multiverse-Core 5.8.1), see the 'imports' environment in
// build.gradle. MVI separates the overworld ("survival" group) from the nether ("nether" group);
// xInventories starts with a single group so it never fights MVI over the inventory, then switches
// to its own survival + nether groups and imports what MVI wrote.
const ENV = 'imports';
const MVI_DIR = runFile(ENV, 'plugins', 'Multiverse-Inventories');

describe.serial('Multiverse-Inventories import', { environments: [ENV] }, () => {
    let home = { x: 0.5, y: 80, z: 0.5 };
    let uuid = '';

    test('MVI keeps separate overworld and nether inventories', async ({ player, server }) => {
        await sleep(1000);
        await player.makeOp();
        // Spec files run in no fixed order: make sure xInventories is in its single-group layout.
        useXinvGroups(ENV, 'single');
        await reloadXinv(player);
        uuid = offlineUuid(player.username);
        home = spawnPos({ player });
        await player.giveItem('diamond', 5);
        await expect(player).toContainItem('diamond');

        await toNether({ player, server });
        await expect(player).not.toContainItem('diamond', { timeout: 10000 });
        await player.giveItem('netherrack', 3);
        await expect(player).toContainItem('netherrack');

        await sleep(600);
        await toOverworld({ player, server }, home);
        await expect(player).toContainItem('diamond', { timeout: 10000 });
        await expect(player).not.toContainItem('netherrack');

        // Both group profiles are on disk, named after the player (MVI 5.x layout).
        const survivalFile = `${MVI_DIR}/groups/survival/${player.username}.json`;
        const netherFile = `${MVI_DIR}/groups/nether/${player.username}.json`;
        await waitFor(() => fs.existsSync(survivalFile) && fs.existsSync(netherFile), `MVI profiles ${survivalFile}, ${netherFile}`);
        console.log('MVI files:', listFiles(MVI_DIR).join(', '));
        console.log('survival profile:', fs.readFileSync(survivalFile, 'utf8'));
        console.log('nether profile:', fs.readFileSync(netherFile, 'utf8'));
        // Let xInventories finish handling the last world change (1-tick delay + async save) under
        // the single-group layout before the next test swaps the groups underneath it.
        await sleep(2000);
    });

    test('xInventories switches to its own survival + nether groups', async ({ player }) => {
        useXinvGroups(ENV, 'split');
        await reloadXinv(player);
        const marker = player.getMessageBufferIndex();
        player.chat('/xinv group list');
        await expect(player).toHaveReceivedMessage('nether (1 worlds)', { since: marker });
        await expect(player).toHaveReceivedMessage('survival (2 worlds)', { since: marker });
    });

    test('/xinv import detect lists Multiverse-Inventories', async ({ player }) => {
        const detect = await importDetect(player);
        const mvi = detect.sources.get('mvi');
        expect(mvi?.name).toBe('Multiverse-Inventories');
        expect(mvi?.status).toBe('API available');
        expect(mvi?.groups).toBe(2);
        // One player per name-keyed profile file (other specs' bots may have MVI profiles too).
        const names = new Set(listFiles(MVI_DIR).filter(f => /^(groups|worlds)\//.test(f)).map(f => f.replace(/^.*\//, '').replace(/\.json$/, '')));
        expect(names.has(player.username)).toBe(true);
        expect(mvi?.players).toBe(names.size);
    });

    test('preview and the unconfirmed import write nothing', async ({ player }) => {
        let marker = player.getMessageBufferIndex();
        player.chat('/xinv import preview mvi');
        await expect(player).toHaveReceivedMessage('Generating import preview for Multiverse-Inventories...', { since: marker, timeout: 10000 });
        await expect(player).toHaveReceivedMessage('To import, run: /xinv import mvi --confirm', { since: marker, timeout: 15000 });

        marker = player.getMessageBufferIndex();
        player.chat('/xinv import mvi');
        await expect(player).toHaveReceivedMessage(/^ {2}Players to import: [1-9]\d*$/, { since: marker, timeout: 15000 });
        await expect(player).toHaveReceivedMessage('Add --confirm to execute the import:', { since: marker, timeout: 15000 });

        await sleep(1000);
        expect(fs.existsSync(xinvPlayerFile(ENV, uuid, 'survival'))).toBe(false);
        expect(fs.existsSync(xinvPlayerFile(ENV, uuid, 'nether'))).toBe(false);
    });

    test('/xinv import mvi --confirm imports both groups', async ({ player }) => {
        const marker = player.getMessageBufferIndex();
        player.chat('/xinv import mvi --confirm');
        await expect(player).toHaveReceivedMessage('Import Complete!', { since: marker, timeout: 30000 });
        await expect(player).toHaveReceivedMessage(/^ {2}Players imported: [1-9]\d*$/, { since: marker });
        await expect(player).toHaveReceivedMessage('Players failed: 0', { since: marker });
        await waitFor(() => fs.existsSync(xinvPlayerFile(ENV, uuid, 'survival')) && fs.existsSync(xinvPlayerFile(ENV, uuid, 'nether')),
            'xInventories data files for the imported groups');
    });

    test('/xinv load restores the imported overworld inventory', async ({ player, server }) => {
        await server.execute(`clear ${player.username}`);
        await expect(player).not.toContainItem('diamond');
        player.chat(`/xinv load ${player.username} survival`);
        await expect(player).toHaveReceivedMessage(`Inventory loaded for ${player.username}.`, { timeout: 10000 });
        await expect(player).toContainItem('diamond');
        expect(countItem(player, 'diamond')).toBe(5);
        expect(countItem(player, 'netherrack')).toBe(0);
    });

    test('/xinv load restores the imported nether inventory', async ({ player, server }) => {
        await server.execute(`clear ${player.username}`);
        await expect(player).not.toContainItem('diamond');
        player.chat(`/xinv load ${player.username} nether`);
        await expect(player).toHaveReceivedMessage(`Inventory loaded for ${player.username}.`, { timeout: 10000 });
        await expect(player).toContainItem('netherrack');
        expect(countItem(player, 'netherrack')).toBe(3);
        expect(countItem(player, 'diamond')).toBe(0);
    });
});
