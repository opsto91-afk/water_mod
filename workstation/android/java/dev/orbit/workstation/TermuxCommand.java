package dev.orbit.workstation;

import java.io.IOException;
import org.json.JSONObject;

/** A fixed argv contract. No shell, PATH lookup, user-defined executable or flag strings. */
final class TermuxCommand {

  static final String PREFIX = "/data/data/com.termux/files/usr/bin/",
    HOME = "/data/data/com.termux/files/home/";
  final String tool, path, stdin;
  final String[] args;
  final boolean interactive, capture;

  TermuxCommand(JSONObject q, String checkedPath) throws Exception {
    tool = q.getString("tool");
    interactive = tool.equals("agy-open");
    capture = !interactive || q.optBoolean("capture", false);
    String in = null;
    String[] argv = new String[0];
    if (tool.equals("wc") || tool.equals("date")) {
      path = PREFIX + tool;
      if (tool.equals("wc")) {
        in = q.getString("text");
        if (
          in.getBytes("UTF-8").length > 65536 || in.indexOf('\0') >= 0
        ) throw new IOException("Invalid wc input.");
      } else argv = new String[] { "-u" };
    } else {
      String slot = q.optString("slot", "prefix");
      if (!slot.equals("prefix") && !slot.equals("home")) throw new IOException(
        "Unknown agy location."
      );
      path = (slot.equals("home") ? HOME + ".local/bin/" : PREFIX) + "agy";
      if (tool.equals("agy-version")) argv = new String[] { "--version" };
      else {
        if (!path.equals(checkedPath)) throw new IOException(
          "Check the installed agy version at this location first (Console → CLI tools)."
        );
        if (tool.equals("agy-models")) argv = new String[] { "models" };
        else if (tool.equals("agy-usage")) argv = new String[] {
          "-p",
          "/usage",
        };
        else if (interactive) {
          String brief = q.optString("brief", "");
          if (
            brief.length() > 1800 || brief.indexOf('\0') >= 0
          ) throw new IOException("CLI brief too long or invalid.");
          if (!brief.isEmpty()) argv = new String[] {
            "--mode",
            "plan",
            "--prompt-interactive=" + brief,
          };
        } else throw new IOException("Unknown terminal tool.");
      }
    }
    args = argv;
    stdin = in;
  }
}
