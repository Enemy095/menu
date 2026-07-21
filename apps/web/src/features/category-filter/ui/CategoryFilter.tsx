"use client";

import type { ReactNode } from "react";
import { useState } from "react";

type FilterCategory = {
  id: string;
  name: string;
  itemCount: number;
};

type CategoryFilterProps = {
  categories: FilterCategory[];
  totalItemCount: number;
  heading: ReactNode;
  children: (selectedCategories: ReadonlySet<string>) => ReactNode;
};

export function CategoryFilter({ categories, totalItemCount, heading, children }: CategoryFilterProps) {
  const [selectedCategories, setSelectedCategories] = useState<Set<string>>(() => new Set());
  const allCategoriesSelected = selectedCategories.size === 0;

  const visibleCount =
    allCategoriesSelected
      ? totalItemCount
      : categories.reduce(
          (count, category) =>
            count + (selectedCategories.has(category.id) ? category.itemCount : 0),
          0,
        );

  function toggleCategory(categoryId: string) {
    setSelectedCategories((current) => {
      const next = new Set(current);
      if (next.has(categoryId)) next.delete(categoryId);
      else next.add(categoryId);
      return next;
    });
  }

  function resetCategories() {
    setSelectedCategories(new Set());
  }

  return (
    <div>
      <div className="menu-toolbar">
        {heading}
        {categories.length ? (
          <div className="category-list" role="group" aria-label="Категории меню">
            <div className="category-list__all">
              <button
                type="button"
                className="category-list__all-button"
                aria-pressed={allCategoriesSelected}
                onClick={resetCategories}
              >
                Все
              </button>
            </div>
            <div className="category-list__options">
              {categories.map((category) => {
                const selected = selectedCategories.has(category.id);
                return (
                  <button
                    type="button"
                    key={category.id}
                    aria-pressed={selected}
                    onClick={() => toggleCategory(category.id)}
                  >
                    <span className="category-list__check" aria-hidden="true">✓</span>
                    {category.name}
                  </button>
                );
              })}
            </div>
            <button
              className="category-list__reset"
              type="button"
              disabled={allCategoriesSelected}
              onClick={resetCategories}
            >
              <svg viewBox="0 0 24 24" aria-hidden="true">
                <path d="M4 5h16l-6.2 7v5.2l-3.6 1.8v-7Z" />
                <path d="m16.5 15.5 4 4m0-4-4 4" />
              </svg>
              Сбросить
            </button>
          </div>
        ) : null}
      </div>
      <p className="sr-only" aria-live="polite">
        Показано позиций: {visibleCount}
      </p>
      {children(selectedCategories)}
    </div>
  );
}
