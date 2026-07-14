import type { DesignTokens } from "@e-menu/api-contract/generated";
import type { CSSProperties } from "react";

import { renderedContactPalette } from "./theme-policy";

type ThemeProperties = CSSProperties & Record<`--theme-${string}`, string>;

export function themeStyle(tokens: DesignTokens): ThemeProperties {
  const contacts = renderedContactPalette(tokens);
  return {
    "--theme-primary": tokens.primaryColor,
    "--theme-secondary": tokens.secondaryColor,
    "--theme-accent": tokens.accentColor,
    "--theme-background": tokens.backgroundColor,
    "--theme-surface": tokens.surfaceColor,
    "--theme-text": tokens.textColor,
    "--theme-muted": tokens.mutedTextColor,
    "--theme-radius": tokens.borderRadius,
    "--theme-font": tokens.fontFamily,
    "--theme-contact-background": contacts.background,
    "--theme-contact-text": contacts.text,
    "--theme-contact-secondary-text": contacts.secondaryText,
    "--theme-contact-accent-text": contacts.accentText,
  };
}
