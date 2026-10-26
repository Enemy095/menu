--
-- PostgreSQL database dump
--

-- Dumped from database version 17.5
-- Dumped by pg_dump version 17.5

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET transaction_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

--
-- Data for Name: assets; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.assets (id, object_key, asset_type, content_type, size_bytes, sha256, status, created_at) VALUES ('41eec9b7-b68e-5c82-ac50-a988df90b415', 'assets/logo/90374c21b9cd4c15ef5b861521d0e5bcdc4571c3fae6f4aebd5c3927bfb6ee9b.png', 'logo', 'image/png', 43728, '90374c21b9cd4c15ef5b861521d0e5bcdc4571c3fae6f4aebd5c3927bfb6ee9b', 'active', '2026-07-14 22:42:53.333539+00');


--
-- Data for Name: restaurants; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.restaurants (id, external_key, slug, display_name, status, is_default, created_at, updated_at) VALUES ('d4579042-8239-57df-b716-c27d3a6c213a', 'stambul', 'stambul', 'Ресторан-кафе у Ризвана', 'active', true, '2026-07-14 22:42:53.333539+00', '2026-07-14 22:42:53.333539+00');


--
-- Data for Name: menu_versions; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.menu_versions (id, restaurant_id, version, status, published_at, created_at, updated_at, currency) VALUES ('db0d6a46-9145-55c7-af56-2795b510fa19', 'd4579042-8239-57df-b716-c27d3a6c213a', 1, 'published', '2026-07-01 00:00:00+00', '2026-07-14 22:42:53.333539+00', '2026-07-14 22:42:53.333539+00', 'RUB');


--
-- Data for Name: menu_categories; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.menu_categories (id, menu_version_id, public_id, name, description, image_asset_id, sort_order, available) VALUES ('42c9b27a-bea6-5ded-8e60-0ed3785467dd', 'db0d6a46-9145-55c7-af56-2795b510fa19', 'rolls', 'Роллы', NULL, NULL, 0, true);
INSERT INTO public.menu_categories (id, menu_version_id, public_id, name, description, image_asset_id, sort_order, available) VALUES ('a1602dfb-0cf8-5221-97ce-d5406edc6f5a', 'db0d6a46-9145-55c7-af56-2795b510fa19', 'pizza', 'Пицца', NULL, NULL, 1, true);
INSERT INTO public.menu_categories (id, menu_version_id, public_id, name, description, image_asset_id, sort_order, available) VALUES ('7d5ee8f1-a863-5c3f-bce2-fe31bbddbb9e', 'db0d6a46-9145-55c7-af56-2795b510fa19', 'sides', 'Гарниры', NULL, NULL, 2, true);
INSERT INTO public.menu_categories (id, menu_version_id, public_id, name, description, image_asset_id, sort_order, available) VALUES ('a2170b9f-0997-5260-a16b-7c9bebb0a9df', 'db0d6a46-9145-55c7-af56-2795b510fa19', 'burgers', 'Бургеры', NULL, NULL, 3, true);


