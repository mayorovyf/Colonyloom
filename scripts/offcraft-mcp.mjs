import { readFile, writeFile } from 'node:fs/promises';
import { resolve, dirname } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
if (process.argv.length > 2) {
  if (process.argv.length !== 3 || process.argv[2] !== '--write-config') {
    throw new Error('Usage: node scripts/offcraft-mcp.mjs [--write-config]');
  }
  const config = { mcpServers: { offcraft: {
    command: process.execPath, args: [fileURLToPath(import.meta.url)],
  } } };
  await writeFile(resolve(root, 'mcp.offcraft.local.json'), JSON.stringify(config, null, 2) + '\n');
  console.error('Wrote private mcp.offcraft.local.json; merge its entry into your MCP host configuration.');
} else {
  const launch = JSON.parse(await readFile(resolve(root, 'launch.offcraft.json'), 'utf8'));
  const { launchAdapter } = await import(pathToFileURL(resolve(root, '.offcraft/mcp/adapter.mjs')));
  // ModDevGradle requires its generated environment as well as the Java argument array.
  Object.assign(process.env, launch.env);
  const { adapter, closed } = launchAdapter({ client: launch.java, args: launch.args, cwd: launch.gameDir });
  const stop = () => adapter.close();
  process.on('SIGINT', stop);
  process.on('SIGTERM', stop);
  const { code } = await closed;
  process.removeListener('SIGINT', stop);
  process.removeListener('SIGTERM', stop);
  if (code !== 0) process.exitCode = 1;
}
