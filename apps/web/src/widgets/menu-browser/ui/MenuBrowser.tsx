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
  "cezar-roll-chicken": "/assets/menu/photography-v1/cezar-roll-chicken-7dc769982bdab850.webp",
  "cezar-roll-shrimp": "/assets/menu/photography-v1/cezar-roll-shrimp-7c50c0f0c6baae58.webp",
  "sake-maki": "/assets/menu/photography-v1/sake-maki-8170d304e0776d61.webp",
  "hot-roll-salmon": "/assets/menu/photography-v1/hot-roll-salmon-123388ade11f92fd.webp",
  "cheese-baked-roll": "/assets/menu/photography-v1/cheese-baked-roll-3b0eb66a0be29d8a.webp",
  "bonito-roll": "/assets/menu/photography-v1/bonito-roll-5a33298cfcc3f459.webp",
  "philadelphia-chicken-roll": "/assets/menu/photography-v1/philadelphia-chicken-roll-afd6c28c3bcd5c1b.webp",
  "philadelphia-roll": "/assets/menu/photography-v1/philadelphia-roll-db54c61e3091c8e1.webp",
  "pizza-caesar": "/assets/menu/photography-v1/pizza-caesar-63115e2201295be7.webp",
  "pizza-pepperoni": "/assets/menu/photography-v1/pizza-pepperoni-d459e3ea7b1d7a2f.webp",
  "pizza-mushroom": "/assets/menu/photography-v1/pizza-mushroom-c62268ed0c9c75d4.webp",
  "pizza-cheese": "/assets/menu/photography-v1/pizza-cheese-fa1398bcafa98e81.webp",
  "pizza-julienne": "/assets/menu/photography-v1/pizza-julienne-e96e11836c1f7693.webp",
  "pizza-assorti": "/assets/menu/photography-v1/pizza-assorti-3f6ca4bba2b58549.webp",
  "pizza-margherita": "/assets/menu/photography-v1/pizza-margherita-5631ca238a882b7b.webp",
  "pizza-chicken": "/assets/menu/photography-v1/pizza-chicken-a44491421957ba32.webp",
  "pizza-four-cheese": "/assets/menu/photography-v1/pizza-four-cheese-e045ebee06a3b2cd.webp",
  "pizza-hunter": "/assets/menu/photography-v1/pizza-hunter-d3cb381b50b38846.webp",
  "french-fries": "/assets/menu/photography-v1/french-fries-938f1d0f75c318a0.webp",
  "village-potato": "/assets/menu/photography-v1/village-potato-62a24a78cb95e50f.webp",
  "nuggets": "/assets/menu/photography-v1/nuggets-98870f81e1ec5483.webp",
  "burger-beef": "/assets/menu/photography-v1/burger-beef-f344e8920973879d.webp",
  "burger-chicken": "/assets/menu/photography-v1/burger-chicken-fddeb97af583712d.webp"
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
