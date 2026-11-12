package com.yaoji.uwu;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.util.Base64;
import android.view.View;
import android.view.WindowInsets;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.webkit.JavaScriptReplyProxy;
import androidx.webkit.WebMessageCompat;
import androidx.webkit.WebViewAssetLoader;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONObject;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String ORIGIN = "https://appassets.androidplatform.net";
    private static final String HOME = ORIGIN + "/assets/www/index.html";
    private static final int PICK_FILE = 10, SAVE_FILE = 11, MEDIA_PERMISSION = 12;
    private static final long MAX_EXPORT = 512L * 1024 * 1024;
    private WebView web;
    private ValueCallback<Uri[]> fileCallback;
    private PermissionRequest mediaRequest;
    private final ExecutorService writer = Executors.newSingleThreadExecutor();
    private JavaScriptReplyProxy beginReply;
    private String beginId;
    private long exportSize, exportWritten;
    private String exportToken;
    private Uri exportUri;
    private OutputStream exportStream;
    private FrameLayout root;
    private View videoView;
    private WebChromeClient.CustomViewCallback videoCallback;
    private JSONObject remoteAssets = new JSONObject();

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.WHITE);
        getWindow().setNavigationBarColor(Color.WHITE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        root = new FrameLayout(this);
        web = new WebView(this);
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        // Android 15 enforces edge-to-edge: keep content clear of bars and keyboard.
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return WindowInsets.CONSUMED;
            });
        }
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true); // Only URIs explicitly picked by the user.
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setSupportMultipleWindows(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false);
        WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this)).build();
        try (java.io.InputStream input = getAssets().open("remote-assets.json")) {
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            byte[] bytes = new byte[4096]; int count;
            while ((count = input.read(bytes)) != -1) buffer.write(bytes, 0, count);
            remoteAssets = new JSONObject(buffer.toString("UTF-8"));
        } catch (Exception ignored) { /* Custom remote images still use the network. */ }
        web.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String bundled = remoteAssets.optString(request.getUrl().toString(), "");
                if (!bundled.isEmpty()) try {
                    String mime = java.net.URLConnection.guessContentTypeFromName(request.getUrl().getPath());
                    if (mime == null && bundled.endsWith(".webp")) mime = "image/webp";
                    return new WebResourceResponse(mime == null ? "application/octet-stream" : mime, null,
                        200, "OK", Collections.singletonMap("Access-Control-Allow-Origin", "*"), getAssets().open(bundled));
                } catch (IOException ignored) { }
                WebResourceResponse local = loader.shouldInterceptRequest(request.getUrl());
                if (local != null) return local;
                if ("appassets.androidplatform.net".equals(request.getUrl().getHost())) {
                    return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found",
                            Collections.emptyMap(), new java.io.ByteArrayInputStream(new byte[0]));
                }
                return null;
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (!request.isForMainFrame()) return false;
                Uri uri = request.getUrl();
                if (isLocal(uri) && uri.getPath() != null && uri.getPath().startsWith("/assets/www/")) return false;
                openExternal(uri);
                return true;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                List<String> types = new ArrayList<>();
                for (String type : params.getAcceptTypes()) if (type != null && type.contains("/") && !type.contains(",")) types.add(type);
                if (!types.isEmpty()) intent.putExtra(Intent.EXTRA_MIME_TYPES, types.toArray(new String[0]));
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE);
                try { startActivityForResult(intent, PICK_FILE); }
                catch (ActivityNotFoundException error) { fileCallback.onReceiveValue(null); fileCallback = null; toast("找不到系统文件选择器"); }
                return true;
            }
            @Override public void onPermissionRequest(PermissionRequest request) {
                if (!isLocal(request.getOrigin()) || mediaRequest != null) { request.deny(); return; }
                List<String> permissions = new ArrayList<>();
                for (String resource : request.getResources()) {
                    String permission = PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource) ? Manifest.permission.RECORD_AUDIO
                            : PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource) ? Manifest.permission.CAMERA : null;
                    if (permission == null) { request.deny(); return; }
                    if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) permissions.add(permission);
                }
                mediaRequest = request;
                if (permissions.isEmpty()) { request.grant(request.getResources()); mediaRequest = null; }
                else requestPermissions(permissions.toArray(new String[0]), MEDIA_PERMISSION);
            }
            @Override public void onPermissionRequestCanceled(PermissionRequest request) { if (mediaRequest == request) mediaRequest = null; }
            @Override public boolean onCreateWindow(WebView view, boolean dialog, boolean userGesture, android.os.Message message) {
                if (!userGesture) return false;
                WebView popup = new WebView(MainActivity.this);
                popup.setWebViewClient(new WebViewClient() {
                    @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                        openExternal(request.getUrl()); popup.destroy(); return true;
                    }
                });
                ((WebView.WebViewTransport) message.obj).setWebView(popup);
                message.sendToTarget();
                return true;
            }
            @Override public void onShowCustomView(View view, CustomViewCallback callback) {
                if (videoView != null) { callback.onCustomViewHidden(); return; }
                videoView = view; videoCallback = callback;
                root.addView(view, new FrameLayout.LayoutParams(-1, -1)); web.setVisibility(View.GONE);
            }
            @Override public void onHideCustomView() { closeVideo(); }
        });
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            new android.app.AlertDialog.Builder(this).setMessage("请先更新 Android System WebView 或 Chrome，再打开 UwU。")
                .setPositiveButton("关闭", (dialog, which) -> finish()).setCancelable(false).show();
            return;
        }
        // Origin-scoped messages; no Java interface is exposed to third-party frames.
        WebViewCompat.addWebMessageListener(web, "YuanNative", Collections.singleton(ORIGIN),
                (view, message, sourceOrigin, isMainFrame, reply) -> {
                    if (!isMainFrame || !isLocal(sourceOrigin)) return;
                    try { handleMessage(new JSONObject(message.getData()), reply); }
                    catch (Exception error) { toast("文件保存请求无效"); }
                });
        web.setDownloadListener((url, agent, disposition, mime, length) -> {
            String name = android.webkit.URLUtil.guessFileName(url, disposition, mime);
            web.evaluateJavascript("window.YuanAndroid?.saveUrl(" + JSONObject.quote(url) + "," + JSONObject.quote(name) + ")", null);
        });
        web.loadUrl(HOME);
    }

    private boolean isLocal(Uri uri) { return "https".equals(uri.getScheme()) && "appassets.androidplatform.net".equals(uri.getHost()) && (uri.getPort() == -1 || uri.getPort() == 443); }
    private void openExternal(Uri uri) {
        String scheme = uri.getScheme();
        if (!"https".equals(scheme) && !"http".equals(scheme) && !"mailto".equals(scheme)) return;
        try { startActivity(new Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)); }
        catch (ActivityNotFoundException error) { toast("没有可打开此链接的应用"); }
    }
    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }
    private void reply(JavaScriptReplyProxy proxy, String id, boolean ok, String error, String token) {
        try {
            JSONObject data = new JSONObject().put("id", id).put("ok", ok);
            if (error != null) data.put("error", error);
            if (token != null) data.put("token", token);
            proxy.postMessage(data.toString());
        } catch (Exception ignored) { /* Page may have closed while a system picker was open. */ }
    }
    private void handleMessage(JSONObject data, JavaScriptReplyProxy reply) throws Exception {
        String id = data.getString("id"), op = data.getString("op");
        if ("begin".equals(op)) {
            if (beginReply != null || exportToken != null) { reply(reply, id, false, "请先完成当前保存", null); return; }
            long size = data.getLong("size");
            if (size < 0 || size > MAX_EXPORT) { reply(reply, id, false, "单次导出最多支持 512 MB，请分批备份", null); return; }
            exportSize = size; exportWritten = 0;
            beginReply = reply; beginId = id;
            String name = data.optString("filename", "download").replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
            if (name.trim().isEmpty()) name = "download";
            String mime = data.optString("mime", "application/octet-stream").split(";")[0];
            if (!mime.matches("[a-zA-Z0-9.+_-]+/[a-zA-Z0-9.+_-]+")) mime = "application/octet-stream";
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType(mime).putExtra(Intent.EXTRA_TITLE, name);
            try { startActivityForResult(intent, SAVE_FILE); }
            catch (ActivityNotFoundException error) { reply(reply, id, false, "找不到系统文件选择器", null); beginReply = null; }
        } else {
            writer.execute(() -> {
                try {
                    if ("abort".equals(op)) { closeExport(true); }
                    else {
                        if (exportStream == null || !data.optString("token").equals(exportToken)) throw new IOException("保存任务已失效");
                        if ("chunk".equals(op)) {
                            String encoded = data.getString("data");
                            if (encoded.length() > 262144 || data.getLong("offset") != exportWritten) throw new IOException("文件分块顺序错误");
                            byte[] bytes = Base64.decode(encoded, Base64.NO_WRAP);
                            if (bytes.length > 196608 || exportWritten + bytes.length > exportSize) throw new IOException("文件大小不正确");
                            exportStream.write(bytes); exportWritten += bytes.length;
                        } else if ("end".equals(op)) {
                            if (exportWritten != exportSize) throw new IOException("文件未完整写入");
                            exportStream.flush(); closeExport(false);
                        } else throw new IOException("不支持的保存操作");
                    }
                    runOnUiThread(() -> reply(reply, id, true, null, null));
                } catch (Exception error) {
                    closeExport(true);
                    runOnUiThread(() -> reply(reply, id, false, "写入失败，请检查保存位置和剩余空间", null));
                }
            });
        }
    }
    private void closeExport(boolean delete) {
        if (exportStream != null) try { exportStream.close(); } catch (IOException ignored) { }
        if (delete && exportUri != null) try { DocumentsContract.deleteDocument(getContentResolver(), exportUri); } catch (Exception ignored) { }
        exportStream = null; exportToken = null; exportUri = null;
    }
    @Override protected void onActivityResult(int requestCode, int resultCode, Intent result) {
        super.onActivityResult(requestCode, resultCode, result);
        if (requestCode == PICK_FILE && fileCallback != null) {
            Uri[] uris = null;
            if (resultCode == RESULT_OK && result != null) {
                if (result.getClipData() != null) {
                    uris = new Uri[result.getClipData().getItemCount()];
                    for (int i = 0; i < uris.length; i++) uris[i] = result.getClipData().getItemAt(i).getUri();
                } else if (result.getData() != null) uris = new Uri[]{result.getData()};
            }
            fileCallback.onReceiveValue(uris); fileCallback = null;
        } else if (requestCode == SAVE_FILE && beginReply != null) {
            JavaScriptReplyProxy callback = beginReply; String id = beginId; beginReply = null;
            if (resultCode != RESULT_OK || result == null || result.getData() == null) { reply(callback, id, false, "cancelled", null); return; }
            exportUri = result.getData();
            try {
                exportStream = getContentResolver().openOutputStream(exportUri, "wt");
                if (exportStream == null) throw new IOException("No output stream");
                exportToken = UUID.randomUUID().toString();
                reply(callback, id, true, null, exportToken);
            } catch (Exception error) { closeExport(true); reply(callback, id, false, "无法创建文件", null); }
        }
    }
    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(requestCode, permissions, grants);
        if (requestCode == MEDIA_PERMISSION && mediaRequest != null) {
            PermissionRequest request = mediaRequest; mediaRequest = null;
            boolean allowed = grants.length > 0;
            for (int grant : grants) allowed &= grant == PackageManager.PERMISSION_GRANTED;
            if (allowed) request.grant(request.getResources()); else request.deny();
        }
    }
    private void closeVideo() {
        if (videoView == null) return;
        root.removeView(videoView); videoView = null; web.setVisibility(View.VISIBLE);
        if (videoCallback != null) videoCallback.onCustomViewHidden(); videoCallback = null;
    }
    @Override public void onBackPressed() {
        if (videoView != null) { closeVideo(); return; }
        web.evaluateJavascript("Boolean(window.YuanAndroid?.handleBack())", value -> {
            if (!"true".equals(value)) moveTaskToBack(true);
        });
    }
    @Override protected void onDestroy() {
        if (fileCallback != null) fileCallback.onReceiveValue(null);
        if (mediaRequest != null) mediaRequest.deny();
        writer.execute(() -> closeExport(true)); writer.shutdown();
        web.destroy(); super.onDestroy();
    }
}
