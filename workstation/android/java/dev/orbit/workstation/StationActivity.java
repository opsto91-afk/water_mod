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

/** Packaged UI with explicit document grants and opt-in native provider connections. */
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

  private static final class Receipt {

    String id, probe;
    PendingIntent callback;
    Runnable timeout;
  }

  private final Handler timer = new Handler(Looper.getMainLooper());
  private final Map<String, Receipt> tickets = new HashMap<String, Receipt>();
  private WebView web;
  private ValueCallback<Uri[]> files;
  private String pickId;
  private volatile Uri tree;
  private volatile String grantId = UUID.randomUUID().toString();
  private volatile boolean single = false,
    writable = true;
  private boolean pickingFile, pickingWrite;
  private final CloudClient cloud = new CloudClient();
  private String checkedAgy = null,
    interactiveTicket = null;

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    current = new WeakReference<StationActivity>(this);
    getWindow().setStatusBarColor(0xff242321);
    getWindow().setNavigationBarColor(0xff242321);
    String saved = getPreferences(0).getString("tree", null);
    if (saved != null) tree = Uri.parse(saved);
    single = getPreferences(0).getBoolean("single", false);
    writable = getPreferences(0).getBoolean("write", true);
    io.execute(
      new Runnable() {
        public void run() {
          pruneGrants();
        }
      }
    );
    web = new WebView(this);
    web.setBackgroundColor(0xff191918);
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
              if (op.equals("infer")) {
                cloud.start(
                  key,
                  q,
                  new CloudClient.Reply() {
                    public void end(String id, boolean ok, String text) {
                      reply(id, ok, text);
                    }
                  }
                );
                return;
              }
              if (op.equals("cancelInfer")) {
                cloud.cancel(q.getString("target"));
                reply(id, true, true);
                return;
              }
              if (
                op.equals("keyStatus") ||
                op.equals("forgetKey") ||
                op.equals("setKey")
              ) {
                final String provider = q.getString("provider");
                if (!CloudClient.valid(provider)) throw new IOException(
                  "Unknown provider."
                );
                if (op.equals("keyStatus")) {
                  reply(id, true, cloud.has(provider));
                  return;
                }
                if (op.equals("forgetKey")) {
                  cloud.forget(provider);
                  reply(id, true, true);
                  return;
                }
                runOnUiThread(
                  new Runnable() {
                    public void run() {
                      askKey(key, provider);
                    }
                  }
                );
                return;
              }
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
                        if (op.equals("pick")) pick(
                          key,
                          q.optBoolean("single", false),
                          q.optBoolean("write", false)
                        );
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
                setTree(null, false, false);
                reply(id, true, true);
                return;
              }
              Uri u = tree;
              if (op.equals("current")) {
                reply(
                  id,
                  true,
                  u == null ? JSONObject.NULL : scopeMeta(u, single, writable)
                );
                return;
              }
              if (!grantId.equals(q.optString("grant"))) throw new IOException(
                "Workspace changed. Select the file again."
              );
              if (u == null) throw new IOException(
                "Choose a project folder first."
              );
              reply(
                id,
                true,
                new TreeStore(getContentResolver(), u, single, writable).run(
                  op,
                  q
                )
              );
            } catch (Exception e) {
              error(id, e);
            }
          }
        }
      );
    }
  }

  private void askKey(final String id, final String provider) {
    final android.widget.EditText field = new android.widget.EditText(this);
    field.setSingleLine(true);
    field.setSaveEnabled(false);
    field.setImportantForAutofill(
      android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
    );
    field.setInputType(
      android.text.InputType.TYPE_CLASS_TEXT |
        android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
    );
    AlertDialog box = new AlertDialog.Builder(this)
      .setTitle(
        (provider.equals("claude") ? "Claude" : "DeepSeek") + " API key"
      )
      .setMessage(
        "Kept in memory until Orbit closes. Never included in chat or workflow files. Provider usage may cost money."
      )
      .setView(field)
      .setPositiveButton(
        "Use for session",
        new DialogInterface.OnClickListener() {
          public void onClick(DialogInterface d, int w) {
            try {
              cloud.put(provider, field.getText().toString().trim());
              reply(id, true, true);
            } catch (Exception e) {
              error(id, e);
            } finally {
              field.getText().clear();
            }
          }
        }
      )
      .setNegativeButton(
        "Cancel",
        new DialogInterface.OnClickListener() {
          public void onClick(DialogInterface d, int w) {
            reply(id, true, false);
          }
        }
      )
      .create();
    box.setOnCancelListener(
      new DialogInterface.OnCancelListener() {
        public void onCancel(DialogInterface d) {
          reply(id, true, false);
        }
      }
    );
    box
      .getWindow()
      .addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
    box.show();
  }

  private JSONObject scopeMeta(Uri u, boolean one, boolean write)
    throws Exception {
    return new JSONObject()
      .put("name", new TreeStore(getContentResolver(), u, one, write).label())
      .put("single", one)
      .put("write", write)
      .put("grant", grantId);
  }

  // The only persistent Android permission Orbit needs is the active document/tree.
  private void pruneGrants() {
    for (UriPermission perm : getContentResolver().getPersistedUriPermissions()) {
      int held =
        (perm.isReadPermission() ? Intent.FLAG_GRANT_READ_URI_PERMISSION : 0) |
        (perm.isWritePermission() ? Intent.FLAG_GRANT_WRITE_URI_PERMISSION : 0);
      int keep = perm.getUri().equals(tree)
        ? (writable ? GRANTS : Intent.FLAG_GRANT_READ_URI_PERMISSION)
        : 0;
      int release = held & ~keep;
      if (release != 0) try {
        getContentResolver().releasePersistableUriPermission(
          perm.getUri(),
          release
        );
      } catch (SecurityException ignored) {}
    }
  }

  private void setTree(Uri next, boolean one, boolean write) {
    grantId = UUID.randomUUID().toString();
    tree = next;
    single = one;
    writable = write;
    getPreferences(0)
      .edit()
      .putString("tree", next == null ? null : next.toString())
      .putBoolean("single", one)
      .putBoolean("write", write)
      .apply();
    pruneGrants();
  }

  private void pick(String id, boolean one, boolean write) {
    if (pickId != null) {
      reply(id, false, "A folder picker is already open.");
      return;
    }
    pickId = id;
    pickingFile = one;
    pickingWrite = write;
    try {
      Intent pick = new Intent(
        one ? Intent.ACTION_OPEN_DOCUMENT : Intent.ACTION_OPEN_DOCUMENT_TREE
      );
      if (one) pick.addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
      pick.addFlags(
        (write ? GRANTS : Intent.FLAG_GRANT_READ_URI_PERMISSION) |
          Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
      );
      startActivityForResult(pick, TREE);
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
        final boolean one = pickingFile,
          write = pickingWrite;
        final int flags = write
          ? GRANTS
          : Intent.FLAG_GRANT_READ_URI_PERMISSION;
        if (
          !"content".equals(u.getScheme()) || (data.getFlags() & flags) != flags
        ) throw new IOException(
          "Provider did not grant the requested access. Try read-only."
        );
        io.execute(
          new Runnable() {
            public void run() {
              try {
                JSONObject meta = scopeMeta(u, one, write);
                getContentResolver().takePersistableUriPermission(u, flags);
                setTree(u, one, write);
                reply(id, true, meta.put("grant", grantId));
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
    final TermuxCommand command = new TermuxCommand(q, checkedAgy);
    if (command.tool.equals("agy-version")) checkedAgy = null;
    if (
      command.interactive && command.capture && interactiveTicket != null
    ) throw new IOException(
      "Exit the current captured CLI session before starting another."
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
    final String ticket = UUID.randomUUID().toString();
    Intent i = new Intent("com.termux.RUN_COMMAND").setClassName(
      "com.termux",
      "com.termux.app.RunCommandService"
    );
    i.putExtra("com.termux.RUN_COMMAND_PATH", command.path);
    i.putExtra("com.termux.RUN_COMMAND_BACKGROUND", !command.interactive);
    if (command.args.length != 0) i.putExtra(
      "com.termux.RUN_COMMAND_ARGUMENTS",
      command.args
    );
    if (command.stdin != null) i.putExtra(
      "com.termux.RUN_COMMAND_STDIN",
      command.stdin
    );
    if (command.interactive) i.putExtra(
      "com.termux.RUN_COMMAND_SESSION_ACTION",
      "0"
    );
    if (!command.capture) {
      if (startService(i) == null) throw new IOException(
        "Termux command service not found."
      );
      reply(
        id,
        true,
        "Requested interactive agy in Termux (no transcript). If it doesn’t open, tap the Termux notification."
      );
      return;
    }
    final Intent back = new Intent(this, TermuxResult.class)
      .setData(Uri.parse("orbit:result/" + ticket))
      .putExtra("ticket", ticket);

    final PendingIntent cb = PendingIntent.getBroadcast(
      this,
      0,
      back,
      PendingIntent.FLAG_ONE_SHOT |
        (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0)
    );
    i.putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", cb);
    final Receipt receipt = new Receipt();
    receipt.id = id;
    receipt.probe = command.tool.equals("agy-version") ? command.path : null;
    receipt.callback = cb;
    tickets.put(ticket, receipt);
    if (command.interactive) interactiveTicket = ticket;
    try {
      if (startService(i) == null) throw new IOException(
        "Termux command service not found."
      );
    } catch (Exception e) {
      tickets.remove(ticket);
      if (ticket.equals(interactiveTicket)) interactiveTicket = null;
      cb.cancel();
      throw e;
    }
    receipt.timeout = new Runnable() {
      public void run() {
        Receipt expired = take(ticket);
        if (expired != null) reply(
          expired.id,
          false,
          "No Termux callback. Check its permission/allow-external-apps and the selected agy location if applicable."
        );
      }
    };
    timer.postDelayed(receipt.timeout, command.interactive ? 3600000 : 45000);
  }

  private Receipt take(String ticket) {
    Receipt r = tickets.remove(ticket);
    if (ticket != null && ticket.equals(interactiveTicket)) interactiveTicket =
      null;
    if (r != null) {
      if (r.timeout != null) timer.removeCallbacks(r.timeout);
      r.callback.cancel();
    }
    return r;
  }

  void onTermuxResult(Intent data) {
    String ticket = data.getStringExtra("ticket");
    boolean interactive = ticket != null && ticket.equals(interactiveTicket);
    Receipt r = take(ticket);
    if (r == null) return;
    String id = r.id;
    Bundle b = data.getBundleExtra("result");
    if (b == null) {
      reply(id, false, "Termux returned no result.");
      return;
    }
    int exit = b.getInt("exitCode", -1);
    boolean ok = exit == 0 && b.getInt("err", RESULT_OK) == RESULT_OK;
    String probe = r.probe;
    if (probe != null) checkedAgy = ok ? probe : null;
    String out =
      b.getString("stdout", "") +
      b.getString("stderr", "") +
      b.getString("errmsg", "");
    if (out.length() > 8192) out = interactive
      ? "[last 8192 characters]\n" + out.substring(out.length() - 8192)
      : out.substring(0, 8192) + "\n[output clipped]";
    reply(id, ok, "Termux exit: " + exit + (out.isEmpty() ? "" : "\n" + out));
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
    cloud.close();
    io.shutdownNow();
    timer.removeCallbacksAndMessages(null);
    for (Receipt r : tickets.values()) r.callback.cancel();
    tickets.clear();
    web.removeJavascriptInterface("OrbitHost");
    web.destroy();
    web = null;
    super.onDestroy();
  }
}
