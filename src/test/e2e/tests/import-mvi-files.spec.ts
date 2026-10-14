import * as fs from 'node:fs';
import { describe, expect, test } from '@plugwright/runner';
import { countItem, offlineUuid, reloadXinv, useXinvGroups, waitFor, xinvPlayerFile } from './support/run-dir.js';
import { importDetect } from './support/imports.js';

// A Multiverse-Inventories data folder on a server WITHOUT the plugin (the 'myworlds'
// environment): what is left after removing MVI. The files (server-files/myworlds) were written
// by MVI 5.3.6 for the import-mvi spec, renamed to the player "MviFiles"; the survival profile
// also has armor, an ender chest and one Base64 byte-serialized item, as MVI writes them with
// use-byte-serialization-for-inventory-data. Profiles are keyed by player NAME, playernames.json
// maps the offline UUID back to it.
const ENV = 'myworlds';
const NAME = 'MviFiles';

describe.serial('Multiverse-Inventories import from files (plugin removed)', { environments: [ENV] }, () => {
    test('/xinv import detect finds the MVI data folder', async ({ player }) => {
        await player.makeOp();
        useXinvGroups(ENV, 'split');
        await reloadXinv(player);

        const mvi = (await importDetect(player)).sources.get('mvi');
        expect(mvi?.name).toBe('Multiverse-Inventories');
        expect(mvi?.status).toBe('File-based only');
        expect(mvi?.players).toBe(1);
        expect(mvi?.groups).toBe(2);
    });

    test('/xinv import mvi --confirm imports the name-keyed profiles', async ({ player }) => {
        const marker = player.getMessageBufferIndex();
        player.chat('/xinv import mvi --confirm');
        await expect(player).toHaveReceivedMessage('Import Complete!', { since: marker, timeout: 30000 });
        await expect(player).toHaveReceivedMessage('Players imported: 1', { since: marker });
        await expect(player).toHaveReceivedMessage('Players failed: 0', { since: marker });
        const uuid = offlineUuid(NAME);
        await waitFor(() => fs.existsSync(xinvPlayerFile(ENV, uuid, 'survival')) && fs.existsSync(xinvPlayerFile(ENV, uuid, 'nether')),
            'xInventories data files for MviFiles');
        const stored = fs.readFileSync(xinvPlayerFile(ENV, uuid, 'survival'), 'utf8');
        expect(stored).toMatch(/minecraft:obsidian/);
        expect(stored).toMatch(/^level: 5$/m);
    });

    test('the player gets the imported inventories back', async ({ player, server, createPlayer }) => {
        const bot = await createPlayer({ username: NAME });
        await server.execute(`clear ${NAME}`);
        player.chat(`/xinv load ${NAME} survival`);
        await expect(player).toHaveReceivedMessage(`Inventory loaded for ${NAME}.`, { timeout: 10000 });
        await expect(bot).toContainItem('diamond', { timeout: 10000 });
        expect(countItem(bot, 'diamond')).toBe(5);
        expect(countItem(bot, 'apple')).toBe(3);
        expect(bot.bot.inventory.slots[5]?.name).toBe('iron_helmet');

        await server.execute(`clear ${NAME}`);
        player.chat(`/xinv load ${NAME} nether`);
        await expect(bot).toContainItem('netherrack', { timeout: 10000 });
        expect(countItem(bot, 'netherrack')).toBe(3);
        expect(countItem(bot, 'diamond')).toBe(0);
    });
});
