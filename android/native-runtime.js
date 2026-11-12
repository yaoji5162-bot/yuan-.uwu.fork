/* Android-only integration; the original website is left unchanged. */
(() => {
    'use strict';
    const pending = new Map();
    let sequence = 0;
    let exporting = false;
    const bridge = window.YuanNative;
    if (!bridge) return;
    bridge.onmessage = event => {
        const result = JSON.parse(event.data);
        const task = pending.get(result.id);
        if (!task) return;
        pending.delete(result.id);
        result.ok ? task.resolve(result) : task.reject(new Error(result.error || '保存失败'));
    };
    const request = payload => new Promise((resolve, reject) => {
        const id = String(++sequence);
        pending.set(id, { resolve, reject });
        bridge.postMessage(JSON.stringify({ ...payload, id }));
    });
    const notify = message => typeof showToast === 'function' ? showToast(message) : console.info(message);
    async function save(blob, filename = 'download') {
        if (exporting) { notify('请先完成当前文件保存'); return false; }
        exporting = true;
        try {
            if (!(blob instanceof Blob)) throw new Error('没有可保存的文件');
            const { token } = await request({ op: 'begin', filename, mime: blob.type, size: blob.size });
            for (let offset = 0; offset < blob.size; offset += 196608) {
                const bytes = new Uint8Array(await blob.slice(offset, offset + 196608).arrayBuffer());
                let binary = '';
                for (let i = 0; i < bytes.length; i += 8192) binary += String.fromCharCode(...bytes.subarray(i, i + 8192));
                await request({ op: 'chunk', token, offset, data: btoa(binary) });
            }
            await request({ op: 'end', token });
            notify('文件已保存');
            return true;
        } catch (error) {
            await request({ op: 'abort' }).catch(() => {});
            notify(error.message === 'cancelled' ? '已取消保存' : `保存未完成：${error.message}`);
            return false;
        } finally { exporting = false; }
    }
    async function saveUrl(url, filename) {
        try {
            const response = await fetch(url);
            if (!response.ok) throw new Error(`HTTP ${response.status}`);
            return await save(await response.blob(), filename);
        } catch (error) { notify(`无法读取文件：${error.message}`); return false; }
    }
    document.addEventListener('click', event => {
        const link = event.target.closest?.('a[download]');
        if (!link?.href) return;
        event.preventDefault();
        event.stopImmediatePropagation();
        void saveUrl(link.href, link.download || 'download');
    }, true);
    document.addEventListener('DOMContentLoaded', () => {
        if (window.OvoFileDownload) window.OvoFileDownload.save = save;
        // Updates for a bundled APK arrive via a new APK, never a stale web cache.
        window.OVOFileUpdate = {
            ...(window.OVOFileUpdate || {}),
            run: async () => { notify('App 更新请安装新版 APK，更新前建议导出备份'); return false; }
        };
    });
    window.YuanAndroid = {
        saveUrl,
        handleBack() {
            const overlay = [...document.querySelectorAll('.modal-overlay.visible, .action-sheet-overlay.visible, .settings-sidebar.open')]
                .filter(el => el.getClientRects().length).at(-1);
            if (overlay) {
                const close = [...overlay.querySelectorAll('button')].find(button =>
                    /^(关闭|取消|返回|稍后|完成)$/.test(button.textContent.trim()));
                if (close) close.click();
                else overlay.dispatchEvent(new MouseEvent('click', { bubbles: true }));
                return true;
            }
            const screen = document.querySelector('.screen.active');
            if (!screen || screen.id === 'home-screen') return false;
            const back = screen.querySelector('.back-btn[data-target], [data-action="return"], [data-action="exit"]');
            if (back) back.click();
            else if (typeof switchScreen === 'function') switchScreen('home-screen');
            return true;
        }
    };
})();
