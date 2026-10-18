import { redirect } from "next/navigation";

export default async function PackageEditRedirect({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  redirect(`/admin/packages/${id}/edit`);
}
