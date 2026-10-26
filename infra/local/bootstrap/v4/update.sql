-- Owner-requested fulfillment choices: delivery and pickup only.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM public.site_content_releases WHERE release_id = 'vam-pizza-order-types-v4')
       AND EXISTS (SELECT 1 FROM public.restaurants WHERE id = 'd4579042-8239-57df-b716-c27d3a6c213a') THEN
        UPDATE public.site_features
        SET enabled = (feature_key <> 'preorder'),
            config = jsonb_set(config, '{default}', to_jsonb(feature_key = 'delivery'), true),
            updated_at = now()
        WHERE feature_key IN ('delivery', 'pickup', 'preorder');
        INSERT INTO public.site_content_releases(release_id) VALUES ('vam-pizza-order-types-v4');
    END IF;
END $$;
