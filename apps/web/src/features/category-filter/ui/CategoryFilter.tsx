"use client";

import type { ReactNode } from "react";
import { useEffect, useRef, useState } from "react";

type FilterCategory = {
  id: string;
  name: string;
  itemCount: number;
};

type CategoryFilterProps = {
  categories: FilterCategory[];
  totalItemCount: number;
  children: ReactNode;
};

const ALL_CATEGORIES = "__all__";

export function CategoryFilter({ categories, totalItemCount, children }: CategoryFilterProps) {
  const rootRef = useRef<HTMLDivElement>(null);
  const [activeCategory, setActiveCategory] = useState(ALL_CATEGORIES);

  useEffect(() => {
    for (const item of rootRef.current?.querySelectorAll<HTMLElement>("[data-menu-category]") ?? []) {
      item.hidden = activeCategory !== ALL_CATEGORIES && item.dataset.menuCategory !== activeCategory;
    }
  }, [activeCategory]);

  const visibleCount =
    activeCategory === ALL_CATEGORIES
      ? totalItemCount
      : (categories.find((category) => category.id === activeCategory)?.itemCount ?? 0);

  return (
    <div ref={rootRef}>
      {categories.length ? (
        <div className="category-list" role="group" aria-label="Категории меню">
          <button
            type="button"
            aria-pressed={activeCategory === ALL_CATEGORIES}
            onClick={() => setActiveCategory(ALL_CATEGORIES)}
          >
            Все
          </button>
          {categories.map((category) => (
            <button
              type="button"
              key={category.id}
              aria-pressed={activeCategory === category.id}
              onClick={() => setActiveCategory(category.id)}
            >
              {category.name}
            </button>
          ))}
        </div>
      ) : null}
      <p className="sr-only" aria-live="polite">
        Показано позиций: {visibleCount}
      </p>
      {children}
    </div>
  );
}
