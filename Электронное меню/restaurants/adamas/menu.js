"use strict";

/**
 * Adamas menu — versioned menu snapshot.
 * Aligned with menu_versions PostgreSQL table.
 */

window.RESTAURANT_ADAMAS_MENU = Object.freeze({
  version: 1,
  publishedAt: "2026-07-13T00:00:00.000Z",
  categories: Object.freeze(["Пицца", "Роллы", "Бургеры", "Гарниры"]),
  items: Object.freeze([

    // ======================
    // ПИЦЦА
    // ======================

    Object.freeze({
      id: "pizza-pepperoni",
      category: "Пицца",
      name: "Пицца Пепперони",
      description: "Классическая пицца с пикантной пепперони и моцареллой",
      price: 590,
      image: ""
    }),

    Object.freeze({
      id: "pizza-margherita",
      category: "Пицца",
      name: "Пицца Маргарита",
      description: "Томаты, моцарелла, базилик на тонком тесте",
      price: 490,
      image: ""
    }),

    Object.freeze({
      id: "pizza-four-cheese",
      category: "Пицца",
      name: "Пицца 4 сыра",
      description: "Моцарелла, пармезан, дор-блю, чеддер",
      price: 620,
      image: ""
    }),

    Object.freeze({
      id: "pizza-chicken",
      category: "Пицца",
      name: "Пицца Куриная",
      description: "Курица, грибы, моцарелла, соус альфредо",
      price: 550,
      image: ""
    }),

    Object.freeze({
      id: "pizza-hawaiian",
      category: "Пицца",
      name: "Пицца Гавайская",
      description: "Ветчина, ананас, моцарелла",
      price: 540,
      image: ""
    }),

    Object.freeze({
      id: "pizza-mushroom",
      category: "Пицца",
      name: "Пицца Грибная",
      description: "Шампиньоны, белые грибы, трюфельное масло",
      price: 570,
      image: ""
    }),

    // ======================
    // РОЛЛЫ
    // ======================

    Object.freeze({
      id: "roll-philadelphia",
      category: "Роллы",
      name: "Сет Филадельфия",
      description: "Классический ролл с лососем и сливочным сыром",
      price: 849,
      image: ""
    }),

    Object.freeze({
      id: "roll-california",
      category: "Роллы",
      name: "Калифорния",
      description: "Краб, авокадо, огурец, икра тобико",
      price: 690,
      image: ""
    }),

    Object.freeze({
      id: "roll-hot-salmon",
      category: "Роллы",
      name: "Горячий ролл с лососем",
      description: "Запечённый ролл с лососем и сливочным сыром",
      price: 720,
      image: ""
    }),

    Object.freeze({
      id: "roll-dragon",
      category: "Роллы",
      name: "Дрэгон ролл",
      description: "Угорь, авокадо, огурец, соус унаги",
      price: 790,
      image: ""
    }),

    Object.freeze({
      id: "roll-spicy-tuna",
      category: "Роллы",
      name: "Спайси тунец",
      description: "Тунец, спайси соус, кунжут, авокадо",
      price: 750,
      image: ""
    }),

    // ======================
    // БУРГЕРЫ
    // ======================

    Object.freeze({
      id: "burger-classic",
      category: "Бургеры",
      name: "Чизбургер",
      description: "Говяжья котлета, сыр чеддер, томаты, соус",
      price: 350,
      image: ""
    }),

    Object.freeze({
      id: "burger-double",
      category: "Бургеры",
      name: "Дабл чизбургер",
      description: "Две говяжьи котлеты, двойной сыр, соус",
      price: 490,
      image: ""
    }),

    Object.freeze({
      id: "burger-chicken",
      category: "Бургеры",
      name: "Куриный бургер",
      description: "Хрустящая курица, салат, томаты, соус цезарь",
      price: 370,
      image: ""
    }),

    Object.freeze({
      id: "burger-bbq",
      category: "Бургеры",
      name: "Барбекю бургер",
      description: "Говяжья котлета, бекон, лук кольцами, BBQ соус",
      price: 420,
      image: ""
    }),

    // ======================
    // ГАРНИРЫ
    // ======================

    Object.freeze({
      id: "fries-standard",
      category: "Гарниры",
      name: "Картофель фри",
      description: "Хрустящий картофель фри",
      price: 140,
      image: ""
    }),

    Object.freeze({
      id: "fries-wedges",
      category: "Гарниры",
      name: "Картофель по-деревенски",
      description: "Картофельные дольки со специями",
      price: 160,
      image: ""
    }),

    Object.freeze({
      id: "nuggets-8",
      category: "Гарниры",
      name: "Наггетсы (8 шт)",
      description: "Куриные наггетсы с хрустящей корочкой",
      price: 220,
      image: ""
    }),

    Object.freeze({
      id: "onion-rings",
      category: "Гарниры",
      name: "Кольца лука",
      description: "Хрустящие кольца лука в панировке",
      price: 180,
      image: ""
    })
  ])
});
