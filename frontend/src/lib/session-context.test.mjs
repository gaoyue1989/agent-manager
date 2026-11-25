import assert from "node:assert/strict";
import { test } from "node:test";
import { contextMessagesFromHistory, isWelcomeState } from "./session-context.ts";

const HEADLINE = {
  key: "headline",
  title: "今日头条",
  content: "第一条\n第二条",
  content_chars: 11,
  created_at: "2026-11-25T09:00:12",
  updated_at: "2026-11-25T11:00:40",
};

test("history 的 context[] 映射为会话顶部的上下文消息，顺序即注入顺序", () => {
  const msgs = contextMessagesFromHistory({ context: [HEADLINE, { key: "notice", content: "公告" }] });
  assert.deepEqual(msgs, [
    {
      role: "context",
      content: "第一条\n第二条",
      context: { key: "headline", title: "今日头条", createdAt: "2026-11-25T09:00:12", updatedAt: "2026-11-25T11:00:40" },
    },
    { role: "context", content: "公告", context: { key: "notice", title: "notice", createdAt: "", updatedAt: "" } },
  ]);
});

test("history 未返回 context（老后端）或字段不是数组时得到空列表", () => {
  assert.deepEqual(contextMessagesFromHistory({ messages: [] }), []);
  assert.deepEqual(contextMessagesFromHistory({ context: null }), []);
  assert.deepEqual(contextMessagesFromHistory({ context: "x" }), []);
  assert.deepEqual(contextMessagesFromHistory({}), []);
});

test("缺少资料名的条目被丢弃，不渲染无名卡片", () => {
  assert.deepEqual(contextMessagesFromHistory({ context: [{ title: "no key" }, null, "junk"] }), []);
});

test("欢迎态：系统提示与已注入上下文不算对话内容", () => {
  const system = { role: "system", content: "欢迎" };
  const context = contextMessagesFromHistory({ context: [HEADLINE] })[0];
  assert.equal(isWelcomeState([system]), true);
  assert.equal(isWelcomeState([context, system]), true);
  assert.equal(isWelcomeState([context, system, { role: "user", content: "hi" }]), false);
  assert.equal(isWelcomeState([context, { role: "assistant", content: "hi" }]), false);
  assert.equal(isWelcomeState([context, { role: "tool", content: "step" }]), false);
});
