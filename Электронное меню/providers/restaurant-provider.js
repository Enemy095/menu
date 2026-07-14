"use strict";

/**
 * RestaurantProvider interface definition.
 *
 * Every data source (JSON files, Go backend API, mock) must implement this contract.
 * The engine never imports data directly — it always goes through a provider.
 *
 * Aligned with backend OpenAPI: methods map to /api/v1/public/{slug}/* endpoints.
 */

/**
 * @typedef {Object} RestaurantConfig
 * @property {string} slug                - URL-safe unique identifier (matches business.slug in DB)
 * @property {string} theme               - Theme name reference (not inline theme data)
 * @property {string} submitMode          - "whatsapp" | "backend" | "mock"
 * @property {string} managerPhone        - WhatsApp recipient phone (digits only)
 * @property {string} apiBaseUrl          - Backend API base URL (empty = no backend)
 * @property {string[]} allowedImageOrigins - Allowed HTTPS origins for external images
 * @property {number} clientMenuVersion   - Client-side menu version for backend sync
 * @property {BrandConfig} brand          - Display name, logo, slogan, meta, UI text
 * @property {ProfileConfig} profile      - Contact info, hours, location, social links
 * @property {FeatureFlags} features      - Boolean feature flags (mirrors business_features table)
 * @property {OrderTypeConfig[]} orderTypes - Available order types for this restaurant
 * @property {LimitConfig} limits         - Input/quantity limits
 */

/**
 * @typedef {Object} BrandConfig
 * @property {string} displayName
 * @property {string} shortName
 * @property {string} businessTypeLabel
 * @property {string} cuisineText
 * @property {string} slogan
 * @property {string} introText
 * @property {string} pageTitle
 * @property {string} metaDescription
 * @property {string} logoUrl
 * @property {string} logoAlt
 * @property {BrandSection} menuSection
 * @property {BrandTexts} cartTexts
 * @property {BrandTexts} orderTexts
 * @property {BrandTexts} formTexts
 */

/**
 * @typedef {Object} ProfileConfig
 * @property {string} phone
 * @property {string} publicPhoneLabel
 * @property {string} address
 * @property {string} workingHoursText
 * @property {{ latitude: number, longitude: number, label: string }} location
 * @property {{ type: string, label: string, url: string }[]} socialLinks
 */

/**
 * @typedef {Object} FeatureFlags
 * Mirrors the business_features PostgreSQL table exactly.
 * @property {boolean} orders              - Enable order placement
 * @property {boolean} contacts            - Show contact section
 * @property {boolean} geoLocation         - Show map link
 * @property {boolean} socialLinks         - Show social media links
 * @property {boolean} delivery            - Enable delivery order type
 * @property {boolean} pickup              - Enable pickup order type
 * @property {boolean} preorder            - Enable preorder order type
 * @property {boolean} customTheme         - Apply restaurant theme
 * @property {boolean} whatsappFallback    - Allow WhatsApp as fallback
 * @property {boolean} promotions          - Enable promotions display
 * @property {boolean} loyalty             - Enable loyalty program
 * @property {boolean} reservations        - Enable reservation system
 */

/**
 * @typedef {Object} OrderTypeConfig
 * @property {string} code       - API code: "preorder" | "delivery" | "pickup"
 * @property {string} label      - Display label
 * @property {string} legacyValue - Legacy UI value for migration
 * @property {string} feature    - Feature flag that controls this type
 * @property {boolean} [default] - Whether this is the default selection
 */

/**
 * @typedef {Object} LimitConfig
 * @property {number} maxItemQuantity
 * @property {number} maxTotalItems
 * @property {number} maxNameLength
 * @property {number} maxPhoneLength
 * @property {number} maxAddressLength
 * @property {number} maxCommentLength
 * @property {number} maxFinalMessageLength
 * @property {number} delayedOrderConfirmationMinutes
 */

/**
 * @typedef {Object} MenuItem
 * @property {string} id          - Unique string identifier
 * @property {string} category    - Category name
 * @property {string} name        - Display name
 * @property {string} description - Short description
 * @property {number} price       - Price in base currency units
 * @property {string} image       - Image path or URL (empty string = use fallback)
 */

/**
 * @typedef {Object} MenuVersion
 * Aligned with the menu_versions PostgreSQL table.
 * @property {number} version     - Monotonically increasing version number
 * @property {string|null} publishedAt - ISO 8601 timestamp of publication
 * @property {string[]} categories - Ordered list of unique category names
 * @property {MenuItem[]} items   - Full menu item list
 */

/**
 * Abstract base — concrete providers extend or implement this shape.
 * Not a class (vanilla JS) — the contract is documented here and enforced by convention.
 *
 * @typedef {Object} RestaurantProvider
 * @property {string} slug
 * @property {() => Promise<RestaurantConfig>} getConfig
 * @property {() => Promise<MenuVersion>} getMenu
 * @property {() => Promise<boolean>} isAvailable
 */

if (typeof window !== "undefined") {
  window.RestaurantProviderTypes = Object.freeze({
    /** @returns {RestaurantConfig} */
    createDefaultConfig: () => Object.freeze({
      slug: "",
      theme: "stambul",
      submitMode: "whatsapp",
      managerPhone: "",
      apiBaseUrl: "",
      allowedImageOrigins: Object.freeze([]),
      clientMenuVersion: 1,
      brand: Object.freeze({
        displayName: "", shortName: "", businessTypeLabel: "", cuisineText: "",
        slogan: "", introText: "", pageTitle: "", metaDescription: "",
        logoUrl: "", logoAlt: "",
        menuSection: Object.freeze({ eyebrow: "", title: "" }),
        cartTexts: Object.freeze({ cartBarLabel: "", drawerEyebrow: "", title: "", clearButton: "", closeLabel: "" }),
        orderTexts: Object.freeze({ typeLegend: "", submitWhatsapp: "", submitBackend: "", submitMock: "", noteWhatsapp: "", noteBackend: "", noteMock: "" }),
        formTexts: Object.freeze({ phoneLabel: "", phonePlaceholder: "", phoneHint: "", phoneError: "", nameLabel: "", namePlaceholder: "", guestCountLabel: "", optionalLabel: "", guestCountPlaceholder: "", addressLabel: "", addressPlaceholder: "", addressError: "", commentLabel: "", commentPlaceholder: "" })
      }),
      profile: Object.freeze({
        phone: "", publicPhoneLabel: "", address: "", workingHoursText: "",
        location: Object.freeze({ latitude: 0, longitude: 0, label: "" }),
        socialLinks: Object.freeze([])
      }),
      features: Object.freeze({
        orders: true, contacts: true, geoLocation: false, socialLinks: false,
        delivery: false, pickup: false, preorder: true, customTheme: false,
        whatsappFallback: true, promotions: false, loyalty: false, reservations: false
      }),
      orderTypes: Object.freeze([]),
      limits: Object.freeze({
        maxItemQuantity: 99, maxTotalItems: 200, maxNameLength: 80,
        maxPhoneLength: 32, maxAddressLength: 240, maxCommentLength: 500,
        maxFinalMessageLength: 3500, delayedOrderConfirmationMinutes: 15, clientMenuVersion: 1
      })
    }),
    /** @returns {MenuVersion} */
    createEmptyMenu: () => Object.freeze({
      version: 0,
      publishedAt: null,
      categories: Object.freeze([]),
      items: Object.freeze([])
    })
  });
}
