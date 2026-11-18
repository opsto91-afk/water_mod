package dev.orbit.workstation;

import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.HttpsURLConnection;
import org.json.*;

/** Session-only credentials. Only these two fixed HTTPS text APIs, never model-supplied URLs or tools. */
final class CloudClient {

  interface Reply {
    void end(String id, boolean ok, String text);
  }

  private final Map<String, String> keys = new ConcurrentHashMap<
    String,
    String
  >();
  private final ExecutorService pool = Executors.newFixedThreadPool(5);
  private final ConcurrentMap<String, Job> jobs = new ConcurrentHashMap<
    String,
    Job
  >();

  static final class Job {

    volatile boolean cancelled;
    volatile HttpsURLConnection conn;
  }

  static boolean valid(String p) {
    return "claude".equals(p) || "deepseek".equals(p);
  }

  boolean has(String p) {
    return keys.containsKey(p);
  }

  void forget(String p) {
    keys.remove(p);
  }

  void put(String p, String key) throws IOException {
    if (!valid(p) || !key.matches("[!-~]{8,2048}")) throw new IOException(
      "Invalid provider or key format."
    );
    keys.put(p, key);
  }

  void cancel(String id) {
    Job j = jobs.get(id);
    if (j != null) {
      j.cancelled = true;
      HttpsURLConnection c = j.conn;
      if (c != null) c.disconnect();
    }
  }

  void close() {
    keys.clear();
    for (String id : jobs.keySet()) cancel(id);
    pool.shutdownNow();
  }

  static JSONObject payload(String provider, JSONObject q) throws Exception {
    if (!valid(provider)) throw new IOException("Unknown provider.");
    String model = q.getString("model"),
      sys = q.getString("system");
    int budget = q.getInt("budget");
    if (
      !model.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}") ||
      sys.length() > 5000 ||
      budget < 1 ||
      budget > 4096
    ) throw new IOException("Invalid model settings.");
    JSONArray src = q.getJSONArray("turns"),
      msgs = new JSONArray();
    if (src.length() == 0 || src.length() > 16) throw new IOException(
      "Invalid chat length."
    );
    if (provider.equals("deepseek")) msgs.put(
      new JSONObject().put("role", "system").put("content", sys)
    );
    int total = 0;
    for (int i = 0; i < src.length(); i++) {
      JSONObject item = src.getJSONObject(i);
      String role = item.getString("role"),
        text = item.getString("content");
      total += text.length();
      if (
        (!role.equals("user") && !role.equals("assistant")) || total > 58000
      ) throw new IOException("Invalid chat payload.");
      msgs.put(new JSONObject().put("role", role).put("content", text));
    }
    JSONObject body = new JSONObject()
      .put("model", model)
      .put("max_tokens", budget)
      .put("messages", msgs);
    if (provider.equals("claude")) body.put("system", sys);
    return body;
  }

  static String answer(String provider, JSONObject json) throws Exception {
    if (provider.equals("deepseek")) {
      Object content = json
        .getJSONArray("choices")
        .getJSONObject(0)
        .getJSONObject("message")
        .get("content");
      if (!(content instanceof String)) throw new IOException("No final text.");
      return (String) content;
    }
    JSONArray blocks = json.getJSONArray("content");
    StringBuilder b = new StringBuilder();
    for (int i = 0; i < blocks.length(); i++) {
      JSONObject block = blocks.getJSONObject(i);
      if ("text".equals(block.optString("type"))) b.append(
        block.getString("text")
      );
    }
    return b.toString();
  }

  void start(final String id, JSONObject q, final Reply callback)
    throws Exception {
    final String p = q.getString("provider");
    final String key = keys.get(p);
    if (key == null) throw new IOException(
      "Add a session API key in Models first."
    );
    final byte[] body = payload(p, q)
      .toString()
      .getBytes(StandardCharsets.UTF_8);
    if (jobs.size() >= 6) throw new IOException("Provider queue is full.");
    final Job job = new Job();
    if (jobs.putIfAbsent(id, job) != null) throw new IOException(
      "Duplicate provider request."
    );
    pool.execute(
      new Runnable() {
        public void run() {
          try {
            boolean claude = p.equals("claude");
            HttpsURLConnection c = (HttpsURLConnection) new URL(
              claude
                ? "https://api.anthropic.com/v1/messages"
                : "https://api.deepseek.com/v1/chat/completions"
            ).openConnection();
            job.conn = c;
            if (job.cancelled) throw new IOException();
            c.setInstanceFollowRedirects(false);
            c.setUseCaches(false);
            c.setConnectTimeout(12000);
            c.setReadTimeout(120000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setFixedLengthStreamingMode(body.length);
            c.setRequestProperty("Content-Type", "application/json");
            if (claude) {
              c.setRequestProperty("x-api-key", key);
              c.setRequestProperty("anthropic-version", "2023-06-01");
            } else c.setRequestProperty("Authorization", "Bearer " + key);
            try (OutputStream out = c.getOutputStream()) {
              if (job.cancelled) throw new IOException();
              out.write(body);
            }
            int code = c.getResponseCode();
            if (code != 200) {
              callback.end(
                id,
                false,
                "Provider HTTP " +
                  code +
                  ". Check the model ID, key, balance or rate limit. No automatic retry."
              );
              return;
            }
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
              byte[] block = new byte[8192];
              int n;
              while ((n = in.read(block)) != -1) {
                if (
                  job.cancelled || buf.size() + n > 262144
                ) throw new IOException();
                buf.write(block, 0, n);
              }
            }
            String text = answer(p, new JSONObject(buf.toString("UTF-8")));
            if (text.trim().isEmpty() || job.cancelled) throw new IOException();
            callback.end(
              id,
              true,
              text.substring(0, Math.min(24000, text.length()))
            );
          } catch (Exception e) {
            callback.end(
              id,
              false,
              job.cancelled
                ? "Stopped."
                : "Provider returned no usable reply. Check connectivity, model and output budget."
            );
          } finally {
            if (job.conn != null) job.conn.disconnect();
            jobs.remove(id, job);
          }
        }
      }
    );
  }
}
