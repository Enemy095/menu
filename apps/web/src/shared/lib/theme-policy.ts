import type { DesignTokens } from "@e-menu/api-contract/generated";

export const SAFE_FONT_FAMILY =
  /^[A-Za-z_-][A-Za-z0-9 _-]*(?:,\s*[A-Za-z_-][A-Za-z0-9 _-]*)*$/u;

const MINIMUM_TEXT_CONTRAST = 4.5;

type ThemeColor = keyof Pick<
  DesignTokens,
  | "primaryColor"
  | "secondaryColor"
  | "accentColor"
  | "backgroundColor"
  | "surfaceColor"
  | "textColor"
  | "mutedTextColor"
>;

const REQUIRED_CONTRAST_PAIRS: ReadonlyArray<readonly [ThemeColor, ThemeColor]> = [
  ["textColor", "backgroundColor"],
  ["textColor", "surfaceColor"],
  ["mutedTextColor", "backgroundColor"],
  ["mutedTextColor", "surfaceColor"],
  ["surfaceColor", "primaryColor"],
  ["surfaceColor", "secondaryColor"],
  ["primaryColor", "backgroundColor"],
  ["secondaryColor", "backgroundColor"],
];

export function renderedContactPalette(tokens: DesignTokens) {
  return {
    background: tokens.secondaryColor,
    text: tokens.surfaceColor,
    secondaryText: tokens.surfaceColor,
    accentText: tokens.surfaceColor,
  } as const;
}

function relativeLuminance(hex: string): number {
  const channel = (offset: number) => Number.parseInt(hex.slice(offset, offset + 2), 16) / 255;
  const linear = (value: number) =>
    value <= 0.04045 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4;
  const [red, green, blue] = [channel(1), channel(3), channel(5)].map((value) =>
    linear(value),
  );
  return 0.2126 * (red ?? 0) + 0.7152 * (green ?? 0) + 0.0722 * (blue ?? 0);
}

export function contrastRatio(foreground: string, background: string): number {
  const lighter = Math.max(relativeLuminance(foreground), relativeLuminance(background));
  const darker = Math.min(relativeLuminance(foreground), relativeLuminance(background));
  return (lighter + 0.05) / (darker + 0.05);
}

export function themeContrastIssues(tokens: DesignTokens): string[] {
  return REQUIRED_CONTRAST_PAIRS.flatMap(([foreground, background]) =>
    contrastRatio(tokens[foreground], tokens[background]) < MINIMUM_TEXT_CONTRAST
      ? [`${foreground} must have at least 4.5:1 contrast against ${background}`]
      : [],
  );
}
