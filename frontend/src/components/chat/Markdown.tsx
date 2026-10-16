"use client";
// 助手消息 Markdown 渲染：GFM（表格/任务列表/删除线）+ rehype-sanitize 净化
// 围栏代码块走 Prism oneLight 高亮；内联代码/表格/引用块按平台视觉精修
import { memo } from "react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import rehypeSanitize from "rehype-sanitize";
import SyntaxHighlighter from "react-syntax-highlighter/dist/esm/prism";
import { oneLight } from "react-syntax-highlighter/dist/esm/styles/prism";

const Markdown = memo(function Markdown({ text }: { text: string }) {
  return (
    <ReactMarkdown
      remarkPlugins={[remarkGfm]}
      rehypePlugins={[rehypeSanitize]}
      components={{
        p: (props) => <p {...props} className="my-2 leading-relaxed first:mt-0 last:mb-0" />,
        code({ className, children, ...rest }) {
          const match = /language-(\w+)/.exec(className || "");
          const code = String(children).replace(/\n$/, "");
          if (match) {
            return (
              <SyntaxHighlighter language={match[1]} style={oneLight} PreTag="div"
                customStyle={{ margin: "8px 0", borderRadius: 10, fontSize: 12.5, border: "1px solid #e5e7eb" }}>
                {code}
              </SyntaxHighlighter>
            );
          }
          return <code className="rounded border border-blue-100 bg-blue-50 px-1.5 py-0.5 font-mono text-[12px] text-blue-700" {...rest}>{children}</code>;
        },
        a: (props) => <a {...props} target="_blank" rel="noreferrer" className="text-blue-600 underline decoration-blue-300 underline-offset-2 hover:text-blue-700" />,
        h1: (props) => <h1 {...props} className="mt-3 mb-1.5 text-base font-semibold tracking-tight" />,
        h2: (props) => <h2 {...props} className="mt-3 mb-1.5 text-base font-semibold tracking-tight" />,
        h3: (props) => <h3 {...props} className="mt-2 mb-1 text-sm font-semibold tracking-tight" />,
        ul: (props) => <ul {...props} className="my-1.5 ml-5 list-disc space-y-0.5 marker:text-blue-400" />,
        ol: (props) => <ol {...props} className="my-1.5 ml-5 list-decimal space-y-0.5 marker:text-blue-400" />,
        li: (props) => <li {...props} className="pl-0.5" />,
        blockquote: (props) => <blockquote {...props} className="my-2 rounded-r-md border-l-2 border-blue-300 bg-blue-50/50 py-0.5 pl-3 pr-2 text-gray-600" />,
        table: (props) => <table {...props} className="my-2 block max-w-full overflow-x-auto border-collapse rounded-lg border border-gray-200 text-xs" />,
        thead: (props) => <thead {...props} className="bg-gray-50" />,
        th: (props) => <th {...props} className="border-b border-gray-200 px-2.5 py-1.5 text-left font-medium text-gray-600" />,
        td: (props) => <td {...props} className="border-b border-gray-100 px-2.5 py-1.5 align-top" />,
        hr: (props) => <hr {...props} className="my-3 border-gray-200" />,
      }}
    >
      {text}
    </ReactMarkdown>
  );
});

export default Markdown;
