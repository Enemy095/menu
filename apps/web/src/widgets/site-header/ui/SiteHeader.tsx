import type { Branding, LocalizedSiteText, PublicProfile, SiteFeatures } from "@e-menu/api-contract/generated";

type SiteHeaderProps = {
  branding: Branding;
  text: LocalizedSiteText;
  profile: PublicProfile;
  features: SiteFeatures;
};

export function SiteHeader({ branding, text, profile, features }: SiteHeaderProps) {
  const phoneHref = `tel:${profile.publicPhone.replace(/[^+\d]/gu, "")}`;

  return (
    <header className="site-header">
      <div className="shell site-header__inner">
        {/* The API exposes arbitrary immutable CDN origins, so next/image cannot use a static allowlist. */}
        {/* eslint-disable-next-line @next/next/no-img-element */}
        <img className="site-header__logo" src={branding.logoUrl} alt={branding.logoAlt} />
        <div className="site-header__identity">
          <span className="site-header__name">{text.shortName}</span>
          <span className="site-header__type">{text.businessTypeLabel}</span>
        </div>
        {features.contacts ? (
          <a className="site-header__phone" href={phoneHref}>
            <span>{profile.publicPhoneLabel}</span>
            <strong>{profile.publicPhone}</strong>
          </a>
        ) : null}
      </div>
    </header>
  );
}
