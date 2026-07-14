"use strict";

/**
 * Delivery blocks feature module.
 *
 * Renders optional delivery-specific UI blocks:
 *   - Popular items section
 *   - Category cards with images
 *   - Delivery benefits strip
 *   - Hero section with burger background
 *
 * Only visible when the "deliveryBlocks" feature is enabled.
 * Completely independent from Стамбул — no shared state or DOM references.
 */

window.FeatureDeliveryBlocks = (() => {

  /** @type {HTMLElement[]} Created elements to clean up on unmount */
  let createdElements = [];
  /** @type {{ element: HTMLElement, event: string, handler: Function }[]} Listener references to remove on unmount */
  let listeners = [];

  /**
   * Mount delivery blocks into the page.
   * @param {{ menuItems?: Array, categories?: string[], config?: object }} context
   */
  function mount(context) {
    unmount(); // Clean up any previous mount

    const items = context.menuItems || [];
    const categories = context.categories || [];
    const config = context.config || {};

    // 1. Enhance hero section with delivery-specific content
    enhanceHero(config);

    // 2. Render popular items section (first 4 items from popular categories)
    renderPopularSection(items);

    // 3. Render category cards
    renderCategoryCards(categories, items);

    // 4. Render delivery benefits section
    renderDeliveryBenefits();
  }

  /**
   * Track an event listener for explicit cleanup on unmount.
   */
  function trackListener(element, event, handler) {
    element.addEventListener(event, handler);
    listeners.push({ element, event, handler });
  }

  /**
   * Escape HTML to prevent XSS.
   */
  function escapeHtml(str) {
    const div = document.createElement("div");
    div.textContent = str;
    return div.innerHTML;
  }

  /**
   * Unmount and clean up all delivery blocks.
   */
  function unmount() {
    // Remove tracked event listeners before removing elements
    listeners.forEach(({ element, event, handler }) => {
      element.removeEventListener(event, handler);
    });
    listeners = [];

    createdElements.forEach(el => {
      if (el && el.parentNode) el.parentNode.removeChild(el);
    });
    createdElements = [];

    // Restore hero to default state
    const hero = document.querySelector(".hero");
    if (hero) {
      hero.classList.remove("delivery-hero");
      // Restore original hero content from index.html
      hero.innerHTML = [
        '<div class="hero-ornament" aria-hidden="true">☾</div>',
        '<p class="eyebrow" id="cuisineText" data-brand-cuisine></p>',
        '<h1 data-brand-slogan></h1>',
        '<p id="orderIntro" data-brand-intro></p>',
        '<div class="schedule" id="scheduleBlock"><span aria-hidden="true">◷</span><span id="scheduleText"></span></div>'
      ].join("");
    }

    // Restore hidden elements
    const eyebrow = document.querySelector(".eyebrow");
    const schedule = document.querySelector(".schedule");
    if (eyebrow) eyebrow.style.display = "";
    if (schedule) schedule.style.display = "";
  }

  /**
   * Enhance the hero section with delivery styling.
   * Reads config.hero for all content and image.
   */
  function enhanceHero(config) {
    const hero = document.querySelector(".hero");
    if (!hero) return;

    const heroConfig = config.hero || {};
    const image = heroConfig.image || "";
    const titleLine1 = heroConfig.titleLine1 || "ВКУСНАЯ ЕДА";
    const titleLine2 = heroConfig.titleLine2 || "БЫСТРАЯ ДОСТАВКА";
    const subtitle = heroConfig.subtitle || "";
    const ctaText = heroConfig.ctaText || "Смотреть меню";
    const benefits = heroConfig.benefits || [];

    hero.classList.add("delivery-hero");

    // Build two-column layout: left (text) + right (image)
    const content = document.createElement("div");
    content.className = "hero-content";

    // Left column: text content
    const textCol = document.createElement("div");
    textCol.className = "hero-text";

    const title = document.createElement("h1");
    title.className = "hero-title";
    title.innerHTML = `<span class="hero-title-line1">${escapeHtml(titleLine1)}</span><span class="hero-title-line2">${escapeHtml(titleLine2)}</span>`;
    textCol.appendChild(title);

    if (subtitle) {
      const sub = document.createElement("p");
      sub.className = "hero-subtitle";
      sub.textContent = subtitle;
      textCol.appendChild(sub);
    }

    // Benefits row
    if (benefits.length > 0) {
      const benefitsRow = document.createElement("div");
      benefitsRow.className = "hero-benefits";
      benefits.forEach(b => {
        const item = document.createElement("div");
        item.className = "hero-benefit";
        const icon = document.createElement("span");
        icon.className = "hero-benefit-icon";
        icon.textContent = b.icon || "";
        const text = document.createElement("span");
        text.className = "hero-benefit-text";
        text.textContent = b.text || "";
        item.append(icon, text);
        benefitsRow.appendChild(item);
      });
      textCol.appendChild(benefitsRow);
    }

    // CTA button
    const cta = document.createElement("button");
    cta.className = "hero-cta";
    cta.type = "button";
    cta.textContent = ctaText;
    trackListener(cta, "click", () => {
      const menuSection = document.querySelector(".menu-section");
      if (menuSection) menuSection.scrollIntoView({ behavior: "smooth" });
    });
    textCol.appendChild(cta);

    content.appendChild(textCol);

    // Right column: hero image
    if (image) {
      const imageCol = document.createElement("div");
      imageCol.className = "hero-image-col";

      const imgWrapper = document.createElement("div");
      imgWrapper.className = "hero-image-wrapper";

      const img = document.createElement("img");
      img.className = "hero-image";
      img.src = image;
      img.alt = heroConfig.imageAlt || "";
      img.width = 600;
      img.height = 600;
      img.loading = "eager";
      imgWrapper.appendChild(img);
      imageCol.appendChild(imgWrapper);
      content.appendChild(imageCol);
    }

    // Decorative background glow
    const glow = document.createElement("div");
    glow.className = "hero-glow";
    glow.setAttribute("aria-hidden", "true");
    content.appendChild(glow);

    // Replace hero children with new layout
    hero.innerHTML = "";
    hero.appendChild(content);
    createdElements.push(content);

    // Hide original eyebrow/schedule (now replaced by new content)
    const eyebrow = document.querySelector(".eyebrow");
    const schedule = document.querySelector(".schedule");
    if (eyebrow) eyebrow.style.display = "none";
    if (schedule) schedule.style.display = "none";
  }

  /**
   * Render popular items section after the hero.
   * Selects up to 4 items from featured categories.
   */
  function renderPopularSection(items) {
    const main = document.querySelector("main");
    if (!main || !items.length) return;

    const section = document.createElement("section");
    section.className = "popular-section";
    section.setAttribute("aria-labelledby", "popularTitle");

    const heading = document.createElement("div");
    heading.className = "section-heading";
    heading.innerHTML = '<div><h2 id="popularTitle">Популярное</h2></div>';
    section.appendChild(heading);

    const grid = document.createElement("div");
    grid.className = "popular-grid";
    grid.id = "popularGrid";

    // Select popular items (prefer pizza and burgers)
    const popularCategories = ["Пицца", "Бургеры", "Роллы"];
    const popularItems = [];
    for (const cat of popularCategories) {
      const catItems = items.filter(i => i.category === cat);
      if (catItems.length > 0) popularItems.push(catItems[0]);
      if (popularItems.length >= 4) break;
    }
    // Fill remaining with any items
    for (const item of items) {
      if (popularItems.length >= 4) break;
      if (!popularItems.some(p => p.id === item.id)) popularItems.push(item);
    }

    popularItems.slice(0, 4).forEach(item => {
      const card = createPopularCard(item);
      grid.appendChild(card);
    });

    section.appendChild(grid);

    // Insert after hero, before contact/menu sections
    const contactSection = document.querySelector("#contactSection");
    const menuSection = document.querySelector(".menu-section");
    const insertBefore = contactSection || menuSection;
    if (insertBefore) {
      main.insertBefore(section, insertBefore);
    } else {
      main.appendChild(section);
    }
    createdElements.push(section);
  }

  /**
   * Create a popular item card element.
   */
  function createPopularCard(item) {
    const card = document.createElement("div");
    card.className = "popular-card";
    card.dataset.dishId = item.id;

    const visual = document.createElement("div");
    visual.className = "popular-card__visual";
    const img = document.createElement("img");
    img.src = item.image || "./restaurants/adamas/branding/hero-burger.png";
    img.alt = item.name;
    img.loading = "lazy";
    img.width = 400;
    img.height = 300;
    visual.appendChild(img);

    const body = document.createElement("div");
    body.className = "popular-card__body";

    const name = document.createElement("p");
    name.className = "popular-card__name";
    name.textContent = item.name;

    const footer = document.createElement("div");
    footer.className = "popular-card__footer";

    const price = document.createElement("span");
    price.className = "popular-card__price";
    price.textContent = `${item.price} ₽`;

    const addBtn = document.createElement("button");
    addBtn.className = "popular-card__add";
    addBtn.type = "button";
    addBtn.dataset.action = "increase";
    addBtn.dataset.id = item.id;
    addBtn.setAttribute("aria-label", `Добавить ${item.name}`);
    addBtn.textContent = "+";
    // Delegate to existing cart system
    trackListener(addBtn, "click", (e) => {
      e.stopPropagation();
      // Trigger the same action as the main menu + button
      const mainBtn = document.querySelector(`.menu-grid [data-dish-id="${item.id}"] [data-action="increase"]`);
      if (mainBtn) mainBtn.click();
      updatePopularCardQuantity(item.id);
    });

    footer.append(price, addBtn);
    body.append(name, footer);
    card.append(visual, body);
    return card;
  }

  /**
   * Update popular card quantity display to match main menu.
   */
  function updatePopularCardQuantity(itemId) {
    const mainCard = document.querySelector(`.menu-grid [data-dish-id="${itemId}"]`);
    const popCard = document.querySelector(`.popular-grid [data-dish-id="${itemId}"]`);
    if (!mainCard || !popCard) return;

    const mainQty = mainCard.querySelector("[data-quantity-value]");
    const addBtn = popCard.querySelector(".popular-card__add");
    if (mainQty && addBtn) {
      const qty = parseInt(mainQty.textContent, 10) || 0;
      if (qty > 0) {
        addBtn.textContent = qty;
        addBtn.style.borderRadius = "12px";
        addBtn.style.width = "auto";
        addBtn.style.padding = "0 10px";
        addBtn.style.fontSize = "13px";
      } else {
        addBtn.textContent = "+";
        addBtn.style.borderRadius = "";
        addBtn.style.width = "";
        addBtn.style.padding = "";
        addBtn.style.fontSize = "";
      }
    }
  }

  /**
   * Render category cards section.
   */
  function renderCategoryCards(categories, items) {
    const main = document.querySelector("main");
    if (!main || !categories.length) return;

    const section = document.createElement("section");
    section.className = "category-cards-section";
    section.setAttribute("aria-labelledby", "categoryCardsTitle");

    const heading = document.createElement("div");
    heading.className = "section-heading";
    heading.innerHTML = '<div><h2 id="categoryCardsTitle">Категории</h2></div>';
    section.appendChild(heading);

    const grid = document.createElement("div");
    grid.className = "category-cards";

    categories.forEach(category => {
      const count = items.filter(i => i.category === category).length;
      const card = document.createElement("div");
      card.className = "category-card";
      card.dataset.category = category;
      card.tabIndex = 0;
      card.setAttribute("role", "button");
      card.setAttribute("aria-label", `${category} — ${count} блюд`);

      // Click filters to this category in the main menu
      trackListener(card, "click", () => {
        const catButton = document.querySelector(`.categories [data-category="${category}"]`);
        if (catButton) catButton.click();
        const menuSection = document.querySelector(".menu-section");
        if (menuSection) menuSection.scrollIntoView({ behavior: "smooth" });
      });

      const name = document.createElement("p");
      name.className = "category-card__name";
      name.textContent = category;

      const countEl = document.createElement("p");
      countEl.className = "category-card__count";
      countEl.textContent = `${count} ${count === 1 ? "блюдо" : count < 5 ? "блюда" : "блюд"}`;

      card.append(name, countEl);
      grid.appendChild(card);
    });

    section.appendChild(grid);

    // Insert after popular section or after hero
    const popularSection = document.querySelector(".popular-section");
    const insertAfter = popularSection;
    if (insertAfter && insertAfter.nextSibling) {
      main.insertBefore(section, insertAfter.nextSibling);
    } else {
      const contactSection = document.querySelector("#contactSection");
      const menuSection = document.querySelector(".menu-section");
      const insertBefore = contactSection || menuSection;
      if (insertBefore) {
        main.insertBefore(section, insertBefore);
      } else {
        main.appendChild(section);
      }
    }
    createdElements.push(section);
  }

  /**
   * Render delivery benefits section (below categories, above menu).
   */
  function renderDeliveryBenefits() {
    const main = document.querySelector("main");
    if (!main) return;

    const section = document.createElement("section");
    section.className = "delivery-info-section";

    const grid = document.createElement("div");
    grid.className = "delivery-info-grid";

    const benefits = [
      { icon: "🔥", title: "Горячая еда", desc: "Доставляем горячо и свежо" },
      { icon: "⏱", title: "От 40 минут", desc: "Быстрая доставка" },
      { icon: "🚗", title: "Бесплатно от 1000 ₽", desc: "Доставка заказов от 1000 ₽" }
    ];

    benefits.forEach(b => {
      const card = document.createElement("div");
      card.className = "delivery-info-card";

      const icon = document.createElement("div");
      icon.className = "delivery-info-card__icon";
      icon.textContent = b.icon;

      const title = document.createElement("p");
      title.className = "delivery-info-card__title";
      title.textContent = b.title;

      const desc = document.createElement("p");
      desc.className = "delivery-info-card__desc";
      desc.textContent = b.desc;

      card.append(icon, title, desc);
      grid.appendChild(card);
    });

    section.appendChild(grid);

    // Insert before menu section
    const menuSection = document.querySelector(".menu-section");
    if (menuSection) {
      main.insertBefore(section, menuSection);
    } else {
      main.appendChild(section);
    }
    createdElements.push(section);
  }

  return Object.freeze({ mount, unmount });
})();
