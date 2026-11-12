// Test the actual packaged web assets in an isolated browser, with an origin-scoped
// native-message stand-in. Android chooser/permission UI still needs a real device.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import http from 'node:http';
import path from 'node:path';
import { createRequire } from 'node:module';
const require = createRequire(import.meta.url);
const { chromium } = require('playwright');
const root = path.resolve(import.meta.dirname, '../android/app/src/main/assets/www');
const server = http.createServer((req, res) => {
    const name = path.resolve(root, '.' + new URL(req.url, 'http://localhost').pathname);
    if (!name.startsWith(root + path.sep)) { res.writeHead(403); res.end(); return; }
    try {
        res.setHeader('Content-Type', name.endsWith('.html') ? 'text/html; charset=utf-8'
            : name.endsWith('.css') ? 'text/css' : name.endsWith('.json') ? 'application/json' : 'application/javascript');
        res.end(fs.readFileSync(name));
    } catch { res.writeHead(404); res.end(); }
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const browser = await chromium.launch({ executablePath: process.env.OVO_TEST_CHROMIUM || '/usr/bin/chromium', args: ['--no-sandbox'] });
try {
    const context = await browser.newContext({ viewport: { width: 390, height: 844 }, deviceScaleFactor: 1, isMobile: true, hasTouch: true });
    const localImages = JSON.parse(fs.readFileSync(path.join(root, '../remote-assets.json'), 'utf8'));
    await context.route('**/*', route => {
        const url = route.request().url();
        if (url.startsWith('http://127.0.0.1:')) return route.continue();
        if (localImages[url]) return route.fulfill({ path: path.join(root, '..', localImages[url]), headers: { 'Access-Control-Allow-Origin': '*' } });
        return route.abort();
    });
    await context.addInitScript(() => {
        window.nativeExports = [];
        window.nativeCancel = false;
        let active;
        window.YuanNative = {
            postMessage(text) {
                const request = JSON.parse(text);
                const response = { id: request.id, ok: true };
                try {
                    if (request.op === 'begin') {
                        if (window.nativeCancel) { window.nativeCancel = false; throw new Error('cancelled'); }
                        active = { name: request.filename, size: request.size, parts: [], written: 0 };
                        response.token = 'test-token';
                    } else if (request.op === 'chunk') {
                        if (request.token !== 'test-token' || request.offset !== active.written) throw new Error('Bad sequence');
                        const bytes = Uint8Array.from(atob(request.data), c => c.charCodeAt(0));
                        active.parts.push(bytes); active.written += bytes.length;
                    } else if (request.op === 'end') {
                        if (active.written !== active.size) throw new Error('Truncated export');
                        window.nativeExports.push(active); active = null;
                    } else if (request.op === 'abort') active = null;
                } catch (error) { response.ok = false; response.error = error.message; }
                queueMicrotask(() => window.YuanNative.onmessage({ data: JSON.stringify(response) }));
            }
        };
    });
    const page = await context.newPage();
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await page.goto(`http://127.0.0.1:${server.address().port}/index.html`, { waitUntil: 'load' });
    await page.waitForFunction(() => typeof db !== 'undefined' && window.OvoFileDownload && typeof switchScreen === 'function');
    await page.waitForTimeout(1200);
    assert.deepEqual(errors, [], 'Packaged page should initialize without JS exceptions');
    assert.equal(await page.evaluate(() => typeof Dexie), 'function', 'Database dependency must work with external network blocked');
    assert.equal(await page.evaluate(() => document.querySelectorAll('.screen').length > 10), true);
    assert.equal(await page.evaluate(() => [...document.querySelectorAll('.screen.active img')].filter(img => img.src && !img.naturalWidth).length), 0,
        'Default desktop images must render without external network');
    await page.evaluate(() => {
        document.querySelectorAll('.modal-overlay.visible, .action-sheet-overlay.visible').forEach(el => el.classList.remove('visible'));
        switchScreen('chat-list-screen');
    });
    assert.equal(await page.evaluate(() => YuanAndroid.handleBack()), true);
    assert.equal(await page.evaluate(() => document.querySelector('.screen.active').id), 'home-screen');
    assert.equal(await page.evaluate(() => YuanAndroid.handleBack()), false);
    const exported = await page.evaluate(async () => {
        const bytes = new Uint8Array(700000).map((_, i) => i % 251);
        const ok = await OvoFileDownload.save(new Blob([bytes], { type: 'application/octet-stream' }), '测试备份.bin');
        const item = nativeExports.at(-1);
        const out = new Blob(item.parts);
        return { ok, name: item.name, size: out.size, matches: new Uint8Array(await out.arrayBuffer()).every((v, i) => v === bytes[i]), parts: item.parts.length };
    });
    assert.deepEqual(exported, { ok: true, name: '测试备份.bin', size: 700000, matches: true, parts: 4 });
    assert.equal(await page.evaluate(async () => { window.nativeCancel = true; return OvoFileDownload.save(new Blob(['cancel']), 'cancel.txt'); }), false);
    assert.equal(await page.evaluate(() => OvoFileDownload.save(new Blob([]), 'empty.txt')), true);
    await page.evaluate(() => {
        const a = document.createElement('a'); a.href = URL.createObjectURL(new Blob(['图片导出测试']));
        a.download = 'anchor.txt'; document.body.append(a); a.click(); a.remove();
    });
    await page.waitForFunction(() => nativeExports.some(item => item.name === 'anchor.txt'));
    // Persist through the real IndexedDB backend, then reload the entire app.
    await page.evaluate(async () => {
        const test = new Dexie('android-smoke-persistence'); test.version(1).stores({ records: '&id' });
        await test.table('records').put({ id: 'test', text: '保留数据' }); test.close();
    });
    await page.reload({ waitUntil: 'load' });
    const stored = await page.evaluate(async () => {
        const test = new Dexie('android-smoke-persistence'); test.version(1).stores({ records: '&id' });
        const value = await test.table('records').get('test'); await test.delete(); return value.text;
    });
    assert.equal(stored, '保留数据');
    assert.deepEqual(errors, []);
    await page.screenshot({ path: process.env.YUAN_SCREENSHOT || '/tmp/yuan-android-smoke.png' });
    await context.close();
    console.log('Android web assets: offline initialization, back navigation, chunked binary/empty/anchor exports, cancellation and IndexedDB persistence passed.');
} finally { await browser.close(); server.close(); }
