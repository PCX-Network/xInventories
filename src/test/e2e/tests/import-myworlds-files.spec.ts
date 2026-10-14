import * as fs from 'node:fs';
import * as path from 'node:path';
import { describe, expect, sleep, test } from '@plugwright/runner';
import { importDetect } from './support/imports.js';
import {
    countItem, listFiles, offlineUuid, reloadXinv, runDir, runFile, useXinvGroups, waitFor, writeRunFile, xinvPlayerFile,
} from './support/run-dir.js';

// MyWorlds REMOVED, its data left behind (the 'imports' server has no MyWorlds): only
// plugins/My_Worlds/config.yml and the per-world player files. MyWorlds writes plain vanilla
// player data, so the server's own world/players/data/<uuid>.dat IS the main world's MyWorlds
// inventory, and a copy of it in the nether's data/my_worlds/playerdata is a nether inventory in
// exactly the format MyWorlds 2.x writes on the Paper 26.x world layout.
const ENV = 'imports';
const NETHER_MW_DIR = runFile(ENV, 'world', 'dimensions', 'minecraft', 'the_nether', 'data', 'my_worlds', 'playerdata');

function vanillaFile(uuid: string): string {
    return runFile(ENV, 'world', 'players', 'data', `${uuid}.dat`);
}

async function saveAndWait(server: { execute(cmd: string): Promise<string> }, file: string): Promise<void> {
    const before = fs.existsSync(file) ? fs.statSync(file).mtimeMs : 0;
    await sleep(50);
    await server.execute('save-all');
    await waitFor(() => fs.existsSync(file) && fs.statSync(file).mtimeMs > before, `${file} rewritten by save-all`);
    await sleep(200);
}

describe.serial('MyWorlds import from files (plugin removed)', { environments: [ENV] }, () => {
    let uuid = '';

    test('the bot\'s vanilla files become MyWorlds world and nether inventories', async ({ player, server }) => {
        await player.makeOp();
        useXinvGroups(ENV, 'split');
        await reloadXinv(player);
        uuid = offlineUuid(player.username);

        await player.giveItem('cod', 3);
        await expect(player).toContainItem('cod');
        await saveAndWait(server, vanillaFile(uuid));
        fs.mkdirSync(NETHER_MW_DIR, { recursive: true });
        fs.copyFileSync(vanillaFile(uuid), path.join(NETHER_MW_DIR, `${uuid}.dat`));

        await server.execute(`clear ${player.username}`);
        await player.giveItem('iron_ingot', 6);
        await expect(player).toContainItem('iron_ingot');
        await saveAndWait(server, vanillaFile(uuid));

        writeRunFile(ENV, 'plugins/My_Worlds/config.yml', 'useWorldInventories: true\n');
        writeRunFile(ENV, 'plugins/xInventories/import-mapping.yml', [
            'source: myworlds',
            'mappings:',
            '  world: survival',
            '  world_nether: nether',
            'options:',
            '  overwriteExisting: true',
            '',
        ].join('\n'));
    });

    test('/xinv import detect finds the MyWorlds data folder', async ({ player }) => {
        const myWorlds = (await importDetect(player)).sources.get('myworlds');
        expect(myWorlds?.status).toBe('File-based only');
        expect(myWorlds?.groups).toBe(3);
        const players = new Set([
            ...listFiles(runFile(ENV, 'world', 'players', 'data')),
            ...listFiles(NETHER_MW_DIR),
        ].filter(f => /^[0-9a-f-]{36}\.dat$/.test(f)));
        expect(players.has(`${uuid}.dat`)).toBe(true);
        expect(myWorlds?.players).toBe(players.size);
    });

    test('/xinv import myworlds --confirm imports both inventories', async ({ player }) => {
        const marker = player.getMessageBufferIndex();
        player.chat('/xinv import myworlds --confirm');
        await expect(player).toHaveReceivedMessage('Import Complete!', { since: marker, timeout: 30000 });
        await expect(player).toHaveReceivedMessage(/^ {2}Players imported: [1-9]\d*$/, { since: marker });
        await expect(player).toHaveReceivedMessage('Players failed: 0', { since: marker });
        await waitFor(() => fs.existsSync(xinvPlayerFile(ENV, uuid, 'nether')), 'imported nether data');
    });

    test('/xinv load gives back the nether and overworld inventories', async ({ player, server }) => {
        await server.execute(`clear ${player.username}`);
        player.chat(`/xinv load ${player.username} nether`);
        await expect(player).toContainItem('cod', { timeout: 10000 });
        expect(countItem(player, 'cod')).toBe(3);
        expect(countItem(player, 'iron_ingot')).toBe(0);

        await server.execute(`clear ${player.username}`);
        player.chat(`/xinv load ${player.username} survival`);
        await expect(player).toContainItem('iron_ingot', { timeout: 10000 });
        expect(countItem(player, 'iron_ingot')).toBe(6);
        expect(countItem(player, 'cod')).toBe(0);
    });
});
