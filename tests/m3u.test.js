import { test } from 'node:test';
import assert from 'node:assert/strict';
import { parseM3U, toM3U, isHlsMedia, groupsOf, dedupe, nameFromUrl } from '../js/m3u.js';

const SAMPLE = `﻿#EXTM3U x-tvg-url="https://epg.example/guide.xml"\r
#EXTINF:-1 tvg-id="news.us" tvg-name="News, Live" tvg-logo="https://img.example/news.png" group-title="News",News 24, HD\r
https://cdn.example/news/index.m3u8\r
#EXTINF:-1 tvg-id="movies" catchup="default",Movies One\r
#EXTGRP:Films\r
#EXTVLCOPT:http-user-agent=Mozilla/5.0\r
http://provider.example:8080/live/u/p/123.ts\r
\r
# a comment\r
https://radio.example/stream.mp3\r
`;

test('parses extended attributes, titles with commas and header', () => {
  const { header, channels } = parseM3U(SAMPLE);
  assert.equal(header['x-tvg-url'], 'https://epg.example/guide.xml');
  assert.equal(channels.length, 3);

  const [news, movies, radio] = channels;
  assert.equal(news.name, 'News 24, HD');
  assert.equal(news.tvgName, 'News, Live');
  assert.equal(news.tvgId, 'news.us');
  assert.equal(news.logo, 'https://img.example/news.png');
  assert.equal(news.group, 'News');
  assert.equal(news.url, 'https://cdn.example/news/index.m3u8');
  assert.equal(news.duration, -1);

  assert.equal(movies.group, 'Films');
  assert.deepEqual(movies.attrs, { catchup: 'default' });
  assert.deepEqual(movies.opts, ['http-user-agent=Mozilla/5.0']);

  assert.equal(radio.name, 'stream');
  assert.equal(radio.group, '');
  assert.notEqual(news.id, movies.id);
});

test('handles plain URL lists and odd attribute quoting', () => {
  const { channels } = parseM3U(`https://a.example/one.m3u8\n#EXTINF:5 tvg-logo='x.png' group-title=Sports,O'Brien's Show\nhttps://b.example/two`);
  assert.equal(channels.length, 2);
  assert.equal(channels[0].name, 'one');
  assert.equal(channels[1].name, "O'Brien's Show");
  assert.equal(channels[1].logo, 'x.png');
  assert.equal(channels[1].group, 'Sports');
  assert.equal(channels[1].duration, 5);
});

test('round-trips through toM3U', () => {
  const first = parseM3U(SAMPLE);
  const text = toM3U(first.channels, first.header);
  assert.match(text, /^#EXTM3U x-tvg-url="https:\/\/epg\.example\/guide\.xml"\n/);
  const second = parseM3U(text);
  const strip = cs => cs.map(({ id, ...rest }) => rest);
  assert.deepEqual(strip(second.channels), strip(first.channels));
  assert.deepEqual(second.header, first.header);
});

test('toM3U keeps output on one line per field', () => {
  const text = toM3U([{ name: 'A\nB', url: 'u', group: 'G "quoted"', logo: '', tvgId: '', tvgName: '', attrs: {}, opts: [] }]);
  assert.equal(text, `#EXTM3U\n#EXTINF:-1 group-title="G 'quoted'",A B\nu\n`);
});

test('detects HLS stream manifests', () => {
  assert.equal(isHlsMedia('#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nlow.m3u8'), true);
  assert.equal(isHlsMedia('#EXTM3U\n#EXT-X-TARGETDURATION:6\nseg.ts'), true);
  assert.equal(isHlsMedia(SAMPLE), false);
});

test('groupsOf and dedupe', () => {
  const cs = [{ url: 'a', group: 'X' }, { url: 'b', group: '' }, { url: 'a', group: 'X' }, { url: 'c' }];
  assert.deepEqual(groupsOf(cs), [{ name: 'X', count: 2 }, { name: '', count: 2 }]);
  assert.deepEqual(dedupe(cs).map(c => c.url), ['a', 'b', 'c']);
});

test('nameFromUrl', () => {
  assert.equal(nameFromUrl('https://x.example/live/Big%20Show.m3u8?token=1'), 'Big Show');
  assert.equal(nameFromUrl('https://x.example/'), 'x.example');
  assert.equal(nameFromUrl('not a url'), 'not a url');
});
