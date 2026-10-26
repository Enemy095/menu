-- Owner-provided pickup address and verified Yandex Maps placemark.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM public.site_content_releases WHERE release_id = 'vam-pizza-pickup-v3')
       AND EXISTS (SELECT 1 FROM public.restaurants WHERE id = 'd4579042-8239-57df-b716-c27d3a6c213a') THEN
        UPDATE public.site_profile
        SET address_text = 'Республика Дагестан, Хасавюрт, посёлок Садовый, 20-я улица, 12',
            latitude = 43.225478, longitude = 46.566266, updated_at = now()
        WHERE singleton_id = 1;
        UPDATE public.site_features SET enabled = true, updated_at = now() WHERE feature_key = 'geo_location';
        INSERT INTO public.site_content_releases(release_id) VALUES ('vam-pizza-pickup-v3');
    END IF;
END $$;
