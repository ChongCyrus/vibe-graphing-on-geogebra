package com.ggb.classic5;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.Gravity;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * GeoGebra Classic 5 Android app.
 *
 * The full GeoGebra Classic 5 (web3d GWT build, the same build that the upstream
 * "classic" applet uses) is bundled inside assets/ and served to the WebView
 * through a tiny local https://appassets.androidplatform.net interceptor.
 *
 * On top of the stock Classic 5 functionality this activity adds:
 *   - SVG export (save / share),
 *   - PNG export,
 *   - .ggb open/save,
 *   - one-tap conversion of the current drawing to a TikZ code block
 *     (~~~tikz ... ~~~) using the SvgToTikzConverter engine from the
 *     obsidian-svg2tikz project.
 */
public class MainActivity extends Activity {

    private static final String ASSET_HOST = "appassets.androidplatform.net";
    private static final String ASSET_PREFIX = "/assets/";
    private static final String ASSET_BASE = "https://" + ASSET_HOST + ASSET_PREFIX;
    private static final String PAGE_PATH = "ggb.html";

    private static final int REQ_OPEN_GGB = 1001;
    private static final int REQ_CREATE_EXPORT = 1002;

    private static final int MAX_GGB_BYTES = 16 * 1024 * 1024; // 16 MB
    private static final int JS_CHUNK_SIZE = 512 * 1024;       // 512 KB per evaluateJavascript call

    private WebView webView;
    private TextView statusView;
    private Handler mainHandler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;

    private final ConcurrentHashMap<String, PendingCall> pending = new ConcurrentHashMap<>();
    private final AtomicInteger requestCounter = new AtomicInteger();

    private PendingWrite pendingWrite;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("ggb_classic5", MODE_PRIVATE);

        statusView = findViewById(R.id.status);
        webView = findViewById(R.id.webview);

