package dev.orbit.workstation;

import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.*;
import android.webkit.*;
import java.io.*;
import java.lang.ref.WeakReference;
import java.util.*;
import java.util.concurrent.*;
import org.json.JSONObject;

/** Offline WebView shell. Exposes folder-scoped operations, not a generic file/command bridge. */
public final class StationActivity extends Activity {

  static WeakReference<StationActivity> current = new WeakReference<
    StationActivity
  >(null);
  private static final int MODEL = 1,
    TREE = 2,
    PERMISSION = 3;
  private static final String ORIGIN = "https://orbit.invalid/",
    PERM = "com.termux.permission.RUN_COMMAND";
  private static final int GRANTS =
    Intent.FLAG_GRANT_READ_URI_PERMISSION |
    Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
  private final ExecutorService io = Executors.newSingleThreadExecutor();
  private final Map<String, String> tickets = new HashMap<String, String>();
  private WebView web;
  private ValueCallback<Uri[]> files;
  private String pickId;
  private volatile Uri tree;

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    current = new WeakReference<StationActivity>(this);
    getWindow().setStatusBarColor(0xff171820);
    getWindow().setNavigationBarColor(0xff171820);
    String saved = getPreferences(0).getString("tree", null);
    if (saved != null) tree = Uri.parse(saved);
    web = new WebView(this);
    web.setBackgroundColor(0xff101118);
    setContentView(web);
    WebSettings w = web.getSettings();
    w.setJavaScriptEnabled(true);
    w.setDomStorageEnabled(true);
    w.setAllowFileAccess(false);
    w.setAllowContentAccess(false);
    w.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
    web.setWebViewClient(
      new WebViewClient() {
        @Override
        public boolean shouldOverrideUrlLoading(
          WebView v,
          WebResourceRequest r
        ) {
          return true;
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(
          WebView v,
          WebResourceRequest r
        ) {
          Uri u = r.getUrl();
          String path = u.getPath();
          if (
            ORIGIN.equals(u.getScheme() + "://" + u.getAuthority() + "/") &&
            path != null &&
            path.matches("/[a-zA-Z0-9_./-]+") &&
            !path.contains("..")
          ) {
            String mime = path.endsWith(".html")
              ? "text/html"
              : path.endsWith(".js")
                ? "text/javascript"
                : path.endsWith(".css")
                  ? "text/css"
                  : path.endsWith(".svg")
                    ? "image/svg+xml"
                    : path.endsWith(".wasm")
                      ? "application/wasm"
                      : "text/plain";
            try {
              return new WebResourceResponse(
                mime,
                path.endsWith(".wasm") ? null : "UTF-8",
                getAssets().open(path.substring(1))
              );
            } catch (IOException ignored) {}
          }
          return new WebResourceResponse(
            "text/plain",
            "UTF-8",
            404,
            "Not Found",
            Collections.<String, String>emptyMap(),
            new ByteArrayInputStream(new byte[0])
          );
        }
      }
    );
    web.setWebChromeClient(
      new WebChromeClient() {
        @Override
        public boolean onShowFileChooser(
          WebView v,
          ValueCallback<Uri[]> cb,
          FileChooserParams params
        ) {
          if (files != null) files.onReceiveValue(null);
          files = cb;
          Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*");
          try {
            startActivityForResult(i, MODEL);
          } catch (ActivityNotFoundException e) {
            files.onReceiveValue(null);
            files = null;
          }
          return true;
        }
      }
    );
    web.addJavascriptInterface(new Bridge(), "OrbitHost");
    web.loadUrl(ORIGIN + "index.html");
  }

  public final class Bridge {

