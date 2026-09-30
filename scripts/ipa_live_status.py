#!/usr/bin/env python3
"""Publish live unsigned-IPA build status and serve the builds page.

The build scripts call this process. Percent moves inside the active stage
band by elapsed/(elapsed + recorded baseline). Remaining time is the
unfinished percent divided by the observed percent-per-second rate.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import plistlib
import re
import sys
import threading
import time
import zipfile
from contextlib import contextmanager
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlparse

STAGES = (
    ("preflight", 0.0, 2.0),
    ("prepare", 2.0, 20.0),
    ("xcodebuild", 20.0, 90.0),
    ("checks", 90.0, 95.0),
    ("zip", 95.0, 100.0),
)
STAGE_NAMES = tuple(name for name, _start, _end in STAGES)
DEFAULT_BASELINE_SECONDS = {
    "preflight": 30.0,
    "prepare": 180.0,
    "xcodebuild": 1800.0,
    "checks": 20.0,
    "zip": 40.0,
}
NOTES_LIMIT = 20000
LOG_LINE_LIMIT = 500
LOG_TAIL_LINES = 80
BUILD_HISTORY_LIMIT = 40
RECENT_BUILD_LIMIT = 8
SECRET_LINE = re.compile(
    r"(?i)(api[_-]?key|client[_-]?secret|secret|token|password|private[_-]?key)\s*[=:]"
)
JWT_LINE = re.compile(r"eyJ[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]{10,}")
# Log lines only. A Trakt/Simkl assignment or a local.properties value is not
# release-note text, so this stays off the notes path.
ACCOUNT_VALUE_LINE = re.compile(
    r"(?i)(?:local\.properties|(?:trakt|simkl)[a-z0-9_-]*)\s*[=:].*\S"
)
BUILD_ID_RE = re.compile(r"^[A-Za-z0-9_-]{1,80}$")
BUILD_LOG_ROUTE = re.compile(r"^/api/builds/([A-Za-z0-9_-]+)/log$")
VERSION_IN_NAME = re.compile(r"(\d+\.\d+\.\d+(?:-[0-9A-Za-z]+)*)")
DESKTOP_KINDS = ("macos", "windows", "linux")
PUBLIC_DOWNLOAD_FIELDS = ("filename", "bytes", "sha256", "version", "commit")
DOWNLOAD_ROUTES = {
    "/download/ipa": "ipa",
    "/download/desktop/macos": "macos",
    "/download/desktop/windows": "windows",
    "/download/desktop/linux": "linux",
}
OLDER_DOWNLOAD_ROUTE = re.compile(r"^/download/older/([A-Za-z0-9._-]+)$")
PRODUCT_IPHONE = "Nuvio for iPhone"
PRODUCT_MAC = "Nuvio for Mac"
PACKAGE_NOTICE = "The package was published and the debug-symbol step failed."

PAGE = r"""<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<title>Nuvio builds</title>
<style>
  :root {
    color-scheme: light dark;
    --bg: #F2F2F7;
    --card: #FFFFFF;
    --label: #000000;
    --secondary: rgba(60, 60, 67, 0.6);
    --separator: rgba(60, 60, 67, 0.29);
    --blue: #007AFF;
    --green: #34C759;
    --red: #FF3B30;
    --orange: #FF9500;
    --track: rgba(118, 118, 128, 0.12);
  }
  @media (prefers-color-scheme: dark) {
    :root {
      --bg: #000000;
      --card: #1C1C1E;
      --label: #FFFFFF;
      --secondary: rgba(235, 235, 245, 0.6);
      --separator: rgba(84, 84, 88, 0.65);
      --blue: #0A84FF;
      --green: #30D158;
      --red: #FF453A;
      --orange: #FF9F0A;
      --track: rgba(118, 118, 128, 0.24);
    }
  }
  * { box-sizing: border-box; }
  [hidden] { display: none !important; }
  html, body {
    margin: 0;
    max-width: 100%;
    min-height: 100%;
    overflow-x: hidden;
    background: var(--bg);
    color: var(--label);
  }
  body {
    min-height: 100dvh;
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
    font-size: 17pt;
    font-weight: 400;
    line-height: 1.29;
    font: -apple-system-body;
  }
  main {
    width: 100%;
    max-width: 28rem;
    min-width: 0;
    margin: 0 auto;
    display: flex;
    flex-direction: column;
    gap: 22pt;
    padding-top: calc(16pt + env(safe-area-inset-top));
    padding-right: calc(16pt + env(safe-area-inset-right));
    padding-bottom: calc(16pt + env(safe-area-inset-bottom));
    padding-left: calc(16pt + env(safe-area-inset-left));
  }
  h1 {
    margin: 0;
    color: var(--label);
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
    font-size: 22pt;
    font-weight: 700;
    line-height: 1.2;
    font: -apple-system-title2;
  }
  .hero { min-width: 0; display: flex; flex-direction: column; gap: 6pt; }
  .hero-metrics {
    display: flex;
    flex-direction: row;
    flex-wrap: wrap;
    align-items: baseline;
    column-gap: 10pt;
    row-gap: 2pt;
    min-width: 0;
    max-width: 100%;
  }
  .percent {
    margin: 0;
    min-width: 0;
    color: var(--label);
    font-family: ui-rounded, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
    font-size: 48pt;
    font-weight: 700;
    line-height: 1;
    letter-spacing: -0.03em;
    font-variant-numeric: tabular-nums;
  }
  .status-word {
    margin: 0;
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
    font-size: 17pt;
    font-weight: 600;
    line-height: 1.2;
    font: -apple-system-headline;
  }
  .status-word[data-state="building"] { color: var(--blue); }
  .status-word[data-state="queued"] { color: var(--orange); }
  .status-word[data-state="succeeded"] { color: var(--green); }
  .status-word[data-state="failed"] { color: var(--red); }
  .status-word[data-state="idle"] { color: var(--secondary); }
  .remaining {
    margin: 0;
    color: var(--secondary);
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
    font-size: 15pt;
    font-weight: 400;
    line-height: 1.25;
    font: -apple-system-subheadline;
    color: var(--secondary);
  }
  .track {
    height: 5pt;
    border-radius: 999px;
    background: var(--track);
    overflow: hidden;
  }
  .bar {
    display: block;
    height: 100%;
    width: 0%;
    border-radius: 999px;
    background: var(--blue);
    transition: width 250ms linear;
  }
  @media (prefers-reduced-motion: reduce) {
    .bar { transition: none; }
    .log-view { scroll-behavior: auto; }
  }
  .log-view {
    height: 12rem;
    margin: 0;
    padding: 8pt 16pt;
    min-width: 0;
    max-width: 100%;
    overflow-x: hidden;
    overflow-y: auto;
    white-space: pre-wrap;
    overflow-wrap: anywhere;
    word-break: break-word;
    scroll-behavior: auto;
    color: var(--label);
    font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
    font-size: 11pt;
    font-weight: 400;
    line-height: 1.35;
  }
  .build-list { min-width: 0; }
  .build-row {
    position: relative;
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    column-gap: 10pt;
    row-gap: 2pt;
    width: 100%;
    min-height: 44pt;
    margin: 0;
    padding: 8pt 16pt;
    border: 0;
    background: transparent;
    text-align: left;
    cursor: pointer;
    color: var(--label);
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
    font-size: 15pt;
    font-weight: 400;
    line-height: 1.25;
    font: -apple-system-subheadline;
    color: var(--label);
  }
  .log-view + .build-list .build-row:first-child::before,
  .build-row + .build-row::before {
    content: "";
    position: absolute;
    left: 16pt;
    right: 0;
    top: 0;
    height: 0.5px;
    background: var(--separator);
  }
  .build-row[aria-selected="true"] { background: var(--track); }
  .build-row:active { opacity: 0.45; }
  .build-status { flex: 0 0 auto; font-weight: 600; }
  .build-commit, .build-when {
    min-width: 0;
    max-width: 100%;
    color: var(--secondary);
    overflow-wrap: anywhere;
  }
  .build-commit { flex: 1 1 auto; }
  .build-when {
    flex: 0 1 auto;
    margin-left: auto;
    font-variant-numeric: tabular-nums;
  }
  .group { min-width: 0; display: flex; flex-direction: column; }
  .group-title {
    margin: 0 0 6pt 16pt;
    color: var(--secondary);
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
    font-size: 13pt;
    font-weight: 400;
    line-height: 1.2;
    font: -apple-system-footnote;
    text-transform: uppercase;
    color: var(--secondary);
  }
  .card {
    background: var(--card);
    border-radius: 10pt;
    corner-shape: squircle;
    overflow: hidden;
    min-width: 0;
  }
  .row {
    position: relative;
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12pt;
    min-height: 44pt;
    padding: 8pt 16pt;
    min-width: 0;
  }
  .row-stack {
    flex-direction: column;
    align-items: stretch;
    justify-content: center;
  }
  .row-line {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12pt;
    min-width: 0;
    min-height: 22pt;
  }
  .row + .row::before,
  .note + .note::before,
  .note + .plain::before,
  .plain + .note::before,
  .row + .note::before,
  .note + .row::before,
  .plain + .plain::before,
  .row + .plain::before,
  .plain + .row::before {
    content: "";
    position: absolute;
    left: 16pt;
    right: 0;
    top: 0;
    height: 0.5px;
    background: var(--separator);
  }
  .row-label, .note-subject, .plain-text, .empty-notes, #error {
    margin: 0;
    min-width: 0;
    color: var(--label);
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
    font-size: 17pt;
    font-weight: 400;
    line-height: 1.29;
    font: -apple-system-body;
  }
  .row-label { flex: 0 0 auto; }
  .row-value {
    flex: 1 1 auto;
    min-width: 0;
    margin: 0;
    text-align: right;
    white-space: normal;
    overflow: visible;
    overflow-wrap: anywhere;
    color: var(--secondary);
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
    font-size: 17pt;
    font-weight: 400;
    line-height: 1.29;
    font-variant-numeric: tabular-nums;
    font: -apple-system-body;
    color: var(--secondary);
  }
  #commit, #desktop-commit {
    margin: 2pt 0 0;
    min-width: 0;
    max-width: 100%;
    color: var(--secondary);
    font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
    font-size: 13pt;
    font-weight: 400;
    line-height: 1.25;
    overflow-wrap: anywhere;
    user-select: text;
    -webkit-user-select: text;
    font: -apple-system-footnote;
    font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  }
  .note, .plain {
    position: relative;
    min-height: 44pt;
    min-width: 0;
    padding: 8pt 16pt;
  }
  .note-subject { color: var(--label); }
  .note-meta, .empty-notes {
    margin: 2pt 0 0;
    color: var(--secondary);
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
    font-size: 13pt;
    font-weight: 400;
    line-height: 1.25;
    font: -apple-system-footnote;
    color: var(--secondary);
  }
  .empty-notes { margin: 0; }
  .note-subject, .plain-text { overflow-wrap: break-word; }
  .plain-text { white-space: pre-wrap; }
  #error, #desktop-error {
    display: block;
    color: var(--red);
    overflow-wrap: anywhere;
  }
  #notes-toggle, #desktop-notes-toggle {
    position: relative;
    display: block;
    width: 100%;
    min-height: 44pt;
    margin: 0;
    padding: 0 16pt;
    border: 0;
    background: transparent;
    text-align: center;
    cursor: pointer;
    color: var(--blue);
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
    font-size: 17pt;
    font-weight: 400;
    font: -apple-system-body;
    color: var(--blue);
  }
  #notes-toggle::before, #desktop-notes-toggle::before {
    content: "";
    position: absolute;
    left: 16pt;
    right: 0;
    top: 0;
    height: 0.5px;
    background: var(--separator);
  }
  #notes-toggle:active, #desktop-notes-toggle:active { opacity: 0.45; }
  .column-title {
    display: block;
    margin: 0;
    min-width: 0;
    overflow-wrap: anywhere;
    font: -apple-system-headline;
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
    font-weight: 600;
  }
  .card-actions {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 12pt;
    min-width: 0;
    max-width: 100%;
  }
  button.copy-link {
    display: flex;
    align-items: center;
    justify-content: center;
    min-height: 44pt;
    min-width: 44pt;
    margin: 0;
    padding: 0 12pt;
    border: 0;
    background: transparent;
    color: var(--blue);
    cursor: pointer;
    font: -apple-system-body;
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
  }
  button.copy-link:active { opacity: 0.45; }
  .package-note, .absent-line {
    margin: 0;
    min-width: 0;
    max-width: 100%;
    color: var(--secondary);
    overflow-wrap: anywhere;
    font: -apple-system-footnote;
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
  }
  .absent-line { font: -apple-system-subheadline; }
  .build-product {
    flex: 1 1 100%;
    min-width: 0;
    overflow-wrap: anywhere;
    font-weight: 600;
  }
  .columns { min-width: 0; display: flex; flex-direction: column; gap: 22pt; }
  .column { min-width: 0; display: flex; flex-direction: column; gap: 22pt; }
  .download-file {
    margin: 0;
    min-width: 0;
    color: var(--label);
    overflow-wrap: anywhere;
    font: -apple-system-body;
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
  }
  .download-meta {
    margin: 2pt 0 0;
    min-width: 0;
    color: var(--secondary);
    overflow-wrap: anywhere;
    font: -apple-system-footnote;
    font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  }
  a.download-link {
    display: flex;
    align-items: center;
    align-self: flex-start;
    min-height: 44pt;
    min-width: 44pt;
    margin: 0;
    padding: 0;
    color: var(--blue);
    font: -apple-system-body;
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
    text-decoration: none;
  }
  a.download-link:active { opacity: 0.45; }
  @media (min-width: 700px) {
    main { max-width: 960px; }
    #ipa-column > .column-title, #desktop-column > .column-title { display: block; }
    .columns {
      display: grid;
      grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
      gap: 22pt;
      align-items: start;
    }
  }