--
-- Data for Name: menu_items; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.menu_items (id, menu_version_id, category_id, public_id, name, description, price_minor, currency, image_asset_id, available, sort_order) VALUES ('ca7266ba-6d1f-5a74-8d25-24b4db527ed7', 'db0d6a46-9145-55c7-af56-2795b510fa19', '42c9b27a-bea6-5ded-8e60-0ed3785467dd', 'cezar-roll-chicken', 'Ролл Цезарь с курицей', 'Ролл с курицей, свежими овощами и соусом Цезарь', 25000, 'RUB', NULL, true, 0);
INSERT INTO public.menu_items (id, menu_version_id, category_id, public_id, name, description, price_minor, currency, image_asset_id, available, sort_order) VALUES ('15598e64-5836-52af-b2d9-bc1cda50fa4d', 'db0d6a46-9145-55c7-af56-2795b510fa19', '42c9b27a-bea6-5ded-8e60-0ed3785467dd', 'cezar-roll-shrimp', 'Ролл Цезарь с креветкой', 'Ролл с креветкой, овощами и фирменным соусом', 30000, 'RUB', NULL, true, 1);
INSERT INTO public.menu_items (id, menu_version_id, category_id, public_id, name, description, price_minor, currency, image_asset_id, available, sort_order) VALUES ('2000dd00-e61a-5893-994d-20d88723c4cf', 'db0d6a46-9145-55c7-af56-2795b510fa19', '42c9b27a-bea6-5ded-8e60-0ed3785467dd', 'sake-maki', 'Ролл Горячий шик', 'Классический ролл с лососем', 30000, 'RUB', NULL, true, 2);
INSERT INTO public.menu_items (id, menu_version_id, category_id, public_id, name, description, price_minor, currency, image_asset_id, available, sort_order) VALUES ('d150014c-17d3-5811-a6a7-324ca8fa2c78', 'db0d6a46-9145-55c7-af56-2795b510fa19', '42c9b27a-bea6-5ded-8e60-0ed3785467dd', 'hot-roll-salmon', 'Горячий ролл с лососем', 'Запечённый ролл с лососем и нежной начинкой', 35000, 'RUB', NULL, true, 3);
INSERT INTO public.menu_items (id, menu_version_id, category_id, public_id, name, description, price_minor, currency, image_asset_id, available, sort_order) VALUES ('d6c819c2-0e74-5a3b-8c14-b381ac74f627', 'db0d6a46-9145-55c7-af56-2795b510fa19', '42c9b27a-bea6-5ded-8e60-0ed3785467dd', 'cheese-baked-roll', 'Ролл Сырный запечённые', 'Запечённые роллы с сырной начинкой', 30000, 'RUB', NULL, true, 4);
INSERT INTO public.menu_items (id, menu_version_id, category_id, public_id, name, description, price_minor, currency, image_asset_id, available, sort_order) VALUES ('de9faa74-59b1-588c-8318-1b1f678fde02', 'db0d6a46-9145-55c7-af56-2795b510fa19', '42c9b27a-bea6-5ded-8e60-0ed3785467dd', 'bonito-roll', 'Ролл Ойси', 'Ролл с традиционной японской начинкой и стружкой тунца', 40000, 'RUB', NULL, true, 5);
INSERT INTO public.menu_items (id, menu_version_id, category_id, public_id, name, description, price_minor, currency, image_asset_id, available, sort_order) VALUES ('07057665-97e4-593a-9df5-4081af58b9de', 'db0d6a46-9145-55c7-af56-2795b510fa19', '42c9b27a-bea6-5ded-8e60-0ed3785467dd', 'philadelphia-chicken-roll', 'Ролл Ойси с курицей', 'Ролл с курицей и сливочным сыром', 35000, 'RUB', NULL, true, 6);
INSERT INTO public.menu_items (id, menu_version_id, category_id, public_id, name, description, price_minor, currency, image_asset_id, available, sort_order) VALUES ('3b54b416-77c8-5775-a302-e244e139734a', 'db0d6a46-9145-55c7-af56-2795b510fa19', '42c9b27a-bea6-5ded-8e60-0ed3785467dd', 'philadelphia-roll', 'Ролл Филадельфия', 'Классический ролл с лососем и сливочным сыром', 40000, 'RUB', NULL, true, 7);
INSERT INTO public.menu_items (id, menu_version_id, category_id, public_id, name, description, price_minor, currency, image_asset_id, available, sort_order) VALUES ('a870e27d-3f1f-5c5c-8955-52fa8a76c1a1', 'db0d6a46-9145-55c7-af56-2795b510fa19', 'a1602dfb-0cf8-5221-97ce-d5406edc6f5a', 'pizza-caesar', 'Пицца Цезарь', 'Пицца с курицей, сыром и соусом Цезарь', 35000, 'RUB', NULL, true, 0);
INSERT INTO public.menu_items (id, menu_version_id, category_id, public_id, name, description, price_minor, currency, image_asset_id, available, sort_order) VALUES ('3e07df98-5bd1-59cc-86f9-7c55f1ca3ca4', 'db0d6a46-9145-55c7-af56-2795b510fa19', 'a1602dfb-0cf8-5221-97ce-d5406edc6f5a', 'pizza-pepperoni', 'Пицца Пепперони', 'Классическая пицца с колбасками пепперони', 35000, 'RUB', NULL, true, 1);
INSERT INTO public.menu_items (id, menu_version_id, category_id, public_id, name, description, price_minor, currency, image_asset_id, available, sort_order) VALUES ('89efb35d-c1b0-53c2-adb3-7cec4e9f63b2', 'db0d6a46-9145-55c7-af56-2795b510fa19', 'a1602dfb-0cf8-5221-97ce-d5406edc6f5a', 'pizza-mushroom', 'Пицца Грибная', 'Пицца с грибами и сыром', 35000, 'RUB', NULL, true, 2);
INSERT INTO public.menu_items (id, menu_version_id, category_id, public_id, name, description, price_minor, currency, image_asset_id, available, sort_order) VALUES ('fb994b52-0243-547e-9724-c0acd7f5da01', 'db0d6a46-9145-55c7-af56-2795b510fa19', 'a1602dfb-0cf8-5221-97ce-d5406edc6f5a', 'pizza-cheese', 'Пицца Сырная', 'Пицца с несколькими видами сыра', 35000, 'RUB', NULL, true, 3);
INSERT INTO public.menu_items (id, menu_version_id, category_id, public_id, name, description, price_minor, currency, image_asset_id, available, sort_order) VALUES ('10e97b2d-0906-54bd-9eb2-d48bbb6f0722', 'db0d6a46-9145-55c7-af56-2795b510fa19', 'a1602dfb-0cf8-5221-97ce-d5406edc6f5a', 'pizza-julienne', 'Пицца Жульен', 'Пицца с курицей, грибами и сливочным соусом', 35000, 'RUB', NULL, true, 4);
INSERT INTO public.menu_items (id, menu_version_id, category_id, public_id, name, description, price_minor, currency, image_asset_id, available, sort_order) VALUES ('3f0abc13-3aac-553e-a3fb-05258e6c451c', 'db0d6a46-9145-55c7-af56-2795b510fa19', 'a1602dfb-0cf8-5221-97ce-d5406edc6f5a', 'pizza-assorti', 'Пицца Ассорти', 'Пицца с разнообразной начинкой', 35000, 'RUB', NULL, true, 5);
INSERT INTO public.menu_items (id, menu_version_id, category_id, public_id, name, description, price_minor, currency, image_asset_id, available, sort_order) VALUES ('9c73deed-7dfb-5b29-b9c8-15e5e4836797', 'db0d6a46-9145-55c7-af56-2795b510fa19', 'a1602dfb-0cf8-5221-97ce-d5406edc6f5a', 'pizza-margherita', 'Пицца Маргарита', 'Классическая пицца с томатами, сыром и соусом', 35000, 'RUB', NULL, true, 6);
INSERT INTO public.menu_items (id, menu_version_id, category_id, public_id, name, description, price_minor, currency, image_asset_id, available, sort_order) VALUES ('97b33d96-7771-53e3-b82e-fa9504e3e52f', 'db0d6a46-9145-55c7-af56-2795b510fa19', 'a1602dfb-0cf8-5221-97ce-d5406edc6f5a', 'pizza-chicken', 'Пицца Куриная', 'Пицца с курицей и сыром', 35000, 'RUB', NULL, true, 7);
INSERT INTO public.menu_items (id, menu_version_id, category_id, public_id, name, description, price_minor, currency, image_asset_id, available, sort_order) VALUES ('9052017b-63c1-54f0-aa8d-6fcad93a2c79', 'db0d6a46-9145-55c7-af56-2795b510fa19', 'a1602dfb-0cf8-5221-97ce-d5406edc6f5a', 'pizza-four-cheese', 'Пицца    4 сыра', 'Пицца с четырьмя видами сыра', 35000, 'RUB', NULL, true, 8);
INSERT INTO public.menu_items (id, menu_version_id, category_id, public_id, name, description, price_minor, currency, image_asset_id, available, sort_order) VALUES ('6ea51911-05d0-5f31-a2ff-af776fd91963', 'db0d6a46-9145-55c7-af56-2795b510fa19', 'a1602dfb-0cf8-5221-97ce-d5406edc6f5a', 'pizza-hunter', 'Пицца Охотничья', 'Пицца с мясными ингредиентами и сыром', 35000, 'RUB', NULL, true, 9);
INSERT INTO public.menu_items (id, menu_version_id, category_id, public_id, name, description, price_minor, currency, image_asset_id, available, sort_order) VALUES ('c4a5b380-8e40-5e63-84bb-2eaa5c96046c', 'db0d6a46-9145-55c7-af56-2795b510fa19', '7d5ee8f1-a863-5c3f-bce2-fe31bbddbb9e', 'french-fries', 'Картофель фри', 'Хрустящий картофель фри', 12000, 'RUB', NULL, true, 0);
INSERT INTO public.menu_items (id, menu_version_id, category_id, public_id, name, description, price_minor, currency, image_asset_id, available, sort_order) VALUES ('8d8cbf7b-f8ab-5a93-8ddc-4b3d4d37c43b', 'db0d6a46-9145-55c7-af56-2795b510fa19', '7d5ee8f1-a863-5c3f-bce2-fe31bbddbb9e', 'village-potato', 'Картофель по-деревенски', 'Картофельные дольки со специями', 12000, 'RUB', NULL, true, 1);
INSERT INTO public.menu_items (id, menu_version_id, category_id, public_id, name, description, price_minor, currency, image_asset_id, available, sort_order) VALUES ('fe137fc2-2733-5452-a592-e80d9d2e8a4f', 'db0d6a46-9145-55c7-af56-2795b510fa19', '7d5ee8f1-a863-5c3f-bce2-fe31bbddbb9e', 'nuggets', 'Наггетсы', 'Куриные наггетсы с хрустящей корочкой', 20000, 'RUB', NULL, true, 2);
INSERT INTO public.menu_items (id, menu_version_id, category_id, public_id, name, description, price_minor, currency, image_asset_id, available, sort_order) VALUES ('1e4b45f6-4101-5092-ab70-8cde59ccb969', 'db0d6a46-9145-55c7-af56-2795b510fa19', 'a2170b9f-0997-5260-a16b-7c9bebb0a9df', 'burger-beef', 'Бургер говяжий', 'Сочный бургер с говяжьей котлетой и фирменным соусом', 27000, 'RUB', NULL, true, 0);
INSERT INTO public.menu_items (id, menu_version_id, category_id, public_id, name, description, price_minor, currency, image_asset_id, available, sort_order) VALUES ('7a34cd0c-5135-5179-a71b-a469ea0f570a', 'db0d6a46-9145-55c7-af56-2795b510fa19', 'a2170b9f-0997-5260-a16b-7c9bebb0a9df', 'burger-chicken', 'Бургер куриный', 'Бургер с куриной котлетой и свежими овощами', 27000, 'RUB', NULL, true, 1);


