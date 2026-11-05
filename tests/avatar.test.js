// Profile pictures (server/avatar.js): upload validation, EXIF stripping, serving with cache headers, the key, presets.
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, writeFile, readFile, rm, readdir, rename } from 'node:fs/promises';
import { spawnSync } from 'node:child_process';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const dir = await mkdtemp(join(tmpdir(), 'm3u-avatar-'));
process.env.PROXY_KEY = 'k3y';
process.env.SYNC_DATA_DIR = dir;
process.env.IMG_CACHE_DIR = join(dir, 'img');
const hasFfmpeg = spawnSync(process.env.FFMPEG || 'ffmpeg', ['-version'], { stdio: 'ignore' }).status === 0;
if (hasFfmpeg && !process.env.FFMPEG) process.env.FFMPEG = 'ffmpeg';
const now = Date.now();
await writeFile(join(dir, 'sync.json'), JSON.stringify({ profiles: { main: { name: 'Will', color: 0, at: now - 10 }, mz1: { name: 'Mazie', color: 3, at: now - 10 }, gone: { deleted: true, at: now - 5 } } }));
const { createAppServer } = await import('../server/server.js');
const { stripMeta, sniff } = await import('../server/avatar.js');
const { mergeProfiles } = await import('../server/sync.js');

let app, base;
before(async () => { app = createAppServer().listen(0); await new Promise(r => app.once('listening', r)); base = `http://127.0.0.1:${app.address().port}`; });
after(async () => { app.close(); await rm(dir, { recursive: true, force: true }); });

// A tiny JPEG with an EXIF block (camera + GPS-like text) and a comment.
const seg = (m, body) => { const b = Buffer.alloc(4); b[0] = 0xff; b[1] = m; b.writeUInt16BE(body.length + 2, 2); return Buffer.concat([b, body]); };
const jfif = seg(0xe0, Buffer.from('JFIF\0\x01\x01\0\0\x01\0\x01\0\0', 'latin1'));
const exif = seg(0xe1, Buffer.from('Exif\0\0SECRET-GPS-51.5N', 'latin1'));
const com = seg(0xfe, Buffer.from('SECRET comment', 'latin1'));
const scan = Buffer.from([0xff, 0xda, 0x00, 0x02, 1, 2, 3, 0xff, 0xd9]);
const jpeg = Buffer.concat([Buffer.from([0xff, 0xd8]), jfif, exif, com, scan]);
const up = (id, body, q = '', key = 'k3y') => fetch(`${base}/avatar/${id}?key=${key}${q}`, { method: 'POST', body, headers: { 'content-type': 'image/jpeg' } });

test('metadata is stripped (JPEG, PNG, WebP) and the format comes from the bytes', () => {
  const clean = stripMeta(jpeg, 'jpg');
  assert.equal(clean.includes('SECRET'), false);
  assert.ok(clean.includes('JFIF')); assert.ok(clean.subarray(-9).equals(scan));
  const chunk = (type, data) => { const h = Buffer.alloc(8); h.writeUInt32BE(data.length, 0); h.write(type, 4, 'latin1'); return Buffer.concat([h, data, Buffer.alloc(4)]); };
  const png = Buffer.concat([Buffer.from('89504e470d0a1a0a', 'hex'), chunk('IHDR', Buffer.alloc(13)), chunk('tEXt', Buffer.from('SECRET')), chunk('eXIf', Buffer.from('SECRET')), chunk('IDAT', Buffer.alloc(4)), chunk('IEND', Buffer.alloc(0))]);
  const p = stripMeta(png, 'png');
  assert.equal(sniff(png), 'png'); assert.equal(p.includes('SECRET'), false); assert.ok(p.includes('IDAT'));
  const wc = (t, d) => { const h = Buffer.alloc(8); h.write(t, 0, 'latin1'); h.writeUInt32LE(d.length, 4); return Buffer.concat([h, d, Buffer.alloc(d.length & 1)]); };
  const vp8x = Buffer.alloc(10); vp8x[0] = 0x0c;
  const body = Buffer.concat([wc('VP8X', vp8x), wc('VP8 ', Buffer.alloc(10)), wc('EXIF', Buffer.from('SECRET')), wc('XMP ', Buffer.from('SECRET!'))]);
  const riff = Buffer.concat([Buffer.from('RIFF'), Buffer.from([0, 0, 0, 0]), Buffer.from('WEBP'), body]); riff.writeUInt32LE(riff.length - 8, 4);
  const w = stripMeta(riff, 'webp');
  assert.equal(sniff(riff), 'webp'); assert.equal(w.includes('SECRET'), false); assert.equal(w[20] & 0x0c, 0, 'EXIF/XMP flags cleared');
  assert.equal(w.readUInt32LE(4), w.length - 8);
  assert.equal(sniff(Buffer.from('GIF89a........')), null);
  assert.equal(stripMeta(jpeg.subarray(0, 30), 'jpg'), null, 'a cut-off file is refused');
});

