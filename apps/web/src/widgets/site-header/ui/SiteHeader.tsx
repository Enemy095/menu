import type { Branding, LocalizedSiteText, PublicProfile, SiteFeatures } from "@e-menu/api-contract/generated";

import { CartHeaderButton } from "@/features/cart/ui/CartHeaderButton";
import { SiteNavigation } from "./SiteNavigation";

type SiteHeaderProps = {
  branding: Branding;
  text: LocalizedSiteText;
  profile: PublicProfile;
  features: SiteFeatures;
  showCartButton?: boolean;
};

export function SiteHeader({ branding, text, profile, features, showCartButton = false }: SiteHeaderProps) {
  const phoneHref = `tel:${profile.publicPhone.replace(/[^+\d]/gu, "")}`;

  return (
    <header className="site-header">
      <div className="shell site-header__inner">
        {/* The API exposes arbitrary immutable CDN origins, so next/image cannot use a static allowlist. */}
        {/* eslint-disable-next-line @next/next/no-img-element */}
        <img
          className="site-header__logo"
          src="/assets/brand/delivery-mascot.png"
          alt={branding.logoAlt || "Доставщик ресторана с бургером"}
        />
        <div className="site-header__identity">
          <span className="site-header__type">{text.businessTypeLabel}</span>
          <span className="site-header__name">{text.shortName}</span>
        </div>
        <SiteNavigation />
        {features.contacts ? (
          <a className="site-header__phone" href={phoneHref}>
            <svg aria-hidden="true" viewBox="0 0 24 24">
              <path
                className="whatsapp-bubble"
                d="M12 2.5a9.5 9.5 0 0 0-8.1 14.46L2.7 21.3l4.46-1.17A9.5 9.5 0 1 0 12 2.5Z"
              />
              <path
                className="whatsapp-handset"
                d="M8.16 7.08c.2-.22.4-.24.6-.24h.43c.14 0 .32.05.4.3l.86 2.08c.08.2.05.39-.05.56l-.4.6c-.14.18-.3.36-.12.66.18.3.8 1.3 1.74 2.1 1.2 1.03 2.2 1.35 2.52 1.5.31.16.5.13.68-.08l.9-1.04c.2-.23.42-.18.7-.08l2.03.96c.3.14.5.21.57.33.07.12.07.7-.17 1.38-.24.68-1.4 1.3-1.93 1.38-.52.08-1.2.12-1.94-.12-.45-.15-1.03-.34-1.77-.66-3.12-1.35-5.15-4.5-5.3-4.7-.15-.2-1.27-1.68-1.27-3.2 0-1.53.8-2.28 1.08-2.58Z"
              />
            </svg>
            <span>{profile.publicPhoneLabel}</span>
            <strong>{profile.publicPhone}</strong>
          </a>
        ) : null}
        {showCartButton ? <CartHeaderButton /> : null}
      </div>
    </header>
  );
}
