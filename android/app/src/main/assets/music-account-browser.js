/* Read-only adapter for the user's signed-in Amazon Music page. No credentials cross the bridge. */
(function () {
    'use strict';
    const clean = value => String(value || '').replace(/\s+/g, ' ').trim();
    function elements(selector) {
        const result = [];
        const roots = [document];
        while (roots.length) {
            const root = roots.shift();
            result.push(...root.querySelectorAll(selector));
            for (const e of root.querySelectorAll('*')) if (e.shadowRoot) roots.push(e.shadowRoot);
        }
        return result;
    }
    function text(e, selector) {
        const node = e.querySelector(selector) || (e.shadowRoot && e.shadowRoot.querySelector(selector));
        return node ? clean(node.getAttribute('title') || node.textContent) : '';
    }
    function playlistUrl(raw) {
        try {
            const url = new URL(raw, location.href);
            if (url.protocol !== 'https:' || url.hostname !== location.hostname) return null;
            const match = url.pathname.match(/^\/(?:user-playlists|playlists)\/([a-zA-Z0-9_-]+)\/?$/);
            return match ? {id: match[1], url: url.origin + url.pathname} : null;
        } catch (_) { return null; }
    }
    function playlistRows() {
        const rows = [];
        for (const e of elements('[href], [primary-href]')) {
            const link = playlistUrl(e.getAttribute('primary-href') || e.getAttribute('href'));
            if (!link) continue;
            const card = e.closest('music-vertical-item, music-card, music-image-row, [role="listitem"]') || e;
            const title = clean(card.getAttribute('primary-text') || e.getAttribute('title') || e.getAttribute('aria-label') || e.textContent);
            if (!title) continue;
            const image = card.querySelector('img') || (card.shadowRoot && card.shadowRoot.querySelector('img'));
            rows.push({...link, title, coverUrl: image ? image.src : null, trackCount: -1});
        }
        return rows;
    }
    function trackRows() {
        const result = [];
        for (const e of elements('music-container music-image-row, music-image-row')) {
            const index = Number(text(e, '.index') || e.getAttribute('aria-rowindex') || e.getAttribute('index'));
            if (!Number.isInteger(index) || index < 1) continue;
            const title = clean(e.getAttribute('primary-text') || text(e, '.col1 music-link'));
            const artist = clean(e.getAttribute('secondary-text-1') || text(e, '.col2 music-link'));
            const album = clean(e.getAttribute('secondary-text-2') || text(e, '.col3 music-link'));
            const duration = clean(text(e, '.col4 music-link'));
            const link = e.querySelector('.col1 music-link, .col1 a');
            const href = link && (link.getAttribute('href') || '');
            const id = href && ((href.match(/(?:trackAsin=|\/tracks\/)([a-zA-Z0-9]+)/) || [])[1]);
            if (title && artist) result.push({index, id: id || '', title, artist, album, duration});
        }
        return result;
    }
    function expectedTracks() {
        const headers = elements('music-detail-header, music-detail-header-main, header, h1').map(e =>
            [e.textContent, ...Array.from(e.attributes || []).map(a => a.value)].join(' ')).join(' ');
        // Request English in WebView; fail explicitly if a localized page doesn't expose a count.
        const match = clean(headers + ' ' + (document.body ? document.body.innerText : ''))
            .match(/(?:^|\s)([\d,]+)\s+(?:songs?|tracks?)(?:\s|$|[·•])/i);
        return match ? Number(match[1].replace(/,/g, '')) : -1;
    }
    function scrollTargets() {
        return elements('*').filter(e => e.clientHeight > 100 && e.scrollHeight > e.clientHeight + 8 &&
            /auto|scroll/.test(getComputedStyle(e).overflowY));
    }
    function advance() {
        let moved = false;
        const targets = [...scrollTargets(), document.scrollingElement].filter(Boolean);
        for (const e of new Set(targets)) {
            const old = e.scrollTop;
            e.scrollTop += Math.max(200, e.clientHeight * 0.65);
            if (e.scrollTop > old + 1) moved = true;
        }
        return moved;
    }
    function validateTracks(rows, expected) {
        if (expected < 0) throw new Error('Amazon did not expose a total song count. Open the playlist in the web page, let it load, and retry. Nothing was saved.');
        const ordered = [...rows].sort((a, b) => a.index - b.index);
        if (ordered.length !== expected || ordered.some((row, i) => row.index !== i + 1))
            throw new Error('Read ' + ordered.length + ' of ' + expected + ' songs. Nothing was saved for this playlist. Reload and retry.');
        return ordered;
    }
    async function scan(mode, jobId) {
        const job = window.__auralisMusicJob = {id: jobId, status: 'loading', count: 0};
        try {
            if (!/^music\.amazon\.(com|in|co\.uk|de|fr|it|es|co\.jp|ca|com\.au|com\.mx|com\.br)$/.test(location.hostname))
                throw new Error('Open Amazon Music and sign in first.');
            if (mode === 'library' && !/^\/my\/playlists\/?$/.test(location.pathname))
                throw new Error('Open Library → Playlists in Amazon Music, then load playlists.');
            if (mode === 'tracks' && !playlistUrl(location.href)) throw new Error('Open the selected playlist and retry.');
            for (const e of [...scrollTargets(), document.scrollingElement].filter(Boolean)) e.scrollTop = 0;
            const collected = new Map();
            let stable = 0;
            let completed = false;
            for (let round = 0; round < 160; round++) {
                if (window.__auralisMusicJob !== job || job.cancelled) return;
                const before = collected.size;
                for (const row of mode === 'library' ? playlistRows() : trackRows())
                    collected.set(mode === 'library' ? row.id : row.index, row);
                job.count = collected.size;
                const moved = advance();
                stable = !moved && before === collected.size ? stable + 1 : 0;
                if (stable >= 8) { completed = true; break; }
                await new Promise(resolve => setTimeout(resolve, 600));
            }
            if (!completed) throw new Error('The page is still loading more items. Retry after it finishes. Nothing was imported.');
            let rows = [...collected.values()];
            if (mode === 'tracks') rows = validateTracks(rows, expectedTracks());
            else if (!rows.length) throw new Error('No playlists were found. Sign in and open Library → Playlists, then retry.');
            job.result = rows;
            job.status = 'done';
        } catch (error) { job.error = error.message; job.status = 'error'; }
    }
    window.AuralisMusicReader = {scan};
    // Expose the pure checks for offline fixture tests; no networking or session data involved.
    if (typeof module !== 'undefined') module.exports = {playlistUrl, validateTracks, trackRows, playlistRows};
})();