</style>
</head>
<body>
<main>
  <h1>Nuvio builds</h1>
  <section class="group" id="downloads-group">
    <h2 class="group-title">Downloads</h2>
  </section>
  <div class="columns">
    <section class="column" id="ipa-column">
      <h2 class="column-title">Nuvio for iPhone</h2>
      <div class="card" id="iphone-card">
        <div class="row"><p class="empty-notes">No package</p></div>
      </div>
      <header class="hero">
        <div class="hero-metrics">
          <p class="percent" id="percent"></p>
          <p class="status-word" id="status" data-state="idle">Idle</p>
        </div>
        <p class="remaining" id="remaining" hidden></p>
        <div class="track" id="track" role="progressbar" aria-valuemin="0" aria-valuemax="100" aria-valuenow="0" aria-label="IPA build progress"><span class="bar" id="bar"></span></div>
      </header>
      <section class="group" id="error-group" hidden>
        <h2 class="group-title">Error</h2>
        <div class="card">
          <p class="row" id="error" role="alert"></p>
        </div>
      </section>
      <section class="group" id="build-group">
        <h2 class="group-title">Build</h2>
        <div class="card">
          <div class="row">
            <p class="row-label">Branch</p>
            <p class="row-value" id="branch">None</p>
          </div>
          <div class="row row-stack">
            <div class="row-line">
              <p class="row-label">Commit</p>
              <p class="row-value" id="commit-short">None</p>
            </div>
            <p id="commit"></p>
          </div>
          <div class="row" id="stage-row" hidden>
            <p class="row-label">Stage</p>
            <p class="row-value" id="stage"></p>
          </div>
        </div>
      </section>
      <section class="group" id="log-group">
        <h2 class="group-title">Log</h2>
        <div class="card">
          <pre id="log-view" class="log-view">none</pre>
          <div class="build-list" id="build-list"></div>
        </div>
      </section>
      <section class="group" id="notes-group">
        <h2 class="group-title">Release notes</h2>
        <div class="card">
          <div id="notes">
            <div class="row"><p class="empty-notes">No release notes</p></div>
          </div>
          <button type="button" id="notes-toggle" hidden>Show All</button>
        </div>
      </section>
    </section>
    <section class="column" id="desktop-column">
      <h2 class="column-title">Nuvio for Mac</h2>
      <div class="card" id="mac-card">
        <div class="row"><p class="empty-notes">No package</p></div>
      </div>
      <header class="hero">
        <div class="hero-metrics">
          <p class="percent" id="desktop-percent"></p>
          <p class="status-word" id="desktop-status" data-state="idle">Idle</p>
        </div>
        <p class="remaining" id="desktop-remaining" hidden></p>
        <div class="track" id="desktop-track" role="progressbar" aria-valuemin="0" aria-valuemax="100" aria-valuenow="0" aria-label="Desktop build progress"><span class="bar" id="desktop-bar"></span></div>
      </header>
      <section class="group" id="desktop-error-group" hidden>
        <h2 class="group-title">Error</h2>
        <div class="card">
          <p class="row" id="desktop-error" role="alert"></p>
        </div>
      </section>
      <section class="group">
        <h2 class="group-title">Build</h2>
        <div class="card">
          <div class="row">
            <p class="row-label">Branch</p>
            <p class="row-value" id="desktop-branch">None</p>
          </div>
          <div class="row row-stack">
            <div class="row-line">
              <p class="row-label">Commit</p>
              <p class="row-value" id="desktop-commit-short">None</p>
            </div>
            <p id="desktop-commit"></p>
          </div>
          <div class="row" id="desktop-stage-row" hidden>
            <p class="row-label">Stage</p>
            <p class="row-value" id="desktop-stage"></p>
          </div>
        </div>
      </section>
      <section class="group" id="desktop-log-group">
        <h2 class="group-title">Log</h2>
        <div class="card">
          <pre id="desktop-log-view" class="log-view">none</pre>
          <div class="build-list" id="desktop-build-list"></div>
        </div>
      </section>
      <section class="group">
        <h2 class="group-title">Release notes</h2>
        <div class="card">
          <div id="desktop-notes">
            <div class="row"><p class="empty-notes">No release notes</p></div>
          </div>
          <button type="button" id="desktop-notes-toggle" hidden>Show All</button>
        </div>
      </section>
    </section>
  </div>
  <p class="absent-line" id="absent-packages">Windows and Linux have no package.</p>
  <section class="group" id="older-group" hidden>
    <h2 class="group-title">Older</h2>
    <div class="card" id="older"></div>
  </section>
