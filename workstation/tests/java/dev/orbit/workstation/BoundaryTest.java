package dev.orbit.workstation;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.cert.Certificate;
import java.util.Arrays;
import java.util.concurrent.*;
import javax.net.ssl.HttpsURLConnection;
import org.json.*;

/** JVM contract tests. No live keys, network, Android device, or real Termux required. */
public final class BoundaryTest {

  static void check(boolean v) {
    if (!v) throw new AssertionError();
  }

  static void reject(String op, String path, boolean w, String file)
    throws Exception {
    try {
      GrantPolicy.check(op, path, w, file);
      throw new AssertionError("Unexpected grant");
    } catch (IOException expected) {}
  }

  static void grants() throws Exception {
    GrantPolicy.check("list", "", false, "one.md");
    GrantPolicy.check("read", "one.md", false, "one.md");
    GrantPolicy.check("save", "one.md", true, "one.md");
    GrantPolicy.check("create", "nested/file", true, null);
    for (String v : new String[] {
      "../one.md",
      "nested/one.md",
      "sibling",
      "/one.md",
    })
      reject("read", v, true, "one.md");
    reject("save", "one.md", false, "one.md");
    reject("save", "nested/file", false, null);
    reject("list", "nested", true, "one.md");
    reject("mkdir", "new", true, "one.md");
    reject("create", "one.md", true, "one.md");
    reject("read", ".hidden/x", true, null);
  }

  static void argv() throws Exception {
    JSONObject q = new JSONObject()
      .put("tool", "agy-version")
      .put("slot", "home");
    TermuxCommand version = new TermuxCommand(q, null);
    check(
      version.path.endsWith("/home/.local/bin/agy") &&
        Arrays.equals(version.args, new String[] { "--version" })
    );
    q
      .put("tool", "agy-open")
      .put("brief", "write a project plan")
      .put("capture", true);
    try {
      new TermuxCommand(q, null);
      throw new AssertionError();
    } catch (IOException expected) {}
    TermuxCommand open = new TermuxCommand(q, version.path);
    check(
      open.interactive &&
        open.capture &&
        open.stdin == null &&
        open.args.length == 3 &&
        open.args[2].equals("--prompt-interactive=write a project plan")
    );
    q.put("tool", "agy-usage");
    check(
      Arrays.equals(
        new TermuxCommand(q, version.path).args,
        new String[] { "-p", "/usage" }
      )
    );
    q.put("slot", "other");
    try {
      new TermuxCommand(q, version.path);
      throw new AssertionError();
    } catch (IOException expected) {}
    check(
      new TermuxCommand(
        new JSONObject().put("tool", "wc").put("text", "one two"),
        null
      ).stdin.equals("one two")
    );
  }

  static class Stub extends HttpsURLConnection {

    final ByteArrayOutputStream body = new ByteArrayOutputStream();
    volatile boolean disconnected;

    Stub(URL url) {
      super(url);
    }

    public void connect() {}

    public void disconnect() {
      disconnected = true;
    }

    public boolean usingProxy() {
      return false;
    }

    public String getCipherSuite() {
      return "fixture";
    }

    public Certificate[] getLocalCertificates() {
      return null;
    }

    public Certificate[] getServerCertificates() {
      return null;
    }

    public OutputStream getOutputStream() {
      return body;
    }

    public int getResponseCode() {
      return status;
    }

    public InputStream getInputStream() {
      return new ByteArrayInputStream(
        response.getBytes(StandardCharsets.UTF_8)
      );
    }
  }

  static volatile Stub last;
  static volatile String response;
  static volatile int status = 200;

  static class Result implements CloudClient.Reply {

    String text;
    boolean ok;
    final CountDownLatch done = new CountDownLatch(1);

    public void end(String id, boolean success, String value) {
      ok = success;
      text = value;
      done.countDown();
    }
  }

  static int seq;

  static Result run(CloudClient c, String p) throws Exception {
    JSONObject q = new JSONObject()
      .put("provider", p)
      .put("model", "fixture")
      .put("system", "rules")
      .put("budget", 1024)
      .put(
        "turns",
        new JSONArray().put(
          new JSONObject().put("role", "user").put("content", "hi")
        )
      );
    Result r = new Result();
    c.start(String.valueOf(++seq), q, r);
    check(r.done.await(3, TimeUnit.SECONDS));
    return r;
  }

  public static void main(String[] args) throws Exception {
    grants();
    argv();
    URL.setURLStreamHandlerFactory(
      new URLStreamHandlerFactory() {
        public URLStreamHandler createURLStreamHandler(String protocol) {
          if (!protocol.equals("https")) return null;
          return new URLStreamHandler() {
            protected URLConnection openConnection(URL url) {
              return last = new Stub(url);
            }
          };
        }
      }
    );
    CloudClient c = new CloudClient();
    try {
      for (String p : new String[] { "claude", "deepseek" }) {
        boolean claude = p.equals("claude");
        try {
          run(c, p);
          throw new AssertionError();
        } catch (IOException expected) {}
        c.put(p, "fixture-key");
        response = claude
          ? "{\"content\":[{\"type\":\"text\",\"text\":\"answer\"}]}"
          : "{\"choices\":[{\"message\":{\"content\":\"answer\"}}]}";
        status = 200;
        Result r = run(c, p);
        check(r.ok && r.text.equals("answer"));
        String host = claude ? "api.anthropic.com" : "api.deepseek.com",
          path = claude ? "/v1/messages" : "/v1/chat/completions";
        check(
          last.getURL().getHost().equals(host) &&
            last.getURL().getPath().equals(path) &&
            !last.getInstanceFollowRedirects()
        );
        check(last.getRequestMethod().equals("POST"));
        String key = last.getRequestProperty(
          claude ? "x-api-key" : "Authorization"
        );
        check((claude ? "fixture-key" : "Bearer fixture-key").equals(key));
        if (claude) check(
          "2023-06-01".equals(last.getRequestProperty("anthropic-version"))
        );
        JSONObject req = new JSONObject(last.body.toString("UTF-8"));
        check(
          !req.toString().contains("fixture-key") &&
            !req.has("tools") &&
            req.getInt("max_tokens") == 1024
        );
        check(
          claude
            ? req.getString("system").equals("rules")
            : req
                .getJSONArray("messages")
                .getJSONObject(0)
                .getString("role")
                .equals("system")
        );
        status = 429;
        Result err = run(c, p);
        check(
          !err.ok &&
            err.text.contains("429") &&
            !err.text.contains("fixture-key")
        );
        c.forget(p);
        check(!c.has(p));
      }
    } finally {
      c.close();
    }
    System.out.println(
      "Native grant policy, fixed CLI argv, both HTTPS contracts and rate-limit paths: OK (mock transport)."
    );
  }
}
