import { test } from 'node:test';
import assert from 'node:assert/strict';
import { Xtream, normalizeServer, parseXtreamLink, formatServerTime, liveChannels, movieItems, seriesItems } from '../js/xtream.js';

test('normalizes server addresses and recognises Xtream links', () => {
  assert.equal(normalizeServer('provider.tv:8080/'), 'http://provider.tv:8080');
  assert.equal(normalizeServer('https://p.tv/get.php?username=a'), 'https://p.tv');
  assert.deepEqual(parseXtreamLink('http://p.tv:8080/get.php?username=bob&password=s3&type=m3u_plus'), { server: 'http://p.tv:8080', user: 'bob', pass: 's3' });
  assert.equal(parseXtreamLink('https://example.com/list.m3u'), null);
});

test('builds stream URLs', () => {
  const xt = new Xtream({ server: 'p.tv', user: 'bob', pass: 'p@ss' });
  assert.equal(xt.liveUrl(7), 'http://p.tv/live/bob/p%40ss/7.m3u8');
  assert.equal(xt.movieUrl(9, 'mkv'), 'http://p.tv/movie/bob/p%40ss/9.mkv');
  assert.equal(xt.episodeUrl(3, ''), 'http://p.tv/series/bob/p%40ss/3.mp4');
  assert.equal(xt.timeshiftUrl(7, new Date(Date.UTC(2025, 9, 25, 12, 5)), 60, 'Europe/London'), 'http://p.tv/timeshift/bob/p%40ss/60/2025-10-25:13-05/7.ts');
  assert.equal(formatServerTime(new Date(Date.UTC(2025, 0, 1, 0, 0)), 'Not/AZone'), '2025-01-01:00-00');
});

test('maps API lists to channels and items in category order', () => {
  const xt = new Xtream({ server: 'http://p.tv', user: 'u', pass: 'p' });
  const cats = [{ category_id: '2', category_name: 'Sports' }, { category_id: '1', category_name: 'News' }];
  const live = liveChannels(xt, cats, [
    { stream_id: 1, name: 'News 1', category_id: '1', stream_icon: 'n.png', epg_channel_id: 'news.1', tv_archive: 1, tv_archive_duration: '3' },
    { stream_id: 2, name: 'Sport 1', category_id: '2', tv_archive: 0 },
  ]);
  assert.deepEqual(live.map(c => [c.name, c.group, c.tvgId, c.xt.archive]), [['Sport 1', 'Sports', '', 0], ['News 1', 'News', 'news.1', 3]]);
  assert.equal(live[1].url, 'http://p.tv/live/u/p/1.m3u8');

  const movies = movieItems(xt, cats, [{ stream_id: 5, name: 'Film (2019)', category_id: '1', container_extension: 'mp4', rating: '7.5' }]);
  assert.deepEqual([movies[0].kind, movies[0].year, movies[0].rating, movies[0].url], ['movie', '2019', 7.5, 'http://p.tv/movie/u/p/5.mp4']);

  const series = seriesItems(cats, [{ series_id: 8, name: 'Show', cover: 'c.jpg', category_id: '2', releaseDate: '2020-01-01' }]);
  assert.deepEqual([series[0].id, series[0].group, series[0].year], ['s8', 'Sports', '2020']);
});
