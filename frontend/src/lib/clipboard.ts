// 剪贴板复制策略（issue #377）：平台入口为 HTTP 非安全上下文（http://…:8911）时
// navigator.clipboard 为 undefined，裸调 writeText 静默 no-op，复制根本没发生。
// 策略级联：安全上下文 async Clipboard API → execCommand 同步兜底（已废弃但仍是
// 非安全上下文唯一可行路径）→ 均不可用返回失败。依赖注入 navigator/document 便于 node:test 单测。

type MinimalDocument = {
  createElement(tag: string): HTMLTextAreaElement;
  execCommand(commandId: string): boolean;
  body: { appendChild(el: unknown): void; removeChild(el: unknown): void };
};

export type CopyVia = "async-clipboard" | "exec-command" | "unavailable";
export type CopyResult = { ok: boolean; via: CopyVia };

export function resolveCopyStrategy(
  nav: Partial<Navigator> | undefined,
  doc: MinimalDocument | undefined,
): CopyVia {
  if (typeof nav?.clipboard?.writeText === "function") return "async-clipboard";
  if (doc && typeof doc.createElement === "function" && typeof doc.execCommand === "function") return "exec-command";
  return "unavailable";
}

function execCommandCopy(text: string, doc: MinimalDocument): boolean {
  const ta = doc.createElement("textarea");
  ta.value = text;
  ta.setAttribute("readonly", "");
  ta.style.position = "fixed";
  ta.style.opacity = "0";
  doc.body.appendChild(ta);
  let ok = false;
  try {
    ta.select();
    ok = doc.execCommand("copy");
  } catch {
    ok = false;
  } finally {
    doc.body.removeChild(ta);
  }
  return ok;
}

export async function copyToClipboard(
  text: string,
  deps: { nav?: Partial<Navigator>; document?: MinimalDocument } = {},
): Promise<CopyResult> {
  const nav = deps.nav ?? (typeof navigator !== "undefined" ? navigator : undefined);
  const doc = deps.document ?? (typeof document !== "undefined" ? (document as unknown as MinimalDocument) : undefined);
  const strategy = resolveCopyStrategy(nav, doc);
  if (strategy === "unavailable") return { ok: false, via: "unavailable" };
  if (strategy === "async-clipboard") {
    try {
      await nav!.clipboard!.writeText(text);
      return { ok: true, via: "async-clipboard" };
    } catch {
      // 权限拒绝/焦点丢失等场景回落 execCommand（有 document 才可能走到这）
      if (!doc || !execCommandCopy(text, doc)) return { ok: false, via: "async-clipboard" };
      return { ok: true, via: "exec-command" };
    }
  }
  return { ok: execCommandCopy(text, doc!), via: "exec-command" };
}
