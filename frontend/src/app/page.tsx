import { redirect } from "next/navigation";

// 存量 URL 重定向（M0 §3.1：现有 8 页整体迁入 (admin)/admin/*，旧路径 307 保 e2e 不感知）
export default function Home() {
  redirect("/admin");
}
