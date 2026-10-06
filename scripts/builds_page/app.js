var COMMIT_LINE = /^- ([0-9a-fA-F]{7,40}) (.+) @(\S+)\s*$/;
var ISSUE_LINK = /^SIM-\d+$/;
var ISSUE_ORIGIN = "https://simsalabim-paperclip.steelyx.org/SIM/issues/";
var PREFIX_HELP = "Checking a commit includes that commit and everything before it. Two commits that change the same file both land in the package.";
var REQUEST_LABEL = {
  ipa: "Request this iPhone build",
  dmg: "Request this Mac build"
};
var IDLE_POLL_MS = 30000;
var ACTIVE_POLL_MS = 2000;
var pollTimer = 0;
var refreshRunning = false;
var lastActive = false;
var STOP_LATEST = "This stops the compile that is running and requests the latest instead.";
var cutState = {
  ipa: { idStamp: "", domStamp: "", updateStamp: "", selected: {}, armUpdate: false, armChoose: false },
  dmg: { idStamp: "", domStamp: "", updateStamp: "", selected: {}, armUpdate: false, armChoose: false }
};
var logFollow = { "": true, "desktop-": true };

function statusWord(value) {
  if (value === "queued") return "Queued";
  if (value === "building") return "Building";
  if (value === "failed") return "Failed";
  if (value === "succeeded") return "Succeeded";
  return value ? String(value) : "";
}
function percentText(value) {
  var number = Number(value);
  if (!isFinite(number)) number = 0;
  return (Math.round(number * 10) / 10).toFixed(1) + "%";
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
      items.push({ kind: "text", text: "Truncated" });
    } else {
      items.push({ kind: "text", text: line });
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
function shortWhen(value) {
  if (!value) return "";
  var date = new Date(value);
  if (isNaN(date.getTime())) return String(value);
  var months = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];
  function two(number) { return (number < 10 ? "0" : "") + number; }
  return months[date.getMonth()] + " " + date.getDate() + " " + two(date.getHours()) + ":" + two(date.getMinutes());
}
function versionText(entry) {
  if (!entry) return "";
  var version = entry.version && entry.version !== "none" ? String(entry.version) : "";
  if (entry.build) version = (version ? version + " " : "") + "(" + entry.build + ")";
  return version;
}
function packageFacts(entry) {
  var when = entry.packagedAt ? shortWhen(entry.packagedAt) : "";
  var commit = shortHash(entry.commit);
  var parts = [];
  if (when) parts.push(when);
  if (commit && commit !== "none") parts.push(commit);
  return parts.join(" · ");
}
function downloadLink(href, savedName, label) {
  var link = document.createElement("a");
  link.className = "download-link";
  link.href = href;
  link.textContent = "Download";
  if (label) link.setAttribute("aria-label", label);
  if (savedName) link.setAttribute("download", savedName);
  return link;
}
function noteParts(text) {
  var parsed = parseNotes(text);
  var lines = [];
  var extras = [];
  if (!parsed || parsed.kind === "empty") return { lines: lines, extras: extras };
  if (parsed.kind === "text") {
    extras.push(parsed.text);
    return { lines: lines, extras: extras };
  }
  for (var i = 0; i < parsed.items.length; i++) {
    var item = parsed.items[i];
    if (item.kind === "commit") lines.push(item.subject);
    else if (item.text && lines.indexOf(item.text) < 0) extras.push(item.text);
  }
  return { lines: lines, extras: extras };
}
function renderSummary(node, text) {
  var stamp = String(text == null ? "" : text);
  if (node.getAttribute("data-stamp") === stamp) return;
  var open = false;
  var existing = node.querySelector ? node.querySelector("details") : null;
  if (existing && existing.open) open = true;
  node.setAttribute("data-stamp", stamp);
  clearNode(node);
  var parts = noteParts(text);
  var visible = parts.lines.slice(0, 5);
  var hiddenLines = parts.lines.slice(5);
  var extras = [];
  for (var i = 0; i < parts.extras.length; i++) {
    if (parts.lines.indexOf(parts.extras[i]) < 0) extras.push(parts.extras[i]);
  }
  if (!visible.length && !hiddenLines.length && !extras.length) return;
  if (visible.length) {
    var list = el("ul", "summary-list");
    for (var n = 0; n < visible.length; n++) list.appendChild(el("li", "", visible[n]));
    node.appendChild(list);
  }
  if (!hiddenLines.length && !extras.length) return;
  var details = document.createElement("details");
  details.className = "more";
  if (open) details.open = true;
  var summary = document.createElement("summary");
  summary.textContent = "Show all";
  details.appendChild(summary);
  if (hiddenLines.length) {
    var rest = el("ul", "summary-list");
    for (var h = 0; h < hiddenLines.length; h++) rest.appendChild(el("li", "", hiddenLines[h]));
    details.appendChild(rest);
  }
  if (extras.length) details.appendChild(el("p", "summary-extra", extras.join("\n")));
  node.appendChild(details);
}
function renderPackage(packageId, summaryId, entry, href, notes, platformName) {
  var node = document.getElementById(packageId);
  var summary = document.getElementById(summaryId);
  var stamp = JSON.stringify(entry || null);
  if (node.getAttribute("data-stamp") !== stamp) {
    node.setAttribute("data-stamp", stamp);
    clearNode(node);
    if (!entry) {
      node.appendChild(el("p", "latest", "No package is available."));
    } else {
      var version = versionText(entry);
      if (version) node.appendChild(el("p", "version", version));
      var facts = packageFacts(entry);
      if (facts) node.appendChild(el("p", "meta", facts));
      if (entry.notice) node.appendChild(el("p", "notice", entry.notice));
      var label = "Download " + platformName;
      if (version) label += " " + version;
      node.appendChild(downloadLink(href, entry.savedName || entry.filename, label));
    }
  }
  renderSummary(summary, notes);
}
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
function requestedText(cut) {
  var commit = cut && cut.request ? cut.request.commit : "";
  if (!/^[0-9a-fA-F]{7,40}$/.test(String(commit || ""))) return "Requested";
  return "Requested · " + shortHash(commit);
}
function keptBothNote(cut) {
  var paths = cut && cut.request && cut.request.keptBoth ? cut.request.keptBoth : [];
  if (!paths.length) return "";
  return "Both edits kept in " + paths.join(", ") + ".";
}
function issueAnchor(identifier) {
  var text = String(identifier || "");
  if (!ISSUE_LINK.test(text)) return el("span", "issue-link", text);
  var link = document.createElement("a");
  link.className = "issue-link";
  link.href = ISSUE_ORIGIN + text;
  link.textContent = text;
  return link;
}
function updateSentence(count, short) {
  var noun = count === 1 ? "commit" : "commits";
  return "Includes every commit since this download. " + count + " " + noun + ", through " + short + ".";
}
function confirmCopy(kind, short, busy) {
  if (busy) {
    if (kind === "update") return STOP_LATEST;
    return "This stops the compile that is running and requests " + short + " instead.";
  }
  if (kind === "update") return "This replaces the waiting request and requests the latest instead.";
  return "This replaces the waiting request and requests " + short + " instead.";
}
function replaceLabel(busy) {
  return busy ? "Replace the running build" : "Replace the request";
}
function tipCommit(cut, commits) {
  var tip = cut && cut.tip ? String(cut.tip) : "";
  if (tip && commits.some(function (row) { return row.commit === tip; })) return tip;
  return commits.length ? commits[0].commit : "";
}
function commitShort(commits, commit) {
  for (var i = 0; i < commits.length; i++) {
    if (commits[i].commit === commit) return commits[i].short || shortHash(commit);
  }
  return shortHash(commit);
}
function postBuildRequest(platform, commit, override, stateNode, button) {
  button.disabled = true;
  stateNode.textContent = "Requesting…";
  var body = { platform: platform, commit: commit };
  if (override) body.override = true;
  fetch("/api/build-request", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
    cache: "no-store"
  }).then(function (response) {
    return response.json().then(function (payload) {
      return { ok: response.ok, status: response.status, body: payload };
    });
  }).then(function (result) {
    if (!result.ok) {
      stateNode.textContent = (result.body && result.body.error) || "The request was refused.";
      button.disabled = result.status === 503;
      return;
    }
    cutState[platform].domStamp = "";
    cutState[platform].updateStamp = "";
    refresh();
  }).catch(function () {
    stateNode.textContent = "The request did not reach the builds page.";
    button.disabled = false;
  });
}
function grayLine(cut) {
  if (cut && cut.busy) return cut.busy;
  if (cut && cut.pending) return requestedText(cut);
  return "";
}
function chooseNode(platform) {
  return document.getElementById(platform + "-choose");
}
function renderUpdate(platform, cut, enabled) {
  var root = document.getElementById(platform + "-update");
  var choose = chooseNode(platform);
  var state = cutState[platform];
  if (!root) return;
  if (!enabled) {
    root.hidden = true;
    clearNode(root);
    state.updateStamp = "";
    state.armUpdate = false;
    if (choose) choose.hidden = true;
    return;
  }
  var commits = cut && cut.commits ? cut.commits : [];
  var stamp = JSON.stringify(cut || {});
  var blocked = !!(cut && (cut.busy || cut.pending));
  var target = tipCommit(cut, commits);
  var showChoose = !!(commits.length && !(cut && cut.error));
  if (choose) choose.hidden = !showChoose;
  if (!showChoose) state.armChoose = false;
  if (state.updateStamp === stamp) return;
  state.updateStamp = stamp;
  state.armUpdate = false;
  clearNode(root);
  root.hidden = false;
  if (cut && cut.error) {
    root.appendChild(el("p", "latest", cut.error));
    return;
  }
  if (!commits.length) {
    var branch = (cut && cut.branch) || "this branch";
    if (cut && cut.pending) root.appendChild(el("p", "request-state", requestedText(cut)));
    else root.appendChild(el("p", "latest", "This download is already the latest on " + branch + "."));
    return;
  }
  var short = commitShort(commits, target);
  root.appendChild(el("p", "update-line", updateSentence(commits.length, short)));
  var button = document.createElement("button");
  button.type = "button";
  button.className = "update-button";
  button.id = platform + "-update-button";
  button.textContent = "Update everything";
  button.disabled = blocked || !target;
  var stateNode = el("p", "request-state");
  stateNode.id = platform + "-update-state";
  stateNode.setAttribute("aria-live", "polite");
  var line = grayLine(cut);
  if (line) stateNode.textContent = line;
  else stateNode.hidden = true;
  root.appendChild(button);
  root.appendChild(stateNode);
  if (!blocked) {
    button.addEventListener("click", function () {
      if (!target || button.disabled) return;
      postBuildRequest(platform, target, false, stateNode, button);
    });
    return;
  }
  var replace = document.createElement("button");
  replace.type = "button";
  replace.className = "text-button";
  replace.id = platform + "-replace-button";
  replace.textContent = replaceLabel(!!(cut && cut.busy));
  replace.addEventListener("click", function () {
    if (!target) return;
    if (!state.armUpdate) {
      state.armUpdate = true;
      stateNode.hidden = false;
      stateNode.textContent = confirmCopy("update", short, !!(cut && cut.busy));
      replace.textContent = "Replace it";
      return;
    }
    state.armUpdate = false;
    postBuildRequest(platform, target, true, stateNode, replace);
  });
  root.appendChild(replace);
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
  state.armChoose = false;
  clearNode(root);
  if ((cut && cut.error) || !commits.length) return;
  root.appendChild(el("p", "cut-help", PREFIX_HELP));
  var keptNote = keptBothNote(cut);
  if (keptNote) root.appendChild(el("p", "cut-help", keptNote));
  var list = el("div", "cut-list");
  for (var i = 0; i < commits.length; i++) {
    var row = commits[i];
    var line = el("div", "cut-row");
    var label = document.createElement("label");
    var box = document.createElement("input");
    box.type = "checkbox";
    box.checked = !!state.selected[row.commit];
    box.setAttribute("data-index", String(i));
    label.appendChild(box);
    label.appendChild(el("span", "cut-subject", row.subject || "(no subject)"));
    line.appendChild(label);
    var issues = row.issues || [];
    if (issues.length) {
      var issueRow = el("span", "issue-links");
      for (var n = 0; n < issues.length; n++) issueRow.appendChild(issueAnchor(issues[n]));
      line.appendChild(issueRow);
    }
    line.appendChild(el("span", "cut-meta", shortHash(row.commit)));
    list.appendChild(line);
  }
  root.appendChild(list);
  var stateNode = el("p", "request-state");
  stateNode.id = platform + "-request-state";
  stateNode.setAttribute("aria-live", "polite");
  var button = document.createElement("button");
  button.type = "button";
  button.className = "request-button";
  button.id = platform + "-request-button";
  button.textContent = REQUEST_LABEL[platform];
  function paint() {
    var chosen = newestChecked(commits, state.selected);
    var count = checkedCount(commits, state.selected);
    if (state.armChoose) {
      stateNode.hidden = false;
      stateNode.textContent = confirmCopy("choose", chosen ? commitShort(commits, chosen) : "", !!(cut && cut.busy));
      button.disabled = true;
      return;
    }
    if (cut && cut.pending) stateNode.textContent = requestedText(cut);
    else if (cut && cut.busy) stateNode.textContent = cut.busy;
    else if (!chosen) stateNode.textContent = "Choose a commit.";
    else stateNode.textContent = "Includes " + count + (count === 1 ? " commit." : " commits.");
    stateNode.hidden = false;
    button.disabled = !!(cut && (cut.pending || cut.busy)) || !chosen;
  }
  root.onchange = function (event) {
    var target = event.target;
    if (!target || target.type !== "checkbox") return;
    var index = Number(target.getAttribute("data-index"));
    if (!isFinite(index)) return;
    state.selected = applyPrefixToggle(commits, state.selected, index, target.checked);
    syncCutChecks(root, commits, state.selected);
    if (state.armChoose) state.armChoose = false;
    paint();
  };
  button.addEventListener("click", function () {
    var chosen = newestChecked(commits, state.selected);
    if (!chosen || button.disabled) return;
    postBuildRequest(platform, chosen, false, stateNode, button);
  });
  paint();
  root.appendChild(button);
  root.appendChild(stateNode);
  if (cut && (cut.busy || cut.pending)) {
    var replace = document.createElement("button");
    replace.type = "button";
    replace.className = "text-button";
    replace.textContent = replaceLabel(!!cut.busy);
    replace.addEventListener("click", function () {
      var chosen = newestChecked(commits, state.selected);
      if (!chosen) return;
      if (!state.armChoose) {
        state.armChoose = true;
        replace.textContent = "Replace it";
        paint();
        return;
      }
      state.armChoose = false;
      postBuildRequest(platform, chosen, true, stateNode, replace);
    });
    root.appendChild(replace);
  }
}
function renderSurface(platform, cut, enabled) {
  renderUpdate(platform, cut, enabled);
  var commits = cut && cut.commits ? cut.commits : [];
  var showChoose = !!(enabled && commits.length && !(cut && cut.error));
  if (!showChoose) {
    var idle = document.getElementById(platform + "-cut");
    if (idle) clearNode(idle);
    cutState[platform].domStamp = "";
    cutState[platform].armChoose = false;
    return;
  }
  renderCut(platform, cut, true);
}
function compileRoot(prefix) {
  return document.getElementById(prefix === "desktop-" ? "dmg-compile" : "ipa-compile");
}
function logElementId(prefix) {
  return prefix === "desktop-" ? "desktop-log-view" : "log-view";
}
function nearBottom(node) {
  return node.scrollHeight - node.scrollTop - node.clientHeight < 12;
}
function compileSource(payload) {
  if (!payload) return null;
  var state = payload.status;
  if (state === "building" || state === "queued" || state === "failed") return payload;
  var failure = payload.failure;
  if (!failure || failure.status !== "failed") return null;
  return {
    status: "failed",
    stage: failure.stage,
    percent: failure.percent,
    remainingLabel: failure.remainingLabel,
    remainingSeconds: failure.remainingSeconds,
    commit: failure.commit,
    startedAt: failure.startedAt,
    error: failure.error,
    logTail: payload.logTail
  };
}
function compileFacts(payload) {
  var parts = [];
  var label = payload.remainingLabel;
  if (payload.status !== "failed" && label && label !== "none" && payload.remainingSeconds != null) {
    parts.push(label + " left");
  }
  var commit = shortHash(payload.commit);
  if (commit && commit !== "none") parts.push(commit);
  if (payload.startedAt) parts.push("started " + shortWhen(payload.startedAt));
  return parts.join(" · ");
}
function compileMode(payload) {
  var state = payload && payload.status ? payload.status : "idle";
  if (state === "building" || state === "queued") return "run";
  if (state === "failed") return "fail";
  return "";
}
function renderCompile(prefix, payload) {
  var root = compileRoot(prefix);
  if (!root) return;
  var view = compileSource(payload);
  var mode = compileMode(view);
  if (!mode) {
    if (!root.hidden) {
      root.hidden = true;
      clearNode(root);
      root.removeAttribute("data-shell");
    }
    return;
  }
  root.hidden = false;
  var failed = mode === "fail";
  if (root.getAttribute("data-shell") !== mode) {
    clearNode(root);
    root.setAttribute("data-shell", mode);
    var row = el("div", "compile-row");
    var word = el("p", "status-word");
    word.id = prefix + "status-word";
    var stage = el("p", "stage");
    stage.id = prefix + "stage";
    var percent = el("p", "percent");
    percent.id = prefix + "percent";
    row.appendChild(word);
    row.appendChild(stage);
    row.appendChild(percent);
    root.appendChild(row);
    var track = el("div", "track");
    track.id = prefix + "track";
    track.setAttribute("role", "progressbar");
    track.setAttribute("aria-valuemin", "0");
    track.setAttribute("aria-valuemax", "100");
    track.setAttribute("aria-label", prefix === "desktop-" ? "Mac build progress" : "iPhone build progress");
    var bar = el("span", "bar");
    bar.id = prefix + "bar";
    track.appendChild(bar);
    root.appendChild(track);
    var facts = el("p", "compile-note");
    facts.id = prefix + "facts";
    root.appendChild(facts);
    var error = el("p", "alert");
    error.id = prefix + "error";
    error.setAttribute("role", "alert");
    error.hidden = true;
    root.appendChild(error);
    var details = document.createElement("details");
    details.className = "log";
    details.id = prefix + "log-details";
    if (failed) details.open = true;
    var summary = document.createElement("summary");
    summary.textContent = "Show log";
    details.appendChild(summary);
    var logView = el("pre", "log-view");
    logView.id = logElementId(prefix);
    details.appendChild(logView);
    logView.addEventListener("scroll", function () {
      logFollow[prefix] = nearBottom(logView);
    });
    root.appendChild(details);
  }
  var state = view.status;
  var statusNode = document.getElementById(prefix + "status-word");
  if (statusNode) {
    statusNode.textContent = statusWord(state);
    statusNode.setAttribute("data-state", state);
  }
  var stageNode = document.getElementById(prefix + "stage");
  if (stageNode) {
    var stageName = view.stage && view.stage !== "none" ? String(view.stage) : "";
    stageNode.textContent = stageName;
    stageNode.hidden = !stageName;
  }
  var width = Math.max(0, Math.min(100, Number(view.percent) || 0));
  var percentNode = document.getElementById(prefix + "percent");
  if (percentNode) percentNode.textContent = percentText(view.percent);
  var barNode = document.getElementById(prefix + "bar");
  if (barNode) barNode.style.width = width + "%";
  var trackNode = document.getElementById(prefix + "track");
  if (trackNode) trackNode.setAttribute("aria-valuenow", String(Math.round(width)));
  var factsNode = document.getElementById(prefix + "facts");
  if (factsNode) {
    var factsText = compileFacts(view);
    factsNode.textContent = factsText;
    factsNode.hidden = !factsText;
  }
  var errorNode = document.getElementById(prefix + "error");
  if (errorNode) {
    if (view.error) {
      errorNode.hidden = false;
      errorNode.textContent = view.error;
    } else {
      errorNode.hidden = true;
      errorNode.textContent = "";
    }
  }
  var logNode = document.getElementById(logElementId(prefix));
  if (logNode) {
    var next = String(view.logTail == null ? "" : view.logTail);
    var stick = logFollow[prefix] !== false && nearBottom(logNode);
    if (logNode.textContent !== next) logNode.textContent = next;
    if (stick) logNode.scrollTop = logNode.scrollHeight;
  }
}
function olderRow(entry) {
  var row = el("div", "older-row");
  var product = entry.product || "Package";
  row.appendChild(el("p", "older-product", product));
  var version = versionText(entry);
  if (version) row.appendChild(el("p", "meta", version));
  var name = entry.filename || "";
  var link = document.createElement("a");
  link.className = "older-link";
  link.href = "/download/older/" + encodeURIComponent(name);
  link.textContent = "Download";
  link.setAttribute("aria-label", "Download " + product + (version ? " " + version : ""));
  if (name) link.setAttribute("download", name);
  row.appendChild(link);
  return row;
}
function renderOlder(rows) {
  var group = document.getElementById("older-downloads");
  var root = document.getElementById("older");
  if (!group || !root) return;
  var list = rows || [];
  var stamp = JSON.stringify(list);
  if (root.getAttribute("data-stamp") === stamp) return;
  root.setAttribute("data-stamp", stamp);
  clearNode(root);
  if (!list.length) {
    group.hidden = true;
    return;
  }
  group.hidden = false;
  for (var i = 0; i < list.length; i++) root.appendChild(olderRow(list[i]));
}
var upstreamNextAt = "";
var upstreamTimer = 0;
function twoDigits(number) {
  return (number < 10 ? "0" : "") + number;
}
function countdownLabel(nextAt, nowMs) {
  var target = Date.parse(nextAt);
  var now = typeof nowMs === "number" ? nowMs : Date.now();
  var remain = 0;
  if (isFinite(target)) remain = Math.max(0, Math.floor((target - now) / 1000));
  var hours = Math.floor(remain / 3600);
  var minutes = Math.floor((remain % 3600) / 60);
  var seconds = remain % 60;
  var clock;
  if (hours >= 1) clock = hours + "h " + twoDigits(minutes) + "m";
  else if (minutes >= 1) clock = minutes + "m " + twoDigits(seconds) + "s";
  else clock = seconds + "s";
  return "Next automatic merge and build in " + clock;
}
function aheadLine(name, count, short) {
  if (count === 1) return name + " upstream has 1 commit that is not merged, through " + short + ".";
  return name + " upstream has " + count + " commits that are not merged, through " + short + ".";
}
function upstreamStatusLines(running, surfaces) {
  if (running) return ["Merge and build is running."];
  var list = surfaces || [];
  var readable = 0;
  var i;
  for (i = 0; i < list.length; i++) if (list[i].status !== "unavailable") readable++;
  if (!readable) return ["Upstream could not be checked."];
  var lines = [];
  for (i = 0; i < list.length; i++) {
    var item = list[i];
    if (item.status === "ahead") lines.push(aheadLine(item.name, Number(item.count) || 0, item.short || ""));
    else if (item.status === "unavailable") lines.push(item.name + " upstream could not be checked.");
  }
  if (!lines.length) return ["Upstream is already merged."];
  return lines;
}
function tickUpstream() {
  var clock = document.getElementById("upstream-countdown");
  if (!clock || !upstreamNextAt) return;
  var next = countdownLabel(upstreamNextAt);
  if (clock.textContent !== next) clock.textContent = next;
}
function bindUpstream() {
  var button = document.getElementById("upstream-run-button");
  if (!button || button.getAttribute("data-bound") === "1") return;
  button.setAttribute("data-bound", "1");
  button.addEventListener("click", function () {
    if (button.hidden || button.disabled) return;
    button.disabled = true;
    button.setAttribute("data-pending", "1");
    var state = document.getElementById("upstream-state");
    if (state) {
      state.hidden = false;
      state.textContent = "Starting…";
    }
    fetch("/api/upstream-run", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: "{}",
      cache: "no-store"
    }).then(function (response) {
      return response.json().then(function (payload) {
        return { ok: response.ok, status: response.status, body: payload };
      });
    }).then(function (result) {
      button.removeAttribute("data-pending");
      if (!result.ok) {
        if (state) {
          state.hidden = false;
          state.textContent = (result.body && result.body.error) || "The merge and build could not be started.";
        }
        button.disabled = false;
        return;
      }
      refresh();
    }).catch(function () {
      button.removeAttribute("data-pending");
      button.disabled = false;
      if (state) {
        state.hidden = false;
        state.textContent = "The merge and build could not be started.";
      }
    });
  });
}
function renderUpstream(run) {
  run = run || {};
  var surfaces = run.surfaces || [];
  upstreamNextAt = typeof run.nextAt === "string" ? run.nextAt : "";
  var clock = document.getElementById("upstream-countdown");
  if (clock && upstreamNextAt) clock.textContent = countdownLabel(upstreamNextAt);
  var linesRoot = document.getElementById("upstream-lines");
  var lines = run.lines;
  if (!lines || !lines.length) lines = upstreamStatusLines(!!run.running, surfaces);
  if (linesRoot) {
    var stamp = lines.join("\n");
    if (linesRoot.getAttribute("data-stamp") !== stamp) {
      linesRoot.setAttribute("data-stamp", stamp);
      clearNode(linesRoot);
      for (var i = 0; i < lines.length; i++) linesRoot.appendChild(el("p", "upstream-line", lines[i]));
    }
  }
  var ahead = false;
  for (var s = 0; s < surfaces.length; s++) if (surfaces[s].status === "ahead") ahead = true;
  var show = ahead && !run.running;
  var button = document.getElementById("upstream-run-button");
  if (button) {
    button.hidden = !show;
    if (!show) {
      button.disabled = false;
      button.removeAttribute("data-pending");
      var idleState = document.getElementById("upstream-state");
      if (idleState) {
        idleState.hidden = true;
        idleState.textContent = "";
      }
    } else if (button.getAttribute("data-pending") !== "1") {
      button.disabled = false;
    }
  }
  bindUpstream();
}
function applyDashboard(payload) {
  var downloads = payload.downloads || {};
  var desktopDownload = downloads.desktop || {};
  var iphone = payload.iphone || {};
  var desktop = payload.desktop || {};
  renderPackage("ipa-package", "ipa-summary", downloads.ipa, "/download/ipa", iphone.releaseNotes, "iPhone");
  renderPackage("dmg-package", "dmg-summary", desktopDownload.macos, "/download/desktop/macos", desktop.releaseNotes, "Mac");
  renderCompile("", iphone);
  renderCompile("desktop-", desktop);
  renderOlder(downloads.older || []);
  var cuts = payload.cuts || {};
  var enabled = payload.requestEnabled !== false;
  renderSurface("ipa", cuts.ipa || {}, enabled);
  renderSurface("dmg", cuts.dmg || {}, enabled);
  renderUpstream(payload.upstreamRun || {});
  if (payload.poll) return !!payload.poll.active;
  var iphoneState = iphone.status;
  var desktopState = desktop.status;
  return iphoneState === "building" || iphoneState === "queued" || desktopState === "building" || desktopState === "queued";
}
function readSeed() {
  var node = document.getElementById("dashboard-seed");
  if (!node || !node.textContent) return null;
  try {
    return JSON.parse(node.textContent);
  } catch (err) {
    return null;
  }
}
function refresh() {
  if (refreshRunning) return;
  refreshRunning = true;
  var active = lastActive;
  fetch("/api/dashboard", { cache: "no-store", credentials: "same-origin" }).then(function (response) {
    var type = response.headers.get("content-type") || "";
    if (!response.ok || type.indexOf("json") < 0) return null;
    return response.json();
  }).then(function (payload) {
    if (payload) active = applyDashboard(payload);
  }).catch(function () {
    active = lastActive;
  }).then(function () {
    refreshRunning = false;
    lastActive = active;
    window.clearTimeout(pollTimer);
    pollTimer = window.setTimeout(refresh, active ? ACTIVE_POLL_MS : IDLE_POLL_MS);
  });
}
function boot() {
  bindUpstream();
  var seed = readSeed();
  if (seed) lastActive = applyDashboard(seed);
  tickUpstream();
  if (!upstreamTimer) upstreamTimer = window.setInterval(tickUpstream, 1000);
  refresh();
}
if (typeof document !== "undefined") boot();
