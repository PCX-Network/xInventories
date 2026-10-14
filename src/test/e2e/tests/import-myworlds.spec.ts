import * as fs from 'node:fs';
import { describe, expect, sleep, test } from '@plugwright/runner';
import { spawnPos, toNether, toOverworld } from './support/worlds.js';
import {
    countItem, listFiles, offlineUuid, reloadXinv, runDir, useXinvGroups, waitFor, writeRunFile, xinvPlayerFile,
} from './support/run-dir.js';
import { importDetect } from './support/imports.js';

// Real MyWorlds 2.0.1 on BKCommonLib (the 'myworlds' environment in build.gradle) with
// useWorldInventories: true, so every world keeps its own inventory in a vanilla-format
// <uuid>.dat under that world's player data folder. xInventories starts with a single group, then
// switches to survival + nether and imports with a mapping world -> survival, world_nether -> nether.
const ENV = 'myworlds';

/** Players with a MyWorlds inventory file: main world (world/players/data) or another world's data/my_worlds/playerdata. */
function myWorldsPlayers(): Set<string> {
    return new Set(listFiles(runDir(ENV))
        .filter(f => /^world\/players\/data\/[0-9a-f-]{36}\.dat$/.test(f) || /\/data\/my_worlds\/playerdata\/[0-9a-f-]{36}\.dat$/.test(f))
        .map(f => f.replace(/^.*\//, '').replace(/\.dat$/, '')));
}

describe.serial('MyWorlds import', { environments: [ENV] }, () => {
    let home = { x: 0.5, y: 80, z: 0.5 };
    let uuid = '';

    test('MyWorlds keeps separate overworld and nether inventories', async ({ player, server }) => {
        useXinvGroups(ENV, 'single');
        await sleep(1000);
        await player.makeOp();
        await reloadXinv(player);
        uuid = offlineUuid(player.username);
        home = spawnPos({ player });
        await player.giveItem('gold_ingot', 4);
        await expect(player).toContainItem('gold_ingot');

        await toNether({ player, server });
        await expect(player).not.toContainItem('gold_ingot', { timeout: 10000 });
        await player.giveItem('blaze_rod', 2);
        await expect(player).toContainItem('blaze_rod');

        await sleep(600);
        await toOverworld({ player, server }, home);
        await expect(player).toContainItem('gold_ingot', { timeout: 10000 });
        await expect(player).not.toContainItem('blaze_rod');

        // One <uuid>.dat per world inventory (main world: world/players/data, nether: its
        // dimension's data/my_worlds/playerdata on the Paper 26.x layout).
        const datFiles = () => listFiles(runDir(ENV)).filter(f => f.endsWith(`${uuid}.dat`) && !f.includes('plugins'));
        await waitFor(() => datFiles().some(f => f.includes('my_worlds')), 'MyWorlds nether player data');
        console.log('player data files:', datFiles().join(', '));
        await sleep(2000);
    });

    test('/xinv import detect lists MyWorlds with its world inventories', async ({ player }) => {
        useXinvGroups(ENV, 'split');
        await reloadXinv(player);

        const myWorlds = (await importDetect(player)).sources.get('myworlds');
        expect(myWorlds?.name).toBe('MyWorlds');
        expect(myWorlds?.status).toBe('API available');
        // world, world_nether and world_the_end each keep their own inventory.
        expect(myWorlds?.groups).toBe(3);
        // Every player with a MyWorlds inventory file (other specs' bots have main-world files too).
        const players = myWorldsPlayers();
        expect(players.has(uuid)).toBe(true);
        expect(myWorlds?.players).toBe(players.size);
    });

    test('/xinv import mapping myworlds shows the world inventories and writes a mapping file', async ({ player }) => {
        const marker = player.getMessageBufferIndex();
        player.chat('/xinv import mapping myworlds');
        await expect(player).toHaveReceivedMessage('Group Mappings for MyWorlds:', { since: marker, timeout: 10000 });
        await expect(player).toHaveReceivedMessage(/^ {2}world \[world\] -> world$/, { since: marker });
        await expect(player).toHaveReceivedMessage(/^ {2}world_nether \[world_nether\] -> world_nether$/, { since: marker });
        await expect(player).toHaveReceivedMessage('Default mapping file created.', { since: marker });

        // The operator then points the MyWorlds inventories at xInventories' groups.
        writeRunFile(ENV, 'plugins/xInventories/import-mapping.yml', [
            'source: myworlds',
            'mappings:',
            '  world: survival',
            '  world_nether: nether',
            'options:',
            '  overwriteExisting: false',
            '',
        ].join('\n'));
    });

    test('preview follows the mapping and writes nothing', async ({ player }) => {
        const marker = player.getMessageBufferIndex();
        player.chat('/xinv import preview myworlds');
        await expect(player).toHaveReceivedMessage(`Total players in source: ${myWorldsPlayers().size}`, { since: marker, timeout: 15000 });
        await expect(player).toHaveReceivedMessage('world -> survival', { since: marker });
        await expect(player).toHaveReceivedMessage('world_nether -> nether', { since: marker });
        await sleep(500);
        expect(fs.existsSync(xinvPlayerFile(ENV, uuid, 'survival'))).toBe(false);
        expect(fs.existsSync(xinvPlayerFile(ENV, uuid, 'nether'))).toBe(false);
    });

    test('/xinv import myworlds --confirm imports both world inventories', async ({ player }) => {
        const marker = player.getMessageBufferIndex();
        player.chat('/xinv import myworlds --confirm');
        await expect(player).toHaveReceivedMessage('Import Complete!', { since: marker, timeout: 30000 });
        await expect(player).toHaveReceivedMessage(/^ {2}Players imported: [1-9]\d*$/, { since: marker });
        await expect(player).toHaveReceivedMessage('Players failed: 0', { since: marker });
        await waitFor(() => fs.existsSync(xinvPlayerFile(ENV, uuid, 'survival')) && fs.existsSync(xinvPlayerFile(ENV, uuid, 'nether')),
            'xInventories data files for the mapped groups');
    });

    test('/xinv load restores the imported overworld inventory', async ({ player, server }) => {
        await server.execute(`clear ${player.username}`);
        await expect(player).not.toContainItem('gold_ingot');
        player.chat(`/xinv load ${player.username} survival`);
        await expect(player).toHaveReceivedMessage(`Inventory loaded for ${player.username}.`, { timeout: 10000 });
        await expect(player).toContainItem('gold_ingot');
        expect(countItem(player, 'gold_ingot')).toBe(4);
        expect(countItem(player, 'blaze_rod')).toBe(0);
    });

    test('/xinv load restores the imported nether inventory', async ({ player, server }) => {
        await server.execute(`clear ${player.username}`);
        player.chat(`/xinv load ${player.username} nether`);
        await expect(player).toHaveReceivedMessage(`Inventory loaded for ${player.username}.`, { timeout: 10000 });
        await expect(player).toContainItem('blaze_rod');
        expect(countItem(player, 'blaze_rod')).toBe(2);
        expect(countItem(player, 'gold_ingot')).toBe(0);
    });
});
