import assert from "node:assert/strict";
import { test } from "node:test";
import { copyToClipboard, resolveCopyStrategy } from "./clipboard.ts";

// navigator / document 测试替身：仅模拟复制路径触到的成员
function fakeNavigator({ writeText, reject } = {}) {
  const calls = [];
  const nav = {};
  if (writeText) {
    nav.clipboard = {
      writeText: async (t) => {
        calls.push(t);
        if (reject) throw new Error("denied");
      },
    };
  }
  return { nav, calls };
}

function fakeDocument(execResult = true) {
  const textareas = [];
  const body = {
    children: [],
    appendChild(el) { this.children.push(el); },
    removeChild(el) { this.children = this.children.filter((c) => c !== el); },
  };
  return {
    textareas, body,
    doc: {
      createElement() {
        const el = { value: "", style: {}, selected: false, attrs: [], setAttribute(k, v) { el.attrs.push([k, v]); }, select() { el.selected = true; } };
        textareas.push(el);
        return el;
      },
      execCommand(cmd) { body.lastCmd = cmd; return execResult; },
      body,
    },
  };
}

test("strategy: secure context with async Clipboard API resolves to async-clipboard", () => {
  const { nav } = fakeNavigator({ writeText: true });
  assert.equal(resolveCopyStrategy(nav, undefined), "async-clipboard");
  assert.equal(resolveCopyStrategy(nav, fakeDocument().doc), "async-clipboard");
});

test("strategy: insecure context (no clipboard) with document resolves to exec-command", () => {
  assert.equal(resolveCopyStrategy({}, fakeDocument().doc), "exec-command");
  assert.equal(resolveCopyStrategy(undefined, fakeDocument().doc), "exec-command");
});

test("strategy: neither API available resolves to unavailable", () => {
  assert.equal(resolveCopyStrategy({}, undefined), "unavailable");
  assert.equal(resolveCopyStrategy(undefined, undefined), "unavailable");
});

test("secure context copies via async Clipboard API", async () => {
  const { nav, calls } = fakeNavigator({ writeText: true });
  const r = await copyToClipboard("secret-key", { nav, document: undefined });
  assert.deepEqual(r, { ok: true, via: "async-clipboard" });
  assert.equal(calls[0], "secret-key");
});

test("async API rejection falls back to exec-command textarea path", async () => {
  const { nav } = fakeNavigator({ writeText: true, reject: true });
  const { doc, textareas, body } = fakeDocument(true);
  const r = await copyToClipboard("fallback-text", { nav, document: doc });
  assert.deepEqual(r, { ok: true, via: "exec-command" });
  assert.equal(textareas[0].value, "fallback-text");
  assert.equal(textareas[0].selected, true);
  assert.equal(body.lastCmd, "copy");
  assert.equal(body.children.length, 0);
});

test("insecure context copies via exec-command and cleans up the textarea", async () => {
  const { doc, textareas, body } = fakeDocument(true);
  const r = await copyToClipboard("mem-key", { nav: {}, document: doc });
  assert.deepEqual(r, { ok: true, via: "exec-command" });
  assert.equal(textareas[0].value, "mem-key");
  assert.deepEqual(textareas[0].attrs, [["readonly", ""]]);
  assert.equal(body.children.length, 0);
});

test("execCommand returning false reports failure and still cleans up", async () => {
  const { doc, body } = fakeDocument(false);
  const r = await copyToClipboard("x", { nav: {}, document: doc });
  assert.equal(r.ok, false);
  assert.equal(body.children.length, 0);
});

test("execCommand throwing reports failure and still cleans up", async () => {
  const { doc, body } = fakeDocument(true);
  doc.execCommand = () => { throw new Error("boom"); };
  const r = await copyToClipboard("x", { nav: {}, document: doc });
  assert.equal(r.ok, false);
  assert.equal(body.children.length, 0);
});

test("no strategy available reports failure", async () => {
  const r = await copyToClipboard("x", { nav: {}, document: undefined });
  assert.equal(r.ok, false);
  assert.equal(r.via, "unavailable");
});

test("empty deps (non-browser runtime) reports failure without throwing", async () => {
  const r = await copyToClipboard("x", { nav: undefined, document: undefined });
  assert.equal(r.ok, false);
});
