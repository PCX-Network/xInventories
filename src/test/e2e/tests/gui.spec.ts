import { expect, test } from '@plugwright/runner';
import { LOCAL_ONLY } from './support/environments.js';

test('non-op cannot open the admin GUI', LOCAL_ONLY, async ({ player }) => {
    player.chat('/xinv gui');
    await expect(player).toHaveReceivedMessage("You don't have permission to do this.");
    await expect(player.gui({ title: 'xInventories Admin', timeout: 1500 })).rejects.toThrow();
});

test('op opens the admin GUI and navigates to the group list', LOCAL_ONLY, async ({ player }) => {
    await player.makeOp();
    player.chat('/xinv gui');
    const main = await player.gui({ title: 'xInventories Admin' });

    const manageGroups = main.locator(i => i.displayName.includes('Manage Groups'));
    await expect(manageGroups).toHaveLore('View and edit inventory groups');
    await manageGroups.click();

    const groups = await player.gui({ title: 'Inventory Groups' });
    // The default group is shown as an ender chest, other groups as chests.
    const survival = groups.locator(i => i.name === 'ender_chest' && i.displayName.includes('survival'));
    await expect(survival).toHaveLore('Default group');
    await expect(survival).toHaveLore('Worlds: 2');
    const nether = groups.locator(i => i.name === 'chest' && i.displayName.includes('nether'));
    await expect(nether).toHaveLore('Worlds: 1');
    await expect(groups.locator(i => i.displayName.includes('Page 1/1'))).toHaveLore('2 total groups');
});