test('upload: key required, only pictures, at most 1 MB, only real profiles', async () => {
  assert.equal((await up('mz1', jpeg, '', 'wrong')).status, 403);
  assert.equal((await up('mz1', Buffer.from('<svg xmlns="http://www.w3.org/2000/svg"/>'))).status, 415);
  assert.equal((await up('mz1', Buffer.concat([jpeg.subarray(0, 2), Buffer.alloc((1 << 20) + 10)]))).status, 413);
  assert.equal((await up('gone', jpeg)).status, 404, 'a removed profile');
  assert.equal((await up('nobody', jpeg)).status, 404);
  assert.equal((await up('..%2Fsync', jpeg)).status, 404);
  assert.equal((await up('mz1', jpeg.subarray(0, 40))).status, 400);
});

test('upload, serve (versioned, cached), replace, remove', async () => {
  let r = await up('mz1', jpeg);
  assert.equal(r.status, 200);
  const { avatar, profile } = await r.json();
  assert.match(avatar, /^\/avatar\/mz1\?v=[0-9a-f]{12}$/);
  assert.equal(profile.avatar, avatar); assert.equal(profile.name, 'Mazie', 'the rest of the profile kept');
  const onDisk = await readFile(join(dir, 'avatars', 'mz1.jpg'));
  assert.equal(onDisk.includes('SECRET'), false, 'stored without EXIF');
  // Every device sees it through the profile list.
  const view = await (await fetch(`${base}/sync?key=k3y`)).json();
  assert.equal(view.profiles.mz1.avatar, avatar);
  await new Promise(r => setTimeout(r, 30));
  assert.equal(JSON.parse(await readFile(join(dir, 'sync.json'), 'utf8')).profiles.mz1.avatar, avatar, 'saved');
  r = await fetch(`${base}${avatar}&key=k3y`);
  assert.equal(r.status, 200); assert.equal(r.headers.get('content-type'), 'image/jpeg');
  assert.match(r.headers.get('cache-control'), /max-age=31536000, immutable/);
  assert.ok(Buffer.from(await r.arrayBuffer()).equals(onDisk));
  assert.equal((await fetch(`${base}${avatar}`)).status, 403, 'serving needs the key too');
  r = await fetch(`${base}/avatar/mz1?v=000000000000&key=k3y`);
  assert.equal(r.status, 200); assert.doesNotMatch(r.headers.get('cache-control'), /immutable/, 'an old version is not cached for long');
  // A new picture is a new URL.
  const other = Buffer.concat([Buffer.from([0xff, 0xd8]), jfif, Buffer.from([0xff, 0xda, 0x00, 0x02, 9, 9, 0xff, 0xd9])]);
  const a2 = (await (await up('mz1', other)).json()).avatar;
  assert.notEqual(a2, avatar);
  // A device's older edit of the profile (a rename from before the upload) can't drop it: newest edit wins.
  const l = mergeProfiles({ mz1: { name: 'Mazie', color: 3, at: now + 5000, avatar: a2 } }, { mz1: { name: 'Maz', color: 3, at: now } });
  assert.equal(l.mz1.avatar, a2);
  assert.equal(mergeProfiles({}, { mz1: { name: 'M', at: 1, avatar: '/avatar/other?v=abcdef' } }).mz1.avatar, undefined, 'only its own picture');
  assert.equal(mergeProfiles({}, { mz1: { name: 'M', at: 1, avatar: 'https://evil.test/x.jpg' } }).mz1.avatar, undefined);
  r = await up('mz1', null, '&remove=1');
  assert.equal((await r.json()).profile.avatar, undefined);
  assert.deepEqual((await readdir(join(dir, 'avatars'))).filter(f => f.startsWith('mz1')), []);
  assert.equal((await fetch(`${base}${a2}&key=k3y`)).status, 404);
  // The default profile can have a picture before it has a name.
  assert.equal((await up('main', jpeg)).status, 200);
});

test('resized through the image resizer when a width is asked (ffmpeg)', { skip: !hasFfmpeg && 'no ffmpeg' }, async () => {
  const src = join(dir, 'big.png');
  spawnSync('ffmpeg', ['-hide_banner', '-loglevel', 'error', '-f', 'lavfi', '-i', 'testsrc=s=800x800', '-frames:v', '1', '-y', src]);
  const { avatar } = await (await up('mz1', await readFile(src))).json();
  const r = await fetch(`${base}${avatar}&key=k3y&w=120`);
  assert.equal(r.status, 200); assert.equal(r.headers.get('content-type'), 'image/jpeg');
  const small = Buffer.from(await r.arrayBuffer());
  assert.ok(small.length < (await readFile(src)).length);
});