</main>
<script>
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
function apply(payload) {
  applyInto("", payload);
  fillNotes(payload.releaseNotes);
}
function applyDesktop(payload) {
  applyInto("desktop-", payload);
  desktopNotesStamp = fillNotesInto(payload.releaseNotes, "desktop-notes", "desktop-notes-toggle", desktopNotesExpanded, desktopNotesStamp);
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
async function refresh() {
  try {
    var response = await fetch("/api/status", { cache: "no-store" });
    if (response.ok) apply(await response.json());
  } catch (err) {}
  try {
    var desktopResponse = await fetch("/api/desktop", { cache: "no-store" });
    if (desktopResponse.ok) applyDesktop(await desktopResponse.json());
  } catch (err) {}
  try {
    var downloadsResponse = await fetch("/api/downloads", { cache: "no-store" });
    if (downloadsResponse.ok) applyDownloads(await downloadsResponse.json());
  } catch (err) {}
}
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
setInterval(refresh, 2000);
</script>
</body>
</html>
"""


def repository_root() -> Path:
    return Path(__file__).resolve().parents[1]


def status_directory(explicit: str | None = None) -> Path:
    if explicit:
        return Path(explicit).expanduser().resolve()
    env = os.environ.get("IPA_STATUS_DIR")
    if env:
        return Path(env).expanduser().resolve()
    return repository_root() / "build" / "ipa-live-status"


def iso(timestamp: float) -> str:
    return datetime.fromtimestamp(timestamp, timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def pid_alive(pid: int) -> bool:
    if pid <= 0:
        return False
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return False
    except PermissionError:
        return True
    except OSError:
        return False
    return True


def redact(text: str) -> str:
    kept = []
    for line in text.splitlines():
        if SECRET_LINE.search(line) or JWT_LINE.search(line):
            kept.append("[redacted]")
        else:
            kept.append(line)
    redacted = "\n".join(kept).strip()
    if len(redacted) > NOTES_LIMIT:
        redacted = redacted[:NOTES_LIMIT].rstrip() + "\n[truncated]"
    return redacted


def redact_log_line(line: str) -> str:
    """Store one build line. Secrets use the release-note redact() path."""
    piece = line.split("\n", 1)[0].split("\r", 1)[0]
    redacted = redact(piece)
    if (
        redacted == "[redacted]"
        or SECRET_LINE.search(piece)
        or JWT_LINE.search(piece)
        or ACCOUNT_VALUE_LINE.search(piece)
    ):
        return "[redacted]"
    if len(redacted) > LOG_LINE_LIMIT:
        return redacted[:LOG_LINE_LIMIT]
    return redacted


def atomic_write(path: Path, text: str) -> None:
    temporary = path.with_name(path.name + ".tmp")
    temporary.write_text(text, encoding="utf-8")
    os.replace(temporary, path)


@contextmanager
def locked(directory: Path, exclusive: bool = True):
    directory.mkdir(parents=True, exist_ok=True)
    handle = (directory / ".lock").open("a+")
    try:
        import fcntl

        fcntl.flock(handle.fileno(), fcntl.LOCK_EX if exclusive else fcntl.LOCK_SH)
        yield
    finally:
        import fcntl

        fcntl.flock(handle.fileno(), fcntl.LOCK_UN)
        handle.close()


def read_json(path: Path) -> dict | None:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (FileNotFoundError, json.JSONDecodeError, OSError):
        return None


def write_json(path: Path, payload: dict) -> None:
    atomic_write(path, json.dumps(payload, indent=2, sort_keys=True) + "\n")


def builds_path(directory: Path) -> Path:
    return directory / "builds.json"


def logs_directory(directory: Path) -> Path:
    return directory / "logs"


def valid_build_id(value: object) -> bool:
    return isinstance(value, str) and BUILD_ID_RE.fullmatch(value) is not None


def log_file(directory: Path, build_id: str) -> Path:
    return logs_directory(directory) / f"{build_id}.log"


def load_builds(directory: Path) -> list[dict]:
    raw = read_json(builds_path(directory)) or {}
    items = raw.get("builds")
    if not isinstance(items, list):
        return []
    return [item for item in items if isinstance(item, dict) and valid_build_id(item.get("id"))]


def store_builds(directory: Path, builds: list[dict]) -> None:
    write_json(builds_path(directory), {"builds": builds[:BUILD_HISTORY_LIMIT]})


@contextmanager
def log_locked(directory: Path):
    logs = logs_directory(directory)
    logs.mkdir(parents=True, exist_ok=True)
    handle = (logs / ".lock").open("a+")
    try:
        import fcntl

        fcntl.flock(handle.fileno(), fcntl.LOCK_EX)
        yield
    finally:
        import fcntl

        fcntl.flock(handle.fileno(), fcntl.LOCK_UN)
        handle.close()


def new_build_id(now: float) -> str:
    stamp = datetime.fromtimestamp(now, timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    return f"{stamp}-{os.urandom(4).hex()}"


def open_build_record(directory: Path, session: dict, now: float, platform: str = "ipa") -> str:
    build_id = new_build_id(now)
    builds = load_builds(directory)
    builds.insert(
        0,
        {
            "id": build_id,
            "platform": platform,
            "status": "building",
            "branch": session.get("branch") or "none",
            "commit": session.get("commit") or "none",
            "startedAt": iso(now),
            "finishedAt": None,
        },
    )
    evicted = builds[BUILD_HISTORY_LIMIT:]
    store_builds(directory, builds[:BUILD_HISTORY_LIMIT])
    logs_directory(directory).mkdir(parents=True, exist_ok=True)
    log_file(directory, build_id).touch()
    for old in evicted:
        old_id = old.get("id")
        if valid_build_id(old_id):
            try:
                log_file(directory, str(old_id)).unlink(missing_ok=True)
            except OSError:
                pass
    return build_id


def close_build_record(directory: Path, build_id: object, status: str, now: float) -> None:
    if not valid_build_id(build_id) or status not in ("succeeded", "failed"):
        return
    builds = load_builds(directory)
    for item in builds:
        if item.get("id") != build_id:
            continue
        if item.get("finishedAt"):
            break
        item["status"] = status
        item["finishedAt"] = iso(now)
        break
    store_builds(directory, builds)


def product_label(platform: object) -> str:
    if platform == "ipa":
        return PRODUCT_IPHONE
    if platform == "desktop":
        return PRODUCT_MAC
    return "Nuvio"


def public_build(item: dict) -> dict:
    return {
        "id": item.get("id"),
        "platform": item.get("platform"),
        "product": product_label(item.get("platform")),
        "status": item.get("status"),
        "branch": item.get("branch") or "none",
        "commit": item.get("commit") or "none",
        "startedAt": item.get("startedAt"),
        "finishedAt": item.get("finishedAt"),
    }


def recent_builds(directory: Path, platform: str) -> list[dict]:
    rows = []
    for item in load_builds(directory):
        if item.get("platform") != platform:
            continue
        rows.append(public_build(item))
        if len(rows) >= RECENT_BUILD_LIMIT:
            break
    return rows


def indexed_build(directory: Path, build_id: str) -> dict | None:
    if not valid_build_id(build_id):
        return None
    for item in load_builds(directory):
        if item.get("id") == build_id:
            return item
    return None


def tail_lines(path: Path, limit: int) -> str:
    try:
        size = path.stat().st_size
    except OSError:
        return ""
    if size <= 0:
        return ""
    with path.open("rb") as handle:
        window = 65536
        blob = b""
        position = size
        while position > 0 and blob.count(b"\n") <= limit:
            step = min(window, position)
            position -= step
            handle.seek(position)
            blob = handle.read(step) + blob
            if window < 1024 * 1024:
                window *= 2
    text = blob.decode("utf-8", errors="replace")
    lines = text.splitlines()
    if position > 0 and lines:
        lines = lines[1:]
    if not lines:
        return ""
    return "\n".join(lines[-limit:])


def log_tail(directory: Path, platform: str) -> str:
    for item in load_builds(directory):
        if item.get("platform") != platform:
            continue
        build_id = item.get("id")
        if not valid_build_id(build_id):
            return ""
        return tail_lines(log_file(directory, str(build_id)), LOG_TAIL_LINES)
    return ""


def append_log_line(directory: Path, build_id: str, line: str) -> None:
    if not valid_build_id(build_id):
        return
    stored = redact_log_line(line)
    path = log_file(directory, build_id)
    path.parent.mkdir(parents=True, exist_ok=True)
    data = (stored + "\n").encode("utf-8", errors="replace")
    with log_locked(directory):
        with path.open("ab") as handle:
            handle.write(data)


def attach_platform_logs(payload: dict, directory: Path, platform: str) -> dict:
    enriched = dict(payload)
    if not directory.exists():
        enriched["logTail"] = ""
        enriched["recentBuilds"] = []
        return enriched
    enriched["logTail"] = log_tail(directory, platform)
    enriched["recentBuilds"] = recent_builds(directory, platform)
    return enriched


def session_path(directory: Path) -> Path:
    return directory / "session.json"


def baseline_path(directory: Path) -> Path:
    return directory / "baselines.json"


def public_path(directory: Path) -> Path:
    return directory / "status.json"


def load_baselines(directory: Path) -> dict[str, float]:
    raw = read_json(baseline_path(directory)) or {}
    baselines = dict(DEFAULT_BASELINE_SECONDS)
    for name in STAGE_NAMES:
        value = raw.get(name)
        if isinstance(value, (int, float)) and math.isfinite(value) and value > 0:
            baselines[name] = float(value)
    return baselines


def store_baselines(directory: Path, baselines: dict[str, float]) -> None:
    write_json(baseline_path(directory), {name: baselines[name] for name in STAGE_NAMES})


def band(name: str) -> tuple[float, float]:
    for stage_name, start, end in STAGES:
        if stage_name == name:
            return start, end
    raise KeyError(name)


def intra_fraction(elapsed: float, baseline: float) -> float:
    baseline = max(float(baseline), 1.0)
    elapsed = max(float(elapsed), 0.0)
    # Stays inside the band and keeps moving for as long as the stage runs.
    return elapsed / (elapsed + baseline)


def format_remaining(seconds: float | None) -> str:
    if seconds is None:
        return "none"
    whole = max(0, int(round(seconds)))
    if whole < 60:
        return f"{whole}s"
    minutes, secs = divmod(whole, 60)
    if minutes < 60:
        return f"{minutes}m {secs:02d}s"
    hours, minutes = divmod(minutes, 60)
    return f"{hours}h {minutes:02d}m"


def prior_rate(baselines: dict[str, float]) -> float:
    total = sum(baselines[name] for name in STAGE_NAMES)
    return 100.0 / max(total, 1.0)


def render(session: dict, baselines: dict[str, float], now: float) -> dict:
    branch = session.get("branch") or "none"
    commit = session.get("commit") or "none"
    notes = session.get("releaseNotes") or "none"
    status = session.get("status") or "idle"
    stage = session.get("stage")
    error = session.get("error")
    updated = iso(now)

    if session.get("closed"):
        # Nothing is building. The header stays Idle with an empty stage.
        # The failure or success remains on the history row, not here.
        return {
            "status": "idle",
            "percent": 0,
            "remainingSeconds": None,
            "remainingLabel": "none",
            "branch": branch,
            "commit": commit,
            "releaseNotes": notes,
            "stage": None,
            "error": None,
            "updatedAt": updated,
        }

    build_started = float(session.get("buildStartedAt") or now)
    elapsed_total = max(0.0, now - build_started)

    if status == "queued" or not stage:
        percent = 0.0
        rate = prior_rate(baselines)
        remaining = (100.0 - percent) / rate
    else:
        start, end = band(str(stage))
        stage_started = float(session.get("stageStartedAt") or now)
        elapsed_stage = max(0.0, now - stage_started)
        fraction = intra_fraction(elapsed_stage, baselines[str(stage)])
        percent = start + (end - start) * fraction
        if elapsed_total >= 1.0 and percent > 0.05:
            rate = percent / elapsed_total
        else:
            rate = prior_rate(baselines)
        remaining = max(0.0, 100.0 - percent) / max(rate, 1e-6)

    return {
        "status": status,
        "percent": round(percent, 2),
        "remainingSeconds": int(round(remaining)),
        "remainingLabel": format_remaining(remaining),
        "branch": branch,
        "commit": commit,
        "releaseNotes": notes,
        "stage": stage,
        "error": error,
        "updatedAt": updated,
    }


def idle_payload(now: float) -> dict:
    return {
        "status": "idle",
        "percent": 0,
        "remainingSeconds": None,
        "remainingLabel": "none",
        "branch": "none",
        "commit": "none",
        "releaseNotes": "none",
        "stage": None,
        "error": None,
        "updatedAt": iso(now),
    }


def publish(directory: Path, session: dict, baselines: dict[str, float], now: float) -> dict:
    payload = render(session, baselines, now)
    write_json(session_path(directory), session)
    write_json(public_path(directory), payload)
    return payload


def read_text(path: str | None) -> str:
    if not path:
        return ""
    try:
        return Path(path).read_text(encoding="utf-8", errors="replace")
    except OSError as exc:
        return f"could not read {path}: {exc}"


def notes_from_files(notes_file: str | None, error_file: str | None) -> str:
    error = redact(read_text(error_file))
    notes = redact(read_text(notes_file))
    if error:
        return error
    if notes:
        return notes
    return "(no release notes)"


def command_start(args: argparse.Namespace) -> int:
    directory = status_directory(args.status_dir)
    now = time.time()
    session = {
        "status": "queued",
        "branch": args.branch or "none",
        "commit": args.commit or "none",
        "releaseNotes": notes_from_files(args.notes_file, args.notes_error_file),
        "stage": None,
        "stageStartedAt": None,
        "buildStartedAt": now,
        "parentPid": int(args.parent_pid),
        "error": None,
        "closed": False,
        "frozenPercent": None,
        "frozenRemainingSeconds": None,
    }
    with locked(directory):
        baselines = load_baselines(directory)
        if not baseline_path(directory).exists():
            store_baselines(directory, baselines)
        session["buildId"] = open_build_record(directory, session, now, platform="ipa")
        payload = publish(directory, session, baselines, now)
    print(
        f"ipa-status: {payload['status']} percent={payload['percent']:.2f}",
        file=sys.stderr,
    )
    return 0


def command_stage(args: argparse.Namespace) -> int:
    if args.name not in STAGE_NAMES:
        print(f"unknown stage {args.name}", file=sys.stderr)
        return 2
    directory = status_directory(args.status_dir)
    now = time.time()
    with locked(directory):
        session = read_json(session_path(directory))
        if not session or session.get("closed"):
            print("ipa-status: no active build session", file=sys.stderr)
            return 2
        baselines = load_baselines(directory)
        previous = session.get("stage")
        if previous and previous != args.name:
            started = float(session.get("stageStartedAt") or now)
            elapsed = max(0.0, now - started)
            if elapsed >= 0.5:
                # Recorded baseline is the last completed duration.
                baselines[str(previous)] = elapsed
                store_baselines(directory, baselines)
        if previous != args.name:
            session["stage"] = args.name
            session["stageStartedAt"] = now
        session["status"] = "building"
        payload = publish(directory, session, baselines, now)
    print(
        f"ipa-status: stage {args.name} percent={payload['percent']:.2f} remaining={payload['remainingLabel']}",
        file=sys.stderr,
    )
    return 0


def command_finish(args: argparse.Namespace) -> int:
    directory = status_directory(args.status_dir)
    now = time.time()
    with locked(directory):
        session = read_json(session_path(directory))
        if not session:
            return 0
        if session.get("closed"):
            return 0
        baselines = load_baselines(directory)
        current = render(session, baselines, now)
        previous = session.get("stage")
        if previous and args.status == "succeeded":
            started = float(session.get("stageStartedAt") or now)
            elapsed = max(0.0, now - started)
            if elapsed >= 0.5:
                baselines[str(previous)] = elapsed
                store_baselines(directory, baselines)
        session["status"] = args.status
        session["closed"] = True
        session["error"] = args.message or None
        close_build_record(directory, session.get("buildId"), args.status, now)
        if args.status == "succeeded":
            session["frozenPercent"] = 100.0
            session["frozenRemainingSeconds"] = 0
            session["stage"] = previous
        else:
            session["frozenPercent"] = current["percent"]
            session["frozenRemainingSeconds"] = current["remainingSeconds"]
        payload = publish(directory, session, baselines, now)
    print(
        f"ipa-status: {payload['status']} percent={payload['percent']:.2f}",
        file=sys.stderr,
    )
    return 0


def command_active(args: argparse.Namespace) -> int:
    directory = status_directory(args.status_dir)
    session = read_json(session_path(directory))
    if session and session.get("status") in ("queued", "building") and not session.get("closed"):
        return 0
    return 1


def command_show(args: argparse.Namespace) -> int:
    payload = current_public(status_directory(args.status_dir), time.time())
    sys.stdout.write(json.dumps(payload, indent=2, sort_keys=True) + "\n")
    return 0


def current_public(directory: Path, now: float) -> dict:
    if not directory.exists():
        return attach_platform_logs(idle_payload(now), directory, "ipa")
    with locked(directory, exclusive=False):
        session = read_json(session_path(directory))
        if not session:
            stored = read_json(public_path(directory))
            if stored and stored.get("status"):
                payload = stored
            else:
                payload = idle_payload(now)
        else:
            baselines = load_baselines(directory)
            payload = render(session, baselines, now)
        return attach_platform_logs(payload, directory, "ipa")


def desktop_session_path(directory: Path) -> Path:
    return directory / "desktop-session.json"


def downloads_path(directory: Path) -> Path:
    return directory / "downloads.json"


def artifacts_directory(directory: Path) -> Path:
    return directory / "artifacts"


def empty_catalog() -> dict:
    return {"ipa": None, "ipa-debug": None, "desktop": {"macos": None, "windows": None, "linux": None}}


def load_catalog(directory: Path) -> dict:
    raw = read_json(downloads_path(directory)) or {}
    catalog = empty_catalog()
    ipa = raw.get("ipa")
    if isinstance(ipa, dict):
        catalog["ipa"] = ipa
    ipa_debug = raw.get("ipa-debug")
    if isinstance(ipa_debug, dict):
        catalog["ipa-debug"] = ipa_debug
    desktop = raw.get("desktop")
    if isinstance(desktop, dict):
        for kind in DESKTOP_KINDS:
            entry = desktop.get(kind)
            if isinstance(entry, dict):
                catalog["desktop"][kind] = entry
    return catalog


def _finite_number(value: object) -> float | None:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return None
    if not math.isfinite(value):
        return None
    return float(value)


def desktop_public(directory: Path, now: float) -> dict:
    """Read desktop-session.json. A missing file is the idle payload."""
    if not directory.exists():
        return attach_platform_logs(idle_payload(now), directory, "desktop")
    with locked(directory, exclusive=False):
        raw = read_json(desktop_session_path(directory))
        if not raw:
            payload = idle_payload(now)
        else:
            closed = bool(raw.get("closed")) or raw.get("status") in ("succeeded", "failed")
            branch = raw.get("branch") or "none"
            commit = raw.get("commit") or "none"
            notes = raw.get("releaseNotes") or "none"
            stage = raw.get("stage")
            error = raw.get("error")
            updated = raw.get("updatedAt") or iso(now)
            if closed:
                payload = {
                    "status": "idle",
                    "percent": 0,
                    "remainingSeconds": None,
                    "remainingLabel": "none",
                    "branch": branch,
                    "commit": commit,
                    "releaseNotes": notes,
                    "stage": None,
                    "error": None,
                    "updatedAt": updated,
                }
            else:
                status = raw.get("status") or "idle"
                if status not in ("idle", "queued", "building"):
                    status = "idle"
                percent = _finite_number(raw.get("percent"))
                if percent is None:
                    percent = 0.0
                remaining_seconds = raw.get("remainingSeconds")
                if _finite_number(remaining_seconds) is None:
                    remaining_out = None
                else:
                    remaining_out = int(round(float(remaining_seconds)))
                payload = {
                    "status": status,
                    "percent": round(percent, 2),
                    "remainingSeconds": remaining_out,
                    "remainingLabel": raw.get("remainingLabel") or "none",
                    "branch": branch,
                    "commit": commit,
                    "releaseNotes": notes,
                    "stage": stage,
                    "error": error,
                    "updatedAt": updated,
                }
        return attach_platform_logs(payload, directory, "desktop")


def safe_filename(name: str) -> str:
    cleaned = re.sub(r"[^A-Za-z0-9._-]", "_", Path(name).name)
    if cleaned in {"", ".", ".."}:
        return "artifact"
    return cleaned


def content_disposition(filename: str) -> str:
    return f'attachment; filename="{safe_filename(filename)}"'


def infer_version(filename: str) -> str:
    match = VERSION_IN_NAME.search(Path(filename).name)
    return match.group(1) if match else "none"


def artifact_file(directory: Path, entry: dict | None) -> Path | None:
    if not entry:
        return None
    name = entry.get("file") or entry.get("filename")
    if not isinstance(name, str) or not name:
        return None
    relative = Path(name)
    if relative.is_absolute() or any(part in {"", ".", ".."} for part in relative.parts):
        return None
    root = artifacts_directory(directory).resolve()
    path = (root / relative).resolve()
    if path != root and root not in path.parents:
        return None
    if not path.is_file():
        return None
    return path


def desktop_manifest_path(directory: Path) -> Path:
    return artifacts_directory(directory) / "desktop" / "manifest.json"


def overlay_published_desktop(directory: Path, catalog: dict) -> dict:
    """Fill desktop slots from the package publisher's manifest.

    A seeded entry with the same sha256 stays, so the download keeps the
    filename and commit recorded for that file. A different published sha,
    or a platform this catalog does not have, replaces the slot.
    """
    raw = read_json(desktop_manifest_path(directory)) or {}
    for kind in DESKTOP_KINDS:
        published = raw.get(kind)
        if not isinstance(published, dict):
            continue
        filename = published.get("filename")
        if not isinstance(filename, str) or filename != Path(filename).name:
            continue
        candidate = {
            "filename": filename,
            "bytes": published.get("bytes"),
            "sha256": published.get("sha256"),
            "version": published.get("version") or "none",
            "commit": published.get("commit") or "none",
            "file": f"desktop/{filename}",
        }
        if artifact_file(directory, candidate) is None:
            continue
        current = catalog["desktop"].get(kind)
        if isinstance(current, dict) and current.get("sha256") == candidate.get("sha256"):
            if artifact_file(directory, current) is not None:
                continue
            current["file"] = candidate["file"]
            continue
        catalog["desktop"][kind] = candidate
    return catalog


def public_download_entry(directory: Path, entry: dict | None) -> dict | None:
    if artifact_file(directory, entry) is None or entry is None:
        return None
    return {field: entry.get(field) for field in PUBLIC_DOWNLOAD_FIELDS}


def short_commit(commit: object) -> str:
    if not isinstance(commit, str) or not re.fullmatch(r"[0-9a-fA-F]{7,40}", commit):
        return ""
    return commit[:8]


def ipa_bundle_versions(path: Path) -> tuple[str | None, str | None]:
    try:
        if not zipfile.is_zipfile(path):
            return None, None
        with zipfile.ZipFile(path) as archive:
            for name in archive.namelist():
                if not name.startswith("Payload/") or not name.endswith(".app/Info.plist"):
                    continue
                if name.count("/") != 2:
                    continue
                info = plistlib.loads(archive.read(name))
                short = info.get("CFBundleShortVersionString")
                build = info.get("CFBundleVersion")
                short_text = str(short) if short else None
                build_text = str(build) if build else None
                return short_text, build_text
    except (OSError, zipfile.BadZipFile, plistlib.InvalidFileException, ValueError):
        return None, None
    return None, None


def saved_download_name(kind: str, version: object, build: object, commit: object) -> str:
    short = short_commit(commit) or "unknown"
    ver = version if isinstance(version, str) and version and version != "none" else "unknown"
    ver = safe_filename(ver)
    if kind == "ipa":
        if isinstance(build, str) and build and build != "none":
            build_text = safe_filename(build)
            return safe_filename(f"Nuvio-iPhone-{ver}-{build_text}-{short}.ipa")
        return safe_filename(f"Nuvio-iPhone-{ver}-{short}.ipa")
    return safe_filename(f"Nuvio-Mac-{ver}-{short}.dmg")


def copy_line(title: str, version: object, build: object, commit: object) -> str:
    short = short_commit(commit) or "none"
    ver = version if isinstance(version, str) and version and version != "none" else "none"
    if isinstance(build, str) and build and build != "none":
        ver = f"{ver} ({build})"
    return f"{title} {ver} · {short}"


def package_time(directory: Path, commit: object, platform: str, path: Path) -> str:
    if isinstance(commit, str):
        for item in load_builds(directory):
            if item.get("platform") == platform and item.get("commit") == commit and item.get("finishedAt"):
                return str(item["finishedAt"])
    try:
        return iso(path.stat().st_mtime)
    except OSError:
        return ""


def published_failure_notice(directory: Path, commit: object) -> str | None:
    """The current iPhone card explains a package that exists after a failed build.

    Sideload does not need the dSYM. The history row stays failed.
    """
    if not short_commit(commit):
        return None
    for item in load_builds(directory):
        if item.get("platform") != "ipa":
            continue
        if item.get("commit") != commit:
            return None
        if item.get("status") == "failed":
            return PACKAGE_NOTICE
        return None
    return None


def public_named_entry(directory: Path, entry: dict | None, kind: str) -> dict | None:
    path = artifact_file(directory, entry) if entry else None
    if entry is None or path is None:
        return None
    version = entry.get("version")
    build = entry.get("build") if kind == "ipa" else None
    commit = entry.get("commit")
    if kind == "ipa":
        short, bundle_build = ipa_bundle_versions(path)
        if short and (not isinstance(version, str) or not version or version == "none"):
            version = short
        if bundle_build:
            build = bundle_build
    title = PRODUCT_IPHONE if kind == "ipa" else PRODUCT_MAC
    platform = "ipa" if kind == "ipa" else "desktop"
    payload = {
        "filename": entry.get("filename"),
        "bytes": entry.get("bytes") if entry.get("bytes") is not None else path.stat().st_size,
        "sha256": entry.get("sha256"),
        "version": version,
        "commit": commit,
        "product": title,
        "savedName": saved_download_name(kind, version, build, commit),
        "copyText": copy_line(title, version, build, commit),
        "packagedAt": package_time(directory, commit, platform, path),
    }
    if kind == "ipa" and isinstance(build, str) and build:
        payload["build"] = build
        notice = published_failure_notice(directory, commit)
        if notice:
            payload["notice"] = notice
    return payload


def catalog_snapshot(catalog: dict) -> str:
    return json.dumps(catalog, sort_keys=True, separators=(",", ":"))


def reconcile_catalog(directory: Path) -> dict:
    """Make downloads.json name the same desktop file the manifest publishes."""
    if not directory.exists():
        return empty_catalog()
    with locked(directory):
        catalog = load_catalog(directory)
        before = catalog_snapshot(catalog)
        overlay_published_desktop(directory, catalog)
        ipa = catalog.get("ipa")
        if isinstance(ipa, dict):
            path = artifact_file(directory, ipa)
            if path is not None:
                short, build = ipa_bundle_versions(path)
                if build and ipa.get("build") != build:
                    ipa["build"] = build
                if short and (not ipa.get("version") or ipa.get("version") == "none"):
                    ipa["version"] = short
        if catalog_snapshot(catalog) != before:
            write_json(downloads_path(directory), catalog)
        return catalog


def known_catalog_entries(catalog: dict) -> list[dict]:
    rows: list[dict] = []
    ipa = catalog.get("ipa")
    if isinstance(ipa, dict):
        rows.append(ipa)
    debug = catalog.get("ipa-debug")
    if isinstance(debug, dict):
        rows.append(debug)
    desktop = catalog.get("desktop") if isinstance(catalog.get("desktop"), dict) else {}
    for kind in DESKTOP_KINDS:
        entry = desktop.get(kind)
        if isinstance(entry, dict):
            rows.append(entry)
    return rows


def entry_matches_file(entry: dict, relative: str, name: str) -> bool:
    stored = entry.get("file")
    filename = entry.get("filename")
    return stored in {relative, name} or filename == name


def older_downloads(directory: Path, catalog: dict) -> list[dict]:
    current: set[Path] = set()
    for entry in (catalog.get("ipa"), (catalog.get("desktop") or {}).get("macos")):
        path = artifact_file(directory, entry if isinstance(entry, dict) else None)
        if path is not None:
            current.add(path.resolve())
    root = artifacts_directory(directory)
    if not root.is_dir():
        return []
    packages: list[Path] = []
    for path in root.rglob("*"):
        if not path.is_file() or path.suffix.lower() not in {".ipa", ".dmg"}:
            continue
        relative_parts = path.relative_to(root).parts
        if any(part.startswith(".") for part in relative_parts):
            continue
        packages.append(path)
    packages.sort(key=lambda item: item.stat().st_mtime, reverse=True)
    known = known_catalog_entries(catalog)
    rows: list[dict] = []
    for path in packages:
        if path.resolve() in current:
            continue
        relative = path.relative_to(root).as_posix()
        match = next((entry for entry in known if entry_matches_file(entry, relative, path.name)), None)
        kind = "ipa" if path.suffix.lower() == ".ipa" else "macos"
        version = match.get("version") if match else None
        commit = match.get("commit") if match else None
        build = match.get("build") if match else None
        if kind == "ipa":
            short, bundle_build = ipa_bundle_versions(path)
            if short:
                version = short
            if bundle_build:
                build = bundle_build
        if not isinstance(version, str) or not version or version == "none":
            inferred = infer_version(path.name)
            version = None if inferred == "none" else inferred
        if not isinstance(commit, str) or commit == "none" or not short_commit(commit):
            commit = None
        try:
            size = path.stat().st_size
        except OSError:
            continue
        item = {
            "filename": path.name,
            "bytes": size,
            "version": version,
            "commit": commit,
            "product": PRODUCT_IPHONE if kind == "ipa" else PRODUCT_MAC,
            "status": "older",
        }
        if isinstance(build, str) and build:
            item["build"] = build
        if match and match.get("sha256"):
            item["sha256"] = match.get("sha256")
        rows.append(item)
    return rows


def current_downloads(directory: Path) -> dict:
    catalog = reconcile_catalog(directory)
    desktop = catalog["desktop"]
    return {
        "ipa": public_named_entry(directory, catalog.get("ipa"), "ipa"),
        "desktop": {
            "macos": public_named_entry(directory, desktop.get("macos"), "macos"),
            "windows": public_download_entry(directory, desktop.get("windows")),
            "linux": public_download_entry(directory, desktop.get("linux")),
        },
        "older": older_downloads(directory, catalog),
    }


def resolved_catalog(directory: Path) -> dict:
    if not directory.exists():
        catalog = empty_catalog()
    else:
        with locked(directory, exclusive=False):
            catalog = load_catalog(directory)
    return overlay_published_desktop(directory, catalog)


def catalog_entry(directory: Path, kind: str) -> dict | None:
    catalog = resolved_catalog(directory)
    if kind == "ipa":
        entry = catalog.get("ipa")
    else:
        entry = catalog["desktop"].get(kind)
    if not isinstance(entry, dict):
        return None
    if artifact_file(directory, entry) is None:
        return None
    return entry


def hash_file(path: Path) -> tuple[int, str]:
    digest = hashlib.sha256()
    total = 0
    with path.open("rb") as handle:
        while True:
            chunk = handle.read(1024 * 1024)
            if not chunk:
                break
            digest.update(chunk)
            total += len(chunk)
    return total, digest.hexdigest()


def copy_hashed(source: Path, dest: Path) -> tuple[int, str]:
    dest.parent.mkdir(parents=True, exist_ok=True)
    partial = dest.with_name(dest.name + ".partial")
    digest = hashlib.sha256()
    total = 0
    try:
        with source.open("rb") as src, partial.open("wb") as out:
            while True:
                chunk = src.read(1024 * 1024)
                if not chunk:
                    break
                digest.update(chunk)
                out.write(chunk)
                total += len(chunk)
        os.replace(partial, dest)
    except Exception:
        try:
            partial.unlink()
        except OSError:
            pass
        raise
    return total, digest.hexdigest()


def is_zip_ipa(path: Path) -> bool:
    try:
        if not path.is_file() or path.stat().st_size < 22:
            return False
    except OSError:
        return False
    return zipfile.is_zipfile(path)


def command_record_download(args: argparse.Namespace) -> int:
    kind = args.kind
    if kind not in ("ipa", *DESKTOP_KINDS):
        print(f"unknown download kind {kind}", file=sys.stderr)
        return 2
    source = Path(args.path).expanduser()
    if not source.is_file():
        print(f"ipa-status: missing file {source}", file=sys.stderr)
        return 2
    if kind == "ipa" and not is_zip_ipa(source):
        print(f"ipa-status: {source} is not a zip IPA", file=sys.stderr)
        return 2
    directory = status_directory(args.status_dir)
    filename = source.name
    stored = safe_filename(filename)
    version = args.version or infer_version(filename)
    build_number = None
    if kind == "ipa":
        short, bundle_build = ipa_bundle_versions(source)
        if args.version is None and short:
            version = short
        build_number = bundle_build
    commit = args.commit
    if not commit:
        session = read_json(session_path(directory)) or {}
        commit = session.get("commit") or "none"
    dest = artifacts_directory(directory) / stored
    if source.resolve() == dest.resolve():
        total, digest = hash_file(source)
    else:
        total, digest = copy_hashed(source, dest)
    entry = {
        "filename": filename,
        "bytes": total,
        "sha256": digest,
        "version": str(version),
        "commit": str(commit),
        "file": stored,
    }
    if build_number:
        entry["build"] = build_number
    with locked(directory):
        catalog = load_catalog(directory)
        if kind == "ipa":
            catalog["ipa"] = entry
        else:
            catalog["desktop"][kind] = entry
        write_json(downloads_path(directory), catalog)
    print(f"ipa-status: recorded {kind} {filename} bytes={total}", file=sys.stderr)
    return 0


def daemonize(directory: Path) -> None:
    """Fork a session leader and return only in that child.

    The original process exits after the child is ready, so a build script
    does not wait on the heartbeat loop.
    """
    directory.mkdir(parents=True, exist_ok=True)
    read_fd, write_fd = os.pipe()
    pid = os.fork()
    if pid > 0:
        os.close(write_fd)
        ack = os.read(read_fd, 64)
        os.close(read_fd)
        os._exit(0 if ack else 1)
    os.close(read_fd)
    os.setsid()
    (directory / "heartbeat.pid").write_text(f"{os.getpid()}\n", encoding="utf-8")
    os.write(write_fd, b"ok")
    os.close(write_fd)
    devnull = os.open(os.devnull, os.O_RDWR)
    log_fd = os.open(
        str(directory / "heartbeat.log"),
        os.O_WRONLY | os.O_CREAT | os.O_APPEND,
        0o644,
    )
    os.dup2(devnull, 0)
    os.dup2(log_fd, 1)
    os.dup2(log_fd, 2)
    os.close(devnull)
    os.close(log_fd)


def command_heartbeat(args: argparse.Namespace) -> int:
    directory = status_directory(args.status_dir)
    parent = int(args.parent_pid)
    if not args.foreground:
        daemonize(directory)
    while True:
        now = time.time()
        with locked(directory):
            session = read_json(session_path(directory))
            if not session or session.get("closed"):
                return 0
            if not pid_alive(parent):
                baselines = load_baselines(directory)
                current = render(session, baselines, now)
                session["status"] = "failed"
                session["closed"] = True
                session["error"] = session.get("error") or "build process exited"
                session["frozenPercent"] = current["percent"]
                session["frozenRemainingSeconds"] = current["remainingSeconds"]
                close_build_record(directory, session.get("buildId"), "failed", now)
                publish(directory, session, baselines, now)
                return 0
            baselines = load_baselines(directory)
            publish(directory, session, baselines, now)
        time.sleep(2)


def command_serve(args: argparse.Namespace) -> int:
    directory = status_directory(args.status_dir)
    server = ThreadingHTTPServer((args.host, args.port), _handler_for(directory))
    print(f"ipa-status: listening on {args.host}:{server.server_address[1]}", file=sys.stderr)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        return 0
    return 0


def self_test() -> int:
    import tempfile

    failures = []

    def check(condition: bool, message: str) -> None:
        if not condition:
            failures.append(message)

    with tempfile.TemporaryDirectory() as temporary:
        directory = Path(temporary)
        baselines = dict(DEFAULT_BASELINE_SECONDS)
        now = 1_000_000.0
        session = {
            "status": "building",
            "branch": "sim-44-ipa-status",
            "commit": "abc123",
            "releaseNotes": "notes",
            "stage": "xcodebuild",
            "stageStartedAt": now,
            "buildStartedAt": now,
            "parentPid": os.getpid(),
            "error": None,
            "closed": False,
        }
        first = render(session, baselines, now + 30)
        second = render(session, baselines, now + 90)
        check(20.0 <= first["percent"] < 90.0, f"first percent out of band: {first['percent']}")
        check(second["percent"] > first["percent"], "percent did not increase inside xcodebuild")
        check(second["remainingSeconds"] != first["remainingSeconds"], "remaining did not change")
        check(first["branch"] == "sim-44-ipa-status", "branch missing")
        check(first["commit"] == "abc123", "commit missing")
        check(first["releaseNotes"] == "notes", "notes missing")

        preflight = dict(session, stage="preflight", stageStartedAt=now, buildStartedAt=now)
        early = render(preflight, baselines, now + 1)
        later = render(preflight, baselines, now + 40)
        check(0.0 < early["percent"] < 2.0, f"preflight early {early['percent']}")
        check(later["percent"] > early["percent"], "preflight percent stuck")
        check(later["percent"] < 2.0, "preflight reached the next band early")

        prepare = dict(session, stage="prepare", stageStartedAt=now + 40, buildStartedAt=now)
        snapped = render(prepare, baselines, now + 40)
        check(abs(snapped["percent"] - 2.0) < 0.02, f"prepare did not snap to 2: {snapped['percent']}")
        check(snapped["remainingSeconds"] != later["remainingSeconds"], "remaining did not update on stage change")

        secret = redact(
            "keep this line\n"
            "TRAKT_CLIENT_SECRET=supersecretvalue\n"
            "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.abcdefghijklmnop.signaturevalue\n"
        )
        check("supersecretvalue" not in secret and "eyJ" not in secret and "keep this line" in secret, "redaction failed")

        idle = idle_payload(now)
        check(idle["status"] == "idle", "idle status")
        check(idle["branch"] == "none" and idle["commit"] == "none" and idle["releaseNotes"] == "none", "idle none")
        check(idle["remainingLabel"] == "none", "empty idle remaining")

        closed = dict(
            session,
            closed=True,
            status="failed",
            error="build script exited 65",
            frozenPercent=38.64,
            frozenRemainingSeconds=1044,
        )
        closed_view = render(closed, baselines, now + 120)
        check(closed_view["status"] == "idle", "closed session status")
        check(closed_view["remainingLabel"] == "none", "closed session remaining label")
        check(closed_view["remainingSeconds"] is None, "closed session remaining seconds")
        check(closed_view["branch"] == "sim-44-ipa-status", "closed session branch")
        check(closed_view["commit"] == "abc123", "closed session commit")
        check(closed_view["releaseNotes"] == "notes", "closed session notes")
        check(closed_view["error"] is None, "idle header kept the last error")
        check(closed_view["percent"] == 0, "idle header kept a percent")
        check(closed_view["stage"] is None, "idle header kept a stage")
        succeeded = dict(closed, status="succeeded", frozenPercent=100.0, frozenRemainingSeconds=0)
        succeeded_view = render(succeeded, baselines, now + 120)
        check(
            succeeded_view["status"] == "idle" and succeeded_view["remainingLabel"] == "none",
            "finished success should still read idle",
        )

        os.environ["IPA_STATUS_DIR"] = str(directory)
        notes = directory / "notes.txt"
        errors = directory / "errors.txt"
        notes.write_text("hello notes\n", encoding="utf-8")
        errors.write_text("", encoding="utf-8")
        namespace = argparse.Namespace(
            status_dir=str(directory),
            branch="demo",
            commit="f" * 40,
            notes_file=str(notes),
            notes_error_file=str(errors),
            parent_pid=os.getpid(),
            name="preflight",
            status="succeeded",
            message=None,
            host="127.0.0.1",
            port=0,
        )
        check(command_start(namespace) == 0, "start failed")
        time.sleep(0.05)
        first_live = current_public(directory, time.time())
        check(first_live["status"] == "queued", f"expected queued, got {first_live['status']}")
        check(first_live["releaseNotes"] == "hello notes", "live notes missing")
        check(command_stage(namespace) == 0, "stage failed")
        time.sleep(1.2)
        moved = current_public(directory, time.time())
        check(moved["status"] == "building", "stage did not enter building")
        check(moved["percent"] > 0, "building percent stayed 0")
        namespace.message = "build script exited 65"
        namespace.status = "failed"
        errors.write_text("note generator exploded\n", encoding="utf-8")
        failed_notes = notes_from_files(str(notes), str(errors))
        check("exploded" in failed_notes, "error notes were dropped")
        check(command_finish(namespace) == 0, "finish failed")
        done = current_public(directory, time.time())
        check(done["status"] == "idle", "closed session should be idle")
        check(done["remainingLabel"] == "none", "closed session remaining should be none")
        check(done["remainingSeconds"] is None, "closed session remaining seconds should be none")
        check(done["branch"] == "demo", "closed session dropped branch")
        check(done["commit"] == "f" * 40, "closed session dropped commit")
        check(done["releaseNotes"] == "hello notes", "closed session dropped notes")
        check(done["error"] is None, "idle status kept the last error")
        check(done["percent"] == 0, "idle status kept a percent")
        check(done["stage"] is None, "idle status kept a stage")
        stored_session = read_json(session_path(directory)) or {}
        check(stored_session.get("error") == "build script exited 65", "session dropped the failure")
        check(command_active(namespace) == 1, "closed session still active")
        recorded = load_builds(directory)
        check(len(recorded) == 1, f"expected one build row, got {len(recorded)}")
        check(recorded[0]["status"] == "failed", "finish did not mark the build failed")
        check(recorded[0]["platform"] == "ipa", "build platform")
        check(bool(recorded[0].get("finishedAt")), "finish did not set finishedAt")
        check(log_file(directory, recorded[0]["id"]).exists(), "closing deleted the log")
        append_log_line(directory, recorded[0]["id"], "smoke: first line")
        append_log_line(directory, recorded[0]["id"], "TRAKT_CLIENT_SECRET=supersecretvalue")
        append_log_line(directory, recorded[0]["id"], "SIMKL_CLIENT_ID=simkl-value")
        append_log_line(directory, recorded[0]["id"], "local.properties TRAKT_CLIENT_ID=abc")
        append_log_line(directory, recorded[0]["id"], "x" * 800)
        stored_log = log_file(directory, recorded[0]["id"]).read_text(encoding="utf-8")
        check("smoke: first line" in stored_log, "plain log line missing")
        check("supersecretvalue" not in stored_log and "simkl-value" not in stored_log, "secret value stored")
        check(stored_log.count("[redacted]") >= 3, "redaction marker missing")
        check("x" * 501 not in stored_log, "log line was not capped at 500")
        tailed = current_public(directory, time.time())
        check("logTail" in tailed and "recentBuilds" in tailed, "status log fields missing")
        check("smoke: first line" in tailed["logTail"], "logTail dropped the line")
        check("supersecretvalue" not in tailed["logTail"] and "[redacted]" in tailed["logTail"], "logTail leaked a secret")
        check(len(tailed["recentBuilds"]) == 1, "recentBuilds")
        check(tailed["recentBuilds"][0]["id"] == recorded[0]["id"], "recent build id")
        check(tailed["recentBuilds"][0]["finishedAt"], "recent finishedAt")

        httpd = ThreadingHTTPServer(("127.0.0.1", 0), _handler_for(directory))
        thread = threading.Thread(target=httpd.serve_forever, daemon=True)
        thread.start()
        import urllib.request

        port = httpd.server_address[1]
        with urllib.request.urlopen(f"http://127.0.0.1:{port}/", timeout=5) as response:
            html = response.read().decode("utf-8")
        check("Idle" in html and 'id="notes"' in html, "page missing idle fields")
        check("flex-direction: column" in html, "page is not one column")
        check("min-width: 0" in html, "full commit line cannot shrink inside the column")
        check(
            "#commit" in html
            and "overflow-wrap: anywhere" in html
            and "word-break: break-all" not in html,
            "full commit sha is not forced to wrap inside the column",
        )
        check("viewport-fit=cover" in html, "missing viewport-fit=cover")
        check(
            "safe-area-inset-top" in html and "safe-area-inset-bottom" in html,
            "missing safe-area insets",
        )
        check("color-scheme: light dark" in html, "color-scheme is not light and dark")
        check("#101218" not in html, "forced dark background still in the page")
        check("Show All" in html and "Show Less" in html, "release notes cannot expand")
        check(
            'fetch("/api/status"' in html and "setInterval(refresh, 2000)" in html,
            "status polling changed",
        )
        with urllib.request.urlopen(f"http://127.0.0.1:{port}/api/status", timeout=5) as response:
            body = json.loads(response.read().decode("utf-8"))
        check(body["status"] == "idle", "api did not serve idle for a closed session")
        check(body["remainingLabel"] == "none", "api remaining was not none")
        check(body["branch"] == "demo", "api dropped branch")
        check(body["commit"] == "f" * 40, "api dropped commit")
        check(body["releaseNotes"] == "hello notes", "api dropped notes")
        check(body["error"] is None, "api kept the idle error")
        check(body["percent"] == 0, "api kept an idle percent")
        check(body["stage"] is None, "api kept an idle stage")
        check("servedAt" in body, "api timestamp missing")
        check("Nuvio builds" in html and "Downloads" in html, "builds page title or downloads group missing")
        check("Nuvio for iPhone" in html and "Nuvio for Mac" in html, "product titles missing")
        check("IPA debug" not in html, "debug file is still a current label")
        check("Older" in html, "older section missing")
        check("Windows and Linux have no package." in html, "missing desktop package line")
        check("copy-link" in html and "min-height: 44pt" in html, "copy control size missing")
        check('fetch("/api/desktop"' in html and 'fetch("/api/downloads"' in html, "desktop fetches missing")
        check("min-width: 700px" in html and "960px" in html, "wide layout missing")
        check("minmax(0, 1fr) minmax(0, 1fr)" in html, "two equal columns missing")
        check("a.download-link" in html and "min-height: 44pt" in html, "download control size missing")
        check("max-width: 28rem" in html, "narrow width missing")
        check("prefers-reduced-motion" in html, "reduced motion missing")
        check('id="log-view"' in html and 'id="desktop-log-view"' in html, "log views missing")
        check("12rem" in html and "build-row" in html and "Log" in html, "log group missing")
        check(body["logTail"] and "[redacted]" in body["logTail"], "api logTail missing")
        check("supersecretvalue" not in body["logTail"], "api logTail leaked")
        check(body["recentBuilds"] and body["recentBuilds"][0]["status"] == "failed", "api recentBuilds")
        import urllib.error
        build_id = body["recentBuilds"][0]["id"]
        with urllib.request.urlopen(f"http://127.0.0.1:{port}/api/builds/{build_id}/log", timeout=5) as response:
            log_type = response.headers.get("Content-Type", "")
            log_body = response.read().decode("utf-8")
        check(log_type.startswith("text/plain"), f"log content type {log_type}")
        check("smoke: first line" in log_body and "supersecretvalue" not in log_body, "build log endpoint")
        try:
            urllib.request.urlopen(f"http://127.0.0.1:{port}/api/builds/not-a-real-build/log", timeout=5)
            check(False, "unknown build log should 404")
        except urllib.error.HTTPError as exc:
            check(exc.code == 404, f"unknown build log returned {exc.code}")

        import urllib.error

        with urllib.request.urlopen(f"http://127.0.0.1:{port}/api/desktop", timeout=5) as response:
            desktop_body = json.loads(response.read().decode("utf-8"))
        check(desktop_body["status"] == "idle", "missing desktop session should be idle")
        check(desktop_body["remainingLabel"] == "none", "missing desktop remaining")
        check(desktop_body["remainingSeconds"] is None, "missing desktop remaining seconds")
        check(
            desktop_body["branch"] == "none"
            and desktop_body["commit"] == "none"
            and desktop_body["releaseNotes"] == "none",
            "missing desktop identity",
        )
        check(desktop_body["logTail"] == "" and desktop_body["recentBuilds"] == [], "desktop log fields")
        write_json(
            directory / "desktop-session.json",
            {
                "status": "building",
                "percent": 42.5,
                "remainingLabel": "3m 02s",
                "remainingSeconds": 182,
                "stage": "package",
                "branch": "Dev",
                "commit": "abc123def",
                "releaseNotes": "desktop notes",
                "error": None,
            },
        )
        with urllib.request.urlopen(f"http://127.0.0.1:{port}/api/desktop", timeout=5) as response:
            building = json.loads(response.read().decode("utf-8"))
        check(building["status"] == "building", "desktop fixture status")
        check(building["percent"] == 42.5, f"desktop fixture percent {building.get('percent')}")
        check(building["remainingLabel"] == "3m 02s", "desktop fixture remaining")
        check(building["branch"] == "Dev" and building["commit"] == "abc123def", "desktop fixture identity")
        check(building["releaseNotes"] == "desktop notes", "desktop fixture notes")
        write_json(
            directory / "desktop-session.json",
            {
                "status": "failed",
                "closed": True,
                "percent": 18,
                "frozenPercent": 18,
                "remainingLabel": "9m 00s",
                "remainingSeconds": 540,
                "stage": "package",
                "branch": "Dev",
                "commit": "abc123def",
                "releaseNotes": "desktop notes",
                "error": "disk full",
            },
        )
        with urllib.request.urlopen(f"http://127.0.0.1:{port}/api/desktop", timeout=5) as response:
            closed_desktop = json.loads(response.read().decode("utf-8"))
        check(closed_desktop["status"] == "idle", "closed desktop should read idle")
        check(closed_desktop["remainingLabel"] == "none", "closed desktop remaining label")
        check(closed_desktop["remainingSeconds"] is None, "closed desktop remaining seconds")
        check(closed_desktop["error"] is None, "closed desktop kept the error in the header")
        check(closed_desktop["percent"] == 0, "closed desktop kept a percent")
        check(closed_desktop["stage"] is None, "closed desktop kept a stage")
        (directory / "desktop-session.json").unlink()

        with urllib.request.urlopen(f"http://127.0.0.1:{port}/api/downloads", timeout=5) as response:
            empty_downloads = json.loads(response.read().decode("utf-8"))
        check(empty_downloads["ipa"] is None, "ipa download should start empty")
        check(empty_downloads["desktop"]["macos"] is None, "macos should start empty")
        check(empty_downloads["desktop"]["windows"] is None and empty_downloads["desktop"]["linux"] is None, "other desktops")

        sample_ipa = directory / "sample.ipa"
        with zipfile.ZipFile(sample_ipa, "w") as archive:
            archive.writestr("Payload/Hello.txt", "hi")
        bad_ipa = directory / "bad.ipa"
        bad_ipa.write_bytes(b"not a zip")
        check(
            command_record_download(
                argparse.Namespace(
                    status_dir=str(directory),
                    kind="ipa",
                    path=str(bad_ipa),
                    version="0.0.1",
                    commit="abc",
                )
            )
            == 2,
            "non-zip ipa was recorded",
        )
        check(
            command_record_download(
                argparse.Namespace(
                    status_dir=str(directory),
                    kind="ipa",
                    path=str(sample_ipa),
                    version="0.5.3",
                    commit="3" * 40,
                )
            )
            == 0,
            "zip ipa was not recorded",
        )
        sample_dmg = directory / "Nuvio-macOS-arm64-0.1.26-alpha.dmg"
        sample_dmg.write_bytes(b"dmg-bytes")
        check(
            command_record_download(
                argparse.Namespace(
                    status_dir=str(directory),
                    kind="macos",
                    path=str(sample_dmg),
                    version=None,
                    commit="d" * 40,
                )
            )
            == 0,
            "macos package was not recorded",
        )
        with urllib.request.urlopen(f"http://127.0.0.1:{port}/api/downloads", timeout=5) as response:
            listed = json.loads(response.read().decode("utf-8"))
        check(listed["ipa"]["filename"] == "sample.ipa", "ipa filename")
        check(listed["ipa"]["bytes"] == sample_ipa.stat().st_size, "ipa bytes")
        check(listed["ipa"]["commit"] == "3" * 40, "ipa commit")
        check(listed["ipa"]["version"] == "0.5.3", "ipa version")
        check("file" not in listed["ipa"], "download record leaked storage name")
        check(listed["desktop"]["macos"]["filename"] == sample_dmg.name, "macos filename")
        check(listed["desktop"]["macos"]["version"] == "0.1.26-alpha", "macos version inferred")
        check(listed["desktop"]["windows"] is None and listed["desktop"]["linux"] is None, "windows and linux stayed empty")
        check(listed["ipa"]["savedName"] == "Nuvio-iPhone-0.5.3-33333333.ipa", f"ipa saved name {listed['ipa'].get('savedName')}")
        check(listed["ipa"]["copyText"] == "Nuvio for iPhone 0.5.3 · 33333333", f"ipa copy text {listed['ipa'].get('copyText')}")
        check(listed["desktop"]["macos"]["savedName"] == "Nuvio-Mac-0.1.26-alpha-dddddddd.dmg", "macos saved name")
        check(listed["desktop"]["macos"]["copyText"] == "Nuvio for Mac 0.1.26-alpha · dddddddd", "macos copy text")
        check(listed.get("older") == [], "fresh catalog should have no older packages")
        check("ipa-debug" not in listed, "debug ipa is still a current download")
        check(published_failure_notice(directory, "f" * 40) == PACKAGE_NOTICE, "failed iphone package notice")
        check(published_failure_notice(directory, "3" * 40) is None, "notice attached to a different commit")
        extra = artifacts_directory(directory) / "nuvio-0.5.2-full-debug.ipa"
        with zipfile.ZipFile(extra, "w") as archive:
            archive.writestr(
                "Payload/Nuvio.app/Info.plist",
                plistlib.dumps({"CFBundleShortVersionString": "0.5.2", "CFBundleVersion": "134"}),
            )
        with urllib.request.urlopen(f"http://127.0.0.1:{port}/api/downloads", timeout=5) as response:
            aged = json.loads(response.read().decode("utf-8"))
        check(aged["ipa"]["filename"] == "sample.ipa", "older ipa replaced the current ipa")
        check(len(aged["older"]) == 1 and aged["older"][0]["filename"] == extra.name, f"older row {aged.get('older')}")
        check(aged["older"][0]["version"] == "0.5.2", "older version")
        check(aged["older"][0]["build"] == "134", "older build")
        check(aged["older"][0]["status"] == "older", "older status")
        with urllib.request.urlopen(f"http://127.0.0.1:{port}/download/ipa", timeout=5) as response:
            disposition = response.headers.get("Content-Disposition", "")
            downloaded = response.read()
        check(disposition.startswith("attachment;"), f"ipa disposition {disposition}")
        check('filename="Nuvio-iPhone-0.5.3-33333333.ipa"' in disposition, f"ipa filename header {disposition}")
        check(downloaded == sample_ipa.read_bytes(), "ipa download bytes mismatch")
        with urllib.request.urlopen(f"http://127.0.0.1:{port}/download/desktop/macos", timeout=5) as response:
            dmg_disposition = response.headers.get("Content-Disposition", "")
            dmg_bytes = response.read()
        check(dmg_disposition.startswith("attachment;"), f"dmg disposition {dmg_disposition}")
        check('filename="Nuvio-Mac-0.1.26-alpha-dddddddd.dmg"' in dmg_disposition, f"dmg filename header {dmg_disposition}")
        check(dmg_bytes == b"dmg-bytes", "dmg download bytes mismatch")
        with urllib.request.urlopen(
            f"http://127.0.0.1:{port}/download/older/{extra.name}",
            timeout=5,
        ) as response:
            older_bytes = response.read()
        check(older_bytes == extra.read_bytes(), "older ipa bytes mismatch")
        extra.unlink()
        for missing in ("/download/desktop/windows", "/download/desktop/linux"):
            try:
                urllib.request.urlopen(f"http://127.0.0.1:{port}{missing}", timeout=5)
                check(False, f"{missing} should 404")
            except urllib.error.HTTPError as exc:
                check(exc.code == 404, f"{missing} returned {exc.code}")
        (artifacts_directory(directory) / "sample.ipa").unlink()
        try:
            urllib.request.urlopen(f"http://127.0.0.1:{port}/download/ipa", timeout=5)
            check(False, "missing ipa file should 404")
        except urllib.error.HTTPError as exc:
            check(exc.code == 404, f"missing ipa file returned {exc.code}")
        with urllib.request.urlopen(f"http://127.0.0.1:{port}/api/downloads", timeout=5) as response:
            after_delete = json.loads(response.read().decode("utf-8"))
        check(after_delete["ipa"] is None, "missing ipa file should not stay a link")
        published = artifacts_directory(directory) / "desktop"
        published.mkdir(parents=True)
        (published / "windows.msi").write_bytes(b"msi")
        write_json(
            published / "manifest.json",
            {
                "macos": {
                    "filename": "macos.dmg",
                    "bytes": 9,
                    "sha256": listed["desktop"]["macos"]["sha256"],
                    "version": "0.1.26-alpha",
                    "commit": "unknown",
                },
                "windows": {
                    "filename": "windows.msi",
                    "bytes": 3,
                    "sha256": "abc",
                    "version": "0.1.26-alpha",
                    "commit": "eeeeeee",
                },
            },
        )
        (published / "macos.dmg").write_bytes(b"dmg-bytes")
        with urllib.request.urlopen(f"http://127.0.0.1:{port}/api/downloads", timeout=5) as response:
            merged = json.loads(response.read().decode("utf-8"))
        check(merged["desktop"]["macos"]["filename"] == sample_dmg.name, "same sha should keep the seeded dmg name")
        check(merged["desktop"]["windows"]["filename"] == "windows.msi", "published windows package missing")
        check(merged["desktop"]["linux"] is None, "linux stayed empty")
        with urllib.request.urlopen(f"http://127.0.0.1:{port}/download/desktop/windows", timeout=5) as response:
            windows_bytes = response.read()
            windows_disposition = response.headers.get("Content-Disposition", "")
        check(windows_bytes == b"msi", "windows download bytes")
        check('filename="windows.msi"' in windows_disposition, f"windows disposition {windows_disposition}")
        for offset in range(45):
            open_build_record(directory, {"branch": "b", "commit": "c" * 40}, now + offset)
        capped = load_builds(directory)
        check(len(capped) == 40, f"build index cap {len(capped)}")
        check(len(recent_builds(directory, "ipa")) == 8, "recent window is not 8")
        httpd.shutdown()

    if failures:
        for message in failures:
            print(f"FAIL {message}", file=sys.stderr)
        return 1
    print("ipa-status self-test passed")
    return 0


def command_log_follow(args: argparse.Namespace) -> int:
    if not valid_build_id(args.build_id):
        print("ipa-status: invalid build id", file=sys.stderr)
        return 2
    directory = status_directory(args.status_dir)
    fifo = Path(args.fifo)
    with fifo.open("r", encoding="utf-8", errors="replace", buffering=1) as handle:
        while True:
            line = handle.readline()
            if line == "":
                break
            if line.endswith("\n"):
                line = line[:-1]
            if line.endswith("\r"):
                line = line[:-1]
            try:
                append_log_line(directory, args.build_id, line)
            except Exception:
                continue
    return 0


def send_bytes(handler: BaseHTTPRequestHandler, code: int, content_type: str, body: bytes) -> None:
    handler.send_response(code)
    handler.send_header("Content-Type", content_type)
    handler.send_header("Content-Length", str(len(body)))
    handler.send_header("Cache-Control", "no-store")
    handler.end_headers()
    handler.wfile.write(body)


def send_json(handler: BaseHTTPRequestHandler, payload: dict) -> None:
    body = (json.dumps(payload, sort_keys=True) + "\n").encode("utf-8")
    send_bytes(handler, 200, "application/json; charset=utf-8", body)


def send_build_log(handler: BaseHTTPRequestHandler, directory: Path, build_id: str) -> None:
    with locked(directory, exclusive=False):
        if indexed_build(directory, build_id) is None:
            send_bytes(handler, 404, "text/plain; charset=utf-8", b"not found\n")
            return
        path = log_file(directory, build_id)
    try:
        body = path.read_bytes()
    except OSError:
        body = b""
    send_bytes(handler, 200, "text/plain; charset=utf-8", body)


def send_file(handler: BaseHTTPRequestHandler, path: Path, filename: str) -> None:
    size = path.stat().st_size
    handler.send_response(200)
    handler.send_header("Content-Type", "application/octet-stream")
    handler.send_header("Content-Length", str(size))
    handler.send_header("Content-Disposition", content_disposition(filename))
    handler.send_header("Cache-Control", "no-store")
    handler.end_headers()
    with path.open("rb") as handle:
        while True:
            chunk = handle.read(1024 * 1024)
            if not chunk:
                break
            try:
                handler.wfile.write(chunk)
            except (BrokenPipeError, ConnectionResetError):
                return


def older_file(directory: Path, name: str) -> Path | None:
    if not re.fullmatch(r"[A-Za-z0-9._-]+", name):
        return None
    if Path(name).suffix.lower() not in {".ipa", ".dmg"}:
        return None
    root = artifacts_directory(directory).resolve()
    if not root.is_dir():
        return None
    matches: list[Path] = []
    for candidate in root.rglob(name):
        if not candidate.is_file() or candidate.name != name:
            continue
        resolved = candidate.resolve()
        if resolved != root and root not in resolved.parents:
            continue
        matches.append(resolved)
    if len(matches) != 1:
        return None
    return matches[0]


def send_download(handler: BaseHTTPRequestHandler, directory: Path, kind: str) -> None:
    entry = catalog_entry(directory, kind)
    path = artifact_file(directory, entry)
    if entry is None or path is None:
        send_bytes(handler, 404, "text/plain; charset=utf-8", b"not found\n")
        return
    if kind in ("ipa", "macos"):
        named = public_named_entry(directory, entry, kind)
        filename = str((named or {}).get("savedName") or entry.get("filename") or path.name)
    else:
        filename = str(entry.get("filename") or path.name)
    send_file(handler, path, filename)


def send_older(handler: BaseHTTPRequestHandler, directory: Path, name: str) -> None:
    path = older_file(directory, name)
    if path is None:
        send_bytes(handler, 404, "text/plain; charset=utf-8", b"not found\n")
        return
    send_file(handler, path, path.name)


def _handler_for(directory: Path):
    class Handler(BaseHTTPRequestHandler):
        def do_GET(self) -> None:  # noqa: N802
            path = urlparse(self.path).path
            if path in ("/", "/index.html"):
                send_bytes(self, 200, "text/html; charset=utf-8", PAGE.encode("utf-8"))
                return
            if path == "/api/status":
                payload = current_public(directory, time.time())
                payload["servedAt"] = iso(time.time())
                send_json(self, payload)
                return
            if path == "/api/desktop":
                payload = desktop_public(directory, time.time())
                payload["servedAt"] = iso(time.time())
                send_json(self, payload)
                return
            if path == "/api/downloads":
                send_json(self, current_downloads(directory))
                return
            kind = DOWNLOAD_ROUTES.get(path)
            if kind:
                send_download(self, directory, kind)
                return
            older_match = OLDER_DOWNLOAD_ROUTE.fullmatch(path)
            if older_match:
                send_older(self, directory, older_match.group(1))
                return
            match = BUILD_LOG_ROUTE.fullmatch(path)
            if match:
                send_build_log(self, directory, match.group(1))
                return
            send_bytes(self, 404, "text/plain; charset=utf-8", b"not found\n")

        def log_message(self, fmt: str, *log_args) -> None:
            return

    return Handler


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Live IPA build status")
    parser.add_argument("--status-dir", default=None)
    sub = parser.add_subparsers(dest="command", required=True)

    start = sub.add_parser("start")
    start.add_argument("--branch", required=True)
    start.add_argument("--commit", required=True)
    start.add_argument("--notes-file", required=True)
    start.add_argument("--notes-error-file", default=None)
    start.add_argument("--parent-pid", required=True)
    start.set_defaults(func=command_start)

    stage = sub.add_parser("stage")
    stage.add_argument("--name", required=True)
    stage.set_defaults(func=command_stage)

    finish = sub.add_parser("finish")
    finish.add_argument("--status", required=True, choices=("succeeded", "failed"))
    finish.add_argument("--message", default=None)
    finish.set_defaults(func=command_finish)

    record = sub.add_parser("record-download")
    record.add_argument("--kind", required=True, choices=("ipa", "macos", "windows", "linux"))
    record.add_argument("--path", required=True)
    record.add_argument("--version", default=None)
    record.add_argument("--commit", default=None)
    record.set_defaults(func=command_record_download)

    active = sub.add_parser("active")
    active.set_defaults(func=command_active)

    show = sub.add_parser("show")
    show.set_defaults(func=command_show)

    follow = sub.add_parser("log-follow")
    follow.add_argument("--build-id", required=True)
    follow.add_argument("--fifo", required=True)
    follow.set_defaults(func=command_log_follow)

    heartbeat = sub.add_parser("heartbeat")
    heartbeat.add_argument("--parent-pid", required=True)
    heartbeat.add_argument("--foreground", action="store_true")
    heartbeat.set_defaults(func=command_heartbeat)

    serve = sub.add_parser("serve")
    serve.add_argument("--host", default="0.0.0.0")
    serve.add_argument("--port", type=int, default=8765)
    serve.set_defaults(func=command_serve)

    test = sub.add_parser("self-test")
    test.set_defaults(func=lambda _args: self_test())
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    return int(args.func(args))


if __name__ == "__main__":
    sys.exit(main())
