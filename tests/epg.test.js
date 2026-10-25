import { test } from 'node:test';
import assert from 'node:assert/strict';
import { XmltvParser, Guide, parseTime, decode, wantedFor, archiveDays, m3uCatchupUrl } from '../js/epg.js';

const XML = `<?xml version="1.0" encoding="UTF-8"?>
<tv generator-info-name="test">
  <channel id="BBC1.uk"><display-name>BBC One HD</display-name><icon src="x.png"/></channel>
  <channel id="other.id"><display-name lang="en">Sky News</display-name></channel>
  <channel id="unwanted"><display-name>Nobody</display-name></channel>
  <programme start="20251025120000 +0000" stop="20251025130000 +0000" channel="BBC1.uk">
    <title lang="en">News &amp; Weather</title><desc>Headlines.</desc>
  </programme>
  <programme start="20251025130000 +0000" stop="20251025140000 +0000" channel="BBC1.uk"><title><![CDATA[Film <Night>]]></title></programme>
  <programme start="20251025140000 +0100" stop="20251025150000 +0100" channel="other.id"><title>Sky &#8211; Live</title></programme>
  <programme start="20251025120000 +0000" stop="20251025130000 +0000" channel="unwanted"><title>Skip</title></programme>
  <programme start="20251020120000 +0000" stop="20251020130000 +0000" channel="BBC1.uk"><title>Too old</title></programme>
</tv>`;

test('parseTime handles offsets', () => {
  assert.equal(parseTime('20251025120000 +0000'), Date.UTC(2025, 9, 25, 12));
  assert.equal(parseTime('20251025140000 +0100'), Date.UTC(2025, 9, 25, 13));
  assert.equal(parseTime('20251025120000 -0530'), Date.UTC(2025, 9, 25, 17, 30));
  assert.ok(Number.isNaN(parseTime('garbage')));
});

test('decode entities and CDATA', () => {
  assert.equal(decode('a &amp; b &#8211; &#x41; &unknown;'), 'a & b – A &unknown;');
  assert.equal(decode('<![CDATA[<b>]]>'), '<b>');
});

test('streams XMLTV in small chunks, filters channels and time window', () => {
  const channels = [{ tvgId: 'bbc1.uk', name: 'BBC 1' }, { name: 'Sky News HD' }];
  const parser = new XmltvParser({ from: Date.UTC(2025, 9, 24), to: Date.UTC(2025, 9, 27), wanted: wantedFor(channels) });
  for (let i = 0; i < XML.length; i += 7) parser.feed(XML.slice(i, i + 7));
  const data = parser.finish();
  assert.deepEqual(Object.keys(data.programmes).sort(), ['bbc1.uk', 'other.id']);
  assert.deepEqual(data.programmes['bbc1.uk'].map(p => p[2]), ['News & Weather', 'Film <Night>']);
  assert.equal(data.programmes['bbc1.uk'][0][3], 'Headlines.');

  const guide = new Guide(data);
  const t = Date.UTC(2025, 9, 25, 12, 30);
  assert.equal(guide.nowNext(channels[0], t).now[2], 'News & Weather');
  assert.equal(guide.nowNext(channels[0], t).next[2], 'Film <Night>');
  // matched by display name
  assert.equal(guide.nowNext(channels[1], Date.UTC(2025, 9, 25, 13, 30)).now[2], 'Sky – Live');
  assert.equal(guide.nowNext(channels[0], Date.UTC(2025, 9, 26)).now, null);
});

test('catch-up support detection and URL templates', () => {
  assert.equal(archiveDays({ xt: { archive: 3 } }), 3);
  assert.equal(archiveDays({ attrs: { catchup: 'default', 'catchup-days': '5' } }), 5);
  assert.equal(archiveDays({ attrs: {} }), 0);

  const start = Date.UTC(2025, 9, 25, 12), stop = Date.UTC(2025, 9, 25, 13), now = Date.UTC(2025, 9, 25, 15);
  const s = start / 1000;
  assert.equal(
    m3uCatchupUrl({ url: 'http://x/live.m3u8', attrs: { catchup: 'shift' } }, start, stop, now),
    `http://x/live.m3u8?utc=${s}&lutc=${now / 1000}`);
  assert.equal(
    m3uCatchupUrl({ url: 'http://x/live.m3u8', attrs: { catchup: 'append', 'catchup-source': '?start=${start}&dur={duration}' } }, start, stop, now),
    `http://x/live.m3u8?start=${s}&dur=3600`);
  assert.equal(
    m3uCatchupUrl({ url: 'http://x/live', attrs: { catchup: 'default', 'catchup-source': 'http://y/{Y}/{m}/{d}/{H}{M}.m3u8' } }, start, stop, now),
    'http://y/2025/10/25/1200.m3u8');
});