test('presets: the owner\'s folder in themed rows, made consistent, listed and served; empty = none', { skip: !hasFfmpeg && 'no ffmpeg' }, async () => {
  assert.deepEqual(await (await fetch(`${base}/avatars?key=k3y`)).json(), []);
  assert.equal((await fetch(`${base}/avatars?key=no`)).status, 403);
  const srcDir = join(dir, 'owner-folder');
  for (const t of ['Robots', 'Creatures', 'Kids']) await mkdir(join(srcDir, t), { recursive: true });
  const ff = (args, out) => spawnSync('ffmpeg', ['-hide_banner', '-loglevel', 'error', ...args, '-frames:v', '1', '-y', out]);
  // A circle drawn off-centre on a white canvas, and a shape on a transparent canvas.
  ff(['-f', 'lavfi', '-i', 'color=white:s=600x400', '-vf', "geq=r='if(lt(hypot(X-200,Y-220),120),200,255)':g='if(lt(hypot(X-200,Y-220),120),60,255)':b='if(lt(hypot(X-200,Y-220),120),60,255)'"], join(srcDir, 'Creatures', 'white.png'));
  ff(['-f', 'lavfi', '-i', 'color=black@0:s=500x500,format=rgba', '-vf', "geq=r=40:g=180:b=160:a='if(lt(hypot(X-350,Y-150),100),255,0)'"], join(srcDir, 'Creatures', 'clear.png'));
  ff(['-f', 'lavfi', '-i', 'color=c=red:s=900x600'], join(srcDir, 'Robots', 'r.jpg'));
  ff(['-f', 'lavfi', '-i', 'color=c=green:s=300x300'], join(srcDir, 'Kids', 'k.jpg'));
  ff(['-f', 'lavfi', '-i', 'color=c=blue:s=300x300'], join(srcDir, 'loose.jpg'));
  await writeFile(join(srcDir, 'notes.txt'), 'not a picture');
  const presets = join(dir, 'avatars', 'presets');
  const run = () => spawnSync(process.execPath, ['scripts/import-avatars.mjs', srcDir, presets], { encoding: 'utf8' }).stdout;
  assert.match(run(), /5 pictures in 4 rows .*5 new/);
  assert.match(run(), /0 new, 5 unchanged/, 'idempotent');
  let rows = await (await fetch(`${base}/avatars?key=k3y`)).json();
  assert.deepEqual(rows.map(r => r.theme), ['Creatures', 'Kids', 'Robots', 'More'], 'Creatures first, More (loose files) last');
  assert.match(rows[0].items[0].url, /^\/avatar\/preset\/[0-9a-f]{12}$/);
  // Made consistent: 512 square; the white canvas and the transparent area become the shared dark backdrop,
  // and the subject is centred (the middle pixel is the subject's colour).
  const { decode } = await import('../scripts/import-avatars.mjs');
  const files = Object.fromEntries((await readdir(join(presets, 'creatures'))).map(f => [f, join(presets, 'creatures', f)]));
  for (const f of Object.values(files)) {
    const img = decode(f);
    assert.equal(img.w, 512); assert.equal(img.h, 512);
    const px = (x, y) => [...img.px.subarray((y * 512 + x) * 4, (y * 512 + x) * 4 + 3)];
    assert.ok(px(2, 2).every(v => v < 40), `corner is the dark backdrop, not white: ${px(2, 2)}`);
    assert.ok(Math.max(...px(256, 256)) > 150, 'subject in the middle');
  }
  const r = await fetch(`${base}${rows[0].items[0].url}?key=k3y&w=200`);
  assert.equal(r.status, 200); assert.match(r.headers.get('cache-control'), /immutable/);
  assert.equal((await fetch(`${base}${rows[0].items[0].url}`)).status, 403);
  // A profile can pick one (through /sync, like any profile edit).
  const s = await (await fetch(`${base}/sync?key=k3y`, { method: 'POST', body: JSON.stringify({ profiles: { mz1: { name: 'Mazie', color: 3, at: Date.now() + 99999, avatar: rows[1].items[0].url } } }) })).json();
  assert.equal(s.profiles.mz1.avatar, rows[1].items[0].url);
  await rename(join(srcDir, 'Robots', 'r.jpg'), join(dir, 'r-moved.jpg'));
  await new Promise(r => setTimeout(r, 20));
  assert.match(run(), /4 pictures in 3 rows .*1 removed/, 'a picture gone from the folder goes, and its empty row');
  rows = await (await fetch(`${base}/avatars?key=k3y`)).json();
  assert.deepEqual(rows.map(r => r.theme), ['Creatures', 'Kids', 'More']);
});
