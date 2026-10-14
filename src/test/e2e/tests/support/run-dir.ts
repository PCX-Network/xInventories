import { createHash } from 'node:crypto';
import * as fs from 'node:fs';
import * as path from 'node:path';
import { fileURLToPath } from 'node:url';
import { expect, type TestContext } from '@plugwright/runner';

/**
 * Helpers for specs that look at (or prepare) files in a LocalMode server's run directory,
 * src/test/e2e/generated/<environment>/run. Compiled to dist/tests/support, three levels below
 * src/test/e2e.
 */
const E2E_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..', '..');

export function runDir(environment: string): string {
    return path.join(E2E_ROOT, 'generated', environment, 'run');
}

export function runFile(environment: string, ...parts: string[]): string {
    return path.join(runDir(environment), ...parts);
}

/** The UUID an offline-mode server gives a name: UUID.nameUUIDFromBytes("OfflinePlayer:" + name). */
export function offlineUuid(name: string): string {
    const md5 = createHash('md5').update(`OfflinePlayer:${name}`, 'utf8').digest();
    md5[6] = (md5[6] & 0x0f) | 0x30; // version 3
    md5[8] = (md5[8] & 0x3f) | 0x80; // IETF variant
    const hex = md5.toString('hex');
    return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

export function writeRunFile(environment: string, relative: string, content: string | Buffer): void {
    const target = runFile(environment, relative);
    fs.mkdirSync(path.dirname(target), { recursive: true });
    fs.writeFileSync(target, content);
}

/** Lists files below dir (recursively), relative to it; [] when it does not exist. */
export function listFiles(dir: string): string[] {
    if (!fs.existsSync(dir)) return [];
    return fs.readdirSync(dir, { recursive: true, withFileTypes: true })
        .filter(e => e.isFile())
        .map(e => path.relative(dir, path.join(e.parentPath, e.name)));
}

/** Polls until predicate() is true; fails with message after timeoutMs. */
export async function waitFor(predicate: () => boolean, message: string, timeoutMs = 10000): Promise<void> {
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
        if (predicate()) return;
        await new Promise(r => setTimeout(r, 200));
    }
    throw new Error(`Timed out after ${timeoutMs}ms: ${message}`);
}

/** Number of items of a material in the bot's inventory (all slots mineflayer reports). */
export function countItem(player: TestContext['player'], material: string): number {
    return player.bot.inventory.items()
        .filter((i: { name: string }) => i.name === material)
        .reduce((n: number, i: { count: number }) => n + i.count, 0);
}

/** Runs /xinv reload as the given op bot and waits for the confirmation. */
export async function reloadXinv(player: TestContext['player']): Promise<void> {
    const marker = player.getMessageBufferIndex();
    player.chat('/xinv reload');
    await expect(player).toHaveReceivedMessage('Configuration reloaded successfully!', { since: marker, timeout: 15000 });
}

/** src/test/e2e/server-files, where the staged configs live. */
export function serverFile(...parts: string[]): string {
    return path.join(E2E_ROOT, 'server-files', ...parts);
}

/**
 * Replaces the run directory's plugins/xInventories/groups.yml: 'single' puts every world in one
 * "staging" group (xInventories never swaps), 'split' is the usual survival + nether layout.
 * Takes effect on the next /xinv reload.
 */
export function useXinvGroups(environment: string, layout: 'single' | 'split'): void {
    const source = layout === 'single' ? serverFile('imports', 'xinv-groups-single.yml') : serverFile('groups.yml');
    writeRunFile(environment, 'plugins/xInventories/groups.yml', fs.readFileSync(source));
}

/** plugins/xInventories/data/players/<uuid>/<group>_<GAMEMODE>.yml (YAML storage). */
export function xinvPlayerFile(environment: string, uuid: string, group: string, gameMode = 'SURVIVAL'): string {
    return runFile(environment, 'plugins', 'xInventories', 'data', 'players', uuid, `${group}_${gameMode}.yml`);
}