    @JavascriptInterface
    public void request(final String data) {
      if (data == null || data.length() > 900000) return;
      io.execute(
        new Runnable() {
          public void run() {
            String id = "";
            try {
              final JSONObject q = new JSONObject(data);
              id = q.getString("id");
              if (!id.matches("[0-9]{1,12}")) return;
              final String key = id,
                op = q.getString("op");
              if (op.equals("modelPage")) {
                runOnUiThread(
                  new Runnable() {
                    public void run() {
                      try {
                        startActivity(
                          new Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse(
                              "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/tree/main"
                            )
                          )
                        );
                        reply(key, true, true);
                      } catch (Exception e) {
                        error(key, e);
                      }
                    }
                  }
                );
                return;
              }
              if (op.equals("pick") || op.equals("terminal")) {
                runOnUiThread(
                  new Runnable() {
                    public void run() {
                      try {
                        if (op.equals("pick")) pick(key);
                        else terminal(key, q);
                      } catch (Exception e) {
                        error(key, e);
                      }
                    }
                  }
                );
                return;
              }
              if (op.equals("forget")) {
                setTree(null);
                reply(id, true, true);
                return;
              }
              Uri u = tree;
              if (op.equals("current")) {
                reply(
                  id,
                  true,
                  u == null
                    ? JSONObject.NULL
                    : new TreeStore(getContentResolver(), u).label()
                );
                return;
              }
              if (u == null) throw new IOException(
                "Choose a project folder first."
              );
              reply(
                id,
                true,
                new TreeStore(getContentResolver(), u).run(op, q)
              );
            } catch (Exception e) {
              error(id, e);
            }
          }
        }
      );
    }
  }

  private synchronized void setTree(Uri next) {
    Uri old = tree;
    tree = next;
    if (next == null) getPreferences(0).edit().remove("tree").apply();
    else getPreferences(0).edit().putString("tree", next.toString()).apply();
    if (old != null && !old.equals(next)) try {
      getContentResolver().releasePersistableUriPermission(old, GRANTS);
    } catch (SecurityException ignored) {}
  }

  private void pick(String id) {
    if (pickId != null) {
      reply(id, false, "A folder picker is already open.");
      return;
    }
    pickId = id;
    try {
      startActivityForResult(
        new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
          GRANTS | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        ),
        TREE
      );
    } catch (RuntimeException e) {
      pickId = null;
      error(id, e);
    }
  }

  @Override
  protected void onActivityResult(int code, int result, Intent data) {
    super.onActivityResult(code, result, data);
    final Uri u = result == RESULT_OK && data != null ? data.getData() : null;
    if (code == MODEL) {
      if (files != null) files.onReceiveValue(
        u != null && "content".equals(u.getScheme()) ? new Uri[] { u } : null
      );
      files = null;
    }
    if (code == TREE && pickId != null) {
      final String id = pickId;
      pickId = null;
      if (u == null) {
        reply(id, true, JSONObject.NULL);
        return;
      }
      try {
        if ((data.getFlags() & GRANTS) != GRANTS) throw new IOException(
          "Select a writable folder."
        );
        getContentResolver().takePersistableUriPermission(u, GRANTS);
        io.execute(
          new Runnable() {
            public void run() {
              try {
                String label = new TreeStore(getContentResolver(), u).label();
                setTree(u);
                reply(id, true, label);
              } catch (Exception e) {
                error(id, e);
              }
            }
          }
        );
      } catch (Exception e) {
        error(id, e);
      }
    }
  }

  private void terminal(final String id, JSONObject q) throws Exception {
    String tool = q.getString("tool");
    if (!tool.equals("wc") && !tool.equals("date")) throw new IOException(
      "Unsupported terminal tool."
    );
    if (checkSelfPermission(PERM) != PackageManager.PERMISSION_GRANTED) {
      try {
        getPackageManager().getPackageInfo("com.termux", 0);
      } catch (PackageManager.NameNotFoundException e) {
        throw new IOException(
          "Termux is not installed. The rest of the workstation works without it."
        );
      }
      requestPermissions(new String[] { PERM }, PERMISSION);
      throw new IOException(
        "Termux access is optional. Grant the permission, enable allow-external-apps in Termux, then retry."
      );
    }
    String text = q.optString("text", "");
    if (text.getBytes("UTF-8").length > TreeStore.LIMIT) throw new IOException(
      "Input too large."
    );
    final String ticket = UUID.randomUUID().toString();
    Intent back = new Intent(this, TermuxResult.class)
      .setData(Uri.parse("orbit:result/" + ticket))
      .putExtra("ticket", ticket);
    PendingIntent cb = PendingIntent.getBroadcast(
      this,
      0,
      back,
      PendingIntent.FLAG_ONE_SHOT |
        (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0)
    );
    Intent i = new Intent("com.termux.RUN_COMMAND").setClassName(
      "com.termux",
      "com.termux.app.RunCommandService"
    );
    i.putExtra(
      "com.termux.RUN_COMMAND_PATH",
      "/data/data/com.termux/files/usr/bin/" + tool
    );
    i.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true);
    i.putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", cb);
    if (tool.equals("wc")) i.putExtra("com.termux.RUN_COMMAND_STDIN", text);
    else i.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", new String[] { "-u" });
    tickets.put(ticket, id);
    try {
      if (startService(i) == null) throw new IOException(
        "Termux command service not found."
      );
    } catch (Exception e) {
      tickets.remove(ticket);
      cb.cancel();
      throw e;
    }
    new Handler(Looper.getMainLooper()).postDelayed(
      new Runnable() {
        public void run() {
          String k = tickets.remove(ticket);
          if (k != null) reply(
            k,
            false,
            "Termux did not reply. Check its permissions and allow-external-apps setting."
          );
        }
      },
      25000
    );
  }

  void onTermuxResult(Intent data) {
    String id = tickets.remove(data.getStringExtra("ticket"));
    if (id == null) return;
    Bundle b = data.getBundleExtra("result");
    if (b == null) {
      reply(id, false, "Termux returned no result.");
      return;
    }
    String out =
      b.getString("stdout", "") +
      b.getString("stderr", "") +
      b.getString("errmsg", "");
    boolean ok =
      b.getInt("exitCode", -1) == 0 && b.getInt("err", RESULT_OK) == RESULT_OK;
    reply(
      id,
      ok,
      out.isEmpty()
        ? "Termux exit: " + b.getInt("exitCode", -1)
        : out.substring(0, Math.min(8192, out.length()))
    );
  }

  private void error(String id, Exception e) {
    reply(
      id,
      false,
      e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()
    );
  }

  private void reply(String id, boolean ok, Object result) {
    try {
      final String s = new JSONObject()
        .put("id", id)
        .put("ok", ok)
        .put(ok ? "value" : "error", result)
        .toString();
      runOnUiThread(
        new Runnable() {
          public void run() {
            if (!isDestroyed() && web != null) web.evaluateJavascript(
              "window.orbitReply && window.orbitReply(" +
                JSONObject.quote(s) +
                ")",
              null
            );
          }
        }
      );
    } catch (Exception ignored) {}
  }

  @Override
  public void onBackPressed() {
    web.evaluateJavascript(
      "window.orbitBack ? window.orbitBack() : 'exit'",
      new ValueCallback<String>() {
        public void onReceiveValue(String result) {
          if ("\"stay\"".equals(result)) return;
          if ("\"confirm\"".equals(result)) new AlertDialog.Builder(
            StationActivity.this
          )
            .setMessage(
              "Close Orbit? Unsaved edits or an active assistant turn will be lost."
            )
            .setNegativeButton("Stay", null)
            .setPositiveButton(
              "Close",
              new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) {
                  finish();
                }
              }
            )
            .show();
          else finish();
        }
      }
    );
  }

  @Override
  protected void onDestroy() {
    if (current.get() == this) current.clear();
    if (files != null) files.onReceiveValue(null);
    io.shutdownNow();
    tickets.clear();
    web.removeJavascriptInterface("OrbitHost");
    web.destroy();
    web = null;
    super.onDestroy();
  }
}
