import { readFile, mkdir, mkdtemp, rm, copyFile, writeFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { resolve, dirname, relative } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawn } from 'node:child_process';

async function run(command, args, cwd = root) {
  const child = spawn(command, args, { cwd, stdio: 'inherit', shell: false });
  await new Promise((accept, reject) => {
    child.on('error', reject);
    child.on('close', code => code === 0 ? accept() : reject(new Error(`${command} exited ${code}`)));
  });
}

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const home = process.argv[2] ?? process.env.OFFCRAFT_HOME;
if (!home) throw new Error('Usage: node scripts/install-offcraft.mjs <Offcraft checkout with dist/>');
const revision = 'aa1f6403e5eacfbc5f7c5638b07999edf3b0a7fe';
const files = [
  ['dist/offcraft-1.0.0-mc1.21.1-neoforge.jar', 'colonyloom-neoforge/runs/offcraft/mods/offcraft-1.0.0-mc1.21.1-neoforge.jar', '763d61ca166627a57e5da6478c88874a0cc2aacc66ad9774251e00dea30e76fe'],
  ['mcp/adapter.mjs', '.offcraft/mcp/adapter.mjs', 'ec016413a7449ae8b121d5a47fd0526cb156e783fb3c4f35c69c9ebb26460639'],
  ['mcp/schemas.mjs', '.offcraft/mcp/schemas.mjs', '074a886b22486a2a5b68d7fdba03d85097c2eeb2b8a892bf0508dbf0df86f8cd'],
  ['mcp/index.mjs', '.offcraft/mcp/index.mjs', '4f0b736218aba9034fa9a8285d190a59a0e6649eb7d6c05500c78bef6e15bcdc'],
  ['LICENSE', '.offcraft/LICENSE', '156b542038a302f8fae862eecb40929b1cffeeee90776e85005ed32b135c7171'],
];
// Verify the entire input before installing any file. Never edit the upstream checkout.
for (const [source, , expected] of files) {
  const hash = createHash('sha256').update(await readFile(resolve(home, source))).digest('hex');
  if (hash !== expected) throw new Error(`Pinned SHA-256 mismatch: ${source}`);
}
// Rebuild the pinned source with type-based loading-screen checks. String class names
// are not stable when Yarn is remapped to NeoForge's runtime names.
if (process.platform !== 'win32') throw new Error('This Offcraft development installation requires Windows x64.');
await mkdir(resolve(root, '.offcraft'), { recursive: true });
const source = await mkdtemp(resolve(root, '.offcraft/source-'));
try {
  const archive = resolve(source, 'source.zip');
  await run('git', ['-C', resolve(home), 'archive', '--format=zip', `--output=${archive}`, revision]);
  await run('tar', ['-xf', archive, '-C', source]);
  const patchDirectory = relative(root, source).replaceAll('\\', '/');
  await run('git', ['apply', `--directory=${patchDirectory}`, 'scripts/offcraft-loading.patch']);
  await run('cmd.exe', ['/d', '/c', 'gradlew.bat build --no-daemon --console=plain'], resolve(source, 'neoforge'));
  for (const [inputPath, target] of files) {
    const destination = resolve(root, target);
    await mkdir(dirname(destination), { recursive: true });
    const input = inputPath.startsWith('dist/')
      ? resolve(source, inputPath) : resolve(home, inputPath);
    await copyFile(input, destination);
  }
  await writeFile(resolve(root, '.offcraft/install.json'), JSON.stringify({
    version: '1.0.0', revision, patch: 'scripts/offcraft-loading.patch',
    patchSha256: createHash('sha256').update(await readFile(resolve(root, 'scripts/offcraft-loading.patch'))).digest('hex'),
    files: await Promise.all(files.map(async ([, path]) => ({
      path, sha256: createHash('sha256').update(await readFile(resolve(root, path))).digest('hex'),
    }))),
  }, null, 2) + '\n');
  console.log('Installed pinned Offcraft 1.0.0 NeoForge mod and MCP companion.');
} finally {
  // Remove only this invocation's generated source/build, never an existing checkout.
  await rm(source, { recursive: true, force: true });
}
