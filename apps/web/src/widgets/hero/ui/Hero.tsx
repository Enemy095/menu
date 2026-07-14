import type { Hero as HeroData, LocalizedSiteText } from "@e-menu/api-contract/generated";

type HeroProps = {
  hero: HeroData;
  text: LocalizedSiteText;
};

export function Hero({ hero, text }: HeroProps) {
  return (
    <section className="hero" aria-labelledby="hero-title">
      <div className="shell hero__grid">
        <div className="hero__content">
          {hero.eyebrow ? <p className="eyebrow">{hero.eyebrow}</p> : null}
          <h1 id="hero-title">{hero.title}</h1>
          {hero.subtitle ? <p className="hero__subtitle">{hero.subtitle}</p> : null}
          {text.introText ? <p className="hero__intro">{text.introText}</p> : null}
          <a className="button" href="#menu">
            {hero.ctaLabel}
          </a>
          {hero.benefits?.length ? (
            <ul className="hero__benefits" aria-label={text.slogan || undefined}>
              {hero.benefits.map((benefit) => (
                <li key={`${benefit.icon}-${benefit.text}`}>
                  <span aria-hidden="true">{benefit.icon}</span>
                  {benefit.text}
                </li>
              ))}
            </ul>
          ) : null}
        </div>
        {hero.imageUrl ? (
          <div className="hero__media">
            {/* eslint-disable-next-line @next/next/no-img-element */}
            <img src={hero.imageUrl} alt={hero.imageAlt ?? ""} />
          </div>
        ) : null}
      </div>
    </section>
  );
}
