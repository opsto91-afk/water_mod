import { workflow, ROLES, PROVIDERS, describeModel } from "./session.js";
import { input, choice, field } from "./review.js";
// A saved workflow contains roles, providers and model IDs ONLY. Prompts, replies and keys are not saved.
const key = "orbit.workflow.v2";
function loadSaved() {
  try {
    const l = JSON.parse(localStorage.getItem(key) || "[]");
    if (!Array.isArray(l)) return [];
    return l
      .slice(0, 8)
      .map((w) => ({
        name: String(w.name).slice(0, 40),
        ...workflow(w.workers, w.manager, w.planFirst),
      }));
  } catch {
    return [];
  }
}
export function teamSheet(ui) {
  const {
    dialog,
    el,
    button,
    acts,
    close,
    active,
    defaults,
    run,
    native,
    toast,
  } = ui;
  const b = dialog("Orchestra", "WORKFLOWS");
  b.append(
    el(
      "p",
      "",
      "Assign up to five workers and one manager. Workers draft in parallel; the manager reviews their reports. Only the manager can propose a workspace action for your review.",
    ),
  );
  let saved = loadSaved(),
    roster = ROLES.map((role) => ({ role, ...active })),
    manager = { role: "Lead editor", ...active };
  const template = choice(
    [["", "Custom workflow"], ...saved.map((w, i) => [String(i), w.name])],
    "",
    "Saved workflow",
  );
  const count = choice(
    [1, 2, 3, 4, 5].map((n) => [String(n), String(n)]),
    "3",
    "Worker count",
  );
  const head = el("div", "setting-pair");
  head.append(field("Workflow", template), field("Workers", count));
  b.append(head);
  const planFirst = document.createElement("input");
  planFirst.type = "checkbox";
  planFirst.checked = ui.heavy;
  const planLabel = el("label", "check-row");
  planLabel.append(
    planFirst,
    document.createTextNode("Manager assigns work first (+1 call)"),
  );
  b.append(planLabel);
  const rows = el("div", "team-rows");
  b.append(rows);
  let readers = [];
  function row(agent, i, isManager) {
    const item = el("div", "team-row"),
      slot = el(
        "span",
        "slot-num",
        isManager ? "M" : String(i + 1).padStart(2, "0"),
      ),
      fields = el("div", "team-fields");
    const role = input(agent.role, "Role or responsibility", 96);
    const provider = choice(
      Object.entries({ ...PROVIDERS, local: "Local GGUF" }),
      agent.provider,
      "Agent provider",
    );
    const model = input(agent.model || "", "Provider model ID");
    const lock = () => {
      model.hidden = provider.value === "local";
    };
    lock();
    provider.onchange = () => {
      model.value = defaults[provider.value] || "";
      lock();
    };
    const read = () => ({
      role: role.value.trim(),
      provider: provider.value,
      model: model.value.trim(),
    });
    fields.append(role, provider, model);
    item.append(slot, fields);
    rows.append(item);
    return read;
  }
  function paint() {
    rows.replaceChildren();
    readers = [];
    rows.append(el("div", "eyebrow", "WORKERS / DRAFT"));
    for (let i = 0; i < +count.value; i++)
      readers.push(row(roster[i], i, false));
    rows.append(el("div", "eyebrow", "MANAGER / SYNTHESIS"));
    readers.push(row(manager, 0, true));
  }
  count.onchange = () => {
    manager = readers.pop()();
    readers.forEach((get, i) => (roster[i] = get()));
    paint();
  };
  template.onchange = () => {
    if (template.value === "") return;
    const s = saved[+template.value];
    s.workers.forEach((w, i) => (roster[i] = { ...w }));
    manager = { ...s.manager };
    planFirst.checked = s.planFirst;
    count.value = String(s.workers.length);
    paint();
  };
  paint();
  const brief = el("textarea");
  brief.placeholder = "Describe the goal and deliverable for the team…";
  brief.maxLength = 4000;
  brief.rows = 3;
  brief.setAttribute("aria-label", "Team brief");
  b.append(field("Task brief", brief));
  const attach = document.createElement("input");
  attach.type = "checkbox";
  attach.disabled = !ui.openFile;
  const label = el("label", "check-row");
  label.append(
    attach,
    document.createTextNode(
      "Include open file excerpt" + (ui.openFile ? " · " + ui.openFile : ""),
    ),
  );
  b.append(label);
  const name = input("", "Workflow name", 40);
  b.append(field("Save configuration (optional)", name));
  const plan = () =>
    workflow(
      readers.slice(0, -1).map((f) => f()),
      readers.at(-1)(),
      planFirst.checked,
    );
  acts(
    b,
    button(
      "Review run",
      async () => {
        const p = plan(),
          task = brief.value.trim();
        if (!task) throw Error("Enter a team brief.");
        if (
          !native &&
          [...p.workers, p.manager].some((a) => a.provider !== "local")
        )
          throw Error("API models require the Android APK.");
        await run(p, task, attach.checked);
      },
      true,
    ),
    button("Save workflow", () => {
      const n = name.value.trim();
      if (!n) throw Error("Name this workflow first.");
      const p = plan();
      saved = [{ name: n, ...p }, ...saved.filter((w) => w.name !== n)].slice(
        0,
        8,
      );
      localStorage.setItem(key, JSON.stringify(saved));
      template.replaceChildren(
        new Option("Custom workflow", ""),
        ...saved.map((w, i) => new Option(w.name, String(i))),
      );
      template.value = "0";
      name.value = "";
      toast("Workflow saved on this device.");
    }),
  );
  b.append(
    el(
      "small",
      "footnote",
      "Local roles share one GGUF and run in sequence. API roles can overlap. One call per worker, one final manager report, optional planning call. No retries.",
    ),
  );
}
export function teamRecipients(plan) {
  return [
    ...plan.workers.map((r, i) => `${i + 1}. ${r.role} → ${describeModel(r)}`),
    `Manager: ${plan.manager.role} → ${describeModel(plan.manager)}`,
  ].join("\n");
}
