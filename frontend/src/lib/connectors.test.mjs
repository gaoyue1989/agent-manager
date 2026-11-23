import assert from "node:assert/strict";
import { test } from "node:test";
import { parseDirectReturn, parseSnapshotTools, parseToolPerms } from "./connectors.ts";

const cn = (directReturn) => ({ directReturn });

test("declared direct return tools are returned in declaration order", () => {
  assert.deepEqual(parseDirectReturn(cn('{"tools":["render_confirm_card","submit_order"]}')),
    ["render_confirm_card", "submit_order"]);
});

test("missing, null and legacy empty values mean no direct return tools", () => {
  assert.deepEqual(parseDirectReturn(cn('{"tools":[]}')), []);
  assert.deepEqual(parseDirectReturn(cn("{}")), []);
  assert.deepEqual(parseDirectReturn(cn("")), []);
  assert.deepEqual(parseDirectReturn({}), []);
  assert.deepEqual(parseDirectReturn(cn("not json")), []);
});

test("malformed shapes degrade to no direct return tools", () => {
  assert.deepEqual(parseDirectReturn(cn('{"tools":"render_confirm_card"}')), []);
  assert.deepEqual(parseDirectReturn(cn('{"tools":{"a":true}}')), []);
  assert.deepEqual(parseDirectReturn(cn("[]")), []);
  assert.deepEqual(parseDirectReturn(cn('"{\\"tools\\":[\\"a\\"]}"')), []);
});

test("non-string and blank entries are dropped", () => {
  assert.deepEqual(parseDirectReturn(cn('{"tools":["a",1,null,"  ","b"]}')), ["a", "b"]);
});

test("direct return is independent of permissions and tool scope", () => {
  const row = {
    permissions: '{"tools":{"a":"ask"}}',
    toolScope: '{"mode":"subset","tools":["a","b"]}',
    directReturn: '{"tools":["b"]}',
  };
  assert.deepEqual(parseToolPerms(row), { a: "ask" });
  assert.deepEqual(parseSnapshotTools(row), ["a", "b"]);
  assert.deepEqual(parseDirectReturn(row), ["b"]);
});
