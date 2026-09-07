// react-syntax-highlighter v16 未随包发布 .d.ts；以下子路径在项目内仅作为 JS 模块使用
declare module "react-syntax-highlighter/dist/esm/prism" {
  import { FC } from "react";
  const Prism: FC<{ language?: string; style?: any; PreTag?: string; children?: any; [k: string]: any }>;
  export default Prism;
}
declare module "react-syntax-highlighter/dist/esm/styles/prism" {
  const styles: Record<string, Record<string, React.CSSProperties>>;
  export const oneLight: Record<string, React.CSSProperties>;
  export default styles;
}