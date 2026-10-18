import { redirect } from "next/navigation";

export default async function PackageRedirect({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  redirect(`/admin/packages/${id}`);
}
