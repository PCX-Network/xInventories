import { describe, expect, sleep, test } from '@plugwright/runner';
import { LOCAL_ONLY } from './support/environments.js';
import { NETHER_GROUP, OVERWORLD_GROUP, spawnPos, toNether, toOverworld } from './support/worlds.js';

// One bot walks through the whole chain: overworld items, nether items, back and forth.
describe.serial('per-world inventory separation', LOCAL_ONLY, () => {
    let home = { x: 0.5, y: 80, z: 0.5 };

    test('items given in the overworld are in the inventory', async ({ player }) => {
        // Let the join-time inventory load finish before handing out items.
        await sleep(1000);
        home = spawnPos({ player });
        await player.giveItem('diamond', 5);
        await expect(player).toContainItem('diamond');
    });

    test('entering the nether swaps to the empty nether inventory', async ({ player, server }) => {
        await toNether({ player, server });
        await expect(player).toHaveReceivedMessage(`Inventory switched: ${OVERWORLD_GROUP} → ${NETHER_GROUP}`, { timeout: 10000 });
        await expect(player).not.toContainItem('diamond');
    });

    test('items gained in the nether stay in the nether group', async ({ player }) => {
        await player.giveItem('netherrack', 3);
        await expect(player).toContainItem('netherrack');
    });

    test('returning to the overworld restores the overworld inventory', async ({ player, server }) => {
        await sleep(600); // anti-dupe treats sub-500ms group switches as suspicious
        await toOverworld({ player, server }, home);
        await expect(player).toHaveReceivedMessage(`Inventory switched: ${NETHER_GROUP} → ${OVERWORLD_GROUP}`, { timeout: 10000 });
        await expect(player).toContainItem('diamond');
        await expect(player).not.toContainItem('netherrack');
        const diamonds = player.bot.inventory.items().filter(i => i.name === 'diamond').reduce((n, i) => n + i.count, 0);
        expect(diamonds).toBe(5);
    });

    test('going back to the nether restores the nether inventory', async ({ player, server }) => {
        await sleep(600);
        await toNether({ player, server });
        await expect(player).toContainItem('netherrack');
        await expect(player).not.toContainItem('diamond');
    });

    test('whoami reflects the nether group', async ({ player }) => {
        player.chat('/xinv whoami');
        await expect(player).toHaveReceivedMessage('World: world_nether', { strict: true });
        await expect(player).toHaveReceivedMessage(`Group: ${NETHER_GROUP}`, { strict: true });
    });
});

test('worlds in the same group share one inventory', LOCAL_ONLY, async ({ player, server }) => {
    await sleep(1000);
    const home = spawnPos({ player });
    await player.giveItem('emerald', 2);
    await expect(player).toContainItem('emerald');

    // world_the_end is in the survival group with the overworld; land on the exit-portal pillar.
    await server.execute(`execute in minecraft:the_end run tp ${player.username} 0.5 75 0.5`);
    await expect(server).toHaveReceivedMessage(new RegExp(`Teleported ${player.username}`));
    await sleep(1500);
    await expect(player).toContainItem('emerald');
    await expect(player).not.toHaveReceivedMessage('Inventory switched', { timeout: 1000 });
    player.chat('/xinv whoami');
    await expect(player).toHaveReceivedMessage('World: world_the_end', { strict: true });
    await expect(player).toHaveReceivedMessage(`Group: ${OVERWORLD_GROUP}`, { strict: true });

    await toOverworld({ player, server }, home);
    await sleep(1000);
    await expect(player).toContainItem('emerald');
});
