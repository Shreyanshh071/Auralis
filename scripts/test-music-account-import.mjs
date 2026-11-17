import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

const script = readFileSync(new URL('../android/app/src/main/assets/music-account-browser.js', import.meta.url), 'utf8');
function browser({host = 'music.amazon.com', path = '/my/playlists', rows = [], links = [], count = 0} = {}) {
    const body = {innerText: `${count} songs`};
    const doc = {
        body, scrollingElement: null,
        querySelectorAll(selector) {
            if (selector.includes('music-image-row') && !selector.includes('[href]')) return rows;
            if (selector === '[href], [primary-href]') return links;
            return [];
        }
    };
    const box = {
        module: {exports: {}}, URL, document: doc,
        location: {hostname: host, href: `https://${host}${path}`, pathname: path},
        getComputedStyle: () => ({overflowY: 'visible'}),
        setTimeout: fn => setImmediate(fn),
    };
    box.window = box;
    vm.runInNewContext(script, box);
    return box;
}

let checks = 0;
function check(name, run) { run(); checks++; console.log(`PASS ${name}`); }
const api = browser().module.exports;
check('keeps Amazon region and canonical playlist identity', () => {
    const value = api.playlistUrl('/user-playlists/private_123?ref=library');
    assert.equal(value.id, 'private_123');
    assert.equal(value.url, 'https://music.amazon.com/user-playlists/private_123');
});
check('rejects foreign origins and deceptive Amazon hostnames', () => {
    for (const url of ['https://evil.test/playlists/a', 'https://music.amazon.com.evil.test/playlists/a', 'javascript:alert(1)', 'http://music.amazon.com/playlists/a'])
        assert.equal(api.playlistUrl(url), null);
});
check('does not mistake albums or individual tracks for playlists', () => {
    assert.equal(api.playlistUrl('/albums/B123'), null);
    assert.equal(api.playlistUrl('/tracks/B123'), null);
});
check('reorders virtualized rows without dropping duplicate songs', () => {
    const rows = [{index: 2, id: 'same'}, {index: 1, id: 'same'}];
    const result = api.validateTracks(rows, 2);
    assert.equal(result[0].index, 1);
    assert.equal(result[1].index, 2);
    assert.equal(result[0].id, result[1].id);
});
check('rejects unknown totals, missing pages, gaps, and duplicate row positions', () => {
    assert.throws(() => api.validateTracks([{index: 1}], -1));
    assert.throws(() => api.validateTracks([{index: 1}], 200));
    assert.throws(() => api.validateTracks([{index: 1}, {index: 3}], 2));
    assert.throws(() => api.validateTracks([{index: 1}, {index: 1}], 2));
    assert.throws(() => api.validateTracks([{index: 2}], 1));
});
check('accepts a confirmed empty playlist', () => assert.equal(api.validateTracks([], 0).length, 0));

function row(index, title = 'Song', artist = 'Artist') {
    const attrs = {'primary-text': title, 'secondary-text-1': artist, 'secondary-text-2': 'Album'};
    return {
        shadowRoot: null,
        getAttribute: name => attrs[name] || null,
        querySelector: selector => selector === '.index' ? {textContent: String(index), getAttribute: () => null} : null,
    };
}
check('reads indexed Amazon custom-element metadata', () => {
    const box = browser({rows: [row(1, 'Private song', 'Singer'), row(2)]});
    const result = box.module.exports.trackRows();
    assert.equal(result.length, 2);
    assert.equal(result[0].title, 'Private song');
    assert.equal(result[0].artist, 'Singer');
    assert.equal(result[0].album, 'Album');
});
check('ignores skeleton rows and rows with no artist', () => {
    const box = browser({rows: [row(1, '', ''), row(2, 'Song', ''), row(3)]});
    assert.equal(box.module.exports.trackRows().length, 1);
});
const invalidHost = browser({host: 'music.amazon.com.evil.test'});
await invalidHost.AuralisMusicReader.scan('library', 'host-test');
check('scan cannot operate on a foreign login or redirect page', () => assert.equal(invalidHost.__auralisMusicJob.status, 'error'));
const wrongPage = browser({path: '/charts'});
await wrongPage.AuralisMusicReader.scan('library', 'page-test');
check('does not import recommendations as the user library', () => assert.equal(wrongPage.__auralisMusicJob.status, 'error'));
const complete = browser({path: '/user-playlists/abc', rows: [row(1), row(2)], count: 2});
await complete.AuralisMusicReader.scan('tracks', 'complete-test');
check('complete scan returns ordered songs', () => {
    assert.equal(complete.__auralisMusicJob.status, 'done');
    assert.equal(complete.__auralisMusicJob.result.length, 2);
});
const partial = browser({path: '/user-playlists/abc', rows: [row(1)], count: 20});
await partial.AuralisMusicReader.scan('tracks', 'partial-test');
check('partial scan ends in an error without any import result', () => {
    assert.equal(partial.__auralisMusicJob.status, 'error');
    assert.equal(partial.__auralisMusicJob.result, undefined);
});

// Execute the exact Tidal session reader embedded in Kotlin, without exposing token values.
const kotlin = readFileSync(new URL('../android/app/src/main/java/com/auralis/music/data/importer/MusicAccountBrowser.kt', import.meta.url), 'utf8');
const tidalReader = kotlin.match(/private val TIDAL_SESSION = """([\s\S]*?)"""/)[1].replaceAll("${'$'}", '$');
function storage(values) {
    const keys = Object.keys(values);
    return {length: keys.length, key: i => keys[i], getItem: key => values[key]};
}
const fakeAccess = 'a'.repeat(40), fakeRefresh = 'r'.repeat(40);
const tidal = {
    location: {origin: 'https://listen.tidal.com'},
    localStorage: storage({auth: JSON.stringify({credentials: {accessToken: fakeAccess, refreshToken: fakeRefresh}})}),
    sessionStorage: storage({})
};
check('Tidal reader selects access tokens without returning refresh tokens', () => {
    const tokens = vm.runInNewContext(tidalReader, tidal);
    assert.equal(tokens.length, 1);
    assert.equal(tokens[0], fakeAccess);
});
check('Tidal reader refuses non-player origins', () => {
    assert.equal(vm.runInNewContext(tidalReader, {...tidal, location: {origin: 'https://example.com'}}).length, 0);
});
check('Tidal reader supports credential storage and quoted direct access-token fields', () => {
    const tokens = vm.runInNewContext(tidalReader, {...tidal,
        localStorage: storage({credentials: JSON.stringify({access_token: fakeAccess})}),
        sessionStorage: storage({accessToken: JSON.stringify(fakeAccess)})});
    assert.equal(tokens.length, 1);
    assert.equal(tokens[0], fakeAccess);
});

console.log(`${checks} account-import checks passed. No Android build or live-account calls performed.`);
