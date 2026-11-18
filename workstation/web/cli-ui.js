import { choice, field } from "./review.js";
export const AGY_BINS = {
  prefix: "$PREFIX/bin/agy",
  home: "$HOME/.local/bin/agy",
};
export function cliSheet({
  dialog,
  el,
  button,
  acts,
  close,
  native,
  slot,
  setSlot,
  consoleLine,
  interactive,
}) {
  const b = dialog("Antigravity & Termux", "CLI TOOLS");
  b.append(
    el(
      "p",
      "",
      "Use your existing Termux installation. Check where agy is installed with command -v agy in Termux. Run the version check here first.",
    ),
  );
  const pick = choice(Object.entries(AGY_BINS), slot, "Installed agy path");
  b.append(field("Installed agy executable", pick));
  pick.onchange = () => setSlot(pick.value);
  function action(label, fn, primary = false) {
    const k = button(label, fn, primary);
    k.disabled = !native;
    return k;
  }
  const quick = (label, line) =>
    action(label, () => {
      close();
      consoleLine(line);
    });
  acts(
    b,
    quick("Check version", "agy --version"),
    quick("List models", "agy models"),
    quick("Account usage", "agy -p /usage"),
    quick("Test Termux", "termux date"),
  );
  b.append(
    el("div", "detail", "Interactive session"),
    el(
      "p",
      "",
      "The terminal UI runs in Termux, not inside the Orbit file grant. Its own account, file access, tools and approval settings apply. Do not enter a secret as the task.",
    ),
  );
  const brief = el("textarea");
  brief.maxLength = 1800;
  brief.rows = 2;
  brief.placeholder = "Optional task to open in CLI plan mode";
  b.append(field("Task for agy (optional)", brief));
  const check = document.createElement("input");
  check.type = "checkbox";
  const label = el("label", "check-row");
  label.append(
    check,
    document.createTextNode(
      "Return the end of the terminal transcript to this chat on exit",
    ),
  );
  b.append(label);
  b.append(
    el(
      "small",
      "footnote",
      "Leave transcript off for sign-in. If Android doesn’t bring the terminal forward, tap the Termux notification. Nothing is attached from your project.",
    ),
  );
  acts(
    b,
    action(
      "Review CLI session",
      async () => {
        await interactive(brief.value.trim(), check.checked);
      },
      true,
    ),
  );
}
