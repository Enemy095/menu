import type { MenuResponse, PublicConfigurationResponse } from "@e-menu/api-contract/generated";

import { selectLocalizedText } from "@/entities/site/model/select-localized-text";
import { themeStyle } from "@/shared/lib/theme-style";
import { Contacts } from "@/widgets/contacts/ui/Contacts";
import { Hero } from "@/widgets/hero/ui/Hero";
import { MenuBrowser } from "@/widgets/menu-browser/ui/MenuBrowser";
import { SiteHeader } from "@/widgets/site-header/ui/SiteHeader";
import { CartExperience } from "@/widgets/cart/ui/CartExperience";

type PublicMenuPageProps = {
  configuration: PublicConfigurationResponse;
  menu: MenuResponse | null;
};

export function PublicMenuPage({ configuration, menu }: PublicMenuPageProps) {
  const text = selectLocalizedText(configuration.branding.localizedTexts, configuration.locale);
  const orderTypes = [...configuration.orderTypes];
  const orderingEnabled = Boolean(configuration.features.orders && menu && orderTypes.length);
  const menuBrowser = (
    <MenuBrowser
      title={text.menuSectionTitle}
      categories={menu?.categories ?? []}
      items={menu?.items ?? []}
      locale={configuration.locale}
      currency={configuration.currency}
      orderingEnabled={orderingEnabled}
      fallbackImageUrl={configuration.branding.logoUrl}
    />
  );

  const content = (
    <div className="content-column">
      <Hero hero={configuration.branding.hero} text={text} />
      {menuBrowser}
      <Contacts
        profile={configuration.profile}
        features={configuration.features}
        schedule={configuration.schedule}
      />
    </div>
  );

  return (
    <div className="site" style={themeStyle(configuration.branding.designTokens)}>
      {orderingEnabled && menu ? (
        <CartExperience
          header={(
            <SiteHeader
              branding={configuration.branding}
              text={text}
              profile={configuration.profile}
              features={configuration.features}
              showCartButton
            />
          )}
            catalog={{
              restaurantId: menu.restaurantId,
              menuVersion: menu.version,
              items: menu.items,
            }}
            limits={configuration.limits}
            orderTypes={orderTypes}
            locale={configuration.locale}
            currency={configuration.currency}
            title={text.cartTitle}
          >
            {content}
        </CartExperience>
      ) : (
        <>
          <SiteHeader
            branding={configuration.branding}
            text={text}
            profile={configuration.profile}
            features={configuration.features}
          />
          <main className="single-layout" id="main-content">{content}</main>
        </>
      )}
      <footer className="site-footer">
        <div className="shell">{text.displayName}</div>
      </footer>
    </div>
  );
}
