import assert from "node:assert/strict";
import { test } from "node:test";
import { VIKING_MEMORY_ROOT, vikingEntryName, vikingParentUri } from "./viking-uri.ts";

test("根常量为本人记忆空间根 uri", () => {
  assert.equal(VIKING_MEMORY_ROOT, "viking://~/memories");
});

test("父路径：普通子目录回到根", () => {
  assert.equal(vikingParentUri("viking://~/memories/entities"), VIKING_MEMORY_ROOT);
});

test("父路径：尾斜杠子目录回到父目录而非原地（issue #433）", () => {
  assert.equal(vikingParentUri("viking://~/memories/entities/"), VIKING_MEMORY_ROOT);
});

test("父路径：深层嵌套含尾斜杠逐级回退", () => {
  assert.equal(vikingParentUri("viking://~/memories/a/b/"), "viking://~/memories/a");
});

test("父路径：根目录回退自身", () => {
  assert.equal(vikingParentUri(VIKING_MEMORY_ROOT), VIKING_MEMORY_ROOT);
});

test("父路径：根目录带尾斜杠回退自身", () => {
  assert.equal(vikingParentUri("viking://~/memories/"), VIKING_MEMORY_ROOT);
});

test("条目名：普通 uri 取末段", () => {
  assert.equal(vikingEntryName("viking://~/memories/entities/profile.md"), "profile.md");
});

test("条目名：尾斜杠 uri 取最后一个非空段（issue #433）", () => {
  assert.equal(vikingEntryName("viking://~/memories/entities/"), "entities");
});

test("条目名：根目录取段名", () => {
  assert.equal(vikingEntryName(VIKING_MEMORY_ROOT), "memories");
});
