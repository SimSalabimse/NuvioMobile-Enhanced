var STAGE_LABELS = {
  preflight: "Preflight",
  prepare: "Prepare",
  xcodebuild: "Xcode build",
  checks: "Checks",
  zip: "Zip"
};
var COMMIT_LINE = /^- ([0-9a-fA-F]{7,40}) (.+) @(\S+)\s*$/;
var notesExpanded = false;
var notesStamp = "";
var desktopNotesExpanded = false;
var desktopNotesStamp = "";

function statusWord(value) {
  if (value === "idle") return "Idle";
  if (value === "queued") return "Queued";
  if (value === "building") return "Building";
  if (value === "succeeded") return "Succeeded";
  if (value === "failed") return "Failed";
  return value || "Idle";
}
function percentText(value) {
  var number = Number(value);
  if (!isFinite(number)) number = 0;
  return (Math.round(number * 10) / 10).toFixed(1) + "%";
}
function stageLabel(value) {
  if (value == null || value === "") return "None";
  if (Object.prototype.hasOwnProperty.call(STAGE_LABELS, value)) return STAGE_LABELS[value];
  return String(value);
}
function shortHash(value) {
  var text = value == null || value === "" ? "none" : String(value);
  if (!/^[0-9a-fA-F]{7,40}$/.test(text)) return text;
  if (text.length <= 12) return text;
  return text.slice(0, 8);
}
function trimText(value) {
  return String(value == null ? "" : value).replace(/^\s+|\s+$/g, "");
}
function parseNotes(text) {
  var raw = trimText(text);
  if (!raw || raw === "none" || raw === "(no release notes)") return { kind: "empty" };
  var lines = raw.split("\n");
  var items = [];
  for (var i = 0; i < lines.length; i++) {
    var line = trimText(lines[i]);
    if (!line) continue;
    var match = COMMIT_LINE.exec(line);
    if (match) {
      items.push({ kind: "commit", hash: match[1], subject: trimText(match[2]), author: match[3] });
    } else if (line === "[truncated]") {
      items.push({ kind: "text", text: "Truncated", muted: true });
    } else {
      items.push({ kind: "text", text: line, muted: false });
    }
  }
  var commits = 0;
  for (var j = 0; j < items.length; j++) if (items[j].kind === "commit") commits++;
  if (!commits) return { kind: "text", text: raw };
  return { kind: "rows", items: items };
}
function el(tag, className, text) {
  var node = document.createElement(tag);
  if (className) node.className = className;
  if (text != null) node.textContent = text;
  return node;
}
function clearNode(node) {
  while (node.firstChild) node.removeChild(node.firstChild);
}
function commitRow(row) {
  var wrap = el("div", "note");
  wrap.appendChild(el("p", "note-subject", row.subject));
  wrap.appendChild(el("p", "note-meta", shortHash(row.hash) + " · " + row.author));
  return wrap;
}
function textRow(row) {
  var wrap = el("div", "note");
  wrap.appendChild(el("p", row.muted ? "empty-notes" : "note-subject", row.text));
  return wrap;
}
function fillNotesInto(text, notesId, toggleId, expanded, stamp) {
  var next = (expanded ? "1" : "0") + "\n" + String(text == null ? "" : text);
  if (next === stamp) return stamp;
  var notes = document.getElementById(notesId);
  var toggle = document.getElementById(toggleId);
  clearNode(notes);
  var parsed = parseNotes(text);
  if (parsed.kind === "empty") {
    notes.appendChild(el("div", "row", null));
    notes.firstChild.appendChild(el("p", "empty-notes", "No release notes"));
    toggle.hidden = true;
    return next;
  }
  if (parsed.kind === "text") {
    var plain = el("div", "plain");
    plain.appendChild(el("p", "plain-text", parsed.text));
    notes.appendChild(plain);
    toggle.hidden = true;
    return next;
  }
  var items = parsed.items;
  var limit = expanded ? items.length : Math.min(5, items.length);
  for (var i = 0; i < limit; i++) {
    notes.appendChild(items[i].kind === "commit" ? commitRow(items[i]) : textRow(items[i]));
  }
  if (items.length > 5) {
    toggle.hidden = false;
    toggle.textContent = expanded ? "Show Less" : "Show All";
    toggle.setAttribute("aria-expanded", expanded ? "true" : "false");
  } else {
    toggle.hidden = true;
  }
  return next;
}
function fillNotes(text) {
  notesStamp = fillNotesInto(text, "notes", "notes-toggle", notesExpanded, notesStamp);
}
function applyInto(prefix, payload) {
  var state = payload.status || "idle";
  var idle = state === "idle";
  var status = document.getElementById(prefix + "status");
  status.textContent = statusWord(state);
  status.setAttribute("data-state", state);
  var percentNode = document.getElementById(prefix + "percent");
  percentNode.hidden = idle;
  percentNode.textContent = idle ? "" : percentText(payload.percent);
  var width = idle ? 0 : Math.max(0, Math.min(100, Number(payload.percent) || 0));
  document.getElementById(prefix + "bar").style.width = width + "%";
  var track = document.getElementById(prefix + "track");
  track.hidden = idle;
  track.setAttribute("aria-valuenow", String(Math.round(width)));
  var remaining = document.getElementById(prefix + "remaining");
  var label = payload.remainingLabel || "none";
  if ((state === "building" || state === "queued") && label !== "none") {
    remaining.hidden = false;
    remaining.textContent = label + " left";
  } else {
    remaining.hidden = true;
    remaining.textContent = "";
  }
  var commit = payload.commit || "none";
  document.getElementById(prefix + "branch").textContent = payload.branch || "none";
  document.getElementById(prefix + "commit-short").textContent = shortHash(commit);
  document.getElementById(prefix + "commit").textContent = commit;
  var stageRow = document.getElementById(prefix + "stage-row");
  stageRow.hidden = idle;
  document.getElementById(prefix + "stage").textContent = idle ? "" : stageLabel(payload.stage);
  var errorGroup = document.getElementById(prefix + "error-group");
  var error = document.getElementById(prefix + "error");
  if (!idle && payload.error) {
    errorGroup.hidden = false;
    error.textContent = payload.error;
  } else {
    errorGroup.hidden = true;
    error.textContent = "";
  }
  renderColumnLog(prefix, payload);
}
var selectedBuild = { "": null, "desktop-": null };
var shownBuild = { "": "", "desktop-": "" };
var logFollow = { "": true, "desktop-": true };
var logRequest = { "": 0, "desktop-": 0 };
var buildStamp = { "": "", "desktop-": "" };
function nearBottom(node) {
  return node.scrollHeight - node.scrollTop - node.clientHeight < 12;
}
function jumpLog(node) {
  node.scrollTop = node.scrollHeight;
}
function shortWhen(value) {
  if (!value) return "none";
  var date = new Date(value);
  if (isNaN(date.getTime())) return String(value);
  var months = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];
  function two(number) { return (number < 10 ? "0" : "") + number; }
  return months[date.getMonth()] + " " + date.getDate() + " " + two(date.getHours()) + ":" + two(date.getMinutes());
}
function setLogText(prefix, buildId, text, running) {
  var node = document.getElementById(prefix + "log-view");
  var next = String(text == null ? "" : text);
  var switched = shownBuild[prefix] !== buildId;
  var wasAtBottom = nearBottom(node);
  if (node.textContent !== next) node.textContent = next;
  shownBuild[prefix] = buildId;
  if (switched) {
    logFollow[prefix] = !!running;
    jumpLog(node);
    return;
  }
  if (running && logFollow[prefix] !== false && wasAtBottom) jumpLog(node);
}
function renderBuildList(prefix, builds, chosen) {
  var stamp = chosen;
  for (var i = 0; i < builds.length; i++) {
    var item = builds[i];
    stamp += "\n" + item.id + "|" + item.status + "|" + item.commit + "|" + item.startedAt;
  }
  if (stamp === buildStamp[prefix]) return;
  buildStamp[prefix] = stamp;
  var list = document.getElementById(prefix + "build-list");
  clearNode(list);
  for (var n = 0; n < builds.length; n++) {
    var row = builds[n];
    var button = document.createElement("button");
    button.type = "button";
    button.className = "build-row";
    button.setAttribute("data-id", row.id);
    button.setAttribute("aria-selected", row.id === chosen ? "true" : "false");
    var product = row.product || (prefix === "desktop-" ? "Nuvio for Mac" : "Nuvio for iPhone");
    button.appendChild(el("span", "build-product", product));
    button.appendChild(el("span", "build-status", statusWord(row.status)));
    button.appendChild(el("span", "build-commit", shortHash(row.commit)));
    button.appendChild(el("span", "build-when", shortWhen(row.finishedAt || row.startedAt)));
    list.appendChild(button);
  }
}
function renderColumnLog(prefix, payload) {
  var builds = payload.recentBuilds || [];
  var view = document.getElementById(prefix + "log-view");
  if (!builds.length) {
    selectedBuild[prefix] = null;
    shownBuild[prefix] = "";
    buildStamp[prefix] = "none";
    clearNode(document.getElementById(prefix + "build-list"));
    if (view.textContent !== "none") view.textContent = "none";
    return;
  }
  var chosen = selectedBuild[prefix];
  var known = false;
  for (var i = 0; i < builds.length; i++) {
    if (builds[i].id === chosen) known = true;
  }
  if (!known) {
    selectedBuild[prefix] = null;
    chosen = builds[0].id;
  }
  var active = builds[0];
  for (var j = 0; j < builds.length; j++) {
    if (builds[j].id === chosen) active = builds[j];
  }
  renderBuildList(prefix, builds, chosen);
  var running = active.status === "building";
  if (chosen === builds[0].id) {
    logRequest[prefix] += 1;
    setLogText(prefix, chosen, payload.logTail || "", running);
    return;
  }
  var token = ++logRequest[prefix];
  fetch("/api/builds/" + encodeURIComponent(chosen) + "/log", { cache: "no-store" })
    .then(function (response) { return response.ok ? response.text() : ""; })
    .then(function (text) {
      if (logRequest[prefix] !== token) return;
      if (selectedBuild[prefix] !== chosen) return;
      setLogText(prefix, chosen, text, running);
    })
    .catch(function () {});
}
function bindColumnLog(prefix) {
  var node = document.getElementById(prefix + "log-view");
  function syncFollow() {
    logFollow[prefix] = nearBottom(node);
  }
  node.addEventListener("wheel", function () { setTimeout(syncFollow, 0); });
  node.addEventListener("touchend", syncFollow);
  node.addEventListener("pointerup", syncFollow);
  node.addEventListener("keyup", syncFollow);
  var list = document.getElementById(prefix + "build-list");
  list.addEventListener("click", function (event) {
    var target = event.target;
    while (target && target !== list && target.tagName !== "BUTTON") target = target.parentNode;
    if (!target || target === list) return;
    selectedBuild[prefix] = target.getAttribute("data-id");
    buildStamp[prefix] = "";
    refresh();
  });
}
function versionLines(text) {
  var parsed = parseNotes(text);
  if (!parsed || parsed.kind !== "rows") return ["No summary for this file."];
  var subjects = [];
  for (var i = 0; i < parsed.items.length; i++) {
    if (parsed.items[i].kind === "commit") subjects.push(parsed.items[i].subject);
  }
  if (!subjects.length) return ["No summary for this file."];
  var lines = subjects.slice(0, 4);
  if (subjects.length > 4) lines.push("And " + (subjects.length - 4) + " more in Release notes");
  return lines;
}
function fillVersion(text, elementId) {
  var node = document.getElementById(elementId);
  var lines = versionLines(text);
  var stamp = lines.join("\n");
  if (node.getAttribute("data-stamp") === stamp) return;
  node.setAttribute("data-stamp", stamp);
  clearNode(node);
  for (var i = 0; i < lines.length; i++) {
    var more = lines[i].indexOf("And ") === 0 && lines[i].indexOf(" more in Release notes") > 0;
    var empty = lines[i] === "No summary for this file.";
    var row = el("div", empty ? "row" : "note");
    row.appendChild(el("p", empty ? "empty-notes" : (more ? "more-line" : "note-subject"), lines[i]));
    node.appendChild(row);
  }
}
function apply(payload) {
  applyInto("", payload);
  fillVersion(payload.releaseNotes, "version-lines");
  fillNotes(payload.releaseNotes);
}
function applyDesktop(payload) {
  applyInto("desktop-", payload);
  fillVersion(payload.releaseNotes, "desktop-version-lines");
  desktopNotesStamp = fillNotesInto(payload.releaseNotes, "desktop-notes", "desktop-notes-toggle", desktopNotesExpanded, desktopNotesStamp);
}
function workStatus(value) {
  if (value === "in_progress") return "In progress";
  if (value === "in_review") return "In review";
  if (value === "todo") return "To do";
  if (value === "blocked") return "Blocked";
  if (value === "done") return "Done";
  return value || "None";
}
function packageWord(value) {
  if (value === "ipa") return "IPA";
  if (value === "dmg") return "DMG";
  return "Neither";
}
function fillPackage(cardId, group) {
  var card = document.getElementById(cardId);
  var stamp = JSON.stringify(group || {});
  if (card.getAttribute("data-stamp") === stamp) return;
  card.setAttribute("data-stamp", stamp);
  clearNode(card);
  var next = group && group.inNext ? group.inNext : {};
  var nextBlock = el("div", "block");
  nextBlock.appendChild(el("p", "block-label", group && group.nextLabel ? group.nextLabel : "In the next"));
  var items = next.items || [];
  if (!items.length) {
    nextBlock.appendChild(el("p", "empty-notes", "Nothing new is queued."));
  } else {
    for (var i = 0; i < items.length; i++) nextBlock.appendChild(el("p", "bullet", items[i]));
  }
  card.appendChild(nextBlock);
  var stash = group && group.stashed ? group.stashed : {};
  var stashBlock = el("div", "block");
  stashBlock.appendChild(el("p", "block-label", "Stashed while testing"));
  var stashed = stash.items || [];
  if (!stashed.length) {
    stashBlock.appendChild(el("p", "empty-notes", stash.emptyText || "Nothing is stashed."));
  } else {
    for (var s = 0; s < stashed.length; s++) {
      var line = stashed[s].summary || "";
      if (stashed[s].issue) line += " · " + stashed[s].issue;
      stashBlock.appendChild(el("p", "bullet", line));
    }
    if (stash.more) stashBlock.appendChild(el("p", "more-line", "And " + stash.more + " more stashed."));
  }
  card.appendChild(stashBlock);
  var progress = el("div", "block");
  progress.appendChild(el("p", "block-label", group && group.headline ? group.headline : "Nothing is queued."));
  if (group && group.percent != null) {
    var track = el("div", "track work-track");
    track.setAttribute("role", "progressbar");
    track.setAttribute("aria-valuemin", "0");
    track.setAttribute("aria-valuemax", "100");
    track.setAttribute("aria-valuenow", String(group.percent));
    track.setAttribute("aria-label", group.headline || "Task progress");
    var bar = el("span", "bar");
    bar.style.width = Math.max(0, Math.min(100, Number(group.percent) || 0)) + "%";
    track.appendChild(bar);
    progress.appendChild(track);
  }
  card.appendChild(progress);
  if (group && group.agentTime) card.appendChild(el("div", "block")).appendChild(el("p", "task-meta", group.agentTime));
  if (group && group.compile) card.appendChild(el("div", "block")).appendChild(el("p", "task-meta", group.compile));
  var tasks = group && group.tasks ? group.tasks : [];
  for (var t = 0; t < tasks.length; t++) {
    var task = tasks[t];
    var row = el("div", "task");
    row.appendChild(el("p", "note-subject", (task.id ? task.id + " · " : "") + (task.title || "")));
    var meta = [workStatus(task.status), task.agent || "None", task.elapsed || ""].filter(function (part) { return part; });
    row.appendChild(el("p", "task-meta", meta.join(" · ")));
    if (task.tokens) row.appendChild(el("p", "task-meta", task.tokens));
    if (task.billing) row.appendChild(el("p", "task-meta", task.billing));
    if (task.model) row.appendChild(el("p", "task-meta", task.model));
    card.appendChild(row);
  }
}
function fillWorking(rows) {
  var card = document.getElementById("working-now-card");
  var list = rows || [];
  var stamp = JSON.stringify(list);
  if (card.getAttribute("data-stamp") === stamp) return;
  card.setAttribute("data-stamp", stamp);
  clearNode(card);
  if (!list.length) {
    var empty = el("div", "row");
    empty.appendChild(el("p", "empty-notes", "No one is working right now."));
    card.appendChild(empty);
    return;
  }
  for (var i = 0; i < list.length; i++) {
    var item = list[i];
    var row = el("div", "task");
    row.appendChild(el("p", "note-subject", (item.id || "") + " · " + (item.title || "")));
    var meta = [workStatus(item.status), item.agent || "None", item.elapsed || "", packageWord(item.package)];
    row.appendChild(el("p", "task-meta", meta.filter(function (part) { return part; }).join(" · ")));
    if (item.model) row.appendChild(el("p", "task-meta", item.model));
    card.appendChild(row);
  }
}
function applyWork(payload) {
  fillPackage("next-ipa-card", payload.ipa || {});
  fillPackage("next-dmg-card", payload.dmg || {});
  fillWorking(payload.workingNow || []);
}
function megabytes(bytes) {
  var number = Number(bytes);
  if (!isFinite(number) || number < 0) return "";
  return (number / (1024 * 1024)).toFixed(1) + " MB";
}
function versionLine(entry) {
  var version = entry.version && entry.version !== "none" ? String(entry.version) : "";
  if (entry.build) version = (version ? version + " " : "") + "(" + entry.build + ")";
  var commit = shortHash(entry.commit);
  if (version && commit && commit !== "none") return version + " · " + commit;
  return version || (commit && commit !== "none" ? commit : "");
}
function downloadLink(href, savedName) {
  var link = document.createElement("a");
  link.className = "download-link";
  link.href = href;
  link.textContent = "Download";
  if (savedName) link.setAttribute("download", savedName);
  return link;
}
function fillCard(node, entry, href, title) {
  clearNode(node);
  if (!entry) {
    var empty = el("div", "row");
    empty.appendChild(el("p", "empty-notes", "No package"));
    node.appendChild(empty);
    return;
  }
  var stack = el("div", "row row-stack");
  stack.appendChild(el("p", "download-file", entry.product || title));
  var version = versionLine(entry);
  if (version) stack.appendChild(el("p", "download-meta", version));
  var when = entry.packagedAt ? shortWhen(entry.packagedAt) : "";
  var size = megabytes(entry.bytes);
  var facts = [when, size].filter(function (part) { return part; }).join(" · ");
  if (facts) stack.appendChild(el("p", "download-meta", facts));
  if (entry.notice) stack.appendChild(el("p", "package-note", entry.notice));
  var actions = el("div", "card-actions");
  actions.appendChild(downloadLink(href, entry.savedName || entry.filename));
  var copy = document.createElement("button");
  copy.type = "button";
  copy.className = "copy-link";
  copy.textContent = "Copy";
  copy.setAttribute("data-copy", entry.copyText || "");
  actions.appendChild(copy);
  stack.appendChild(actions);
  node.appendChild(stack);
}
function olderRow(entry) {
  var row = el("div", "row row-stack");
  row.appendChild(el("p", "download-file", entry.filename || "Package"));
  var bits = [];
  var version = versionLine(entry);
  if (version) bits.push(version);
  var size = megabytes(entry.bytes);
  if (size) bits.push(size);
  bits.push("Older");
  row.appendChild(el("p", "download-meta", bits.join(" · ")));
  var name = entry.filename || "";
  row.appendChild(downloadLink("/download/older/" + encodeURIComponent(name), name));
  return row;
}
function applyDownloads(payload) {
  var desktop = payload.desktop || {};
  fillCard(document.getElementById("iphone-card"), payload.ipa, "/download/ipa", "Nuvio for iPhone");
  fillCard(document.getElementById("mac-card"), desktop.macos, "/download/desktop/macos", "Nuvio for Mac");
  var absent = document.getElementById("absent-packages");
  var missing = [];
  if (!desktop.windows) missing.push("Windows");
  if (!desktop.linux) missing.push("Linux");
  if (!desktop.windows && !desktop.linux) {
    absent.hidden = false;
    absent.textContent = "Windows and Linux have no package.";
  } else if (missing.length === 1) {
    absent.hidden = false;
    absent.textContent = missing[0] + " has no package.";
  } else {
    absent.hidden = true;
    absent.textContent = "";
  }
  var olderRoot = document.getElementById("older");
  var olderGroup = document.getElementById("older-group");
  var older = payload.older || [];
  clearNode(olderRoot);
  if (!older.length) {
    olderGroup.hidden = true;
    return;
  }
  olderGroup.hidden = false;
  for (var i = 0; i < older.length; i++) olderRoot.appendChild(olderRow(older[i]));
}
function copyText(text) {
  if (navigator.clipboard && navigator.clipboard.writeText) {
    return navigator.clipboard.writeText(text);
  }
  var area = document.createElement("textarea");
  area.value = text;
  document.body.appendChild(area);
  area.select();
  try { document.execCommand("copy"); } catch (err) {}
  document.body.removeChild(area);
  return Promise.resolve();
}
var IDLE_POLL_MS = 30000;
var ACTIVE_POLL_MS = 2000;
var REQUEST_LABEL = {
  ipa: "Request this iPhone build",
  dmg: "Request this Mac build"
};
var cutState = {
  ipa: { idStamp: "", domStamp: "", selected: {} },
  dmg: { idStamp: "", domStamp: "", selected: {} }
};
var pollTimer = 0;
var refreshRunning = false;
var lastActive = false;

