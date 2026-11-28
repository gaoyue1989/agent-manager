import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { appendStageStep, groupRenderItems, resolveTimelineHeadline, setTurnSummary } from "./stage-progress.ts";

describe("stage-progress", () => {
  it("appends deduplicated stage steps onto last assistant message", () => {
    const base = [
      { role: "user", content: "查服务" },
      { role: "assistant", content: "", pending: true },
    ];
    const s1 = appendStageStep(base, "正在查询平台服务列表");
    const s2 = appendStageStep(s1, "正在查询平台服务列表");
    const s3 = appendStageStep(s2, "正在核对默认配置");
    assert.deepEqual(s3[1].stages, ["正在查询平台服务列表", "正在核对默认配置"]);
  });

  it("sets turnSummary on last assistant message", () => {
    const base = [{ role: "assistant", content: "完成", stages: ["正在查询平台服务列表"] }];
    const next = setTurnSummary(base, "已查询平台已发布服务与默认配置");
    assert.equal(next[0].turnSummary, "已查询平台已发布服务与默认配置");
    assert.equal(resolveTimelineHeadline(next[0].stages, next[0].turnSummary), "已查询平台已发布服务与默认配置");
  });

  it("groups preceding tool messages into assistant when stages are present", () => {
    const msgs = [
      { role: "user", content: "查服务" },
      { role: "tool", content: "list_services" },
      { role: "tool", content: "get_platform_defaults" },
      { role: "assistant", content: "结果如下", stages: ["正在查询平台服务与默认配置"], turnSummary: "已查询平台服务与默认配置" },
    ];
    const grouped = groupRenderItems(msgs);
    assert.equal(grouped.length, 2);
    assert.equal(grouped[0].msg.role, "user");
    assert.equal(grouped[1].msg.role, "assistant");
    assert.equal(grouped[1].tools.length, 2);
  });

  it("keeps standalone tool steps when assistant has no stages", () => {
    const msgs = [
      { role: "user", content: "查服务" },
      { role: "tool", content: "list_services" },
      { role: "assistant", content: "结果如下" },
    ];
    const grouped = groupRenderItems(msgs);
    assert.equal(grouped.length, 3);
    assert.equal(grouped[1].msg.role, "tool");
  });
});
