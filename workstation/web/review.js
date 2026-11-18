// All confirmations resolve false on Back, Escape, close or cancellation. Only one review can be open.
export function review({
  title,
  detail = "",
  preview = "",
  allow = "Allow once",
  deny = "Deny",
}) {
  const d = document.getElementById("consent");
  if (d.open) return Promise.resolve(false);
  document.getElementById("reviewTitle").textContent = title;
  document.getElementById("reviewDetail").textContent = detail;
  const pre = document.getElementById("reviewPreview");
  pre.textContent = preview;
  pre.hidden = !preview;
  const yes = document.getElementById("reviewAllow"),
    no = document.getElementById("reviewDeny");
  yes.textContent = allow;
  no.textContent = deny;
  return new Promise((resolve) => {
    d.returnValue = "deny";
    d.onclose = () => {
      yes.onclick = no.onclick = null;
      resolve(d.returnValue === "allow");
    };
    yes.onclick = () => d.close("allow");
    no.onclick = () => d.close("deny");
    d.showModal();
    no.focus();
  });
}
export function choice(options, value, label) {
  const s = document.createElement("select");
  s.setAttribute("aria-label", label);
  for (const [v, text] of options) {
    const opt = document.createElement("option");
    opt.value = v;
    opt.textContent = text;
    s.append(opt);
  }
  s.value = value;
  return s;
}
export function field(label, control) {
  const l = document.createElement("label");
  l.className = "config-field";
  const span = document.createElement("span");
  span.textContent = label;
  l.append(span, control);
  return l;
}
export function input(value, label, max = 128) {
  const n = document.createElement("input");
  n.value = value;
  n.placeholder = label;
  n.setAttribute("aria-label", label);
  n.maxLength = max;
  return n;
}
