import { redirect } from "next/navigation";

export default function PackagesRedirect() {
  redirect("/admin/packages");
}
