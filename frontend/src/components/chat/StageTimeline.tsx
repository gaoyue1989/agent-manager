"use client";
import { useEffect, useState } from "react";
import { resolveTimelineHeadline } from "@/lib/stage-progress";
import { toolStatus } from "@/lib/assistant-events";
import type { ChatMsg } from "./types";
import { IconAlert, IconCheck, IconChevronDown, IconLoader, IconMinus, IconShieldCheck, IconX } from "./icons";

interface StageTimelineProps {
  stages: string[];
  turnSummary?: string;
  running: boolean;
  hasContent: boolean;
  tools?: ChatMsg[];
}

export default function StageTimeline({ stages, turnSummary, running, hasContent, tools = [] }: StageTimelineProps) {
  const [open, setOpen] = useState(running && !hasContent);
  const [userToggled, setUserToggled] = useState(false);
  const [showTools, setShowTools] = useState(false);

  useEffect(() => {
    if (userToggled) return;
    if (running && !hasContent) {
      setOpen(true);
    } else if (!running || hasContent) {
      setOpen(false);
    }
  }, [running, hasContent, userToggled]);

  const headline = resolveTimelineHeadline(stages, turnSummary);

  const toggle = () => {
    setUserToggled(true);
    setOpen((v) => !v);
  };

  return (
    <div className="mb-2" data-testid="stage-timeline">
      <button
        type="button"
        onClick={toggle}
        aria-expanded={open}
        data-testid="stage-timeline-toggle"
        className="group inline-flex max-w-full items-center gap-1.5 text-left text-[14px] font-medium leading-snug text-gray-600 transition hover:text-gray-900"
      >
        <span className={running && !hasContent ? "shimmer-text" : ""}>{headline}</span>
        <IconChevronDown
          className={`size-3.5 shrink-0 text-gray-400 transition-transform group-hover:text-gray-600 ${open ? "rotate-180" : ""}`}
        />
      </button>

      {open && (
        <div
          data-testid="stage-timeline-steps"
          className="ml-1 mt-2.5 border-l border-gray-200/90 pl-4 space-y-2.5"
        >
          {stages.map((step, idx) => {
            const active = running && !hasContent && idx === stages.length - 1;
            return (
              <div
                key={`${idx}-${step}`}
                data-testid="stage-step"
                className={`text-[13.5px] leading-relaxed ${active ? "shimmer-text font-medium" : "text-gray-600"}`}
              >
                {step}
              </div>
            );
          })}

          {tools.length > 0 && (
            <div className="pt-1">
              <button
                type="button"
                onClick={() => setShowTools((v) => !v)}
                className="inline-flex items-center gap-1 text-xs text-gray-400 transition hover:text-gray-600"
              >
                <span>{showTools ? "收起工具明细" : `${tools.length} 次工具调用`}</span>
                <IconChevronDown className={`size-3 transition-transform ${showTools ? "rotate-180" : ""}`} />
              </button>
              {showTools && (
                <div className="mt-2 space-y-1.5">
                  {tools.map((t, i) => {
                    const status = toolStatus(t);
                    return (
                      <div key={t.toolCallId ?? i} data-testid="tool-step" data-status={status} className="text-xs text-gray-500">
                        <div className="inline-flex items-center gap-1.5 font-mono">
                          {status === "running" ? (
                            <IconLoader className="size-3 shrink-0 animate-spin text-blue-500" />
                          ) : status === "awaiting" ? (
                            <IconShieldCheck className="size-3 shrink-0 text-amber-500" />
                          ) : status === "success" ? (
                            <IconCheck className="size-3 shrink-0 text-emerald-500" />
                          ) : status === "error" ? (
                            <IconX className="size-3 shrink-0 text-red-500" />
                          ) : status === "denied" || status === "interrupted" ? (
                            <IconAlert className="size-3 shrink-0 text-amber-500" />
                          ) : (
                            <IconMinus className="size-3 shrink-0 text-gray-400" />
                          )}
                          <span className="truncate">{t.content}</span>
                        </div>
                        {t.output && (
                          <pre className="chat-scroll mt-1 max-h-40 overflow-auto whitespace-pre-wrap break-words rounded bg-gray-50 p-2 font-mono text-[11px] text-gray-600">
                            {t.output}
                          </pre>
                        )}
                      </div>
                    );
                  })}
                </div>
              )}
            </div>
          )}
        </div>
      )}
    </div>
  );
}