function defaultSelection(commits) {
  var selected = {};
  for (var i = 0; i < commits.length; i++) selected[commits[i].commit] = true;
  return selected;
}
function applyPrefixToggle(commits, selected, index, checked) {
  var next = {};
  var i;
  for (i = 0; i < commits.length; i++) {
    if (selected[commits[i].commit]) next[commits[i].commit] = true;
  }
  if (checked) {
    for (i = index; i < commits.length; i++) next[commits[i].commit] = true;
  } else {
    for (i = 0; i <= index; i++) delete next[commits[i].commit];
  }
  return next;
}
function newestChecked(commits, selected) {
  for (var i = 0; i < commits.length; i++) {
    if (selected[commits[i].commit]) return commits[i].commit;
  }
  return "";
}
function checkedCount(commits, selected) {
  var count = 0;
  for (var i = 0; i < commits.length; i++) {
    if (selected[commits[i].commit]) count++;
  }
  return count;
}
function syncCutChecks(root, commits, selected) {
  var boxes = root.querySelectorAll("input[data-index]");
  for (var i = 0; i < boxes.length; i++) {
    var index = Number(boxes[i].getAttribute("data-index"));
    boxes[i].checked = !!selected[commits[index].commit];
  }
}
function renderCut(platform, cut, enabled) {
  var root = document.getElementById(platform === "ipa" ? "ipa-cut" : "dmg-cut");
  if (!root) return;
  if (!enabled) {
    root.hidden = true;
    clearNode(root);
    cutState[platform].domStamp = "";
    return;
  }
  root.hidden = false;
  var commits = cut && cut.commits ? cut.commits : [];
  var stamp = JSON.stringify(cut || {});
  var state = cutState[platform];
  var idStamp = commits.map(function (row) { return row.commit; }).join("\n");
  if (state.idStamp !== idStamp) {
    state.selected = defaultSelection(commits);
    state.idStamp = idStamp;
    state.domStamp = "";
  }
  if (state.domStamp === stamp) return;
  state.domStamp = stamp;
  clearNode(root);
  root.appendChild(el("p", "block-label", "Cut"));
  if (cut && cut.error) {
    root.appendChild(el("p", "empty-notes", cut.error));
  } else if (!commits.length) {
    root.appendChild(el("p", "empty-notes", "The served package already includes this branch."));
  } else {
    var list = el("div", "cut-list");
    for (var i = 0; i < commits.length; i++) {
      var row = commits[i];
      var label = document.createElement("label");
      label.className = "cut-row";
      var box = document.createElement("input");
      box.type = "checkbox";
      box.checked = !!state.selected[row.commit];
      box.setAttribute("data-index", String(i));
      label.appendChild(box);
      var copy = el("span", "cut-copy");
      var title = el("span", "cut-subject", row.subject || "(no subject)");
      var meta = shortHash(row.commit);
      if (row.issues && row.issues.length) meta += " · " + row.issues.join(" ");
      copy.appendChild(title);
      copy.appendChild(el("span", "cut-meta", meta));
      label.appendChild(copy);
      list.appendChild(label);
    }
    root.appendChild(list);
  }
  var stateNode = el("p", "request-state");
  stateNode.id = platform + "-request-state";
  var button = document.createElement("button");
  button.type = "button";
  button.className = "request-button";
  button.id = platform + "-request-button";
  button.textContent = REQUEST_LABEL[platform];
  function paint() {
    var chosen = newestChecked(commits, state.selected);
    var count = checkedCount(commits, state.selected);
    if (cut && cut.pending) stateNode.textContent = "Requested";
    else if (cut && cut.busy) stateNode.textContent = cut.busy;
    else if (cut && cut.error) stateNode.textContent = "";
    else if (!commits.length) stateNode.textContent = "";
    else if (!chosen) stateNode.textContent = "Choose a commit.";
    else stateNode.textContent = "Includes " + count + (count === 1 ? " commit." : " commits.");
    button.disabled = !!(cut && (cut.pending || cut.busy || cut.error)) || !chosen;
  }
  root.onchange = function (event) {
    var target = event.target;
    if (!target || target.type !== "checkbox") return;
    var index = Number(target.getAttribute("data-index"));
    if (!isFinite(index)) return;
    state.selected = applyPrefixToggle(commits, state.selected, index, target.checked);
    syncCutChecks(root, commits, state.selected);
    paint();
  };
  button.addEventListener("click", function () {
    var chosen = newestChecked(commits, state.selected);
    if (!chosen || button.disabled) return;
    button.disabled = true;
    stateNode.textContent = "Requesting…";
    fetch("/api/build-request", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ platform: platform, commit: chosen }),
      cache: "no-store"
    }).then(function (response) {
      return response.json().then(function (body) {
        return { ok: response.ok, status: response.status, body: body };
      });
    }).then(function (result) {
      if (!result.ok) {
        stateNode.textContent = (result.body && result.body.error) || "The request was refused.";
        button.disabled = result.status === 503;
        return;
      }
      state.domStamp = "";
      refresh();
    }).catch(function () {
      stateNode.textContent = "The request did not reach the builds page.";
      button.disabled = false;
    });
  });
  paint();
  root.appendChild(stateNode);
  root.appendChild(button);
}
async function refresh() {
  if (refreshRunning) return;
  refreshRunning = true;
  var active = lastActive;
  try {
    var response = await fetch("/api/dashboard", { cache: "no-store" });
    if (response.ok) {
      var payload = await response.json();
      apply(payload.iphone || {});
      applyDesktop(payload.desktop || {});
      applyDownloads(payload.downloads || {});
      applyWork(payload.work || {});
      var cuts = payload.cuts || {};
      var enabled = payload.requestEnabled !== false;
      renderCut("ipa", cuts.ipa || {}, enabled);
      renderCut("dmg", cuts.dmg || {}, enabled);
      active = !!(payload.poll && payload.poll.active);
      if (!payload.poll) {
        var iphoneState = payload.iphone && payload.iphone.status;
        var desktopState = payload.desktop && payload.desktop.status;
        active = iphoneState === "building" || iphoneState === "queued" || desktopState === "building" || desktopState === "queued";
      }
    }
  } catch (err) {
    active = false;
  } finally {
    refreshRunning = false;
    lastActive = active;
    window.clearTimeout(pollTimer);
    pollTimer = window.setTimeout(refresh, active ? ACTIVE_POLL_MS : IDLE_POLL_MS);
  }
}
function boot() {
  document.getElementById("notes-toggle").addEventListener("click", function () {
    notesExpanded = !notesExpanded;
    notesStamp = "";
    refresh();
  });
  document.getElementById("desktop-notes-toggle").addEventListener("click", function () {
    desktopNotesExpanded = !desktopNotesExpanded;
    desktopNotesStamp = "";
    refresh();
  });
  bindColumnLog("");
  bindColumnLog("desktop-");
  document.querySelector("main").addEventListener("click", function (event) {
    var target = event.target;
    while (target && target !== document.body && !(target.getAttribute && target.getAttribute("data-copy"))) {
      target = target.parentNode;
    }
    if (!target || !target.getAttribute) return;
    var text = target.getAttribute("data-copy");
    if (!text) return;
    copyText(text).then(function () {
      target.textContent = "Copied";
      setTimeout(function () { target.textContent = "Copy"; }, 1200);
    }).catch(function () {});
  });
  refresh();
}
if (typeof document !== "undefined") boot();