--
-- Data for Name: menu_modifier_groups; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: menu_modifier_options; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: site_configuration; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.site_configuration (singleton_id, status, locale, currency, timezone, created_at, updated_at) VALUES (1, 'active', 'ru-RU', 'RUB', 'Europe/Moscow', '2026-07-14 22:42:53.333539+00', '2026-07-14 22:42:53.333539+00');


--
-- Data for Name: site_branding; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.site_branding (singleton_id, display_name, business_type, logo_asset_id, favicon_asset_id, design_tokens, localized_texts, hero, seo_metadata, updated_at, logo_alt) VALUES (1, 'Ресторан-кафе у Ризвана', 'Ресторан-кафе', '41eec9b7-b68e-5c82-ac50-a988df90b415', NULL, '{"textColor": "#2E2020", "fontFamily": "Georgia, Times New Roman, serif", "accentColor": "#C8A467", "borderRadius": "21px", "primaryColor": "#721F2A", "surfaceColor": "#FFFDF8", "mutedTextColor": "#7C6A64", "secondaryColor": "#4B1018", "backgroundColor": "#F7F0E3"}', '[{"locale": "ru-RU", "slogan": "Вкус Стамбула рядом с вами", "cartTitle": "Ваш заказ", "introText": "Выбирайте любимые блюда — мы соберём заказ и передадим его менеджеру в WhatsApp.", "shortName": "у Ризвана", "cuisineText": "Турецкая кухня", "displayName": "Ресторан-кафе у Ризвана", "orderTypeLegend": "Как хотите получить заказ?", "menuSectionTitle": "Выберите блюда", "businessTypeLabel": "Ресторан-кафе"}]', '{"title": "Вкус Стамбула рядом с вами", "benefits": [], "ctaLabel": "Смотреть меню", "subtitle": "Турецкая кухня"}', '{"title": "У ризвана — электронное меню", "description": "Электронное меню ресторан-кафе «У Ризвана»", "canonicalUrl": "https://stambul.localhost"}', '2026-07-14 22:42:53.333539+00', 'Логотип ресторан-кафе «У Ризвана»');


