import { z } from "zod";

import { SAFE_FONT_FAMILY, themeContrastIssues } from "@/shared/lib/theme-policy";

const nonEmpty = (maximum: number) => z.string().min(1).max(maximum);
const optionalText = (maximum: number) => z.string().max(maximum).optional();
const httpUrl = z
  .string()
  .url()
  .refine((value) => /^https?:\/\//u.test(value), {
    message: "Only HTTP(S) URLs are supported",
  })
  .refine((value) => {
    const parsed = new URL(value);
    return !parsed.username && !parsed.password;
  }, "URL credentials are forbidden");
const locale = z.literal("ru-RU");
const currency = z.string().regex(/^[A-Z]{3}$/u);
const publicKey = z.string().min(1).max(120).regex(/^[a-z0-9]+(?:-[a-z0-9]+)*$/u);
const sortOrder = z.number().int().min(0);
const minorUnits = z.number().int().min(-1_000_000_000_000).max(1_000_000_000_000);

const designTokensSchema = z
  .strictObject({
    primaryColor: z.string().regex(/^#[0-9A-Fa-f]{6}$/u),
    secondaryColor: z.string().regex(/^#[0-9A-Fa-f]{6}$/u),
    accentColor: z.string().regex(/^#[0-9A-Fa-f]{6}$/u),
    backgroundColor: z.string().regex(/^#[0-9A-Fa-f]{6}$/u),
    surfaceColor: z.string().regex(/^#[0-9A-Fa-f]{6}$/u),
    textColor: z.string().regex(/^#[0-9A-Fa-f]{6}$/u),
    mutedTextColor: z.string().regex(/^#[0-9A-Fa-f]{6}$/u),
    borderRadius: z.string().regex(/^[0-9]+(?:\.[0-9]+)?(?:px|rem)$/u),
    fontFamily: nonEmpty(120).regex(SAFE_FONT_FAMILY),
  })
  .superRefine((tokens, context) => {
    for (const message of themeContrastIssues(tokens)) {
      context.addIssue({ code: "custom", message });
    }
  });

const localizedSiteTextSchema = z.strictObject({
  locale,
  displayName: nonEmpty(120),
  shortName: nonEmpty(80),
  businessTypeLabel: nonEmpty(80),
  cuisineText: z.string().max(160),
  slogan: z.string().max(200),
  introText: z.string().max(500),
  menuSectionTitle: nonEmpty(120),
  cartTitle: nonEmpty(120),
  orderTypeLegend: nonEmpty(160),
});

const heroSchema = z.strictObject({
  imageUrl: httpUrl.optional(),
  imageAlt: optionalText(160),
  eyebrow: optionalText(100),
  title: nonEmpty(160),
  subtitle: optionalText(240),
  ctaLabel: nonEmpty(80),
  benefits: z
    .array(z.strictObject({ icon: nonEmpty(16), text: nonEmpty(100) }))
    .max(6)
    .optional(),
});

const brandingSchema = z.strictObject({
  logoUrl: httpUrl,
  logoAlt: nonEmpty(160),
  faviconUrl: httpUrl.optional(),
  hero: heroSchema,
  designTokens: designTokensSchema,
  localizedTexts: z.array(localizedSiteTextSchema).min(1),
});

const profileSchema = z.strictObject({
  publicPhone: z.string().min(5).max(32),
  publicPhoneLabel: nonEmpty(80),
  publicEmail: z.string().email().max(254).optional(),
  address: optionalText(240),
  location: z
    .strictObject({
      latitude: z.number().min(-90).max(90),
      longitude: z.number().min(-180).max(180),
      label: nonEmpty(240),
    })
    .optional(),
  socialLinks: z
    .array(
      z.strictObject({
        type: z.enum(["website", "instagram", "telegram", "vk", "facebook", "other"]),
        label: nonEmpty(80),
        url: httpUrl,
      }),
    )
    .max(20),
});

const featuresSchema = z.strictObject({
  orders: z.boolean(),
  contacts: z.boolean(),
  geoLocation: z.boolean(),
  socialLinks: z.boolean(),
  delivery: z.boolean(),
  pickup: z.boolean(),
  preorder: z.boolean(),
  customTheme: z.boolean(),
  whatsappFallback: z.boolean(),
  promotions: z.boolean(),
  loyalty: z.boolean(),
  reservations: z.boolean(),
  deliveryBlocks: z.boolean(),
});

const scheduleSchema = z.strictObject({
  timezone: nonEmpty(100),
  displayText: nonEmpty(240),
  days: z
    .array(
      z.strictObject({
        dayOfWeek: z.enum([
          "monday",
          "tuesday",
          "wednesday",
          "thursday",
          "friday",
          "saturday",
          "sunday",
        ]),
        intervals: z
          .array(
            z.strictObject({
              opensAt: z.string().regex(/^(?:[01][0-9]|2[0-3]):[0-5][0-9]$/u),
              closesAt: z.string().regex(/^(?:[01][0-9]|2[0-3]):[0-5][0-9]$/u),
            }),
          )
          .max(8),
      }),
    )
    .length(7),
});

export const publicConfigurationSchema = z.strictObject({
  restaurantId: z.string().uuid(),
  branding: brandingSchema,
  seo: z.strictObject({
    title: nonEmpty(120),
    description: nonEmpty(320),
    canonicalUrl: httpUrl,
    openGraphImageUrl: httpUrl.optional(),
  }),
  profile: profileSchema,
  features: featuresSchema,
  orderTypes: z
    .array(
      z.strictObject({
        code: z.enum(["preorder", "delivery", "pickup"]),
        label: nonEmpty(80),
        isDefault: z.boolean(),
      }),
    )
    .min(1)
    .superRefine((orderTypes, context) => {
      const codes = new Set<string>();
      orderTypes.forEach((orderType, index) => {
        if (codes.has(orderType.code)) {
          context.addIssue({
            code: "custom",
            path: [index, "code"],
            message: "Order type codes must be unique",
          });
        }
        codes.add(orderType.code);
      });
    }),
  limits: z.strictObject({
    maxItemQuantity: z.number().int().min(1).max(99),
    maxTotalItems: z.number().int().min(1).max(200),
    maxNameLength: z.number().int().min(1).max(80),
    maxPhoneLength: z.number().int().min(5).max(32),
    maxAddressLength: z.number().int().min(1).max(240),
    maxCommentLength: z.number().int().min(1).max(500),
    delayedOrderConfirmationMinutes: z.number().int().min(0).max(1440),
  }),
  schedule: scheduleSchema,
  deliveryConfiguration: z.strictObject({
    enabled: z.boolean(),
    addressRequired: z.boolean(),
    feePolicy: z.literal("none"),
  }),
  locale,
  currency,
  timezone: nonEmpty(100),
});

const modifierOptionSchema = z.strictObject({
  optionId: publicKey,
  name: nonEmpty(120),
  priceDeltaMinor: minorUnits,
  available: z.boolean(),
  sortOrder,
});

const modifierGroupSchema = z
  .strictObject({
    modifierGroupId: publicKey,
    name: nonEmpty(120),
    minSelections: z.number().int().min(0).max(99),
    maxSelections: z.number().int().min(1).max(99),
    sortOrder,
    options: z.array(modifierOptionSchema).min(1).max(100),
  })
  .refine((value) => value.minSelections <= value.maxSelections, {
    message: "minSelections must not exceed maxSelections",
  });

export const menuResponseSchema = z.strictObject({
  restaurantId: z.string().uuid(),
  version: z.number().int().min(1),
  publishedAt: z.string().datetime({ offset: true }),
  currency,
  categories: z.array(
    z.strictObject({
      categoryId: publicKey,
      name: nonEmpty(120),
      description: optionalText(500),
      imageUrl: httpUrl.optional(),
      sortOrder,
    }),
  ),
  items: z.array(
    z.strictObject({
      menuItemId: publicKey,
      categoryId: publicKey,
      name: nonEmpty(160),
      description: optionalText(1000),
      priceMinor: minorUnits.min(0),
      imageUrl: httpUrl.optional(),
      available: z.boolean(),
      sortOrder,
      modifierGroups: z.array(modifierGroupSchema).max(20),
    }),
  ),
});
