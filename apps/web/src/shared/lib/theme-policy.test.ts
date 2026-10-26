import { describe, expect, it } from "vitest";

import { configurationFixture } from "@/test/fixtures";

import { contrastRatio, renderedContactPalette, themeContrastIssues } from "./theme-policy";
import { themeStyle } from "./theme-style";

describe("rendered theme policy", () => {
  it("uses opaque contact colors whose actual rendered text pairs meet WCAG contrast", () => {
    const tokens = configurationFixture().branding.designTokens;
    const palette = renderedContactPalette(tokens);

    expect(contrastRatio(palette.text, palette.background)).toBeGreaterThanOrEqual(4.5);
    expect(contrastRatio(palette.secondaryText, palette.background)).toBeGreaterThanOrEqual(4.5);
    expect(contrastRatio(palette.accentText, palette.background)).toBeGreaterThanOrEqual(4.5);
    expect(themeContrastIssues(tokens)).toEqual([]);
    expect(themeStyle(tokens)).toMatchObject({
      "--theme-contact-background": palette.background,
      "--theme-contact-text": palette.text,
      "--theme-contact-secondary-text": palette.secondaryText,
      "--theme-contact-accent-text": palette.accentText,
    });
  });
});
