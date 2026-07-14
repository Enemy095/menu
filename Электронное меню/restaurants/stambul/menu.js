"use strict";

/**
 * Stambul menu — versioned menu snapshot.
 *
 * Aligned with the menu_versions PostgreSQL table:
 *   { id, business_id, version, published_at, categories, items, is_published }
 *
 * The UI renders from this structure. When the backend is live,
 * this file is replaced by the API response — same shape, different source.
 *
 * Menu version metadata:
 *   version     — incremented on each publication
 *   publishedAt — ISO 8601 timestamp
 *   categories  — ordered unique category names
 *   items       — full menu item array
 */

window.RESTAURANT_STAMBUL_MENU = Object.freeze({
  version: 1,
  publishedAt: "2026-07-01T00:00:00.000Z",
  categories: Object.freeze(["Роллы", "Пицца", "Гарниры", "Бургеры"]),
  items: Object.freeze([

    // ======================
    // РОЛЛЫ
    // ======================

    Object.freeze({
      id: "cezar-roll-chicken",
      category: "Роллы",
      name: "Ролл Цезарь с курицей",
      description: "Ролл с курицей, свежими овощами и соусом Цезарь",
      price: 250,
      image: ""
    }),

    Object.freeze({
      id: "cezar-roll-shrimp",
      category: "Роллы",
      name: "Ролл Цезарь с креветкой",
      description: "Ролл с креветкой, овощами и фирменным соусом",
      price: 300,
      image: ""
    }),

    Object.freeze({
      id: "sake-maki",
      category: "Роллы",
      name: "Ролл Горячий шик",
      description: "Классический ролл с лососем",
      price: 300,
      image: ""
    }),

    Object.freeze({
      id: "hot-roll-salmon",
      category: "Роллы",
      name: "Горячий ролл с лососем",
      description: "Запечённый ролл с лососем и нежной начинкой",
      price: 350,
      image: ""
    }),

    Object.freeze({
      id: "cheese-baked-roll",
      category: "Роллы",
      name: "Ролл Сырный запечённые",
      description: "Запечённые роллы с сырной начинкой",
      price: 300,
      image: ""
    }),

    Object.freeze({
      id: "bonito-roll",
      category: "Роллы",
      name: "Ролл Ойси",
      description: "Ролл с традиционной японской начинкой и стружкой тунца",
      price: 400,
      image: ""
    }),

    Object.freeze({
      id: "philadelphia-chicken-roll",
      category: "Роллы",
      name: "Ролл Ойси с курицей",
      description: "Ролл с курицей и сливочным сыром",
      price: 350,
      image: ""
    }),

    Object.freeze({
      id: "philadelphia-roll",
      category: "Роллы",
      name: "Ролл Филадельфия",
      description: "Классический ролл с лососем и сливочным сыром",
      price: 400,
      image: ""
    }),

    // ======================
    // ПИЦЦА
    // ======================

    Object.freeze({
      id: "pizza-caesar",
      category: "Пицца",
      name: "Пицца Цезарь",
      description: "Пицца с курицей, сыром и соусом Цезарь",
      price: 350,
      image: ""
    }),

    Object.freeze({
      id: "pizza-pepperoni",
      category: "Пицца",
      name: "Пицца Пепперони",
      description: "Классическая пицца с колбасками пепперони",
      price: 350,
      image: ""
    }),

    Object.freeze({
      id: "pizza-mushroom",
      category: "Пицца",
      name: "Пицца Грибная",
      description: "Пицца с грибами и сыром",
      price: 350,
      image: ""
    }),

    Object.freeze({
      id: "pizza-cheese",
      category: "Пицца",
      name: "Пицца Сырная",
      description: "Пицца с несколькими видами сыра",
      price: 350,
      image: ""
    }),

    Object.freeze({
      id: "pizza-julienne",
      category: "Пицца",
      name: "Пицца Жульен",
      description: "Пицца с курицей, грибами и сливочным соусом",
      price: 350,
      image: ""
    }),

    Object.freeze({
      id: "pizza-assorti",
      category: "Пицца",
      name: "Пицца Ассорти",
      description: "Пицца с разнообразной начинкой",
      price: 350,
      image: ""
    }),

    Object.freeze({
      id: "pizza-margherita",
      category: "Пицца",
      name: "Пицца Маргарита",
      description: "Классическая пицца с томатами, сыром и соусом",
      price: 350,
      image: ""
    }),

    Object.freeze({
      id: "pizza-chicken",
      category: "Пицца",
      name: "Пицца Куриная",
      description: "Пицца с курицей и сыром",
      price: 350,
      image: ""
    }),

    Object.freeze({
      id: "pizza-four-cheese",
      category: "Пицца",
      name: "Пицца    4 сыра",
      description: "Пицца с четырьмя видами сыра",
      price: 350,
      image: ""
    }),

    Object.freeze({
      id: "pizza-hunter",
      category: "Пицца",
      name: "Пицца Охотничья",
      description: "Пицца с мясными ингредиентами и сыром",
      price: 350,
      image: ""
    }),

    // ======================
    // ГАРНИРЫ
    // ======================

    Object.freeze({
      id: "french-fries",
      category: "Гарниры",
      name: "Картофель фри",
      description: "Хрустящий картофель фри",
      price: 120,
      image: ""
    }),

    Object.freeze({
      id: "village-potato",
      category: "Гарниры",
      name: "Картофель по-деревенски",
      description: "Картофельные дольки со специями",
      price: 120,
      image: ""
    }),

    Object.freeze({
      id: "nuggets",
      category: "Гарниры",
      name: "Наггетсы",
      description: "Куриные наггетсы с хрустящей корочкой",
      price: 200,
      image: ""
    }),

    // ======================
    // БУРГЕРЫ
    // ======================

    Object.freeze({
      id: "burger-beef",
      category: "Бургеры",
      name: "Бургер говяжий",
      description: "Сочный бургер с говяжьей котлетой и фирменным соусом",
      price: 270,
      image: ""
    }),

    Object.freeze({
      id: "burger-chicken",
      category: "Бургеры",
      name: "Бургер куриный",
      description: "Бургер с куриной котлетой и свежими овощами",
      price: 270,
      image: ""
    })
  ])
});
