const paths = {
  folder: "M3 7V5h6l2 2h10v12H3z",
  open: "M3 17V5h6l2 2h9v3M2 19l3-9h17l-3 9z",
  file: "M5 3h9l5 5v13H5zM14 3v6h5M8 13h8m-8 4h6",
  newfile: "M4 3h9l4 4v3M4 3v18h8M13 3v5h4m0 5v8m-4-4h8",
  newfolder: "M3 18V5h6l2 2h10v4m-5 3v7m-3.5-3.5h7",
  refresh: "M20 8a8 8 0 1 0 0 8M20 3v6h-6",
  branch: "M6 3v12a3 3 0 0 0 3 3h9M6 10h9",
  layers: "m12 3 9 5-9 5-9-5zM3 12l9 5 9-5M3 16l9 5 9-5",
  chevron: "m7 10 5 5 5-5",
  right: "m9 6 6 6-6 6",
  drive: "m5 4-3 11v5h20v-5L19 4zM2 15h20m-5 2.5h1",
  swap: "m7 4-4 4 4 4M3 8h15m-1 4 4 4-4 4m4-4H6",
  cpu: "M6 6h12v12H6zM9 2v4m6-4v4M9 18v4m6-4v4M2 9h4m-4 6h4m12-6h4m-4 6h4M9 9h6v6H9z",
  plus: "M12 4v16M4 12h16",
  save: "M3 3h15l3 3v15H3zM7 3v6h9V3M7 21v-8h10v8",
  terminal: "M3 4h18v16H3zM6 8l4 4-4 4m6 0h5",
  return: "M20 5v8H5m5-5-5 5 5 5",
  eraser: "m4 14 10-10 6 6-10 10H9zm6 6h10",
  shield: "m12 2 8 4v6c0 5-4 8-8 10-4-2-8-5-8-10V6z",
  spark: "m12 3 2.4 6.6L21 12l-6.6 2.4L12 21l-2.4-6.6L3 12l6.6-2.4z",
  clip: "m8 16 8-8a2 2 0 0 0-3-3l-8 8a5 5 0 0 0 7 7l8-8",
  lock: "M5 10h14v11H5zm3 0V7a4 4 0 0 1 8 0v3",
  up: "M12 20V4m-7 7 7-7 7 7",
  arrow: "M4 12h16m-7-7 7 7-7 7",
  code: "m7 7-5 5 5 5m10-10 5 5-5 5m-4-14-3 18",
  close: "m5 5 14 14m0-14L5 19",
  stop: "M6 6h12v12H6z",
  doc: "M4 5h16v14H4zM7 15V9l3 4 3-4v6m2-3 2 2 2-2",
};
export function icon(name) {
  const ns = "http://www.w3.org/2000/svg",
    svg = document.createElementNS(ns, "svg"),
    p = document.createElementNS(ns, "path");
  svg.setAttribute("viewBox", "0 0 24 24");
  svg.setAttribute("aria-hidden", "true");
  svg.classList.add("ico");
  p.setAttribute("d", paths[name] || paths.file);
  svg.append(p);
  return svg;
}
export function mountIcons() {
  for (const i of document.querySelectorAll("[data-i]"))
    i.replaceWith(icon(i.dataset.i));
}
