import * as fs from 'node:fs';
import { describe, expect, test } from '@plugwright/runner';
import { countItem, offlineUuid, reloadXinv, useXinvGroups, waitFor, xinvPlayerFile } from './support/run-dir.js';
import { importDetect } from './support/imports.js';

// PerWorldInventory cannot load on 26.x, so the 'imports' environment carries its data folder only
// (server-files/imports/PerWorldInventory, staged by build.gradle), written by hand in the format
// PerWorldInventory-kt 2.3.2 writes: worlds.yml groups + data/<uuid>/<group>.json profiles.
//   PwiAlice: data-format 4 (PWI-kt 2.2+), survival + nether profiles.
//   PwiBob:   data-format 3 (PWI 1.x / early 2.x layout), survival only.
// Offline-mode bots get UUID.nameUUIDFromBytes("OfflinePlayer:<name>"), which is what the
// fixture folders are named after.
const ENV = 'imports';
const ALICE = 'PwiAlice';
const BOB = 'PwiBob';

describe.serial('PerWorldInventory import', { environments: [ENV] }, () => {
    test('fixture UUIDs are the bots\' offline UUIDs', async () => {
        expect(offlineUuid(ALICE)).toBe('d7c46df3-9ca9-3e33-93ff-80ef250749e5');
        expect(offlineUuid(BOB)).toBe('a7a45b5b-e6eb-3ef8-9758-3f6664771cea');
    });

    test('/xinv import detect lists PerWorldInventory', async ({ player }) => {
        await player.makeOp();
        useXinvGroups(ENV, 'split');
        await reloadXinv(player);

        const pwi = (await importDetect(player)).sources.get('pwi');
        expect(pwi?.name).toBe('PerWorldInventory');
        expect(pwi?.status).toBe('File-based only');
        expect(pwi?.players).toBe(2);
        expect(pwi?.groups).toBe(2);
    });

    test('preview reports both players and writes nothing', async ({ player }) => {
        const marker = player.getMessageBufferIndex();
        player.chat('/xinv import preview pwi');
        await expect(player).toHaveReceivedMessage('Total players in source: 2', { since: marker, timeout: 15000 });
        await expect(player).toHaveReceivedMessage('survival -> survival', { since: marker });
        await expect(player).toHaveReceivedMessage('nether -> nether', { since: marker });
        await expect(player).toHaveReceivedMessage('To import, run: /xinv import pwi --confirm', { since: marker });
        expect(fs.existsSync(xinvPlayerFile(ENV, offlineUuid(ALICE), 'survival'))).toBe(false);
        expect(fs.existsSync(xinvPlayerFile(ENV, offlineUuid(BOB), 'survival'))).toBe(false);
    });

    test('/xinv import pwi --confirm imports both players', async ({ player }) => {
        const marker = player.getMessageBufferIndex();
        player.chat('/xinv import pwi --confirm');
        await expect(player).toHaveReceivedMessage('Import Complete!', { since: marker, timeout: 30000 });
        await expect(player).toHaveReceivedMessage('Players imported: 2', { since: marker });
        await expect(player).toHaveReceivedMessage('Players failed: 0', { since: marker });
        await waitFor(() => fs.existsSync(xinvPlayerFile(ENV, offlineUuid(ALICE), 'nether')), 'imported nether data for PwiAlice');
    });

    test('PwiAlice joins with her PWI overworld inventory, armor, off-hand and ender chest', async ({ createPlayer }) => {
        const alice = await createPlayer({ username: ALICE, as: 'alice' });
        await expect(alice).toContainItem('emerald', { timeout: 10000 });
        expect(countItem(alice, 'emerald')).toBe(12);
        expect(countItem(alice, 'iron_pickaxe')).toBe(1);
        expect(countItem(alice, 'quartz')).toBe(0);
        // mineflayer window slots: 5 = helmet, 45 = off-hand.
        expect(alice.bot.inventory.slots[5]?.name).toBe('iron_helmet');
        expect(alice.bot.inventory.slots[45]?.name).toBe('torch');
        expect(alice.bot.inventory.slots[45]?.count).toBe(16);

        // The stored copy kept the pickaxe's enchantment and damage and the ender chest.
        const stored = fs.readFileSync(xinvPlayerFile(ENV, offlineUuid(ALICE), 'survival'), 'utf8');
        expect(stored).toMatch(/minecraft:efficiency/);
        expect(stored).toMatch(/minecraft:damage/);
        expect(stored).toMatch(/type: minecraft:speed/);
        expect(stored).toMatch(/^level: 7$/m);
        expect(stored).toMatch(/obsidian/i);
    });

    test('/xinv load gives PwiAlice her PWI nether inventory', async ({ player, createPlayer }) => {
        const alice = await createPlayer({ username: ALICE, as: 'alice' });
        player.chat(`/xinv load ${ALICE} nether`);
        await expect(player).toHaveReceivedMessage(`Inventory loaded for ${ALICE}.`, { timeout: 10000 });
        await expect(alice).toContainItem('quartz', { timeout: 10000 });
        expect(countItem(alice, 'quartz')).toBe(20);
        expect(countItem(alice, 'glowstone_dust')).toBe(9);
        expect(countItem(alice, 'emerald')).toBe(0);
    });

    test('PwiBob (legacy data-format 3) joins with his PWI inventory', async ({ createPlayer }) => {
        const bob = await createPlayer({ username: BOB });
        await expect(bob).toContainItem('bread', { timeout: 10000 });
        expect(countItem(bob, 'bread')).toBe(6);
    });
});
