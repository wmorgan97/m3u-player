// Copies the app's files into www/, which the native iOS app bundles.
import { cp, rm, mkdir } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('..', import.meta.url));
const out = `${root}www`;
await rm(out, { recursive: true, force: true });
await mkdir(out);
for (const item of ['index.html', 'manifest.webmanifest', 'css', 'js', 'vendor', 'icons']) {
  await cp(`${root}${item}`, `${out}/${item}`, { recursive: true });
}
console.log('Built www/');
