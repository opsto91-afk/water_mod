package dev.orbit.workstation;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.webkit.MimeTypeMap;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;

/** All destinations are resolved by descending from the user-granted tree. No raw paths or URIs from JS. */
final class TreeStore {

  static final int LIMIT = 65536;
  final ContentResolver cr;
  final Uri tree;

  final boolean single, writable;

  TreeStore(ContentResolver cr, Uri tree, boolean single, boolean writable) {
    this.single = single;
    this.writable = writable;
    this.cr = cr;
    this.tree = tree;
  }

  private Uri doc(String id) {
    return DocumentsContract.buildDocumentUriUsingTree(tree, id);
  }

  private String root() {
    return DocumentsContract.getTreeDocumentId(tree);
  }

  private static final String[] COLS = {
    Document.COLUMN_DOCUMENT_ID,
    Document.COLUMN_DISPLAY_NAME,
    Document.COLUMN_MIME_TYPE,
  };

  private Cursor children(String parent) throws IOException {
    Cursor c = cr.query(
      DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent),
      COLS,
      null,
      null,
      null
    );
    if (c == null) throw new IOException("Folder provider unavailable.");
    return c;
  }

  String label() throws IOException {
    try (
      Cursor c = cr.query(single ? tree : doc(root()), COLS, null, null, null)
    ) {
      if (c == null || !c.moveToFirst()) throw new IOException(
        "Folder permission lost. Select the folder again."
      );
      String name = c.getString(1);
      if (
        single &&
        (!GrantPolicy.visible(name) ||
          Document.MIME_TYPE_DIR.equals(c.getString(2)))
      ) throw new IOException("Choose one visible text file.");
      return name;
    }
  }

  private String find(String parent, String name) throws IOException {
    String match = null;
    try (Cursor c = children(parent)) {
      while (c.moveToNext()) if (name.equals(c.getString(1))) {
        if (match != null) throw new IOException("Ambiguous filename.");
        match = c.getString(0);
      }
    }
    return match;
  }

  private String walk(String[] p, int end) throws IOException {
    String id = root();
    for (int i = 0; i < end; i++) {
      id = find(id, p[i]);
      if (id == null) throw new IOException(
        "File or folder not found: " + p[i]
      );
    }
    return id;
  }

  private String read(Uri u) throws IOException {
    try (InputStream in = cr.openInputStream(u)) {
      if (in == null) throw new IOException("Can't read this file.");
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] buf = new byte[4096];
      int n;
      while ((n = in.read(buf)) != -1) {
        if (out.size() + n > LIMIT) throw new IOException(
          "Text limit is 64 KiB."
        );
        out.write(buf, 0, n);
      }
      String s = StandardCharsets.UTF_8.newDecoder()
        .decode(ByteBuffer.wrap(out.toByteArray()))
        .toString();
      if (s.indexOf('\0') >= 0) throw new IOException(
        "Binary file; select UTF-8 text."
      );
      return s;
    }
  }

  private byte[] data(JSONObject req) throws Exception {
    Object v = req.get("text");
    if (!(v instanceof String)) throw new IOException("Expected text.");
    String s = (String) v;
    byte[] b = s.getBytes(StandardCharsets.UTF_8);
    if (b.length > LIMIT || s.indexOf('\0') >= 0) throw new IOException(
      "Expected UTF-8 text up to 64 KiB."
    );
    return b;
  }

  private void write(Uri u, byte[] data) throws IOException {
    try (OutputStream out = cr.openOutputStream(u, "wt")) {
      if (out == null) throw new IOException("Provider refused write.");
      out.write(data);
    }
  }

  Object run(String op, JSONObject req) throws Exception {
    String only = single ? label() : null;
    String[] p = GrantPolicy.check(op, req.getString("path"), writable, only);
    if (single) {
      if (op.equals("list")) return new JSONArray().put(
        new JSONObject().put("name", only).put("kind", "file")
      );
      if (op.equals("read")) return read(tree);
      if (op.equals("save")) {
        if (
          !read(tree).equals(req.getString("expected"))
        ) throw new IOException("File changed. Reopen before saving.");
        write(tree, data(req));
        return true;
      }
      throw new IOException("Single-file access cannot create other items.");
    }
    if (op.equals("list")) {
      JSONArray a = new JSONArray();
      try (Cursor c = children(walk(p, p.length))) {
        while (c.moveToNext()) {
          String name = c.getString(1);
          if (!GrantPolicy.visible(name)) continue;
          if (a.length() >= 250) throw new IOException(
            "Open a smaller folder (maximum 250 entries)."
          );
          a.put(
            new JSONObject()
              .put("name", name)
              .put(
                "kind",
                Document.MIME_TYPE_DIR.equals(c.getString(2)) ? "dir" : "file"
              )
          );
        }
      }
      return a;
    }
    String parent = walk(p, p.length - 1),
      name = p[p.length - 1],
      id = find(parent, name);
    if (op.equals("read") || op.equals("save")) {
      if (id == null) throw new IOException("File not found.");
      Uri u = doc(id);
      String old = read(u);
      if (op.equals("read")) return old;
      if (!old.equals(req.getString("expected"))) throw new IOException(
        "File changed; reopen it before saving."
      );
      write(u, data(req));
      return true;
    }
    if (!op.equals("create") && !op.equals("mkdir")) throw new IOException(
      "Unsupported operation."
    );
    if (id != null) throw new IOException(
      "A file or folder already exists here."
    );
    boolean dir = op.equals("mkdir");
    byte[] b = dir ? null : data(req);
    String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(
      name
        .substring(name.lastIndexOf('.') + 1)
        .toLowerCase(java.util.Locale.ROOT)
    );
    Uri created = DocumentsContract.createDocument(
      cr,
      doc(parent),
      dir
        ? Document.MIME_TYPE_DIR
        : mime == null
          ? "application/octet-stream"
          : mime,
      name
    );
    if (created == null) throw new IOException(
      "Provider refused to create the item."
    );
    if (!dir) write(created, b);
    if (find(parent, name) == null) throw new IOException(
      "Provider created the item under a different name. Refresh Explorer."
    );
    return true;
  }
}
