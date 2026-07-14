import type { Metadata } from "next";
import { notFound } from "next/navigation";

import { PublicMenuPage } from "@/app/_components/PublicMenuPage";
import {
  getPublicConfiguration,
  getPublicPageData,
  PublicApiHttpError,
  type PublicPageData,
} from "@/shared/api/public-api";

type RootRouteProps = {
  params: Promise<{ route?: string[] }>;
};

export const dynamic = "force-static";
export const dynamicParams = true;
export const revalidate = 60;

// Returning no build-time paths makes `/` an on-demand ISR page. The first real request
// renders it with runtime configuration; production builds never need live business data.
export function generateStaticParams(): Array<{ route: string[] }> {
  return [];
}

async function requireRoot(params: RootRouteProps["params"]): Promise<void> {
  const { route } = await params;
  if (route?.length) notFound();
}

export async function generateMetadata({ params }: RootRouteProps): Promise<Metadata> {
  await requireRoot(params);
  try {
    const configuration = await getPublicConfiguration();
    const image = configuration.seo.openGraphImageUrl;

    return {
      title: configuration.seo.title,
      description: configuration.seo.description,
      alternates: { canonical: configuration.seo.canonicalUrl },
      icons: configuration.branding.faviconUrl
        ? { icon: configuration.branding.faviconUrl }
        : undefined,
      openGraph: {
        type: "website",
        title: configuration.seo.title,
        description: configuration.seo.description,
        url: configuration.seo.canonicalUrl,
        images: image ? [{ url: image }] : undefined,
      },
      robots: { index: true, follow: true },
    };
  } catch {
    return {
      title: "Электронное меню временно недоступно",
      robots: { index: false, follow: false },
    };
  }
}

async function loadPage(): Promise<PublicPageData> {
  try {
    return await getPublicPageData();
  } catch (error) {
    if (error instanceof PublicApiHttpError && error.status === 404) notFound();
    // Proxy emits dependency outages as HTTP 503 before rendering. A race after that
    // preflight is intentionally an error response, never a misleading successful 200.
    throw error;
  }
}

export default async function Page({ params }: RootRouteProps) {
  await requireRoot(params);
  const data = await loadPage();
  return <PublicMenuPage configuration={data.configuration} menu={data.menu} />;
}
