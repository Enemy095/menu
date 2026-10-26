-- Explicit owner-requested branding update for the existing single-client dataset.
-- Applied once; orders, prices, schedule overrides and notification settings survive.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM public.site_content_releases WHERE release_id = 'vam-pizza-v2')
       AND EXISTS (SELECT 1 FROM public.restaurants WHERE id = 'd4579042-8239-57df-b716-c27d3a6c213a') THEN
        UPDATE public.restaurants SET display_name = 'Вам пицца', updated_at = now()
        WHERE id = 'd4579042-8239-57df-b716-c27d3a6c213a';
        UPDATE public.site_branding
        SET display_name = 'Вам пицца', logo_alt = 'Вам пицца',
            localized_texts = (SELECT jsonb_agg(value || '{"displayName":"Вам пицца","shortName":"Вам пицца"}'::jsonb)
                FROM jsonb_array_elements(localized_texts)),
            seo_metadata = seo_metadata || '{"title":"Вам пицца — меню и заказ","description":"Вам пицца: пицца, роллы, бургеры и закуски"}'::jsonb,
            updated_at = now()
        WHERE singleton_id = 1;
        INSERT INTO public.site_content_releases(release_id) VALUES ('vam-pizza-v2');
    END IF;
END $$;
