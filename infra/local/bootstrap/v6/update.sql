-- Publish revised roll descriptions without changing names/prices or order history.
DO $$
BEGIN
 IF NOT EXISTS (SELECT 1 FROM public.site_content_releases WHERE release_id = 'vam-pizza-roll-photos-v6')
 AND EXISTS (SELECT 1 FROM public.restaurants WHERE id = 'd4579042-8239-57df-b716-c27d3a6c213a') THEN
 IF (SELECT id FROM public.menu_versions WHERE restaurant_id = 'd4579042-8239-57df-b716-c27d3a6c213a' AND status = 'published') IS DISTINCT FROM 'd0d9081d-9d7e-5d65-93bd-7a46c4936866'::uuid THEN RAISE EXCEPTION 'LOCAL_BOOTSTRAP_UNEXPECTED_MENU'; END IF;
 IF EXISTS (SELECT 1 FROM public.menu_modifier_groups g JOIN public.menu_items i ON i.id = g.menu_item_id WHERE i.menu_version_id = 'd0d9081d-9d7e-5d65-93bd-7a46c4936866') THEN RAISE EXCEPTION 'LOCAL_BOOTSTRAP_UNEXPECTED_MODIFIERS'; END IF;
 INSERT INTO public.menu_versions(id,restaurant_id,version,status,published_at,created_at,updated_at,currency) SELECT '60ae073e-4c02-48d7-85dc-2e52e5c66ee3',restaurant_id,3,'draft',NULL,now(),now(),currency FROM public.menu_versions WHERE id = 'd0d9081d-9d7e-5d65-93bd-7a46c4936866';
 INSERT INTO public.menu_categories(id,menu_version_id,public_id,name,description,image_asset_id,sort_order,available) SELECT gen_random_uuid(),'60ae073e-4c02-48d7-85dc-2e52e5c66ee3',public_id,name,description,image_asset_id,sort_order,available FROM public.menu_categories WHERE menu_version_id = 'd0d9081d-9d7e-5d65-93bd-7a46c4936866';
 INSERT INTO public.menu_items(id,menu_version_id,category_id,public_id,name,description,price_minor,currency,image_asset_id,available,sort_order)
 SELECT gen_random_uuid(),'60ae073e-4c02-48d7-85dc-2e52e5c66ee3',nc.id,i.public_id,i.name,CASE i.public_id WHEN 'sake-maki' THEN 'Горячий ролл с лососем, огурцом и сливочным сыром в хрустящей панировке, с икрой масаго и соусом унаги' WHEN 'cezar-roll-chicken' THEN 'Ролл с курицей, салатом айсберг, томатом, пармезаном и соусом Цезарь' WHEN 'cezar-roll-shrimp' THEN 'Ролл с креветкой в кляре, салатом айсберг, томатом, пармезаном и соусом Цезарь' ELSE i.description END,i.price_minor,i.currency,i.image_asset_id,i.available,i.sort_order FROM public.menu_items i JOIN public.menu_categories oc ON oc.id = i.category_id JOIN public.menu_categories nc ON nc.menu_version_id = '60ae073e-4c02-48d7-85dc-2e52e5c66ee3' AND nc.public_id = oc.public_id WHERE i.menu_version_id = 'd0d9081d-9d7e-5d65-93bd-7a46c4936866';
 UPDATE public.menu_versions SET status = 'archived',updated_at = now() WHERE id = 'd0d9081d-9d7e-5d65-93bd-7a46c4936866';
 UPDATE public.menu_versions SET status = 'published',published_at = '2026-10-26 00:00:00+00',updated_at = now() WHERE id = '60ae073e-4c02-48d7-85dc-2e52e5c66ee3';
 INSERT INTO public.site_content_releases(release_id) VALUES ('vam-pizza-roll-photos-v6');
 END IF;
END $$;