--
-- Data for Name: site_features; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.site_features (feature_key, enabled, config, updated_at) VALUES ('orders', true, '{"limits": {"maxNameLength": 80, "maxTotalItems": 200, "maxPhoneLength": 32, "maxItemQuantity": 99, "maxAddressLength": 240, "maxCommentLength": 500, "delayedOrderConfirmationMinutes": 15}}', '2026-07-14 22:42:53.333539+00');
INSERT INTO public.site_features (feature_key, enabled, config, updated_at) VALUES ('contacts', true, '{}', '2026-07-14 22:42:53.333539+00');
INSERT INTO public.site_features (feature_key, enabled, config, updated_at) VALUES ('geo_location', false, '{}', '2026-07-14 22:42:53.333539+00');
INSERT INTO public.site_features (feature_key, enabled, config, updated_at) VALUES ('social_links', false, '{}', '2026-07-14 22:42:53.333539+00');
INSERT INTO public.site_features (feature_key, enabled, config, updated_at) VALUES ('delivery', true, '{"label": "Доставка", "default": false}', '2026-07-14 22:42:53.333539+00');
INSERT INTO public.site_features (feature_key, enabled, config, updated_at) VALUES ('pickup', true, '{"label": "Самовывоз", "default": false}', '2026-07-14 22:42:53.333539+00');
INSERT INTO public.site_features (feature_key, enabled, config, updated_at) VALUES ('preorder', true, '{"label": "Предзаказ", "default": true}', '2026-07-14 22:42:53.333539+00');
INSERT INTO public.site_features (feature_key, enabled, config, updated_at) VALUES ('custom_theme', true, '{}', '2026-07-14 22:42:53.333539+00');
INSERT INTO public.site_features (feature_key, enabled, config, updated_at) VALUES ('whatsapp_fallback', true, '{}', '2026-07-14 22:42:53.333539+00');
INSERT INTO public.site_features (feature_key, enabled, config, updated_at) VALUES ('promotions', false, '{}', '2026-07-14 22:42:53.333539+00');
INSERT INTO public.site_features (feature_key, enabled, config, updated_at) VALUES ('loyalty', false, '{}', '2026-07-14 22:42:53.333539+00');
INSERT INTO public.site_features (feature_key, enabled, config, updated_at) VALUES ('reservations', false, '{}', '2026-07-14 22:42:53.333539+00');
INSERT INTO public.site_features (feature_key, enabled, config, updated_at) VALUES ('delivery_blocks', false, '{}', '2026-07-14 22:42:53.333539+00');


--
-- Data for Name: site_profile; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.site_profile (singleton_id, public_phone, public_phone_label, public_email, address_text, working_hours, working_hours_text, latitude, longitude, social_links, updated_at) VALUES (1, '+7 (989) 442-81-81', 'Позвонить ресторану', NULL, NULL, '[{"day": "monday", "opensAt": "10:00", "closesAt": "23:00"}, {"day": "tuesday", "opensAt": "10:00", "closesAt": "23:00"}, {"day": "wednesday", "opensAt": "10:00", "closesAt": "23:00"}, {"day": "thursday", "opensAt": "10:00", "closesAt": "23:00"}, {"day": "friday", "opensAt": "10:00", "closesAt": "23:00"}, {"day": "saturday", "opensAt": "10:00", "closesAt": "23:00"}, {"day": "sunday", "opensAt": "10:00", "closesAt": "23:00"}]', 'Ежедневно с 10:00 до 23:00', NULL, NULL, '[]', '2026-07-14 22:42:53.333539+00');


--
-- PostgreSQL database dump complete
--
