package com.ggb.classic5;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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
    private boolean pendingWriteIsGgbSave = false;
    private Uri currentGgbUri = null;

    // LLM chat sessions
    private final ArrayList<ChatSession> chatSessions = new ArrayList<>();
    private ChatSession currentChatSession;
    private ChatMessage currentAssistantMessage;
    private ChatMessage currentThinkingMessage;
    private ChatAdapter chatAdapter;
    private ListView chatListView;
    private TextView chatSessionTitleView;
    private TextView chatThinkingHeaderView;
    private AtomicBoolean chatRunning = new AtomicBoolean(false);
    private AtomicBoolean chatStopped = new AtomicBoolean(false);
    private AtomicBoolean chatRunStopped = new AtomicBoolean(false);
    private long chatRunId = 0;
    private Dialog chatDialog;
    private boolean chatPinnedToBottom = true;
    private boolean chatRefreshScheduled = false;
    private final Runnable chatRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            chatRefreshScheduled = false;
            if (chatAdapter != null && chatListView != null) {
                chatAdapter.notifyDataSetChanged();
                scrollChatToBottom();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("ggb_classic5", MODE_PRIVATE);
        loadChatSessions();

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
        // The applet page is always the base page; a fresh reload must not
        // leave old applet copies in the WebView history.
        webView.clearHistory();
    }

    private void buildToolbar() {
        // Undo/redo are the original GeoGebra arrow icons, repositioned by
        // ggb.html above the bottom-right zoom panel (home button). The
        // native top bar only keeps menu/search, which are triggered through
        // the hidden GeoGebra buttons.
        LinearLayout fixedBar = findViewById(R.id.fixed_bar);
        String[] fixedLabels = {"返回", "菜单", "搜索"};
        Runnable[] fixedActions = {
                this::back,
                this::openGgbMenu,
                this::openGgbSearch
        };
        addButtons(fixedBar, fixedLabels, fixedActions);

        LinearLayout toolbar = findViewById(R.id.toolbar);
        String[] labels = {"新建", "打开", "保存", "另存为", "SVG", "PNG", "TikZ", "LaTeX", "脚本", "LLM", "对象", "设置"};
        Runnable[] actions = {
                this::newConstruction,
                this::openGgb,
                this::saveGgb,
                this::saveGgbAs,
                this::exportSvg,
                this::exportPng,
                this::exportTikz,
                this::showLatexDialog,
                this::showScriptDialog,
                this::showLlmChatDialog,
                this::showObjectManagerDialog,
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

    private void back() {
        // If the WebView itself navigated (e.g. a material page opened in the
        // same WebView), a browser-style back is the correct "previous page".
        if (webView.canGoBack()) {
            webView.goBack();
            return;
        }
        // Otherwise let the in-app page stack (main / search / material) go back.
        evalJsBool("(window.__ggbBack ? window.__ggbBack() : false)",
                "返回不可用");
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
        currentGgbUri = null;
        setStatus("已新建作图");
    }

    private void openGgb() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
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
        if (currentGgbUri == null) {
            saveGgbAs();
            return;
        }
        setStatus("正在保存 .ggb…");
        callWithId("window.__ggbGetGgb('__ID__')", 90000, (ok, payload, error) -> {
            if (!ok) {
                setStatus(".ggb 保存失败: " + error);
                toast(".ggb 保存失败: " + error);
                return;
            }
            byte[] bytes = b64ToBytes(payload);
            if (bytes == null || bytes.length == 0) {
                toast("GeoGebra 返回了空的 .ggb 数据");
                return;
            }
            setStatus("正在写入当前 .ggb 文件…");
            writePending(currentGgbUri, new PendingWrite("application/octet-stream", bytes));
        });
    }

    private void saveGgbAs() {
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
            saveBytesAsCurrentGgb("GeoGebra-" + stamp() + ".ggb", "application/octet-stream", bytes);
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

    private void showLatexDialog() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(24, 16, 24, 16);
        scroll.addView(panel, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView tv = new TextView(this);
        tv.setText("输入 LaTeX 公式，将转换为 GeoGebra 命令并执行。\n"
                + "例如：\\frac{1}{2} + \\sqrt{x}，x^2 + \\sin(x)");
        tv.setPadding(0, 8, 0, 8);
        panel.addView(tv);

        EditText input = new EditText(this);
        input.setHint("\\frac{a}{b} \\cdot \\sqrt{x}");
        input.setSingleLine(false);
        input.setMinLines(2);
        panel.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(this)
                .setTitle("LaTeX → GeoGebra")
                .setView(scroll)
                .setPositiveButton("执行", (d, w) -> {
                    String latex = input.getText().toString().trim();
                    if (latex.isEmpty()) {
                        toast("请输入 LaTeX 内容");
                        return;
                    }
                    evalJsBool("(window.__ggbEvalLaTeX ? window.__ggbEvalLaTeX("
                            + JSONObject.quote(latex) + ") : false)",
                            "LaTeX 执行失败（GeoGebra 未就绪）");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showScriptDialog() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(24, 16, 24, 16);
        scroll.addView(panel, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        Spinner mode = spinner(panel, "脚本类型",
                new String[]{"ggb", "js"},
                new String[]{"GGB 命令脚本（每行一条 GeoGebra 命令）", "JavaScript（可调用 window.ggbApi）"},
                "ggb");

        EditText input = new EditText(this);
        input.setHint("A=(1,2)\nB=(3,4)\nf(x)=x^2");
        input.setSingleLine(false);
        input.setMinLines(4);
        input.setHorizontallyScrolling(false);
        panel.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(this)
                .setTitle("脚本执行")
                .setView(scroll)
                .setPositiveButton("执行", (d, w) -> {
                    String script = input.getText().toString();
                    if (script.trim().isEmpty()) {
                        toast("请输入脚本内容");
                        return;
                    }
                    if ("js".equals(selected(mode))) {
                        webView.evaluateJavascript(script, value ->
                                showScriptResult(value == null ? "undefined" : value));
                    } else {
                        evalJsBool("(window.__ggbRunGgbScript ? window.__ggbRunGgbScript("
                                + JSONObject.quote(script) + ") : false)",
                                "GGB 脚本执行失败（GeoGebra 未就绪）");
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showScriptResult(String value) {
        ScrollView scroll = new ScrollView(this);
        TextView tv = new TextView(this);
        tv.setTextIsSelectable(true);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setPadding(24, 24, 24, 24);
        tv.setText(value);
        scroll.addView(tv, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this)
                .setTitle("JavaScript 返回")
                .setView(scroll)
                .setPositiveButton("关闭", null)
                .show();
    }

    // ------------------------------------------------------------------
    // Object manager (clear all / marquee / multi-select / batch style)
    // ------------------------------------------------------------------

    private void showObjectManagerDialog() {
        final Dialog dialog = new Dialog(this);
        dialog.setTitle("对象管理");

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(10, 10, 10, 10);
        root.setBackgroundColor(Color.rgb(248, 248, 248));

        final TextView countView = new TextView(this);
        countView.setPadding(8, 4, 8, 8);
        countView.setTextColor(Color.rgb(80, 80, 80));
        root.addView(countView);

        final ListView listView = new ListView(this);
        listView.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
        final ArrayList<String> objectNames = new ArrayList<>();
        final ArrayAdapter<String> listAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_multiple_choice);
        listView.setAdapter(listAdapter);
        root.addView(listView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        final Runnable refresh = () -> loadObjectList(listView, objectNames, listAdapter, countView);

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        Button allBtn = styleTopButton("全选");
        Button noneBtn = styleTopButton("全不选");
        Button refreshBtn = styleTopButton("刷新");
        allBtn.setOnClickListener(v -> {
            for (int i = 0; i < listAdapter.getCount(); i++) {
                listView.setItemChecked(i, true);
            }
        });
        noneBtn.setOnClickListener(v -> {
            listView.clearChoices();
            listAdapter.notifyDataSetChanged();
        });
        refreshBtn.setOnClickListener(v -> refresh.run());
        row1.addView(allBtn);
        row1.addView(noneBtn);
        row1.addView(refreshBtn);
        root.addView(row1);

        HorizontalScrollView row2Scroll = new HorizontalScrollView(this);
        row2Scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2Scroll.addView(row2, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        Button marqueeBtn = styleTopButton("框选模式");
        Button deleteMarqueeBtn = styleTopButton("删除框选对象");
        Button clearAllBtn = styleTopButton("清空所有对象");
        marqueeBtn.setOnClickListener(v -> {
            webView.evaluateJavascript(
                    "window.__ggbSetMode ? window.__ggbSetMode(77) : false", value -> {
                        if (!"true".equals(value)) {
                            toast("无法切换到框选模式（GeoGebra 未就绪）");
                        }
                    });
            dialog.dismiss();
            toast("已切换到框选模式：在绘图区拖拽一个矩形框选对象；之后重新打开“对象”可删除框选对象。");
        });
        deleteMarqueeBtn.setOnClickListener(v -> {
            new AlertDialog.Builder(this)
                    .setTitle("删除框选对象")
                    .setMessage("将删除当前绘图区中所有被选中的对象（包括点选和框选）。确定？")
                    .setPositiveButton("删除", (d, w) -> {
                        webView.evaluateJavascript(
                                "window.__ggbDeleteSelected ? window.__ggbDeleteSelected() : false",
                                value -> {
                                    toast("true".equals(value) ? "已发送删除命令" : "删除命令发送失败");
                                    refresh.run();
                                });
                    })
                    .setNegativeButton("取消", null)
                    .show();
        });
        clearAllBtn.setOnClickListener(v -> {
            new AlertDialog.Builder(this)
                    .setTitle("清空所有对象")
                    .setMessage("将删除当前作图页面中的全部对象（保留坐标轴、网格与视图设置）。确定？")
                    .setPositiveButton("清空", (d, w) -> {
                        webView.evaluateJavascript(
                                "window.__ggbClearAll ? window.__ggbClearAll() : false",
                                value -> {
                                    toast("true".equals(value) ? "已清空所有对象" : "清空失败（GeoGebra 未就绪）");
                                    refresh.run();
                                });
                    })
                    .setNegativeButton("取消", null)
                    .show();
        });
        row2.addView(marqueeBtn);
        row2.addView(deleteMarqueeBtn);
        row2.addView(clearAllBtn);
        root.addView(row2Scroll);

        HorizontalScrollView row3Scroll = new HorizontalScrollView(this);
        row3Scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout row3 = new LinearLayout(this);
        row3.setOrientation(LinearLayout.HORIZONTAL);
        row3Scroll.addView(row3, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        Button deleteCheckedBtn = styleTopButton("删除勾选");
        Button styleCheckedBtn = styleTopButton("批量设置属性");
        deleteCheckedBtn.setOnClickListener(v -> {
            ArrayList<String> checked = checkedObjectNames(listView, objectNames);
            if (checked.isEmpty()) {
                toast("请先在列表中勾选对象");
                return;
            }
            StringBuilder script = new StringBuilder();
            for (String name : checked) {
                script.append("Delete(").append(name).append(")\n");
            }
            new AlertDialog.Builder(this)
                    .setTitle("删除勾选对象")
                    .setMessage("确定删除勾选的 " + checked.size() + " 个对象？")
                    .setPositiveButton("删除", (d, w) ->
                            runGgbScriptAndRefresh(script.toString(), refresh))
                    .setNegativeButton("取消", null)
                    .show();
        });
        styleCheckedBtn.setOnClickListener(v -> {
            ArrayList<String> checked = checkedObjectNames(listView, objectNames);
            if (checked.isEmpty()) {
                toast("请先在列表中勾选对象");
                return;
            }
            showBatchPropertiesDialog(checked, refresh);
        });
        row3.addView(deleteCheckedBtn);
        row3.addView(styleCheckedBtn);
        root.addView(row3Scroll);

        dialog.setContentView(root);
        dialog.getWindow().setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        dialog.show();
        refresh.run();
    }

    private ArrayList<String> checkedObjectNames(ListView listView, ArrayList<String> objectNames) {
        ArrayList<String> out = new ArrayList<>();
        for (int i = 0; i < objectNames.size(); i++) {
            if (listView.isItemChecked(i)) {
                out.add(objectNames.get(i));
            }
        }
        return out;
    }

    private void loadObjectList(ListView listView, ArrayList<String> objectNames,
                                ArrayAdapter<String> adapter, TextView countView) {
        objectNames.clear();
        adapter.clear();
        adapter.notifyDataSetChanged();
        webView.evaluateJavascript(
                "window.__ggbListObjects ? window.__ggbListObjects() : '[]'", value -> {
                    try {
                        JSONArray arr = new JSONArray(unwrapJsString(value));
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject o = arr.optJSONObject(i);
                            if (o == null) continue;
                            String name = o.optString("name", "");
                            String type = o.optString("type", "");
                            if (name.isEmpty()) continue;
                            objectNames.add(name);
                            adapter.add(type.isEmpty() ? name : name + "  (" + type + ")");
                        }
                    } catch (Exception e) {
                        adapter.add("读取对象列表失败: " + e.getMessage());
                    }
                    countView.setText("共 " + objectNames.size() + " 个对象；勾选后可删除或批量设置属性。");
                    adapter.notifyDataSetChanged();
                });
    }

    private void runGgbScriptAndRefresh(String script, Runnable after) {
        webView.evaluateJavascript(
                "window.__ggbRunGgbScriptWithLog ? window.__ggbRunGgbScriptWithLog("
                        + JSONObject.quote(script) + ") : '{}'",
                value -> {
                    int executed = 0;
                    try {
                        JSONObject res = new JSONObject(unwrapJsString(value));
                        executed = res.optInt("executed", 0);
                    } catch (Exception ignored) {
                    }
                    toast("已执行 " + executed + " 条命令");
                    if (after != null) after.run();
                });
    }

    private void showBatchPropertiesDialog(ArrayList<String> checkedNames, Runnable after) {
        ScrollView scroll = new ScrollView(this);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(24, 16, 24, 16);
        scroll.addView(panel, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        EditText color = editText(panel, "颜色（如 red、blue 或 #FF6600；留空不修改）", "");
        EditText filling = editText(panel, "填充虚实 0-1（0=空心，1=实心；留空不修改）", "");
        EditText thickness = editText(panel, "线宽 1-13（留空不修改）", "");
        EditText pointSize = editText(panel, "点大小 1-9（留空不修改）", "");
        Spinner label = spinner(panel, "显示标签",
                new String[]{"keep", "true", "false"},
                new String[]{"不变", "显示", "隐藏"},
                "keep");

        new AlertDialog.Builder(this)
                .setTitle("批量设置属性（勾选 " + checkedNames.size() + " 个对象）")
                .setView(scroll)
                .setPositiveButton("应用", (d, w) -> {
                    StringBuilder script = new StringBuilder();
                    String col = color.getText().toString().trim();
                    String fil = filling.getText().toString().trim();
                    String th = thickness.getText().toString().trim();
                    String ps = pointSize.getText().toString().trim();
                    String lab = selected(label);

                    for (String name : checkedNames) {
                        if (!col.isEmpty()) {
                            script.append("SetColor(").append(name).append(",")
                                    .append(JSONObject.quote(col)).append(")\n");
                        }
                        if (!fil.isEmpty()) {
                            script.append("SetFilling(").append(name).append(",").append(fil).append(")\n");
                        }
                        if (!th.isEmpty()) {
                            script.append("SetLineThickness(").append(name).append(",").append(th).append(")\n");
                        }
                        if (!ps.isEmpty()) {
                            script.append("SetPointSize(").append(name).append(",").append(ps).append(")\n");
                        }
                        if (!"keep".equals(lab)) {
                            script.append("ShowLabel(").append(name).append(",").append(lab).append(")\n");
                        }
                    }
                    if (script.length() == 0) {
                        toast("没有填写任何属性，未做修改");
                        return;
                    }
                    runGgbScriptAndRefresh(script.toString(), after);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ------------------------------------------------------------------
    // LLM agent chat
    // ------------------------------------------------------------------

    private static final String LLM_PROTOCOL =
            "\n\n你是一个 GeoGebra 操作智能体。你必须只输出一个 JSON 对象，格式如下：\n"
            + "{\"thinking\":\"你的简短思考过程\",\"action\":\"ggb|js|done\","
            + "\"code\":\"要执行的 GGB 命令（每行一条）或 JavaScript 代码\",\"done\":false}\n"
            + "规则：\n"
            + "1. 需要操作 GeoGebra 时，action 用 \"ggb\"，code 中每行一条 GeoGebra 命令。\n"
            + "2. 需要页面内 JavaScript 时，action 用 \"js\"，code 是 JS 代码（可调用 window.ggbApi）。\n"
            + "3. 已经完成用户需求或已经无法继续时，action 用 \"done\" 且 done 为 true。\n"
            + "4. 每轮只能输出一个 JSON 对象，不要输出多余文本。\n";

    private static final String TITLE_PROMPT =
            "你是会话命名助手。请根据用户需求，生成一个不超过 12 个汉字的会话标题。"
            + "只输出标题文本，不要引号，不要解释。";

    private static final String LLM_DEFAULT_URL = "https://api.deepseek.com/chat/completions";
    private static final String LLM_DEFAULT_KEY = "sk-a0576e6832264e9fbd13d52114575196";
    private static final String LLM_DEFAULT_MODEL = "deepseek-v4-flash";

    // ------------------------------------------------------------------
    // Chat data model
    // ------------------------------------------------------------------

    private static class ChatMessage {
        String role;   // user | assistant | thinking | system | tool
        String text;
        boolean hidden;

        ChatMessage(String role, String text) {
            this.role = role;
            this.text = text;
        }

        JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("role", role);
                o.put("text", text);
                o.put("hidden", hidden);
            } catch (Exception ignored) {}
            return o;
        }

        static ChatMessage fromJson(JSONObject o) {
            ChatMessage m = new ChatMessage(o.optString("role", "user"), o.optString("text", ""));
            m.hidden = o.optBoolean("hidden", false);
            return m;
        }
    }

    private static class ChatSession {
        String id;
        String title;
        final ArrayList<ChatMessage> messages = new ArrayList<>();

        ChatSession(String id, String title) {
            this.id = id;
            this.title = title;
        }

        JSONObject toJson() {
            JSONObject o = new JSONObject();
            JSONArray arr = new JSONArray();
            try {
                o.put("id", id);
                o.put("title", title);
                for (ChatMessage m : messages) {
                    arr.put(m.toJson());
                }
                o.put("messages", arr);
            } catch (Exception ignored) {}
            return o;
        }

        static ChatSession fromJson(JSONObject o) {
            ChatSession s = new ChatSession(o.optString("id", UUID.randomUUID().toString()),
                    o.optString("title", "会话"));
            JSONArray arr = o.optJSONArray("messages");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject mo = arr.optJSONObject(i);
                    if (mo != null) {
                        s.messages.add(ChatMessage.fromJson(mo));
                    }
                }
            }
            return s;
        }
    }

    private class ChatAdapter extends BaseAdapter {
        private final ChatSession session;
        private boolean showThinking = true;

        ChatAdapter(ChatSession session) {
            this.session = session;
        }

        /**
         * Global toggle. It writes the visibility directly onto every thinking
         * message, so a later per-round toggle simply overrides this global
         * setting for the round the user clicked.
         */
        void setShowThinking(boolean show) {
            showThinking = show;
            for (ChatMessage m : session.messages) {
                if ("thinking".equals(m.role)) {
                    m.hidden = !show;
                }
            }
            notifyDataSetChanged();
        }

        private List<ChatMessage> visible() {
            ArrayList<ChatMessage> out = new ArrayList<>();
            for (ChatMessage m : session.messages) {
                if ("thinking".equals(m.role) && m.hidden) continue;
                out.add(m);
            }
            return out;
        }

        @Override
        public int getCount() {
            return visible().size();
        }

        @Override
        public Object getItem(int position) {
            return visible().get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ChatMessage m = visible().get(position);
            TextView tv = (TextView) convertView;
            if (tv == null) {
                tv = new TextView(MainActivity.this);
                tv.setPadding(18, 14, 18, 14);
            }
            tv.setText(m.text);
            tv.setTextSize(14);
            tv.setTypeface(Typeface.DEFAULT);
            tv.setGravity(Gravity.START);
            tv.setBackgroundColor(Color.rgb(255, 255, 255));
            switch (m.role) {
                case "user":
                    tv.setBackgroundColor(Color.rgb(224, 232, 255));
                    tv.setGravity(Gravity.END);
                    break;
                case "assistant":
                    tv.setBackgroundColor(Color.rgb(255, 255, 255));
                    break;
                case "thinking":
                    tv.setBackgroundColor(Color.rgb(255, 249, 225));
                    tv.setTypeface(Typeface.MONOSPACE);
                    tv.setTextSize(12);
                    break;
                case "tool":
                    tv.setBackgroundColor(Color.rgb(240, 240, 240));
                    tv.setTypeface(Typeface.MONOSPACE);
                    tv.setTextSize(12);
                    break;
                default:
                    tv.setBackgroundColor(Color.rgb(245, 245, 245));
                    tv.setTypeface(Typeface.MONOSPACE);
                    tv.setTextSize(12);
                    break;
            }
            return tv;
        }
    }

    // ------------------------------------------------------------------
    // Session persistence
    // ------------------------------------------------------------------

    private File chatSessionsFile() {
        return new File(getFilesDir(), "llm_sessions.json");
    }

    private void loadChatSessions() {
        File f = chatSessionsFile();
        if (!f.exists()) return;
        chatSessions.clear();
        try (FileInputStream in = new FileInputStream(f);
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
            JSONArray arr = new JSONArray(new String(bos.toByteArray(), StandardCharsets.UTF_8));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) {
                    chatSessions.add(ChatSession.fromJson(o));
                }
            }
        } catch (Exception e) {
            chatSessions.clear();
        }
    }

    private synchronized void saveChatSessions() {
        JSONArray arr = new JSONArray();
        for (ChatSession s : chatSessions) {
            arr.put(s.toJson());
        }
        try (FileOutputStream out = new FileOutputStream(chatSessionsFile())) {
            out.write(arr.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {}
    }

    // ------------------------------------------------------------------
    // LLM chat dialog
    // ------------------------------------------------------------------

    private void showLlmChatDialog() {
        if (chatSessions.isEmpty()) {
            ChatSession s = new ChatSession(UUID.randomUUID().toString(), "新会话");
            chatSessions.add(s);
        }
        if (currentChatSession == null || !chatSessions.contains(currentChatSession)) {
            currentChatSession = chatSessions.get(0);
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(10, 10, 10, 10);
        root.setBackgroundColor(Color.rgb(248, 248, 248));

        // top bar
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setPadding(0, 4, 0, 4);

        Button sessionsBtn = styleTopButton("会话");
        Button newBtn = styleTopButton("新会话");
        final Button stopBtn = styleTopButton("停止");
        Button settingsBtn = styleTopButton("设置");
        Button closeBtn = styleTopButton("✕");
        closeBtn.setTextSize(18);

        top.addView(sessionsBtn);
        top.addView(newBtn);
        top.addView(stopBtn);
        top.addView(settingsBtn);
        top.addView(closeBtn);
        root.addView(top);

        // thinking chain header (click to show/hide)
        final TextView thinkingHeader = new TextView(this);
        chatThinkingHeaderView = thinkingHeader;
        thinkingHeader.setText("思考链：显示（点击隐藏）");
        thinkingHeader.setTextColor(Color.rgb(120, 90, 0));
        thinkingHeader.setPadding(16, 10, 16, 10);
        thinkingHeader.setGravity(Gravity.CENTER_VERTICAL);
        thinkingHeader.setBackground(roundedBg(Color.rgb(255, 243, 205), 24));
        thinkingHeader.setOnClickListener(v -> {
            // Last operation wins: if any thinking chain is visible now,
            // hide all of them; otherwise show all of them.
            boolean anyVisible = hasVisibleThinking(currentChatSession);
            chatAdapter.setShowThinking(!anyVisible);
            updateThinkingHeader();
        });
        root.addView(thinkingHeader);

        // session title
        chatSessionTitleView = new TextView(this);
        chatSessionTitleView.setText(currentChatSession.title);
        chatSessionTitleView.setTextSize(17);
        chatSessionTitleView.setTypeface(Typeface.DEFAULT_BOLD);
        chatSessionTitleView.setPadding(10, 10, 10, 6);
        chatSessionTitleView.setTextColor(Color.rgb(25, 25, 25));
        root.addView(chatSessionTitleView);

        // message list
        chatListView = new ListView(this);
        chatAdapter = new ChatAdapter(currentChatSession);
        chatListView.setAdapter(chatAdapter);
        chatPinnedToBottom = true;
        chatListView.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView view, int scrollState) {
            }

            @Override
            public void onScroll(AbsListView view, int firstVisibleItem, int visibleItemCount, int totalItemCount) {
                if (totalItemCount == 0) {
                    chatPinnedToBottom = true;
                } else if (firstVisibleItem + visibleItemCount >= totalItemCount - 1) {
                    chatPinnedToBottom = true;
                } else {
                    chatPinnedToBottom = false;
                }
            }
        });
        chatListView.setOnItemClickListener((parent, view, position, id) -> {
            Object item = chatAdapter.getItem(position);
            if (!(item instanceof ChatMessage)) return;
            ChatMessage m = (ChatMessage) item;
            if ("system".equals(m.role) && m.text.startsWith("第 ") && m.text.contains("轮")) {
                toggleThinkingForRound(m);
            }
        });
        root.addView(chatListView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        // input row
        LinearLayout inputRow = new LinearLayout(this);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        inputRow.setPadding(0, 10, 0, 4);
        final EditText input = new EditText(this);
        input.setHint("用自然语言描述你要画的图或要执行的操作…");
        input.setSingleLine(true);
        input.setTextSize(14);
        input.setPadding(18, 10, 18, 10);
        input.setBackground(roundedBg(Color.rgb(255, 255, 255), 24));
        inputRow.addView(input, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        Button sendBtn = new Button(this);
        sendBtn.setText("发送");
        sendBtn.setAllCaps(false);
        sendBtn.setTextColor(Color.WHITE);
        sendBtn.setTextSize(14);
        sendBtn.setPadding(20, 10, 20, 10);
        sendBtn.setBackground(roundedBg(Color.rgb(101, 87, 210), 24));
        LinearLayout.LayoutParams sendLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sendLp.setMargins(10, 0, 0, 0);
        inputRow.addView(sendBtn, sendLp);
        root.addView(inputRow);

        sessionsBtn.setOnClickListener(v -> showSessionListDialog());
        newBtn.setOnClickListener(v -> createNewChatSession());
        settingsBtn.setOnClickListener(v -> showLlmSettingsDialog());
        closeBtn.setOnClickListener(v -> {
            if (chatDialog != null) chatDialog.dismiss();
        });
        stopBtn.setOnClickListener(v -> {
            chatRunStopped.set(true);
            appendChatMessage("system", "[系统] 已请求停止，当前轮结束后停止。");
        });

        sendBtn.setOnClickListener(v -> {
            String text = input.getText().toString().trim();
            if (text.isEmpty()) {
                toast("请输入需求描述");
                return;
            }
            if (chatRunning.get()) {
                toast("正在执行中，请先停止或等待完成");
                return;
            }
            input.setText("");
            appendChatMessage("user", text);
            chatStopped.set(false);
            final long runId = ++chatRunId;
            final AtomicBoolean stopped = new AtomicBoolean(false);
            chatRunStopped = stopped;
            chatRunning.set(true);
            stopBtn.setText("停止");
            new Thread(() -> runLlmAgent(text, runId, stopped)).start();
        });

        chatDialog = new Dialog(this);
        chatDialog.setContentView(root);
        chatDialog.setCancelable(true);
        chatDialog.setOnDismissListener(d -> {
            // Keep the agent running in the background so the user can reopen
            // the chat later and inspect progress. Only the "停止" button or
            // switching/deleting a session stops the run.
        });
        updateThinkingHeader();
        chatDialog.getWindow().setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        chatDialog.show();
        forceScrollChatToBottom();
    }

    private void createNewChatSession() {
        ChatSession s = new ChatSession(UUID.randomUUID().toString(), "新会话");
        chatSessions.add(0, s);
        currentChatSession = s;
        chatRunId++;
        chatStopped.set(true);
        chatRunning.set(false);
        if (chatAdapter != null) {
            chatAdapter.notifyDataSetChanged();
        }
        chatAdapter = new ChatAdapter(currentChatSession);
        chatListView.setAdapter(chatAdapter);
        chatSessionTitleView.setText(s.title);
        updateThinkingHeader();
        saveChatSessions();
        forceScrollChatToBottom();
    }

    private void showSessionListDialog() {
        if (chatSessions.isEmpty()) return;
        ArrayList<String> titles = new ArrayList<>();
        for (ChatSession s : chatSessions) {
            titles.add(s.title + (s == currentChatSession ? "（当前）" : ""));
        }
        final String[] arr = titles.toArray(new String[0]);
        new AlertDialog.Builder(this)
                .setTitle("会话列表")
                .setItems(arr, (d, which) -> {
                    final ChatSession s = chatSessions.get(which);
                    String[] ops = {"打开", "重命名", "删除"};
                    new AlertDialog.Builder(this)
                            .setTitle(s.title)
                            .setItems(ops, (d2, op) -> {
                                if (op == 0) {
                                    chatStopped.set(true);
                                    chatRunId++;
                                    chatRunning.set(false);
                                    currentChatSession = s;
                                    chatAdapter = new ChatAdapter(s);
                                    chatListView.setAdapter(chatAdapter);
                                    chatSessionTitleView.setText(s.title);
                                    updateThinkingHeader();
                                    saveChatSessions();
                                    forceScrollChatToBottom();
                                } else if (op == 1) {
                                    final EditText et = new EditText(this);
                                    et.setText(s.title);
                                    new AlertDialog.Builder(this)
                                            .setTitle("重命名会话")
                                            .setView(et)
                                            .setPositiveButton("保存", (d3, w3) -> {
                                                s.title = et.getText().toString().trim();
                                                if (s.title.isEmpty()) s.title = "会话";
                                                if (s == currentChatSession) {
                                                    chatSessionTitleView.setText(s.title);
                                                }
                                                saveChatSessions();
                                            })
                                            .setNegativeButton("取消", null)
                                            .show();
                                } else {
                                    new AlertDialog.Builder(this)
                                            .setTitle("删除会话")
                                            .setMessage("确定删除该会话及所有消息？")
                                            .setPositiveButton("删除", (d4, w4) -> {
                                                chatSessions.remove(s);
                                                if (chatSessions.isEmpty()) {
                                                    createNewChatSession();
                                                } else if (s == currentChatSession) {
                                                    // Deleting the active session stops its run, just
                                                    // like switching or creating a session.
                                                    chatStopped.set(true);
                                                    chatRunId++;
                                                    chatRunning.set(false);
                                                    currentChatSession = chatSessions.get(0);
                                                    chatAdapter = new ChatAdapter(currentChatSession);
                                                    chatListView.setAdapter(chatAdapter);
                                                    chatSessionTitleView.setText(currentChatSession.title);
                                                    updateThinkingHeader();
                                                }
                                                saveChatSessions();
                                            })
                                            .setNegativeButton("取消", null)
                                            .show();
                                }
                            })
                            .show();
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private void appendChatMessage(String role, String text) {
        appendMessageTo(currentChatSession, role, text);
    }

    private void appendMessageTo(ChatSession session, String role, String text) {
        if (session == null) return;
        ChatMessage m = new ChatMessage(role, text);
        session.messages.add(m);
        if ("user".equals(role)) {
            // A message the user just sent should always bring the list to the bottom.
            chatPinnedToBottom = true;
        }
        scheduleChatRefresh();
        saveChatSessions();
    }

    private void updateChatMessage(ChatMessage m, String newText) {
        if (m == null) return;
        m.text = newText;
        scheduleChatRefresh();
    }

    /** Throttled UI refresh: streaming deltas only re-layout at most every 200 ms. */
    private void scheduleChatRefresh() {
        if (chatRefreshScheduled) return;
        chatRefreshScheduled = true;
        mainHandler.postDelayed(chatRefreshRunnable, 200);
    }

    /**
     * Only auto-scroll while the user is already pinned to the bottom. When
     * the user scrolls up to read thinking text, new streaming output must not
     * yank the list back down.
     */
    private void scrollChatToBottom() {
        if (!chatPinnedToBottom) return;
        if (chatListView != null && chatAdapter != null && chatAdapter.getCount() > 0) {
            chatListView.setSelection(chatAdapter.getCount() - 1);
        }
    }

    private void forceScrollChatToBottom() {
        chatPinnedToBottom = true;
        if (chatListView != null && chatAdapter != null && chatAdapter.getCount() > 0) {
            chatListView.setSelection(chatAdapter.getCount() - 1);
        }
    }

    private boolean hasVisibleThinking(ChatSession session) {
        if (session == null) return false;
        for (ChatMessage m : session.messages) {
            if ("thinking".equals(m.role) && !m.hidden) return true;
        }
        return false;
    }

    private void updateThinkingHeader() {
        if (chatThinkingHeaderView == null) return;
        if (currentChatSession == null) {
            chatThinkingHeaderView.setText("思考链：显示（点击隐藏）");
            return;
        }
        boolean anyThinking = false;
        boolean anyVisible = false;
        for (ChatMessage m : currentChatSession.messages) {
            if ("thinking".equals(m.role)) {
                anyThinking = true;
                if (!m.hidden) anyVisible = true;
            }
        }
        if (!anyThinking) {
            chatThinkingHeaderView.setText("思考链：显示（点击隐藏）");
        } else if (anyVisible) {
            chatThinkingHeaderView.setText("思考链：显示（点击隐藏全部）");
        } else {
            chatThinkingHeaderView.setText("思考链：隐藏（点击显示全部）");
        }
    }

    private void toggleThinkingForRound(ChatMessage roundMarker) {
        if (currentChatSession == null) return;
        int idx = currentChatSession.messages.indexOf(roundMarker);
        if (idx < 0) return;
        // Toggle the thinking message(s) immediately following this round marker.
        // This writes directly to each message's hidden flag, so it overrides
        // whatever the global button set before.
        boolean toggled = false;
        for (int i = idx + 1; i < currentChatSession.messages.size(); i++) {
            ChatMessage m = currentChatSession.messages.get(i);
            if ("thinking".equals(m.role)) {
                m.hidden = !m.hidden;
                toggled = true;
            } else if (!"assistant".equals(m.role)) {
                break;
            }
        }
        if (toggled) {
            if (chatAdapter != null) chatAdapter.notifyDataSetChanged();
            updateThinkingHeader();
            saveChatSessions();
        }
    }

    private GradientDrawable roundedBg(int color, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    private Button styleTopButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(12);
        b.setTextColor(Color.rgb(50, 50, 50));
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(12, 6, 12, 6);
        b.setBackground(roundedBg(Color.rgb(235, 235, 240), 18));
        return b;
    }

    private void showLlmSettingsDialog() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(24, 16, 24, 16);
        scroll.addView(panel, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        // Defaults are DeepSeek. Migrate the old OpenAI default silently only
        // when the user never configured an API key; a configured OpenAI setup
        // is left untouched.
        String savedUrl = prefs.getString("llm_url", "");
        String savedKey = prefs.getString("llm_key", "");
        boolean untouched = savedUrl.isEmpty()
                || (savedUrl.equals("https://api.openai.com/v1/chat/completions") && savedKey.isEmpty());
        String currentUrl = untouched ? LLM_DEFAULT_URL : savedUrl;
        String currentKey = untouched ? LLM_DEFAULT_KEY : savedKey;
        String currentModel = prefs.getString("llm_model",
                untouched ? LLM_DEFAULT_MODEL : "gpt-4o-mini");

        EditText url = editText(panel, "API URL（OpenAI 兼容 /chat/completions）", currentUrl);
        EditText key = editText(panel, "API Key", currentKey);
        EditText model = editText(panel, "模型名称", currentModel);
        EditText temperature = editText(panel, "温度 temperature（0-2）",
                prefs.getString("llm_temperature", "0.7"));
        EditText maxTokens = editText(panel, "max_tokens",
                prefs.getString("llm_max_tokens", "4096"));
        EditText timeoutSec = editText(panel, "超时（秒）",
                prefs.getString("llm_timeout", "120"));
        EditText headers = editText(panel, "额外请求头 JSON（例如 {\"X-Key\":\"v\"}）",
                prefs.getString("llm_headers", "{}"));
        EditText body = editText(panel, "额外请求体 JSON（会合并到请求体）",
                prefs.getString("llm_body", "{}"));

        new AlertDialog.Builder(this)
                .setTitle("LLM 设置")
                .setView(scroll)
                .setPositiveButton("保存", (d, w) -> {
                    prefs.edit()
                            .putString("llm_url", url.getText().toString().trim())
                            .putString("llm_key", key.getText().toString().trim())
                            .putString("llm_model", model.getText().toString().trim())
                            .putString("llm_temperature", temperature.getText().toString().trim())
                            .putString("llm_max_tokens", maxTokens.getText().toString().trim())
                            .putString("llm_timeout", timeoutSec.getText().toString().trim())
                            .putString("llm_headers", headers.getText().toString().trim())
                            .putString("llm_body", body.getText().toString().trim())
                            .apply();
                    toast("LLM 设置已保存");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private String readAsset(String path) throws Exception {
        try (InputStream in = getAssets().open(path);
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private String evalJsSyncOnUiThread(final String js) {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> result = new AtomicReference<>("null");
        mainHandler.post(() -> {
            if (webView == null) {
                result.set("ERROR: WebView is null");
                latch.countDown();
                return;
            }
            webView.evaluateJavascript(js, value -> {
                result.set(value == null ? "null" : value);
                latch.countDown();
            });
        });
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            return "ERROR: interrupted";
        }
        return result.get();
    }

    private String unwrapJsString(String jsResult) {
        if (jsResult == null) return "";
        String v = jsResult.trim();
        if (v.equals("null") || v.equals("undefined")) return "";
        if (v.startsWith("\"") && v.endsWith("\"") && v.length() >= 2) {
            try {
                return new JSONObject("{\"v\":" + v + "}").getString("v");
            } catch (Exception e) {
                return v;
            }
        }
        return v;
    }

    private JSONObject callLlm(List<JSONObject> messages) throws Exception {
        return callLlmInternal(messages, false, null, null);
    }

    private interface LlmStreamCallback {
        void onDelta(String reasoningDelta, String contentDelta);
        void onFinished(String fullReasoning, String fullContent);
    }

    private String jsonString(JSONObject o, String key) {
        if (o == null || o.isNull(key)) return "";
        return o.optString(key, "");
    }

    private JSONObject callLlmInternal(List<JSONObject> messages, boolean stream,
            AtomicBoolean stopped, LlmStreamCallback callback) throws Exception {
        String apiUrl = prefs.getString("llm_url", LLM_DEFAULT_URL);
        String apiKey = prefs.getString("llm_key", LLM_DEFAULT_KEY);
        String model = prefs.getString("llm_model", LLM_DEFAULT_MODEL);
        double temperature = parseDoublePref("llm_temperature", 0.7);
        int maxTokens = parseIntPref("llm_max_tokens", 4096);
        int timeoutSec = parseIntPref("llm_timeout", 120);

        JSONObject payload = new JSONObject();
        payload.put("model", model);
        payload.put("messages", new JSONArray(messages));
        payload.put("temperature", temperature);
        payload.put("max_tokens", maxTokens);
        payload.put("stream", stream);

        JSONObject extraBody = new JSONObject(prefs.getString("llm_body", "{}"));
        for (java.util.Iterator<String> it = extraBody.keys(); it.hasNext();) {
            String k = it.next();
            payload.put(k, extraBody.get(k));
        }

        HttpURLConnection conn = (HttpURLConnection) new URL(apiUrl).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(timeoutSec * 1000);
        conn.setReadTimeout(timeoutSec * 1000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", stream ? "text/event-stream" : "application/json");
        if (!apiKey.isEmpty()) {
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        }
        JSONObject extraHeaders = new JSONObject(prefs.getString("llm_headers", "{}"));
        for (java.util.Iterator<String> it = extraHeaders.keys(); it.hasNext();) {
            String k = it.next();
            conn.setRequestProperty(k, String.valueOf(extraHeaders.get(k)));
        }

        byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(bytes);
        }

        int code = conn.getResponseCode();
        InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
        if (code < 200 || code >= 300) {
            String err = readStream(is);
            throw new Exception("HTTP " + code + ": " + err);
        }

        if (!stream) {
            String response = readStream(is);
            return new JSONObject(response);
        }

        // SSE streaming
        BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        String line;
        StringBuilder fullContent = new StringBuilder();
        StringBuilder fullReasoning = new StringBuilder();
        while ((line = br.readLine()) != null) {
            if (chatStopped.get() || (stopped != null && stopped.get())) {
                break;
            }
            if (!line.startsWith("data:")) continue;
            String data = line.substring(5).trim();
            if (data.isEmpty() || "[DONE]".equals(data)) continue;
            try {
                JSONObject chunk = new JSONObject(data);
                JSONArray choices = chunk.optJSONArray("choices");
                if (choices == null || choices.length() == 0) continue;
                JSONObject delta = choices.getJSONObject(0).optJSONObject("delta");
                if (delta == null) continue;
                String rc = jsonString(delta, "reasoning_content");
                String cc = jsonString(delta, "content");
                if (rc != null && !rc.isEmpty()) fullReasoning.append(rc);
                if (cc != null && !cc.isEmpty()) fullContent.append(cc);
                if (callback != null && (!rc.isEmpty() || !cc.isEmpty())) {
                    callback.onDelta(rc, cc);
                }
            } catch (Exception ignored) {
                // skip malformed SSE line
            }
        }
        br.close();
        if (callback != null) {
            callback.onFinished(fullReasoning.toString(), fullContent.toString());
        }
        // return a synthetic response for compatibility
        JSONObject fake = new JSONObject();
        fake.put("streamed", true);
        return fake;
    }

    private String readStream(InputStream is) throws Exception {
        if (is == null) return "";
        BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) {
            sb.append(line);
        }
        br.close();
        return sb.toString();
    }

    private double parseDoublePref(String key, double def) {
        try {
            return Double.parseDouble(prefs.getString(key, String.valueOf(def)).trim());
        } catch (Exception e) {
            return def;
        }
    }

    private int parseIntPref(String key, int def) {
        try {
            return Integer.parseInt(prefs.getString(key, String.valueOf(def)).trim());
        } catch (Exception e) {
            return def;
        }
    }

    private JSONObject parseLlmAction(String content) {
        int s = content.indexOf('{');
        int e = content.lastIndexOf('}');
        if (s >= 0 && e > s) {
            try {
                return new JSONObject(content.substring(s, e + 1));
            } catch (Exception ignored) {
                // fall through to code-fence parsing
            }
        }

        String ggb = extractFenced(content, "ggb");
        if (!ggb.isEmpty()) {
            try {
                JSONObject o = new JSONObject();
                o.put("thinking", "");
                o.put("action", "ggb");
                o.put("code", ggb);
                o.put("done", false);
                return o;
            } catch (Exception ignored) {}
        }

        String js = extractFenced(content, "js");
        if (js.isEmpty()) js = extractFenced(content, "javascript");
        if (!js.isEmpty()) {
            try {
                JSONObject o = new JSONObject();
                o.put("thinking", "");
                o.put("action", "js");
                o.put("code", js);
                o.put("done", false);
                return o;
            } catch (Exception ignored) {}
        }

        try {
            JSONObject o = new JSONObject();
            o.put("thinking", "");
            o.put("action", "ggb");
            o.put("code", content.trim());
            o.put("done", false);
            return o;
        } catch (Exception ignored) {
            return new JSONObject();
        }
    }

    private String extractFenced(String content, String lang) {
        String marker = "```" + lang;
        int start = content.indexOf(marker);
        if (start < 0) return "";
        start = content.indexOf('\n', start);
        if (start < 0) return "";
        int end = content.indexOf("```", start + 1);
        if (end < 0) return "";
        return content.substring(start + 1, end).trim();
    }

    private void generateSessionTitle(String firstUserMessage, final ChatSession session) {
        try {
            List<JSONObject> msgs = new ArrayList<>();
            JSONObject sys = new JSONObject();
            sys.put("role", "system");
            sys.put("content", TITLE_PROMPT);
            msgs.add(sys);
            JSONObject user = new JSONObject();
            user.put("role", "user");
            user.put("content", firstUserMessage);
            msgs.add(user);

            JSONObject resp = callLlm(msgs);
            JSONArray choices = resp.optJSONArray("choices");
            if (choices == null || choices.length() == 0) return;
            JSONObject msg = choices.getJSONObject(0).getJSONObject("message");
            String title = jsonString(msg, "content").trim();
            if (title.isEmpty()) {
                title = jsonString(msg, "reasoning_content").trim();
            }
            if (title.isEmpty()) return;
            title = title.replaceAll("[\"“”'']", "").trim();
            if (title.length() > 20) title = title.substring(0, 20);
            final String finalTitle = title;
            session.title = finalTitle;
            mainHandler.post(() -> {
                if (currentChatSession == session && chatSessionTitleView != null) {
                    chatSessionTitleView.setText(finalTitle);
                }
            });
            saveChatSessions();
        } catch (Exception ignored) {
            // title generation is best-effort
        }
    }

    private List<JSONObject> buildLlmContext(ChatSession session, String skill) throws Exception {
        List<JSONObject> chat = new ArrayList<>();
        JSONObject sys = new JSONObject();
        sys.put("role", "system");
        sys.put("content", skill + LLM_PROTOCOL);
        chat.add(sys);
        for (ChatMessage m : session.messages) {
            if ("user".equals(m.role)) {
                chat.add(new JSONObject().put("role", "user").put("content", m.text));
            } else if ("assistant".equals(m.role)) {
                chat.add(new JSONObject().put("role", "assistant").put("content", m.text));
            } else if ("tool".equals(m.role)) {
                chat.add(new JSONObject().put("role", "user").put("content", "[工具结果]\n" + m.text));
            }
            // system / thinking are not sent to the model
        }
        return chat;
    }

    private void runLlmAgent(String userMessage, long runId, AtomicBoolean stopped) {
        final ChatSession session = currentChatSession;
        // GeoGebra's own error dialogs must not pop up while the agent is
        // running in the background; errors are reported in the chat log only.
        evalJsSyncOnUiThread("window.__ggbSetErrorDialogsActive ? window.__ggbSetErrorDialogsActive(false) : false");
        try {
            String skill = readAsset("SKILL.md");
            int rounds = 0;
            boolean titleGenerated = false;

            while (rounds < 20 && runId == chatRunId && !chatStopped.get() && !stopped.get()) {
                List<JSONObject> chat = buildLlmContext(session, skill);

                appendMessageTo(session, "system", "第 " + (rounds + 1) + " 轮：正在请求模型…（点击切换本轮思考链）");

                // placeholders for this round
                currentThinkingMessage = new ChatMessage("thinking", "");
                currentAssistantMessage = new ChatMessage("assistant", "");
                final ChatMessage thinkingMsg = currentThinkingMessage;
                final ChatMessage assistantMsg = currentAssistantMessage;
                session.messages.add(thinkingMsg);
                session.messages.add(assistantMsg);
                if (currentChatSession == session) {
                    chatPinnedToBottom = true;
                    scheduleChatRefresh();
                }

                final StringBuilder reasoningBuf = new StringBuilder();
                final StringBuilder contentBuf = new StringBuilder();
                callLlmInternal(chat, true, stopped, new LlmStreamCallback() {
                    @Override
                    public void onDelta(String reasoningDelta, String contentDelta) {
                        if (reasoningDelta != null && !reasoningDelta.isEmpty()) {
                            reasoningBuf.append(reasoningDelta);
                            updateChatMessage(thinkingMsg, reasoningBuf.toString());
                        }
                        if (contentDelta != null && !contentDelta.isEmpty()) {
                            contentBuf.append(contentDelta);
                            updateChatMessage(assistantMsg, contentBuf.toString());
                        }
                    }

                    @Override
                    public void onFinished(String fullReasoning, String fullContent) {
                        // final update happens below
                    }
                });

                if (runId != chatRunId || chatStopped.get() || stopped.get()) {
                    appendMessageTo(session, "system", "[系统] 已停止。");
                    break;
                }

                String content = contentBuf.toString();
                String reasoning = reasoningBuf.toString();
                if (content.trim().isEmpty()) {
                    updateChatMessage(assistantMsg, "[空输出]");
                    appendMessageTo(session, "system", "[系统] 模型没有输出内容，请重试。");
                    break;
                }

                // Remove an empty thinking placeholder (models without reasoning).
                if (reasoning.trim().isEmpty() && session.messages.remove(thinkingMsg)) {
                    if (currentChatSession == session) {
                        scheduleChatRefresh();
                    }
                } else {
                    updateChatMessage(thinkingMsg, reasoning);
                }
                updateChatMessage(assistantMsg, content);

                // Auto title after first round
                if (!titleGenerated && "新会话".equals(session.title)) {
                    titleGenerated = true;
                    new Thread(() -> generateSessionTitle(userMessage, session)).start();
                }

                JSONObject action = parseLlmAction(content);
                boolean done = action.optBoolean("done", false);
                String actionType = jsonString(action, "action");
                if (actionType.isEmpty()) actionType = "done";
                String code = jsonString(action, "code");

                if (done || "done".equals(actionType)) {
                    appendMessageTo(session, "system", "[系统] 模型认为任务已完成。");
                    break;
                }

                if (code.trim().isEmpty()) {
                    appendMessageTo(session, "tool", "模型未给出可执行代码，请其重新输出。");
                    rounds++;
                    continue;
                }

                String toolResult;
                if ("js".equals(actionType)) {
                    String raw = evalJsSyncOnUiThread("window.__ggbRunJsScript("
                            + JSONObject.quote(code) + ")");
                    toolResult = "JavaScript 执行返回: " + unwrapJsString(raw);
                } else {
                    String raw = evalJsSyncOnUiThread("window.__ggbRunGgbScriptWithLog("
                            + JSONObject.quote(code) + ")");
                    toolResult = "GGB 命令执行日志: " + unwrapJsString(raw);
                }

                String snapRaw = evalJsSyncOnUiThread("window.__ggbGetSnapshot()");
                toolResult += "\n当前作图快照: " + unwrapJsString(snapRaw);

                appendMessageTo(session, "tool", toolResult);
                rounds++;
            }

            if (rounds >= 20) {
                appendMessageTo(session, "system", "[系统] 已达到最大轮次（20），停止。");
            }
        } catch (Exception e) {
            appendMessageTo(session, "system", "[错误] " + e.getMessage());
        } finally {
            if (runId == chatRunId) {
                chatRunning.set(false);
            }
            currentAssistantMessage = null;
            currentThinkingMessage = null;
            saveChatSessions();
            // Re-enable GeoGebra dialogs for normal, non-agent use.
            evalJsSyncOnUiThread("window.__ggbSetErrorDialogsActive ? window.__ggbSetErrorDialogsActive(true) : false");
        }
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
                .putExtra(Intent.EXTRA_TITLE, suggestedName)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        pendingWrite = new PendingWrite(mime, data);
        pendingWriteIsGgbSave = false;
        try {
            startActivityForResult(intent, REQ_CREATE_EXPORT);
        } catch (Exception e) {
            pendingWrite = null;
            toast("无法打开保存对话框: " + e.getMessage());
        }
    }

    private void saveBytesAsCurrentGgb(String suggestedName, String mime, byte[] data) {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(mime)
                .putExtra(Intent.EXTRA_TITLE, suggestedName)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        pendingWrite = new PendingWrite(mime, data);
        pendingWriteIsGgbSave = true;
        try {
            startActivityForResult(intent, REQ_CREATE_EXPORT);
        } catch (Exception e) {
            pendingWrite = null;
            pendingWriteIsGgbSave = false;
            toast("无法打开保存对话框: " + e.getMessage());
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK) {
            if (requestCode == REQ_CREATE_EXPORT) {
                pendingWrite = null;
                pendingWriteIsGgbSave = false;
            }
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
            boolean setAsCurrentGgb = pendingWriteIsGgbSave;
            pendingWrite = null;
            pendingWriteIsGgbSave = false;
            if (write == null) {
                return;
            }
            if (setAsCurrentGgb) {
                currentGgbUri = uri;
                takePersistablePermission(uri);
            }
            writePending(uri, write);
        }
    }

    private void readAndLoadGgb(Uri uri) {
        setStatus("正在读取 .ggb 文件…");
        new Thread(() -> {
            try {
                byte[] bytes = readUri(uri, MAX_GGB_BYTES);
                mainHandler.post(() -> {
                    currentGgbUri = uri;
                    takePersistablePermission(uri);
                    loadGgbBytes(bytes);
                });
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

    private void takePersistablePermission(Uri uri) {
        try {
            getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (Exception e) {
            // Some providers do not support persistable permissions; ignore.
        }
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
