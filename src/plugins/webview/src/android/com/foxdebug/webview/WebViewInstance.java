package com.foxdebug.webview;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.DownloadListener;
import android.webkit.JavascriptInterface;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.cordova.CallbackContext;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

public class WebViewInstance {

  private static final String TAG = "WebViewInstance";

  /**
   * Page-side messaging bridge. Injected on page started (best effort, runs
   * before page scripts in most cases) and again on page finished (guaranteed).
   * It is idempotent and non-destructive: the guard keeps callbacks registered
   * by the page between the two injections intact.
   */
  private static final String BRIDGE_JS =
    "(function(){" +
    "if(window.webview&&window.webview.__acodeBridge){return;}" +
    "var callbacks=[];" +
    "window.webview={" +
    "__acodeBridge:true," +
    "onMessage:function(cb){if(typeof cb==='function'){callbacks.push(cb);}}," +
    "offMessage:function(cb){callbacks=callbacks.filter(function(c){return c!==cb;});}," +
    "postMessage:function(msg){" +
    "var data=(typeof msg==='string')?msg:JSON.stringify(msg);" +
    "window.AcodeWebViewNative.postMessage(String(data));" +
    "}," +
    "_dispatch:function(msg){" +
    "callbacks.slice().forEach(function(cb){try{cb(msg);}catch(e){console.error(e);}});" +
    "}" +
    "};" +
    "})();";

  final String id;
  final String mode;
  final String title;
  final int width;
  final int height;
  final int x;
  final int y;
  final boolean allowNavigation;
  final boolean allowDownloads;
  final WebViewPlugin plugin;

  private WebView webView;
  private FrameLayout container;
  private Activity activity;
  private boolean isDestroyed = false;
  private boolean isAttached = false;
  /** Whether the hosting fullscreen activity has been launched. */
  private boolean launched = false;
  /** Content requested before the fullscreen WebView exists yet. */
  private String pendingUrl = null;
  private String pendingHtml = null;

  WebViewInstance(
    String id, String mode, String title,
    int width, int height, int x, int y,
    boolean allowNavigation, boolean allowDownloads,
    Activity activity,
    WebViewPlugin plugin
  ) {
    this.id = id;
    this.mode = mode;
    this.title = title;
    this.width = width;
    this.height = height;
    this.x = x;
    this.y = y;
    this.allowNavigation = allowNavigation;
    this.allowDownloads = allowDownloads;
    this.activity = activity;
    this.plugin = plugin;
  }

  public WebView getWebView() {
    return webView;
  }

  String getTitle() {
    return title;
  }

  boolean isFullscreen() {
    return "fullscreen".equals(mode);
  }

  void markLaunched() {
    launched = true;
  }

  void createWebView(Activity activity) {
    // Idempotent: a recreated hosting activity reuses the existing WebView
    // (and its page state) instead of leaking one instance per recreation.
    if (webView != null) {
      this.activity = activity;
      return;
    }
    this.activity = activity;
    webView = new WebView(activity);

    WebSettings settings = webView.getSettings();
    settings.setJavaScriptEnabled(true);
    settings.setDomStorageEnabled(true);
    // Isolation: hosted content must not reach app/device data. File and
    // content scheme access stay disabled, and loadURL()/navigation below
    // only allow http(s), so these cannot be bypassed with a crafted URL.
    settings.setAllowFileAccess(false);
    settings.setAllowContentAccess(false);
    setFileUrlAccessFlags(settings);
    settings.setDisplayZoomControls(false);
    settings.setLoadWithOverviewMode(true);
    settings.setUseWideViewPort(true);

    webView.setWebViewClient(new InstanceWebViewClient());
    webView.setWebChromeClient(new InstanceWebChromeClient());
    webView.setFocusable(true);
    webView.setFocusableInTouchMode(true);

    webView.addJavascriptInterface(new JsBridge(), "AcodeWebViewNative");

    if (allowDownloads) {
      webView.setDownloadListener(new InstanceDownloadListener(activity));
    }

    injectBridge(webView);

    // Apply content requested while the WebView did not exist yet
    // (fullscreen instances are created lazily by WebViewActivity).
    if (pendingUrl != null) {
      webView.loadUrl(pendingUrl);
      pendingUrl = null;
      pendingHtml = null;
    } else if (pendingHtml != null) {
      webView.loadDataWithBaseURL(null, pendingHtml, "text/html", "UTF-8", null);
      pendingHtml = null;
    }
  }

  @SuppressWarnings("deprecation")
  private static void setFileUrlAccessFlags(WebSettings settings) {
    settings.setAllowFileAccessFromFileURLs(false);
    settings.setAllowUniversalAccessFromFileURLs(false);
  }

