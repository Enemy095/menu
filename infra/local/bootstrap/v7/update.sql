-- Publish revised pizza descriptions without changing names/prices or order history.
DO $$
BEGIN
 IF NOT EXISTS (SELECT 1 FROM public.site_content_releases WHERE release_id = 'vam-pizza-pizza-photos-v7')
 AND EXISTS (SELECT 1 FROM public.restaurants WHERE id = 'd4579042-8239-57df-b716-c27d3a6c213a') THEN
 IF (SELECT id FROM public.menu_versions WHERE restaurant_id = 'd4579042-8239-57df-b716-c27d3a6c213a' AND status = 'published') IS DISTINCT FROM '60ae073e-4c02-48d7-85dc-2e52e5c66ee3'::uuid THEN RAISE EXCEPTION 'LOCAL_BOOTSTRAP_UNEXPECTED_MENU'; END IF;
 IF EXISTS (SELECT 1 FROM public.menu_modifier_groups g JOIN public.menu_items i ON i.id = g.menu_item_id WHERE i.menu_version_id = '60ae073e-4c02-48d7-85dc-2e52e5c66ee3') THEN RAISE EXCEPTION 'LOCAL_BOOTSTRAP_UNEXPECTED_MODIFIERS'; END IF;
 INSERT INTO public.menu_versions(id,restaurant_id,version,status,published_at,created_at,updated_at,currency) SELECT '0386e6dd-6d1b-4ce9-b5ed-23d7fc530e28',restaurant_id,4,'draft',NULL,now(),now(),currency FROM public.menu_versions WHERE id = '60ae073e-4c02-48d7-85dc-2e52e5c66ee3';
 INSERT INTO public.menu_categories(id,menu_version_id,public_id,name,description,image_asset_id,sort_order,available) SELECT gen_random_uuid(),'0386e6dd-6d1b-4ce9-b5ed-23d7fc530e28',public_id,name,description,image_asset_id,sort_order,available FROM public.menu_categories WHERE menu_version_id = '60ae073e-4c02-48d7-85dc-2e52e5c66ee3';
 INSERT INTO public.menu_items(id,menu_version_id,category_id,public_id,name,description,price_minor,currency,image_asset_id,available,sort_order)
 SELECT gen_random_uuid(),'0386e6dd-6d1b-4ce9-b5ed-23d7fc530e28',nc.id,i.public_id,i.name,CASE i.public_id WHEN 'pizza-caesar' THEN 'Пицца с курицей, салатом айсберг, томатами черри, моцареллой, пармезаном и соусом Цезарь' WHEN 'pizza-julienne' THEN 'Пицца с курицей, грибами и сливочным соусом' WHEN 'pizza-four-cheese' THEN 'Пицца с моцареллой, гаудой, пармезаном и дорблю на сливочной основе' ELSE i.description END,i.price_minor,i.currency,i.image_asset_id,i.available,i.sort_order FROM public.menu_items i JOIN public.menu_categories oc ON oc.id = i.category_id JOIN public.menu_categories nc ON nc.menu_version_id = '0386e6dd-6d1b-4ce9-b5ed-23d7fc530e28' AND nc.public_id = oc.public_id WHERE i.menu_version_id = '60ae073e-4c02-48d7-85dc-2e52e5c66ee3';
 UPDATE public.menu_versions SET status = 'archived',updated_at = now() WHERE id = '60ae073e-4c02-48d7-85dc-2e52e5c66ee3';
 UPDATE public.menu_versions SET status = 'published',published_at = '2026-10-26 00:00:00+00',updated_at = now() WHERE id = '0386e6dd-6d1b-4ce9-b5ed-23d7fc530e28';
 INSERT INTO public.site_content_releases(release_id) VALUES ('vam-pizza-pizza-photos-v7');
 END IF;
END $$;
