import * as fs from 'node:fs';
import * as path from 'node:path';
import { expect, test } from '@plugwright/runner';
import { LOCAL_ONLY } from './support/environments.js';
import { importDetect } from './support/imports.js';
import { listFiles, runDir, runFile, waitFor } from './support/run-dir.js';

// The 'local' server has no other inventory plugin and none of their data folders: the importers
// must not claim anything is there. The only player data is the server's own, which every
// vanilla world has; on 1.20.5-1.21.x it sits in <world>/playerdata, the layout the MyWorlds
// importer used to treat as MyWorlds data.

test('/xinv import detect reports nothing on a plain server', LOCAL_ONLY, async ({ player }) => {
    await player.makeOp();
    const detect = await importDetect(player);
    expect(detect.none).toBe(true);
    expect([...detect.sources.keys()]).toEqual([]);
    expect(detect.lines.some(l => l.includes('Checked for: PerWorldInventory, Multiverse-Inventories, MyWorlds'))).toBe(true);
});

test('vanilla <world>/playerdata files are not mistaken for MyWorlds data', LOCAL_ONLY, async ({ player, server, cleanup }) => {
    await player.makeOp();
    await player.giveItem('emerald', 2);
    await server.execute('save-all');
    const ownFile = `${player.bot.player.uuid}.dat`;
    const vanilla = () => listFiles(runDir('local')).filter(f => f.endsWith(ownFile) && !f.startsWith('plugins'));
    await waitFor(() => vanilla().length > 0, 'the bot\'s vanilla player data file');

    // Pre-26 layout: world/playerdata/<uuid>.dat next to the 26.x world/players/data.
    const legacyDir = runFile('local', 'world', 'playerdata');
    const created = !fs.existsSync(legacyDir);
    fs.mkdirSync(legacyDir, { recursive: true });
    fs.copyFileSync(path.join(runDir('local'), vanilla()[0]), path.join(legacyDir, ownFile));
    cleanup(() => {
        if (created) fs.rmSync(legacyDir, { recursive: true, force: true });
        else fs.rmSync(path.join(legacyDir, ownFile), { force: true });
    });

    const detect = await importDetect(player);
    expect(detect.none).toBe(true);
    expect(detect.sources.has('myworlds')).toBe(false);
});

test('preview and import of an absent source are refused', LOCAL_ONLY, async ({ player }) => {
    await player.makeOp();
    let marker = player.getMessageBufferIndex();
    player.chat('/xinv import preview pwi');
    await expect(player).toHaveReceivedMessage("Import source 'PerWorldInventory' is not available (no data found)", { since: marker, timeout: 10000 });

    marker = player.getMessageBufferIndex();
    player.chat('/xinv import myworlds --confirm');
    await expect(player).toHaveReceivedMessage("Import source 'MyWorlds' is not available (no data found)", { since: marker, timeout: 10000 });
    await expect(player).not.toHaveReceivedMessage('Import Complete!', { since: marker, timeout: 1000 });
});
