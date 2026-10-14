import type { TestContext } from '@plugwright/runner';

export interface DetectedSource {
    name: string;
    id: string;
    status: string;
    players: number;
    groups: number;
}

export interface DetectResult {
    /** "No importable data sources detected." was printed. */
    none: boolean;
    sources: Map<string, DetectedSource>;
    lines: string[];
}

const LAST_LINES = [
    "Use '/xinv import preview <plugin>' to preview an import",
    'Checked for: PerWorldInventory, Multiverse-Inventories, MyWorlds',
];

/**
 * Runs /xinv import detect as [player] and parses the report per source, so a count is checked
 * against the right source (several sources print "Players: N" lines).
 */
export async function importDetect(player: TestContext['player'], timeoutMs = 10000): Promise<DetectResult> {
    const lines: string[] = [];
    const bot = player.bot;
    await new Promise<void>((resolve, reject) => {
        const timer = setTimeout(() => {
            bot.removeListener('message', onMessage);
            reject(new Error(`/xinv import detect did not finish within ${timeoutMs}ms; got: ${JSON.stringify(lines)}`));
        }, timeoutMs);
        const onMessage = (msg: unknown) => {
            const line = String(msg);
            lines.push(line);
            if (LAST_LINES.some(l => line.includes(l))) {
                clearTimeout(timer);
                bot.removeListener('message', onMessage);
                resolve();
            }
        };
        bot.on('message', onMessage);
        player.chat('/xinv import detect');
    });

    const sources = new Map<string, DetectedSource>();
    let current: DetectedSource | null = null;
    for (const line of lines) {
        const header = line.match(/^ {2}(\S.*) \((\w+)\)$/);
        if (header) {
            current = { name: header[1], id: header[2], status: '', players: -1, groups: -1 };
            sources.set(current.id, current);
            continue;
        }
        if (!current) continue;
        const status = line.match(/^ {4}Status: (.*)$/);
        const players = line.match(/^ {4}Players: (\d+)$/);
        const groups = line.match(/^ {4}Groups: (\d+)$/);
        if (status) current.status = status[1];
        if (players) current.players = Number(players[1]);
        if (groups) current.groups = Number(groups[1]);
    }
    return { none: lines.some(l => l.includes('No importable data sources detected.')), sources, lines };
}