  private static void injectBridge(WebView view) {
    view.evaluateJavascript(BRIDGE_JS, null);
  }

  void attachToActivity() {
    if (isAttached || isDestroyed || webView == null || activity == null) return;
    if (activity.isFinishing()) return;

    container = new FrameLayout(activity);
    container.setBackgroundColor(Color.argb(180, 0, 0, 0));

    FrameLayout.LayoutParams webViewParams;
    if (mode.equals("window")) {
      int w = width > 0 ? dpToPx(activity, width) : ViewGroup.LayoutParams.MATCH_PARENT;
      int h = height > 0 ? dpToPx(activity, height) : ViewGroup.LayoutParams.MATCH_PARENT;
      webViewParams = new FrameLayout.LayoutParams(w, h);
      if (x > 0 || y > 0) {
        webViewParams.gravity = Gravity.TOP | Gravity.START;
        webViewParams.setMargins(dpToPx(activity, x), dpToPx(activity, y), 0, 0);
      } else {
        webViewParams.gravity = Gravity.CENTER;
        webViewParams.setMargins(
          dpToPx(activity, 16), dpToPx(activity, 48),
          dpToPx(activity, 16), dpToPx(activity, 48)
        );
      }
      container.setOnClickListener(new View.OnClickListener() {
        @Override
        public void onClick(View v) {
          container.setVisibility(View.GONE);
          plugin.sendEventToCordova(id, "dismissed", null);
        }
      });
    } else {
      webViewParams = new FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        height > 0 ? dpToPx(activity, height) : (int) (getScreenHeight(activity) * 0.4)
      );
      webViewParams.gravity = Gravity.BOTTOM;
    }

    if (webView.getParent() != null) {
      ((ViewGroup) webView.getParent()).removeView(webView);
    }
    container.addView(webView, webViewParams);

