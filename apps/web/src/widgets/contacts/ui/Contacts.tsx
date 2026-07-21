import type { PublicProfile, SiteFeatures, WeeklySchedule } from "@e-menu/api-contract/generated";

type ContactsProps = {
  profile: PublicProfile;
  features: SiteFeatures;
  schedule: WeeklySchedule;
};

export function Contacts({ profile, features, schedule }: ContactsProps) {
  if (!features.contacts) return null;

  const phoneHref = `tel:${profile.publicPhone.replace(/[^+\d]/gu, "")}`;
  const showLocation = features.geoLocation && profile.location;
  const showSocials = features.socialLinks && profile.socialLinks.length > 0;

  return (
    <section className="contacts" id="contacts" aria-labelledby="contacts-title">
      <div className="shell">
        <p className="eyebrow" id="contacts-title">Контакты</p>
        <div className="contacts__grid">
          <address className="contact-card">
            <span>{profile.publicPhoneLabel}</span>
            <a href={phoneHref}>{profile.publicPhone}</a>
            {profile.publicEmail ? <a href={`mailto:${profile.publicEmail}`}>{profile.publicEmail}</a> : null}
            {profile.address ? <p>{profile.address}</p> : null}
          </address>
          <div className="contact-card">
            <span>Режим работы</span>
            <strong>{schedule.displayText}</strong>
            <small>{schedule.timezone}</small>
          </div>
          {showLocation ? (
            <div className="contact-card">
              <span>Местоположение</span>
              <strong>{profile.location?.label}</strong>
            </div>
          ) : null}
        </div>
        {showSocials ? (
          <nav className="social-links" aria-label="Социальные сети">
            {profile.socialLinks.map((link) => (
              <a key={`${link.type}-${link.url}`} href={link.url} rel="noreferrer" target="_blank">
                {link.label}
              </a>
            ))}
          </nav>
        ) : null}
      </div>
    </section>
  );
}
