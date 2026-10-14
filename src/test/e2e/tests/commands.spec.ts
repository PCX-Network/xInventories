import { expect, test } from '@plugwright/runner';

test('/xinv with no arguments shows the paginated help', async ({ player }) => {
    player.chat('/xinv');
    await expect(player).toHaveReceivedMessage('xInventories Commands');
    // whoami needs only xinventories.command (default true), so a regular player sees it...
    await expect(player).toHaveReceivedMessage('whoami - Show your current inventory group and why');
    await expect(player).toHaveReceivedMessage(/Page 1\/\d+/);
    // ...but admin-only entries are filtered out of their help.
    await expect(player).not.toHaveReceivedMessage('reload - Reload configuration files', { timeout: 1000 });
});

test('an op sees admin subcommands in /xinv help', async ({ player }) => {
    await player.makeOp();
    player.chat('/xinv help');
    await expect(player).toHaveReceivedMessage('xInventories Commands');
    await expect(player).toHaveReceivedMessage('reload - Reload configuration files');
});

test('unknown subcommand points the player back to the help', async ({ player }) => {
    player.chat('/xinv definitelynotacommand');
    await expect(player).toHaveReceivedMessage('Unknown command: definitelynotacommand. Use /xinv for help.');
});

test('non-op is denied /xinv reload', async ({ player, server }) => {
    player.chat('/xinv reload');
    await expect(player).toHaveReceivedMessage("You don't have permission to do this.");
    await expect(player).not.toHaveReceivedMessage('Configuration reloaded successfully!', { timeout: 1500 });
});

test('op can /xinv reload and groups survive the reload', async ({ player }) => {
    await player.makeOp();
    player.chat('/xinv reload');
    await expect(player).toHaveReceivedMessage('Configuration reloaded successfully!', { timeout: 10000 });

    const marker = player.getMessageBufferIndex();
    player.chat('/xinv group list');
    await expect(player).toHaveReceivedMessage('Inventory Groups', { since: marker });
    await expect(player).toHaveReceivedMessage('nether (1 worlds)', { since: marker });
    await expect(player).toHaveReceivedMessage('survival (2 worlds)', { since: marker });
});

test('/xinv whoami reports the overworld group', async ({ player }) => {
    player.chat('/xinv whoami');
    await expect(player).toHaveReceivedMessage('Inventory Status');
    await expect(player).toHaveReceivedMessage('World: world', { strict: true });
    await expect(player).toHaveReceivedMessage('Group: survival', { strict: true });
});