        configureWebView();
        buildToolbar();
        reloadApplet();
    }

    // ------------------------------------------------------------------
    // WebView setup
    // ------------------------------------------------------------------

    private void configureWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setTextZoom(100);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setBlockNetworkLoads(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);

        webView.setBackgroundColor(0xffffffff);
        webView.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null);
        webView.setWebViewClient(new AssetServingClient());
        webView.addJavascriptInterface(new Bridge(), "AndroidBridge");
    }

    /**
     * The GeoGebra page is loaded from https://appassets.androidplatform.net/assets/ggb.html.
     * This interceptor serves every request under that origin directly from APK assets,
     * so the applet is fully offline and has a proper https origin (XHR / script / CSS work).
     */
    private class AssetServingClient extends WebViewClient {

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            return serveAsset(request.getUrl());
        }

        @SuppressWarnings("deprecation")
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
            return serveAsset(Uri.parse(url));
        }

        @Override
        public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
            if (failingUrl != null && failingUrl.startsWith(ASSET_BASE)) {
                setStatus("加载失败: " + description);
            }
        }
    }

    private WebResourceResponse serveAsset(Uri url) {
        if (url == null || !"https".equals(url.getScheme()) || !ASSET_HOST.equals(url.getHost())) {
            return null;
        }
        String path = url.getPath();
        if (path == null || !path.startsWith(ASSET_PREFIX)) {
            return null;
        }

        String rel = path.substring(ASSET_PREFIX.length());
        if (rel.isEmpty()) {
            rel = PAGE_PATH;
        }

        try {
            InputStream in = getAssets().open(rel, AssetManager.ACCESS_STREAMING);
            String mime = mimeFor(rel);
            String encoding = isTextMime(mime) ? "utf-8" : null;
            Map<String, String> headers = new HashMap<>();
            headers.put("Access-Control-Allow-Origin", "*");
            headers.put("Cache-Control", "no-cache");
            return new WebResourceResponse(mime, encoding, 200, "OK", headers, in);
        } catch (Exception e) {
            byte[] body = ("Asset not found: " + rel).getBytes(StandardCharsets.UTF_8);
            return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", null,
                    new ByteArrayInputStream(body));
        }
    }

    private static String mimeFor(String path) {
        String p = path.toLowerCase(Locale.US);
        if (p.endsWith(".html") || p.endsWith(".htm")) return "text/html";
        if (p.endsWith(".js")) return "text/javascript";
        if (p.endsWith(".css")) return "text/css";
        if (p.endsWith(".svg")) return "image/svg+xml";
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".gif")) return "image/gif";
        if (p.endsWith(".jpg") || p.endsWith(".jpeg")) return "image/jpeg";
        if (p.endsWith(".json")) return "application/json";
        if (p.endsWith(".woff")) return "font/woff";
        if (p.endsWith(".woff2")) return "font/woff2";
        if (p.endsWith(".ttf")) return "font/ttf";
        if (p.endsWith(".otf")) return "font/otf";
        if (p.endsWith(".wasm")) return "application/wasm";
        if (p.endsWith(".xml")) return "application/xml";
        if (p.endsWith(".txt")) return "text/plain";
        return "application/octet-stream";
    }

    private static boolean isTextMime(String mime) {
        return mime != null && (mime.startsWith("text/") || mime.contains("javascript")
                || mime.contains("json") || mime.contains("xml") || mime.contains("svg"));
    }

    // ------------------------------------------------------------------
    // Applet URL / toolbar
    // ------------------------------------------------------------------

    private void reloadApplet() {
        Uri.Builder b = new Uri.Builder()
                .scheme("https")
                .authority(ASSET_HOST)
                .path(ASSET_PREFIX + PAGE_PATH);

        b.appendQueryParameter("language", prefs.getString("language", "zh-CN"));
        b.appendQueryParameter("showMenuBar", "true");
        b.appendQueryParameter("showToolBar", "true");
        b.appendQueryParameter("showAlgebraInput", "true");
        b.appendQueryParameter("showToolBarHelp", "false");
        b.appendQueryParameter("showResetIcon", "false");
        b.appendQueryParameter("enableLabelDrags", "true");
        b.appendQueryParameter("enableShiftDragZoom", "true");
        b.appendQueryParameter("enableRightClick", "true");
        b.appendQueryParameter("showZoomButtons", "true");
        b.appendQueryParameter("showFullscreenButton", "false");
        b.appendQueryParameter("scale", prefs.getString("scale", "1"));
        b.appendQueryParameter("disableAutoScale", "false");
        b.appendQueryParameter("clickToLoad", "false");

        setStatus("正在加载 GeoGebra Classic 5（离线）…");
        webView.loadUrl(b.build().toString());
    }

    private void buildToolbar() {
        // Undo/redo are the original GeoGebra arrow icons, repositioned by
        // ggb.html above the bottom-right zoom panel (home button). The
        // native top bar only keeps menu/search, which are triggered through
        // the hidden GeoGebra buttons.
        LinearLayout fixedBar = findViewById(R.id.fixed_bar);
        String[] fixedLabels = {"菜单", "搜索"};
        Runnable[] fixedActions = {
                this::openGgbMenu,
                this::openGgbSearch
        };
        addButtons(fixedBar, fixedLabels, fixedActions);

        LinearLayout toolbar = findViewById(R.id.toolbar);
        String[] labels = {"新建", "打开", "保存", "SVG", "PNG", "TikZ", "设置"};
        Runnable[] actions = {
                this::newConstruction,
                this::openGgb,
                this::saveGgb,
                this::exportSvg,
                this::exportPng,
                this::exportTikz,
                this::showSettingsDialog
        };
        addButtons(toolbar, labels, actions);
    }

    private void addButtons(LinearLayout container, String[] labels, Runnable[] actions) {
        for (int i = 0; i < labels.length; i++) {
            Button btn = new Button(this);
            btn.setText(labels[i]);
            btn.setAllCaps(false);
            btn.setMinHeight(0);
            btn.setMinimumHeight(0);
            btn.setMinWidth(0);
            btn.setMinimumWidth(0);
            btn.setPadding(12, 4, 12, 4);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(3, 3, 3, 3);
            btn.setLayoutParams(lp);
            final Runnable action = actions[i];
            btn.setOnClickListener(v -> action.run());
            container.addView(btn);
        }
    }

    private void openGgbMenu() {
        evalJsBool("(window.__ggbClickRightButton ? window.__ggbClickRightButton('menu') : false)",
                "GeoGebra 菜单暂不可用");
    }

    private void openGgbSearch() {
        evalJsBool("(window.__ggbClickRightButton ? window.__ggbClickRightButton('search') : false)",
                "GeoGebra 搜索暂不可用");
    }

    /** Evaluate a JS expression expected to return a boolean and toast when it returns false. */
    private void evalJsBool(String js, String unavailableMessage) {
        webView.evaluateJavascript(js, value -> {
            if (!"true".equals(value)) {
                toast(unavailableMessage);
            }
        });
    }

    // ------------------------------------------------------------------
    // JavaScript bridge
    // ------------------------------------------------------------------

    private class Bridge {

        @JavascriptInterface
        public void onAppletReady() {
            mainHandler.post(() -> setStatus("GeoGebra Classic 5 就绪"));
        }

        @JavascriptInterface
        public void onAppletError(String message) {
            mainHandler.post(() -> setStatus("GeoGebra 加载失败: " + message));
        }

        @JavascriptInterface
        public void onResult(String requestId, boolean ok, String payload, String error) {
            mainHandler.post(() -> {
                PendingCall call = pending.remove(requestId);
                if (call != null) {
                    mainHandler.removeCallbacks(call.timeout);
                    call.callback.done(ok, payload, error);
                }
            });
        }
    }

    private interface JsCallback {
        void done(boolean ok, String payload, String error);
    }

    private static class PendingCall {
        final JsCallback callback;
        final Runnable timeout;

        PendingCall(JsCallback callback, Runnable timeout) {
            this.callback = callback;
            this.timeout = timeout;
        }
    }

    private static class PendingWrite {
        final String mime;
        final byte[] data;

        PendingWrite(String mime, byte[] data) {
            this.mime = mime;
            this.data = data;
        }
    }

    /** Evaluate a JS expression like window.__ggbGetSvg('__ID__'); __ID__ is replaced. */
    private void callWithId(String jsTemplate, long timeoutMs, JsCallback callback) {
        String id = "r" + requestCounter.incrementAndGet();
        Runnable timeout = () -> {
            PendingCall call = pending.remove(id);
            if (call != null) {
                call.callback.done(false, null, "操作超时");
            }
        };
        pending.put(id, new PendingCall(callback, timeout));
        mainHandler.postDelayed(timeout, timeoutMs);
        String js = jsTemplate.replace("__ID__", id);
        webView.evaluateJavascript(js, null);
    }

    // ------------------------------------------------------------------
    // GeoGebra operations
    // ------------------------------------------------------------------

    private void newConstruction() {
        webView.evaluateJavascript("window.__ggbReset();", null);
        setStatus("已新建作图");
    }

    private void openGgb() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*");
        try {
            startActivityForResult(intent, REQ_OPEN_GGB);
        } catch (Exception e) {
            toast("无法打开文件选择器: " + e.getMessage());
        }
    }

    private void loadGgbBytes(byte[] data) {
        final String b64 = Base64.encodeToString(data, Base64.NO_WRAP);
        setStatus("正在载入 .ggb 文件…");

        String id = "r" + requestCounter.incrementAndGet();
        Runnable timeout = () -> {
            PendingCall call = pending.remove(id);
            if (call != null) {
                call.callback.done(false, null, "载入 .ggb 超时");
            }
        };
        PendingCall call = new PendingCall((ok, payload, error) -> {
            if (ok) {
                setStatus("已载入 .ggb 文件");
                toast("已载入 .ggb 文件");
            } else {
                setStatus("载入失败: " + error);
                toast("载入失败: " + error);
            }
        }, timeout);
        pending.put(id, call);
        mainHandler.postDelayed(timeout, 90000);

        webView.evaluateJavascript("window.__ggbSetGgbBegin('" + id + "')", null);
        for (int start = 0; start < b64.length(); start += JS_CHUNK_SIZE) {
            String chunk = b64.substring(start, Math.min(b64.length(), start + JS_CHUNK_SIZE));
            webView.evaluateJavascript("window.__ggbSetGgbChunk('" + id + "', "
                    + JSONObject.quote(chunk) + ")", null);
        }
        webView.evaluateJavascript("window.__ggbSetGgbEnd('" + id + "')", null);
    }

    private void saveGgb() {
        setStatus("正在导出 .ggb…");
        callWithId("window.__ggbGetGgb('__ID__')", 90000, (ok, payload, error) -> {
            if (!ok) {
                setStatus(".ggb 导出失败: " + error);
                toast(".ggb 导出失败: " + error);
                return;
            }
            byte[] bytes = b64ToBytes(payload);
            if (bytes == null || bytes.length == 0) {
                toast("GeoGebra 返回了空的 .ggb 数据");
                return;
            }
            setStatus("已生成 .ggb，请选择保存位置");
            saveBytes("GeoGebra-" + stamp() + ".ggb", "application/octet-stream", bytes);
        });
    }

    private void exportSvg() {
        setStatus("正在导出 SVG…");
        callWithId("window.__ggbGetSvg('__ID__')", 60000, (ok, payload, error) -> {
            if (!ok) {
                setStatus("SVG 导出失败: " + error);
                toast("SVG 导出失败: " + error);
                return;
            }
            // exportSVG(callback) now returns the SVG source directly.
            String svg = payload;
            if (svg == null || !svg.contains("<svg")) {
                setStatus("SVG 导出失败: 未返回 SVG 内容");
                toast("GeoGebra 未返回 SVG 内容。请先绘制图形。");
                return;
            }
            setStatus("SVG 已生成，请选择保存位置");
            saveBytes("GeoGebra-" + stamp() + ".svg", "image/svg+xml",
                    svg.getBytes(StandardCharsets.UTF_8));
        });
    }

    private void exportPng() {
        setStatus("正在导出 PNG…");
        callWithId("window.__ggbGetPng('__ID__')", 60000, (ok, payload, error) -> {
            if (!ok) {
                setStatus("PNG 导出失败: " + error);
                toast("PNG 导出失败: " + error);
                return;
            }
            byte[] bytes = b64ToBytes(payload);
            if (bytes == null || bytes.length == 0) {
                toast("GeoGebra 返回了空的 PNG 数据");
                return;
            }
            setStatus("PNG 已生成，请选择保存位置");
            saveBytes("GeoGebra-" + stamp() + ".png", "image/png", bytes);
        });
    }

    private void exportTikz() {
        setStatus("正在生成 TikZ 代码…");

        JSONObject settings = new JSONObject();
        try {
            settings.put("outputMode", prefs.getString("outputMode", "codeblock"));
            settings.put("outputUnit", prefs.getString("outputUnit", "cm"));
            settings.put("scale", Double.parseDouble(prefs.getString("scale", "1")));
            settings.put("roundNumber", Integer.parseInt(prefs.getString("roundNumber", "4")));
            settings.put("reverseY", prefs.getBoolean("reverseY", true));
            settings.put("indent", prefs.getBoolean("indent", true));
            settings.put("ignoreText", prefs.getBoolean("ignoreText", false));
            settings.put("markings", prefs.getString("markings", "arrows"));
            settings.put("arrowStyle", prefs.getString("arrowStyle", "latex"));
        } catch (Exception e) {
            toast("设置解析失败: " + e.getMessage());
            return;
        }

        String js = "window.__ggbGetTikz('__ID__', " + JSONObject.quote(settings.toString()) + ")";
        callWithId(js, 120000, (ok, payload, error) -> {
            if (!ok) {
                setStatus("TikZ 转换失败: " + error);
                toast("TikZ 转换失败: " + error);
                return;
            }
            if (payload == null || payload.trim().isEmpty()) {
                toast("TikZ 转换结果为空");
                return;
            }
            setStatus("TikZ 代码已生成");
            showTikzDialog(payload);
        });
    }

    // ------------------------------------------------------------------
    // TikZ dialog
    // ------------------------------------------------------------------

    private void showTikzDialog(String code) {
        ScrollView scroll = new ScrollView(this);
        TextView tv = new TextView(this);
        tv.setTextIsSelectable(true);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextSize(11);
        tv.setPadding(24, 24, 24, 24);
        tv.setText(code);
        scroll.addView(tv, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("TikZ 代码块（可直接粘贴到 Obsidian / LaTeX）")
                .setView(scroll)
                .setPositiveButton("复制", (d, w) -> copyToClipboard(code))
                .setNeutralButton("保存", (d, w) -> saveBytes(
                        "GeoGebra-" + stamp() + ".md", "text/markdown",
                        code.getBytes(StandardCharsets.UTF_8)))
                .setNegativeButton("关闭", null)
                .create();
        dialog.show();
    }

    private void copyToClipboard(String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("TikZ code", text));
            toast("已复制 TikZ 代码");
        }
    }

    // ------------------------------------------------------------------
    // Settings dialog
    // ------------------------------------------------------------------

    private void showSettingsDialog() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(24, 16, 24, 16);
        scroll.addView(panel, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        Spinner language = spinner(panel, "界面语言",
                new String[]{"zh-CN", "en"},
                new String[]{"简体中文", "English"},
                prefs.getString("language", "zh-CN"));

        Spinner outputMode = spinner(panel, "TikZ 输出模式",
                new String[]{"codeblock", "standalone", "figonly", "codeonly"},
                new String[]{"TikZ 代码块 (~~~tikz)", "独立 LaTeX 文档", "仅 tikzpicture", "仅路径代码"},
                prefs.getString("outputMode", "codeblock"));

        Spinner outputUnit = spinner(panel, "TikZ 单位",
                new String[]{"cm", "mm", "in", "pt", "px"},
                new String[]{"cm", "mm", "in", "pt", "px"},
                prefs.getString("outputUnit", "cm"));

        EditText scale = editText(panel, "缩放系数 scale（例如 1 或 0.8）",
                prefs.getString("scale", "1"));

        EditText roundNumber = editText(panel, "坐标小数位数（1-6）",
                prefs.getString("roundNumber", "4"));

        CheckBox reverseY = checkBox(panel, "反转 Y 轴（SVG 原点在左上，TikZ 在左下）",
                prefs.getBoolean("reverseY", true));
        CheckBox indent = checkBox(panel, "缩进输出",
                prefs.getBoolean("indent", true));
        CheckBox ignoreText = checkBox(panel, "忽略文字元素",
                prefs.getBoolean("ignoreText", false));

        Spinner markings = spinner(panel, "箭头标记处理",
                new String[]{"arrows", "ignore"},
                new String[]{"转换为 TikZ 箭头", "忽略箭头"},
                prefs.getString("markings", "arrows"));

        Spinner arrowStyle = spinner(panel, "箭头样式",
                new String[]{"latex", "stealth", "to", ">"},
                new String[]{"latex", "stealth", "to", ">"},
                prefs.getString("arrowStyle", "latex"));

        new AlertDialog.Builder(this)
                .setTitle("GeoGebra Classic 5 设置")
                .setView(scroll)
                .setPositiveButton("保存并重新加载", (d, w) -> {
                    try {
                        prefs.edit()
                                .putString("language", selected(language))
                                .putString("outputMode", selected(outputMode))
                                .putString("outputUnit", selected(outputUnit))
                                .putString("scale", normalizeScale(scale.getText().toString()))
                                .putString("roundNumber", normalizeRound(roundNumber.getText().toString()))
                                .putBoolean("reverseY", reverseY.isChecked())
                                .putBoolean("indent", indent.isChecked())
                                .putBoolean("ignoreText", ignoreText.isChecked())
                                .putString("markings", selected(markings))
                                .putString("arrowStyle", selected(arrowStyle))
                                .apply();
                    } catch (Exception e) {
                        toast("设置保存失败: " + e.getMessage());
                        return;
                    }
                    toast("设置已保存，正在重新加载 GeoGebra…");
                    reloadApplet();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private Spinner spinner(LinearLayout panel, String label, String[] values, String[] labels, String current) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setPadding(0, 12, 0, 4);
        panel.addView(tv);

        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        int idx = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(current)) {
                idx = i;
                break;
            }
        }
        spinner.setSelection(idx);
        panel.addView(spinner, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        spinner.setTag(values);
        return spinner;
    }

    private EditText editText(LinearLayout panel, String label, String current) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setPadding(0, 12, 0, 4);
        panel.addView(tv);

        EditText et = new EditText(this);
        et.setText(current);
        et.setSingleLine(true);
        panel.addView(et, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return et;
    }

    private CheckBox checkBox(LinearLayout panel, String label, boolean checked) {
        CheckBox cb = new CheckBox(this);
        cb.setText(label);
        cb.setChecked(checked);
        cb.setPadding(0, 12, 0, 4);
        panel.addView(cb);
        return cb;
    }

    private String selected(Spinner spinner) {
        String[] values = (String[]) spinner.getTag();
        return values[spinner.getSelectedItemPosition()];
    }

    private String normalizeScale(String text) {
        try {
            double v = Double.parseDouble(text.trim());
            if (v <= 0.01) v = 0.01;
            if (v > 10) v = 10;
            return String.valueOf(v);
        } catch (Exception e) {
            return "1";
        }
    }

    private String normalizeRound(String text) {
        try {
            int v = Integer.parseInt(text.trim());
            if (v < 1) v = 1;
            if (v > 6) v = 6;
            return String.valueOf(v);
        } catch (Exception e) {
            return "4";
        }
    }

    // ------------------------------------------------------------------
    // File save / open helpers
    // ------------------------------------------------------------------

    private void saveBytes(String suggestedName, String mime, byte[] data) {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(mime)
                .putExtra(Intent.EXTRA_TITLE, suggestedName);
        pendingWrite = new PendingWrite(mime, data);
        try {
            startActivityForResult(intent, REQ_CREATE_EXPORT);
        } catch (Exception e) {
            pendingWrite = null;
            toast("无法打开保存对话框: " + e.getMessage());
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK) {
            return;
        }

        if (requestCode == REQ_OPEN_GGB && data != null && data.getData() != null) {
            Uri uri = data.getData();
            readAndLoadGgb(uri);
            return;
        }

        if (requestCode == REQ_CREATE_EXPORT && data != null && data.getData() != null) {
            Uri uri = data.getData();
            PendingWrite write = pendingWrite;
            pendingWrite = null;
            if (write == null) {
                return;
            }
            writePending(uri, write);
        }
    }

    private void readAndLoadGgb(Uri uri) {
        setStatus("正在读取 .ggb 文件…");
        new Thread(() -> {
            try {
                byte[] bytes = readUri(uri, MAX_GGB_BYTES);
                mainHandler.post(() -> loadGgbBytes(bytes));
            } catch (Exception e) {
                String msg = e.getMessage();
                mainHandler.post(() -> {
                    setStatus("打开 .ggb 失败");
                    toast("打开 .ggb 失败: " + msg);
                });
            }
        }).start();
    }

    private void writePending(Uri uri, PendingWrite write) {
        setStatus("正在保存…");
        new Thread(() -> {
            try {
                try (java.io.OutputStream out = getContentResolver().openOutputStream(uri)) {
                    if (out == null) {
                        throw new java.io.IOException("无法打开输出流");
                    }
                    out.write(write.data);
                    out.flush();
                }
                final String name = uri.getLastPathSegment();
                mainHandler.post(() -> {
                    toast("已保存: " + name);
                    setStatus("已保存: " + name);
                });
            } catch (Exception e) {
                final String msg = e.getMessage();
                mainHandler.post(() -> {
                    toast("保存失败: " + msg);
                    setStatus("保存失败: " + msg);
                });
            }
        }).start();
    }

    private byte[] readUri(Uri uri, int maxBytes) throws Exception {
        try (InputStream in = getContentResolver().openInputStream(uri);
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            if (in == null) {
                throw new java.io.IOException("无法读取所选文件");
            }
            byte[] buf = new byte[65536];
            int n;
            int total = 0;
            while ((n = in.read(buf)) != -1) {
                total += n;
                if (total > maxBytes) {
                    throw new java.io.IOException("文件过大（超过 16 MB）");
                }
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        }
    }

    // ------------------------------------------------------------------
    // Base64 / text helpers
    // ------------------------------------------------------------------

    private static String b64ToUtf8(String b64) {
        if (b64 == null) return null;
        if (b64.startsWith("data:") && b64.contains(",")) {
            b64 = b64.substring(b64.indexOf(",") + 1);
        }
        try {
            return new String(Base64.decode(b64, Base64.DEFAULT), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] b64ToBytes(String b64) {
        if (b64 == null) return null;
        if (b64.startsWith("data:") && b64.contains(",")) {
            b64 = b64.substring(b64.indexOf(",") + 1);
        }
        try {
            return Base64.decode(b64, Base64.DEFAULT);
        } catch (Exception e) {
            return null;
        }
    }

    private static String stamp() {
        return new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
    }

    // ------------------------------------------------------------------
    // UI helpers / lifecycle
    // ------------------------------------------------------------------

    private void setStatus(String text) {
        if (statusView != null) {
            statusView.setText(text);
        }
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (webView != null) webView.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.removeAllViews();
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
    }
}
