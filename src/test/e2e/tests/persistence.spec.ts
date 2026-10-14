import { describe, expect, sleep, test } from '@plugwright/runner';
import { NETHER_GROUP, OVERWORLD_GROUP, spawnPos, toNether, toOverworld } from './support/worlds.js';

test('/xinv save then /xinv load restores a cleared inventory', async ({ player, server }) => {
    await sleep(1000);
    await player.makeOp();
    await player.giveItem('gold_ingot', 7);
    await expect(player).toContainItem('gold_ingot');

    player.chat('/xinv save');
    await expect(player).toHaveReceivedMessage('Your inventory has been saved.', { timeout: 10000 });

    // Wipe it behind the plugin's back (vanilla /clear), then ask the plugin to load the saved copy.
    await server.execute(`clear ${player.username}`);
    await expect(player).not.toContainItem('gold_ingot');

    player.chat(`/xinv load ${player.username}`);
    await expect(player).toHaveReceivedMessage(`Inventory loaded for ${player.username}.`, { timeout: 10000 });
    await expect(player).toContainItem('gold_ingot');
});

test('non-op cannot force a save', async ({ player }) => {
    player.chat('/xinv save');
    await expect(player).toHaveReceivedMessage("You don't have permission to do this.");
    await expect(player).not.toHaveReceivedMessage('Your inventory has been saved.', { timeout: 1500 });
});

describe.serial('group inventories survive a reconnect', () => {
    let home = { x: 0.5, y: 80, z: 0.5 };

    test('fill overworld and nether inventories', async ({ player, server }) => {
        await sleep(1000);
        home = spawnPos({ player });
        await player.giveItem('iron_ingot', 4);
        await expect(player).toContainItem('iron_ingot');

        await toNether({ player, server });
        await expect(player).toHaveReceivedMessage(`Inventory switched: ${OVERWORLD_GROUP} → ${NETHER_GROUP}`, { timeout: 10000 });
        await player.giveItem('quartz', 6);
        await expect(player).toContainItem('quartz');
    });

    test('after rejoining in the nether, both inventories are still separate', async ({ player, server }) => {
        await player.rejoin();
        await sleep(1000);
        await expect(player).toContainItem('quartz');
        await expect(player).not.toContainItem('iron_ingot');

        await toOverworld({ player, server }, home);
        await expect(player).toHaveReceivedMessage(`Inventory switched: ${NETHER_GROUP} → ${OVERWORLD_GROUP}`, { timeout: 10000 });
        await expect(player).toContainItem('iron_ingot');
        await expect(player).not.toContainItem('quartz');
    });
});
