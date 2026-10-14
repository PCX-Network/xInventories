import { expect, test } from '@plugwright/runner';
import { LOCAL_ONLY } from './support/environments.js';

/** RCON responses carry legacy § colour codes; tests assert on the plain text. */
const plain = (s: string) => s.replace(/§[0-9a-fk-orx]/gi, '');

// Server-side checks: the plugin enabled cleanly with the staged groups.yml and its console
// commands behave. Console commands go over RCON, so their output is the execute() response.

test('plugin enables with every service and the staged groups', LOCAL_ONLY, async ({ server }) => {
    // Startup lines were logged before this test began, so read the log from the start.
    const fromStart = { since: 0, timeout: 15000 };
    await expect(server).toHaveReceivedMessage('[xInventories] Loaded 2 inventory groups', fromStart);
    await expect(server).toHaveReceivedMessage('[xInventories] YAML storage initialized successfully', fromStart);
    await expect(server).toHaveReceivedMessage('[xInventories] All services initialized', fromStart);
    await expect(server).not.toHaveReceivedMessage(/\[xInventories\].*(Failed to|Exception)/, { since: 0, timeout: 1000 });
});

test('console world list shows the nether in its own group', LOCAL_ONLY, async ({ server }) => {
    const out = plain(await server.execute('xinv world list'));
    expect(out).toContain('World Assignments');
    expect(out).toMatch(/world_nether → nether/);
    expect(out).toMatch(/world_the_end → survival/);
    expect(out).toMatch(/world → survival/);
});

test('console group info reports the configured worlds', LOCAL_ONLY, async ({ server }) => {
    const out = plain(await server.execute('xinv group info nether'));
    expect(out).toContain('Group: nether');
    expect(out).toContain('Worlds: world_nether');
});

test('player-only subcommand from console is rejected instead of crashing', LOCAL_ONLY, async ({ server }) => {
    const out = plain(await server.execute('xinv whoami'));
    expect(out).toContain('This command can only be run by players.');
    expect(out).not.toContain('An error occurred while executing the command.');
    await expect(server).not.toHaveReceivedMessage(/Error executing command whoami|ClassCastException/, { timeout: 1500 });
});