    ViewGroup rootView = activity.findViewById(android.R.id.content);
    if (rootView instanceof FrameLayout) {
      rootView.addView(container, new FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT
      ));
    }

    isAttached = true;
  }

  private static final Pattern SCHEME_PATTERN =
    Pattern.compile("^([a-zA-Z][a-zA-Z0-9+\\-.]*)://");

  /**
   * Allows only http and https URLs, so hosted pages can never reach local
   * files, app content providers or execute javascript: URLs. Input without
   * a "scheme://" prefix ("example.com", "localhost:8080/page") is treated
   * as a host and loaded over https; anything that is not clearly a URL
   * degrades into a harmless failed https load.
   */
  private static String sanitizeUrl(String url) {
    if (url == null) return null;
    String trimmed = url.trim();
    if (trimmed.isEmpty()) return null;
    Matcher matcher = SCHEME_PATTERN.matcher(trimmed);
    if (matcher.find()) {
      String scheme = matcher.group(1);
      if (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https")) {
        return trimmed;
      }
      return null; // file://, content://, intent://, etc.
    }
    return "https://" + trimmed;
  }

  void loadURL(String url, final CallbackContext callbackContext) {
    if (isDestroyed) {
      callbackContext.error("WebView has been destroyed");
      return;
    }

    final String safeUrl = sanitizeUrl(url);
    if (safeUrl == null) {
      callbackContext.error("Blocked URL: only http:// and https:// URLs are allowed");
      return;
    }

    // Fullscreen instances create their WebView lazily in WebViewActivity.
    if (webView == null) {
      pendingUrl = safeUrl;
      pendingHtml = null;
      callbackContext.success();
      return;
    }

    runOnUiThread(new Runnable() {
      @Override
      public void run() {
        webView.loadUrl(safeUrl);
        callbackContext.success();
      }
    });
  }

  void loadHTML(final String html, final CallbackContext callbackContext) {
    if (isDestroyed) {
      callbackContext.error("WebView has been destroyed");
      return;
    }

    if (webView == null) {
      pendingHtml = html;
      pendingUrl = null;
      callbackContext.success();
      return;
    }

    runOnUiThread(new Runnable() {
      @Override
      public void run() {
        webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
        callbackContext.success();
      }
    });
  }

  void evaluate(String js, final CallbackContext callbackContext) {
    if (isDestroyed) {
      callbackContext.error("WebView has been destroyed");
      return;
    }
    if (webView == null) {
      callbackContext.error("WebView is not ready");
      return;
    }
    runOnUiThread(new Runnable() {
      @Override
      public void run() {
        webView.evaluateJavascript(js, new ValueCallback<String>() {
          @Override
          public void onReceiveValue(String value) {
            callbackContext.success(decodeJsResult(value));
          }
        });
      }
    });
  }

  /**
   * evaluateJavascript() delivers the result as a JSON-encoded string.
   * Decode it properly instead of stripping quotes by hand so escapes
   * (newlines, unicode, quotes) survive the round trip.
   */
  private static String decodeJsResult(String value) {
    if (value == null) return null;
    try {
      Object parsed = new JSONTokener(value).nextValue();
      if (parsed == JSONObject.NULL) return null;
      return String.valueOf(parsed);
    } catch (JSONException e) {
      return value;
    }
  }

  void postMessage(String message, final CallbackContext callbackContext) {
    if (isDestroyed) {
      callbackContext.error("WebView has been destroyed");
      return;
    }
    if (webView == null) {
      callbackContext.error("WebView is not ready");
      return;
    }

    // JSONObject.quote() produces a safe JS string literal for any input,
    // so a malicious or sloppy payload cannot break out of the string and
    // inject code into the page context. The page receives a parsed value
    // for JSON payloads and the raw string otherwise.
    final String js =
      "(function(){" +
      "var raw=" + JSONObject.quote(message) + ";" +
      "var msg;try{msg=JSON.parse(raw);}catch(e){msg=raw;}" +
      "if(window.webview&&window.webview._dispatch){window.webview._dispatch(msg);}" +
      "})();";

    runOnUiThread(new Runnable() {
      @Override
      public void run() {
        webView.evaluateJavascript(js, null);
        callbackContext.success();
      }
    });
  }

  void show(final CallbackContext callbackContext) {
    if (isFullscreen()) {
      showFullscreen(callbackContext);
      return;
    }
    runOnUiThread(new Runnable() {
      @Override
      public void run() {
        if (isDestroyed) {
          callbackContext.error("WebView has been destroyed");
          return;
        }
        if (!isAttached) {
          attachToActivity();
        }
        if (container != null) {
          container.setVisibility(View.VISIBLE);
          callbackContext.success();
        } else {
          callbackContext.error("Cannot show");
        }
      }
    });
  }

  private void showFullscreen(CallbackContext callbackContext) {
    if (isDestroyed) {
      callbackContext.error("WebView has been destroyed");
      return;
    }
    if (launched) {
      callbackContext.success();
      return;
    }
    launched = true;
    runOnUiThread(new Runnable() {
      @Override
      public void run() {
        plugin.launchFullscreenActivity(id);
      }
    });
    callbackContext.success();
  }

  void hide(final CallbackContext callbackContext) {
    if (isFullscreen()) {
      hideFullscreen(callbackContext);
      return;
    }
    runOnUiThread(new Runnable() {
      @Override
      public void run() {
        if (isDestroyed) {
          callbackContext.error("WebView has been destroyed");
          return;
        }
        // Hiding an already hidden (never attached) WebView is a no-op.
        if (container != null) {
          container.setVisibility(View.GONE);
        }
        callbackContext.success();
      }
    });
  }

  /**
   * A fullscreen WebView is hosted by its own activity, so hiding it means
   * closing that activity. WebViewActivity.onDestroy() then destroys the
   * instance and emits the "closed" event.
   */
  private void hideFullscreen(CallbackContext callbackContext) {
    if (isDestroyed) {
      callbackContext.error("WebView has been destroyed");
      return;
    }
    if (!launched) {
      callbackContext.success();
      return;
    }
    launched = false;
    runOnUiThread(new Runnable() {
      @Override
      public void run() {
        if (activity instanceof WebViewActivity && !activity.isFinishing()) {
          activity.finish();
        }
      }
    });
    callbackContext.success();
  }

  void reload(final CallbackContext callbackContext) {
    if (isDestroyed) {
      callbackContext.error("WebView has been destroyed");
      return;
    }
    if (webView == null) {
      callbackContext.error("WebView is not ready");
      return;
    }
    runOnUiThread(new Runnable() {
      @Override
      public void run() {
        webView.reload();
        callbackContext.success();
      }
    });
  }

  void destroy() {
    if (isDestroyed) return;
    isDestroyed = true;

    // Finish the hosting fullscreen activity, if any. Its onDestroy() calls
    // destroy() again, which is a no-op now that isDestroyed is set.
    if (activity instanceof WebViewActivity && !activity.isFinishing()) {
      activity.finish();
    }

    runOnUiThread(new Runnable() {
      @Override
      public void run() {
        if (container != null && container.getParent() != null) {
          ((ViewGroup) container.getParent()).removeView(container);
        }
        if (webView != null) {
          if (webView.getParent() != null) {
            ((ViewGroup) webView.getParent()).removeView(webView);
          }
          webView.removeJavascriptInterface("AcodeWebViewNative");
          webView.setDownloadListener(null);
          webView.setWebChromeClient(null);
          webView.setWebViewClient(null);
          webView.loadUrl("about:blank");
          webView.destroy();
        }
        webView = null;
        container = null;
        isAttached = false;
      }
    });
  }

  void onPageFinished(WebView view) {
    // Navigation replaced the page's JS context, so the bridge injected into
    // the previous document is gone. Re-inject (no-op if already present).
    injectBridge(view);
    try {
      JSONObject data = new JSONObject();
      String url = view.getUrl();
      String pageTitle = view.getTitle();
      data.put("url", url != null ? url : "");
      data.put("title", pageTitle != null ? pageTitle : "");
      plugin.sendEventToCordova(id, "pageFinished", data);
    } catch (JSONException e) {
      Log.e(TAG, "onPageFinished error", e);
    }
  }

  private static void runOnUiThread(Runnable runnable) {
    new Handler(Looper.getMainLooper()).post(runnable);
  }

  private static int dpToPx(Context context, int dp) {
    return (int) (dp * context.getResources().getDisplayMetrics().density);
  }

  private static int getScreenHeight(Activity activity) {
    return activity.getResources().getDisplayMetrics().heightPixels;
  }

  private class InstanceWebViewClient extends WebViewClient {
    @Override
    public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
      return shouldBlockNavigation(request.getUrl());
    }

    @SuppressWarnings("deprecation")
    @Override
    public boolean shouldOverrideUrlLoading(WebView view, String url) {
      return shouldBlockNavigation(Uri.parse(url));
    }

    /**
     * Blocks all navigation when allowNavigation is false. Even when it is
     * true, only http(s) targets may load inside the WebView; other schemes
     * (file:, content:, intent:, javascript:, tel:, ...) are blocked so
     * hostile pages cannot escape the sandbox or launch other apps.
     */
    private boolean shouldBlockNavigation(Uri uri) {
      if (!allowNavigation) return true;
      String scheme = uri.getScheme();
      return scheme == null
        || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"));
    }

    @Override
    public void onPageStarted(WebView view, String url, Bitmap favicon) {
      super.onPageStarted(view, url, favicon);
      // Best effort: gets the bridge in before the page's own scripts run.
      injectBridge(view);
    }

    @Override
    public void onPageFinished(WebView view, String url) {
      super.onPageFinished(view, url);
      WebViewInstance.this.onPageFinished(view);
    }
  }

  private class InstanceWebChromeClient extends WebChromeClient {
    @Override
    public void onReceivedTitle(WebView view, String pageTitle) {
      super.onReceivedTitle(view, pageTitle);
      try {
        JSONObject data = new JSONObject();
        data.put("title", pageTitle != null ? pageTitle : "");
        plugin.sendEventToCordova(id, "titleChanged", data);
      } catch (JSONException e) {
        Log.e(TAG, "onReceivedTitle error", e);
      }
    }
  }

  private class InstanceDownloadListener implements DownloadListener {
    private final Context context;

    InstanceDownloadListener(Context context) {
      this.context = context;
    }

    @Override
    public void onDownloadStart(final String url, final String userAgent, String contentDisposition, final String mimeType, long contentLength) {
      if (context instanceof Activity && ((Activity) context).isFinishing()) return;
      final String fileName = URLUtil.guessFileName(url, contentDisposition, mimeType);

      runOnUiThread(new Runnable() {
        @Override
        public void run() {
          new AlertDialog.Builder(context)
            .setTitle("Download file")
            .setMessage("Do you want to download \"" + fileName + "\"?")
            .setPositiveButton("Yes", new DialogInterface.OnClickListener() {
              @Override
              public void onClick(DialogInterface dialog, int which) {
                DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
                request.setMimeType(mimeType);
                request.addRequestHeader("User-Agent", userAgent);
                request.setDescription("Downloading file...");
                request.setTitle(fileName);
                request.allowScanningByMediaScanner();
                request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);

                DownloadManager dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
                if (dm != null) {
                  dm.enqueue(request);
                  Toast.makeText(context, "Download started...", Toast.LENGTH_SHORT).show();
                }
              }
            })
            .setNegativeButton("Cancel", null)
            .show();
        }
      });
    }
  }

  public class JsBridge {
    @JavascriptInterface
    public void postMessage(String message) {
      plugin.sendMessageToCordova(id, message);
    }
  }
}
