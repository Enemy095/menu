import type { MenuCategory, MenuItem } from "@e-menu/api-contract/generated";

import { CategoryFilter } from "@/features/category-filter/ui/CategoryFilter";
import { formatMoney } from "@/shared/lib/format-money";

type MenuBrowserProps = {
  title: string;
  categories: MenuCategory[];
  items: MenuItem[];
  locale: string;
  currency: string;
};

export function MenuBrowser({ title, categories, items, locale, currency }: MenuBrowserProps) {
  const orderedCategories = [...categories].sort((left, right) => left.sortOrder - right.sortOrder);
  const orderedItems = [...items].sort((left, right) => left.sortOrder - right.sortOrder);
  const filterCategories = orderedCategories.map((category) => ({
    id: category.categoryId,
    name: category.name,
    itemCount: orderedItems.filter((item) => item.categoryId === category.categoryId).length,
  }));

  return (
    <section className="menu-section shell" id="menu" aria-labelledby="menu-title">
      <div className="section-heading">
        <p className="eyebrow">Меню</p>
        <h2 id="menu-title">{title}</h2>
      </div>
      {orderedItems.length ? (
        <CategoryFilter categories={filterCategories} totalItemCount={orderedItems.length}>
          <ul className="menu-grid">
            {orderedItems.map((item) => (
              <li className="menu-card" data-menu-category={item.categoryId} key={item.menuItemId}>
                {item.imageUrl ? (
                  // eslint-disable-next-line @next/next/no-img-element
                  <img className="menu-card__image" src={item.imageUrl} alt="" loading="lazy" />
                ) : null}
                <div className="menu-card__body">
                  <div className="menu-card__heading">
                    <h3>{item.name}</h3>
                    <strong>{formatMoney(item.priceMinor, currency, locale)}</strong>
                  </div>
                  {item.description ? <p>{item.description}</p> : null}
                  {!item.available ? <span className="availability">Временно недоступно</span> : null}
                </div>
              </li>
            ))}
          </ul>
        </CategoryFilter>
      ) : (
        <div className="empty-state" role="status">
          <h3>Меню пока не опубликовано</h3>
          <p>Актуальные позиции появятся здесь после публикации.</p>
        </div>
      )}
    </section>
  );
}
