import assert from "node:assert/strict";
import { test } from "node:test";
import { isPermsDirty, parseDirectReturn, parseSnapshotTools, parseToolPerms } from "./connectors.ts";

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

test("the three parsers share one tolerant JSON entry point", () => {
  for (const raw of ["", "null", "42", '"text"', "[]", "{}", "not json", "{broken"]) {
    assert.deepEqual(parseToolPerms({ permissions: raw }), {}, `permissions ${raw}`);
    assert.deepEqual(parseSnapshotTools({ toolScope: raw }), [], `toolScope ${raw}`);
    assert.deepEqual(parseDirectReturn({ directReturn: raw }), [], `directReturn ${raw}`);
  }
  assert.deepEqual(parseToolPerms({}), {});
  assert.deepEqual(parseSnapshotTools({}), []);
});

test("unedited connector with undeclared snapshot tools is not dirty", () => {
  assert.equal(isPermsDirty({ a: "allow", b: "allow" }, {}, ["a", "b"], [], []), false);
});

test("undeclared tools normalize to allow, partial declarations stay clean", () => {
  assert.equal(isPermsDirty({ a: "ask", b: "allow", c: "allow" }, { a: "ask" }, ["a", "b", "c"], [], []), false);
});

test("a changed tool is dirty until reverted to its declared default", () => {
  assert.equal(isPermsDirty({ a: "deny", b: "allow" }, {}, ["a", "b"], [], []), true);
  assert.equal(isPermsDirty({ a: "deny", b: "allow" }, { a: "ask" }, ["a", "b"], [], []), true);
  assert.equal(isPermsDirty({ a: "allow", b: "allow" }, { a: "deny" }, ["a", "b"], [], []), true);
  assert.equal(isPermsDirty({ a: "allow", b: "allow" }, {}, ["a", "b"], [], []), false);
});

test("direct return set differences are dirty only when sets differ", () => {
  assert.equal(isPermsDirty({}, {}, [], ["a"], []), true);
  assert.equal(isPermsDirty({}, {}, [], [], ["a"]), true);
  assert.equal(isPermsDirty({}, {}, [], ["a", "b"], ["b", "a"]), false);
});
