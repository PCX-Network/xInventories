import type { TestContext } from '@plugwright/runner';

type Ctx = Pick<TestContext, 'player' | 'server'>;

/** The groups.yml staged by build.gradle: world + world_the_end -> survival, world_nether -> nether. */
export const OVERWORLD_GROUP = 'survival';
export const NETHER_GROUP = 'nether';

/** Teleports the bot onto the nether's bedrock roof (layer 127 is solid bedrock, so y=128 is safe). */
export async function toNether({ player, server }: Ctx): Promise<void> {
    await server.execute(`execute in minecraft:the_nether run tp ${player.username} 0.5 128 0.5`);
}

/** Teleports the bot back to where it stood in the overworld. */
export async function toOverworld({ player, server }: Ctx, pos: { x: number; y: number; z: number }): Promise<void> {
    await server.execute(`execute in minecraft:overworld run tp ${player.username} ${pos.x} ${pos.y} ${pos.z}`);
}

/** Current bot position, rounded up a little in y so the return teleport never lands inside a block. */
export function spawnPos({ player }: Pick<TestContext, 'player'>): { x: number; y: number; z: number } {
    const p = player.bot.entity.position;
    return { x: Math.floor(p.x) + 0.5, y: Math.ceil(p.y), z: Math.floor(p.z) + 0.5 };
}
