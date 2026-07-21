"use client";

import type { MenuCategory, MenuItem } from "@e-menu/api-contract/generated";

import { CategoryFilter } from "@/features/category-filter/ui/CategoryFilter";

import { MenuItemCard } from "./MenuItemCard";

type MenuBrowserProps = {
  title: string;
  categories: MenuCategory[];
  items: MenuItem[];
  locale: string;
  currency: string;
  orderingEnabled?: boolean;
  fallbackImageUrl?: string;
};

const GENERATED_DISH_IMAGES: Readonly<Record<string, string>> = {
  "cezar-roll-chicken": "/assets/menu/cezar-roll-chicken.webp",
  "cezar-roll-shrimp": "/assets/menu/cezar-roll-shrimp.webp",
  "sake-maki": "/assets/menu/sake-maki.webp",
  "hot-roll-salmon": "/assets/menu/hot-roll-salmon.webp",
  "cheese-baked-roll": "/assets/menu/hot-roll-salmon.webp",
  "bonito-roll": "/assets/menu/cezar-roll-shrimp.webp",
  "philadelphia-chicken-roll": "/assets/menu/cezar-roll-chicken.webp",
  "philadelphia-roll": "/assets/menu/sake-maki.webp",
  "pizza-caesar": "/assets/menu/pizza-pepperoni.webp",
  "pizza-pepperoni": "/assets/menu/pizza-pepperoni.webp",
  "pizza-mushroom": "/assets/menu/pizza-pepperoni.webp",
  "pizza-cheese": "/assets/menu/pizza-pepperoni.webp",
  "pizza-julienne": "/assets/menu/pizza-pepperoni.webp",
  "pizza-assorti": "/assets/menu/pizza-pepperoni.webp",
  "pizza-margherita": "/assets/menu/pizza-pepperoni.webp",
  "pizza-chicken": "/assets/menu/pizza-pepperoni.webp",
  "pizza-four-cheese": "/assets/menu/pizza-pepperoni.webp",
  "pizza-hunter": "/assets/menu/pizza-pepperoni.webp",
  "french-fries": "/assets/menu/french-fries.webp",
  "village-potato": "/assets/menu/village-potato.webp",
  "nuggets": "/assets/menu/nuggets.webp",
  "burger-beef": "/assets/menu/hero-burger.webp",
  "burger-chicken": "/assets/menu/burger-chicken.webp",
};

const SINGULAR_CATEGORY_NAMES: Readonly<Record<string, string>> = {
  "бургеры": "Бургер",
  "гарниры": "Гарнир",
  "пицца": "Пицца",
  "роллы": "Ролл",
};

function displayCategoryName(name: string | undefined): string | undefined {
  if (!name) return undefined;
  return SINGULAR_CATEGORY_NAMES[name.trim().toLocaleLowerCase("ru-RU")] ?? name;
}

const CATEGORY_WORDS: Readonly<Record<string, readonly string[]>> = {
  "бургеры": ["бургер", "бургеры"],
  "гарниры": ["гарнир", "гарниры"],
  "пицца": ["пицца"],
  "роллы": ["ролл", "роллы"],
};

function displayItemName(name: string, categoryName: string | undefined): string {
  if (!categoryName) return name;
  const categoryWords = CATEGORY_WORDS[categoryName.trim().toLocaleLowerCase("ru-RU")];
  if (!categoryWords) return name;

  const compactName = name
    .split(/\s+/u)
    .filter((word) => !categoryWords.includes(word.toLocaleLowerCase("ru-RU")))
    .join(" ")
    .trim();

  if (!compactName) return name;
  return compactName.charAt(0).toLocaleUpperCase("ru-RU") + compactName.slice(1);
}

export function MenuBrowser({
  title,
  categories,
  items,
  locale,
  currency,
  orderingEnabled = false,
  fallbackImageUrl,
}: MenuBrowserProps) {
  const orderedCategories = [...categories].sort((left, right) => left.sortOrder - right.sortOrder);
  const categoryOrder = new Map(
    orderedCategories.map((category, index) => [category.categoryId, index]),
  );
  const orderedItems = [...items].sort(
    (left, right) =>
      (categoryOrder.get(left.categoryId) ?? Number.MAX_SAFE_INTEGER) -
        (categoryOrder.get(right.categoryId) ?? Number.MAX_SAFE_INTEGER) ||
      left.sortOrder - right.sortOrder,
  );
  const categoryNames = new Map(
    orderedCategories.map((category) => [category.categoryId, category.name]),
  );
  const filterCategories = orderedCategories.map((category) => ({
    id: category.categoryId,
    name: category.name,
    itemCount: orderedItems.filter((item) => item.categoryId === category.categoryId).length,
  }));

  return (
    <section className="menu-section shell" id="menu" aria-labelledby="menu-title">
      {orderedItems.length ? (
        <CategoryFilter
          categories={filterCategories}
          totalItemCount={orderedItems.length}
          heading={(
            <div className="section-heading">
              <p className="eyebrow">Меню</p>
              <h2 id="menu-title" data-catalog-title={title}>Популярное</h2>
            </div>
          )}
        >
          {(selectedCategories) => (
            <ul className="menu-grid">
              {orderedItems
                .filter(
                  (item) =>
                    selectedCategories.size === 0 || selectedCategories.has(item.categoryId),
                )
                .map((item) => {
              const sourceCategoryName = categoryNames.get(item.categoryId);
              return (
                <MenuItemCard
                  key={item.menuItemId}
                  item={item}
                  categoryName={displayCategoryName(sourceCategoryName)}
                  displayName={displayItemName(item.name, sourceCategoryName)}
                  imageUrl={item.imageUrl || GENERATED_DISH_IMAGES[item.menuItemId] || fallbackImageUrl}
                  locale={locale}
                  currency={currency}
                  orderingEnabled={orderingEnabled}
                />
              );
                })}
            </ul>
          )}
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
