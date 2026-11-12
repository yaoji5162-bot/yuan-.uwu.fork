import fs from 'node:fs/promises';
import path from 'node:path';
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';

const root = path.resolve(import.meta.dirname, '..');
const android = path.join(root, 'android');
const output = path.join(android, 'app/src/main/assets/www');
const vendor = path.join(android, 'vendor');
const lock = JSON.parse(await fs.readFile(path.join(android, 'vendor-lock.json'), 'utf8'));
execFileSync(process.execPath, ['scripts/build.mjs'], { cwd: root, stdio: 'inherit' });
await fs.mkdir(vendor, { recursive: true });
await fs.rm(output, { recursive: true, force: true });
await fs.mkdir(output, { recursive: true });
for (const item of ['index.html', 'manifest.json', 'style.css', 'contacts.css', 'more_menu.css', 'js', 'css']) {
    await fs.cp(path.join(root, item), path.join(output, item), { recursive: true });
}
await fs.mkdir(path.join(output, 'vendor'), { recursive: true });
let html = await fs.readFile(path.join(output, 'index.html'), 'utf8');
const remoteAssets = {};
for (const item of lock) {
    const file = path.join(vendor, item.file);
    let bytes;
    try { bytes = await fs.readFile(file); } catch { /* Fetch once and verify before use. */ }
    if (!bytes || createHash('sha256').update(bytes).digest('hex') !== item.sha256) {
        // curl follows the host's configured proxy and trust settings.
        execFileSync('curl', ['--fail', '--location', '--retry', '2', '--max-time', '120', '--silent', '--show-error', item.url, '-o', file]);
        bytes = await fs.readFile(file);
    }
    if (createHash('sha256').update(bytes).digest('hex') !== item.sha256) throw new Error(`Vendor integrity mismatch: ${item.file}`);
    await fs.writeFile(path.join(output, 'vendor', item.file), bytes);
    if (item.kind === 'image') remoteAssets[item.original] = `www/vendor/${item.file}`;
    else html = html.replaceAll(item.original, `vendor/${item.file}`);
}
if (/<script\b[^>]*src=["']https?:/i.test(html)) throw new Error('Unbundled entry script');
html = html.replace('<head>', '<head>\n    <script src="native-runtime.js"></script>');
await fs.writeFile(path.join(output, 'index.html'), html);
await fs.copyFile(path.join(android, 'native-runtime.js'), path.join(output, 'native-runtime.js'));
const mainFile = path.join(output, 'js/main.js');
const main = await fs.readFile(mainFile, 'utf8');
await fs.writeFile(mainFile, main.replace("if ('serviceWorker' in navigator)", 'if (false /* APK assets update with the signed APK */)'));
// Never cache an old web build over the assets of a newly installed APK.
await fs.writeFile(path.join(output, 'js/modules/tutorial/file-update.js'), `(() => {
    const run = async () => { if (typeof showToast === 'function') showToast('App 更新请安装新版 APK，更新前建议导出备份'); };
    window.OVOFileUpdate = { baseUrl: location.href, run, renderState() {} };
})();\n`);
await fs.writeFile(path.join(output, 'THIRD-PARTY.json'), JSON.stringify(lock, null, 2));
await fs.writeFile(path.join(output, '../remote-assets.json'), JSON.stringify(remoteAssets));
console.log('Prepared Android assets with verified local entry dependencies.');
