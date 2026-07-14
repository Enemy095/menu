import { describe, expect, it } from "vitest";

import { rawConfiguration } from "@/test/fixtures";

import { publicConfigurationSchema } from "./public-api-schemas";

function configurationWith(overrides: Record<string, unknown>) {
  return { ...rawConfiguration, ...overrides };
}

describe("public configuration schema", () => {
  it("accepts the supported safe and readable theme", () => {
    expect(publicConfigurationSchema.safeParse(rawConfiguration).success).toBe(true);
  });

  it("rejects CSS declaration injection through fontFamily", () => {
    const branding = {
      ...rawConfiguration.branding,
      designTokens: {
        ...rawConfiguration.branding.designTokens,
        fontFamily: "Arial; background-image:url(https://attacker.invalid/x)",
      },
    };

    expect(publicConfigurationSchema.safeParse(configurationWith({ branding })).success).toBe(false);
  });

  it("rejects configured color combinations below WCAG text contrast", () => {
    const branding = {
      ...rawConfiguration.branding,
      designTokens: {
        ...rawConfiguration.branding.designTokens,
        textColor: "#FFFFFF",
        backgroundColor: "#FFFFFF",
      },
    };

    expect(publicConfigurationSchema.safeParse(configurationWith({ branding })).success).toBe(false);
  });

  it("rejects locales outside the explicitly supported ru-RU release", () => {
    expect(publicConfigurationSchema.safeParse(configurationWith({ locale: "en-US" })).success).toBe(false);
  });

  it("rejects duplicate order type codes", () => {
    const duplicate = { code: "pickup", label: "Самовывоз", isDefault: true };
    expect(
      publicConfigurationSchema.safeParse(
        configurationWith({ orderTypes: [duplicate, { ...duplicate, label: "Другой" }] }),
      ).success,
    ).toBe(false);
  });

  it.each([
    ["canonical URL", { seo: { ...rawConfiguration.seo, canonicalUrl: "https://user:pass@menu.test/" } }],
    [
      "asset URL",
      { branding: { ...rawConfiguration.branding, logoUrl: "https://user@cdn.test/assets/logo.png" } },
    ],
    [
      "social URL",
      {
        profile: {
          ...rawConfiguration.profile,
          socialLinks: [{ type: "website", label: "Сайт", url: "https://user:pass@example.test/" }],
        },
      },
    ],
  ])("rejects credentials in %s", (_label, override) => {
    expect(publicConfigurationSchema.safeParse(configurationWith(override)).success).toBe(false);
  });
});
