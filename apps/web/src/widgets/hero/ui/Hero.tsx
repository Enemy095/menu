import type { Hero as HeroData, LocalizedSiteText } from "@e-menu/api-contract/generated";

type HeroProps = {
  hero: HeroData;
  text: LocalizedSiteText;
};

function BenefitIcon({ icon }: { icon: string }) {
  if (icon === "clock") {
    return (
      <svg viewBox="0 0 24 24" aria-hidden="true">
        <circle cx="12" cy="12" r="8.5" />
        <path d="M12 7.5v5l3.2 1.8M9.3 2.8h5.4" />
      </svg>
    );
  }

  if (icon === "truck") {
    return (
      <svg viewBox="0 0 24 24" aria-hidden="true">
        <path d="M3.5 6.5h10v9h-10zM13.5 9h3.3l3.2 3.2v3.3h-6.5z" />
        <circle cx="7" cy="17.5" r="1.7" />
        <circle cx="17" cy="17.5" r="1.7" />
      </svg>
    );
  }

  return (
    <svg viewBox="0 0 24 24" aria-hidden="true">
      <path d="M12.2 2.5c1.2 4.2-2.5 5.3-2.5 8.1 0 1.2.7 2.1 1.6 2.7-.1-2.1 1.2-3.5 2.8-4.9.2 2.7 3.2 4.1 3.2 7.4a5.4 5.4 0 0 1-10.8 0c0-3.9 2.4-6.8 5.7-13.3Z" />
    </svg>
  );
}

export function Hero({ hero, text }: HeroProps) {
  const benefits = hero.benefits?.length
    ? hero.benefits
    : [
        { icon: "flame", text: "Горячая еда" },
        { icon: "clock", text: "От 40 минут" },
        { icon: "truck", text: "Доставка от 1000 ₽ бесплатно" },
      ];

  return (
    <section className="hero" aria-labelledby="hero-title">
      <div className="shell hero__grid">
        <div className="hero__content">
          <h1 id="hero-title">
            <span className="hero-title__primary">Вкусная еда</span>
            <span>Быстрая доставка</span>
          </h1>
          <p className="hero__intro">Пицца • Роллы • Бургеры • Гарниры</p>
          {benefits.length ? (
            <ul className="hero__benefits" aria-label={text.slogan || undefined}>
              {benefits.map((benefit) => (
                <li key={`${benefit.icon}-${benefit.text}`}>
                  <span className={`benefit-icon benefit-icon--${benefit.icon}`} aria-hidden="true">
                    <BenefitIcon icon={benefit.icon} />
                  </span>
                  <span>{benefit.text}</span>
                </li>
              ))}
            </ul>
          ) : null}
          <a className="button" href="#menu">
            {hero.ctaLabel}
          </a>
        </div>
        <div className="hero__media">
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img
            src={hero.imageUrl || "/assets/menu/hero-burger.webp"}
            alt={hero.imageAlt || "Бургер с картофелем фри"}
          />
        </div>
      </div>
    </section>
  );
}
