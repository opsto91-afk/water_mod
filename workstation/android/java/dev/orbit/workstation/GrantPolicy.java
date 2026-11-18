package dev.orbit.workstation;

import java.io.IOException;

/** Authorize document requests before resolving any descendants in a provider. */
final class GrantPolicy {

  static String[] check(
    String op,
    String path,
    boolean writable,
    String onlyFile
  ) throws IOException {
    boolean list = op.equals("list"),
      read = op.equals("read"),
      save = op.equals("save"),
      create = op.equals("create") || op.equals("mkdir");
    if (!(list || read || save || create)) throw new IOException(
      "Unknown document operation."
    );
    String[] p = parts(path, list);
    if (!writable && (save || create)) throw new IOException(
      "This grant is read-only."
    );
    if (
      onlyFile != null &&
      !(list && p.length == 0) &&
      !((read || save) && p.length == 1 && onlyFile.equals(p[0]))
    ) throw new IOException("Outside the single-file grant.");
    return p;
  }

  private static String[] parts(String path, boolean empty) throws IOException {
    if (empty && path.isEmpty()) return new String[0];
    String[] p = path.split("/", -1);
    if (p.length > 20 || path.length() > 600) throw new IOException(
      "Path too long."
    );
    for (String s : p)
      if (!visible(s)) throw new IOException(
        "Use visible relative names in this project."
      );
    return p;
  }

  private static final java.util.regex.Pattern NON_VISIBLE =
    java.util.regex.Pattern.compile(
      "[\\\\/\\p{Cc}\\u2028-\\u202e\\u2066-\\u2069]"
    );

  static boolean visible(String s) {
    return (
      s != null &&
      !s.isEmpty() &&
      !s.startsWith(".") &&
      s.length() <= 100 &&
      !NON_VISIBLE.matcher(s).find()
    );
  }
}
