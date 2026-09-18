// 内联 SVG 图标（Lucide 线稿路径），避免引入图标库依赖；用法 <Icon className="size-4" />
import type { SVGProps } from "react";

type P = SVGProps<SVGSVGElement>;

function svgProps(props: P): P {
  return {
    xmlns: "http://www.w3.org/2000/svg", width: 24, height: 24, viewBox: "0 0 24 24",
    fill: "none", stroke: "currentColor", strokeWidth: 2, strokeLinecap: "round", strokeLinejoin: "round",
    "aria-hidden": true, ...props,
  };
}

export const IconSparkles = (p: P) => (
  <svg {...svgProps(p)}>
    <path d="M9.937 15.5A2 2 0 0 0 8.5 14.063l-6.135-1.582a.5.5 0 0 1 0-.962L8.5 9.936A2 2 0 0 0 9.937 8.5l1.582-6.135a.5.5 0 0 1 .963 0L14.063 8.5A2 2 0 0 0 15.5 9.937l6.135 1.581a.5.5 0 0 1 0 .964L15.5 14.063a2 2 0 0 0-1.437 1.437l-1.582 6.135a.5.5 0 0 1-.963 0z" />
    <path d="M20 3v4" /><path d="M22 5h-4" />
  </svg>
);

export const IconPaperclip = (p: P) => (
  <svg {...svgProps(p)}>
    <path d="M21.44 11.05l-9.19 9.19a6 6 0 0 1-8.49-8.49l9.19-9.19a4 4 0 0 1 5.66 5.66l-9.2 9.19a2 2 0 0 1-2.83-2.83l8.49-8.48" />
  </svg>
);

export const IconArrowUp = (p: P) => (
  <svg {...svgProps(p)}>
    <path d="M12 19V5" /><path d="m5 12 7-7 7 7" />
  </svg>
);

export const IconLoader = (p: P) => (
  <svg {...svgProps(p)}>
    <path d="M21 12a9 9 0 1 1-6.219-8.56" />
  </svg>
);

export const IconCheck = (p: P) => (
  <svg {...svgProps(p)}>
    <path d="M20 6 9 17l-5-5" />
  </svg>
);

export const IconHistory = (p: P) => (
  <svg {...svgProps(p)}>
    <path d="M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8" /><path d="M3 3v5h5" /><path d="M12 7v5l4 2" />
  </svg>
);

export const IconPlus = (p: P) => (
  <svg {...svgProps(p)}>
    <path d="M5 12h14" /><path d="M12 5v14" />
  </svg>
);

export const IconX = (p: P) => (
  <svg {...svgProps(p)}>
    <path d="M18 6 6 18" /><path d="m6 6 12 12" />
  </svg>
);

export const IconFileText = (p: P) => (
  <svg {...svgProps(p)}>
    <path d="M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7Z" />
    <path d="M14 2v4a2 2 0 0 0 2 2h4" /><path d="M10 9H8" /><path d="M16 13H8" /><path d="M16 17H8" />
  </svg>
);

export const IconImage = (p: P) => (
  <svg {...svgProps(p)}>
    <rect width="18" height="18" x="3" y="3" rx="2" ry="2" /><circle cx="9" cy="9" r="2" />
    <path d="m21 15-3.086-3.086a2 2 0 0 0-2.828 0L6 21" />
  </svg>
);

export const IconDownload = (p: P) => (
  <svg {...svgProps(p)}>
    <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" /><path d="M7 10l5 5 5-5" /><path d="M12 15V3" />
  </svg>
);

export const IconChevronDown = (p: P) => (
  <svg {...svgProps(p)}>
    <path d="m6 9 6 6 6-6" />
  </svg>
);

export const IconMinus = (p: P) => (
  <svg {...svgProps(p)}>
    <path d="M5 12h14" />
  </svg>
);

export const IconShieldCheck = (p: P) => (
  <svg {...svgProps(p)}>
    <path d="M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1z" />
    <path d="m9 12 2 2 4-4" />
  </svg>
);

export const IconAlert = (p: P) => (
  <svg {...svgProps(p)}>
    <path d="m21.73 18-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3" />
    <path d="M12 9v4" /><path d="M12 17h.01" />
  </svg>
);
