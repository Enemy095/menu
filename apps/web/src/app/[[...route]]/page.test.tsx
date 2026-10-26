import { render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { configurationFixture, menuFixture } from "@/test/fixtures";

const apiMocks = vi.hoisted(() => ({
  getPublicConfiguration: vi.fn(),
  getPublicPageData: vi.fn(),
}));

vi.mock("@/shared/api/public-api", () => {
  class PublicApiHttpError extends Error {
    constructor(
      public readonly status: number,
      public readonly resource: "config" | "menu",
    ) {
      super(`HTTP ${status}`);
    }
  }

  return { ...apiMocks, PublicApiHttpError };
});

import Page, { generateMetadata, generateStaticParams, revalidate } from "./page";

const rootParams = Promise.resolve({});

beforeEach(() => {
  apiMocks.getPublicConfiguration.mockResolvedValue(configurationFixture());
  apiMocks.getPublicPageData.mockResolvedValue({
    configuration: configurationFixture(),
    menu: menuFixture(),
  });
});

describe("public route", () => {
  it("uses on-demand ISR without build-time business-data paths", () => {
    expect(generateStaticParams()).toEqual([]);
    expect(revalidate).toBe(60);
  });

  it("server-renders the public composition", async () => {
    render(await Page({ params: rootParams }));

    expect(
      screen.getByRole("heading", { level: 1, name: /— меню$/u }),
    ).toBeInTheDocument();
  });

  it("does not turn a post-preflight API 503 into successful fallback content", async () => {
    apiMocks.getPublicPageData.mockRejectedValue(new Error("upstream unavailable"));

    await expect(Page({ params: rootParams })).rejects.toThrow("upstream unavailable");
  });

  it("builds canonical and Open Graph metadata from server configuration", async () => {
    const metadata = await generateMetadata({ params: rootParams });

    expect(metadata.alternates).toEqual({ canonical: "https://menu.example.test/" });
    expect(metadata.openGraph).toMatchObject({
      title: "Тестовое кафе — меню",
      url: "https://menu.example.test/",
    });
  });

  it("does not index an unavailable configuration fallback", async () => {
    apiMocks.getPublicConfiguration.mockRejectedValue(new Error("unavailable"));

    const metadata = await generateMetadata({ params: rootParams });

    expect(metadata.robots).toEqual({ index: false, follow: false });
  });
});
