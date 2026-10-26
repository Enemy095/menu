import type { LocalizedSiteText } from "@e-menu/api-contract/generated";

export function selectLocalizedText(
  texts: LocalizedSiteText[],
  locale: string,
): LocalizedSiteText {
  const exact = texts.find((text) => text.locale === locale);
  if (exact) return exact;

  const language = locale.split("-")[0];
  const sameLanguage = texts.find((text) => text.locale.split("-")[0] === language);
  if (sameLanguage) return sameLanguage;

  const first = texts[0];
  if (!first) throw new Error("At least one localized site text is required");
  return first;
}
