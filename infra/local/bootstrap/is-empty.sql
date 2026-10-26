SELECT NOT EXISTS (
    SELECT 1 FROM public.site_configuration
    UNION ALL SELECT 1 FROM public.site_branding
    UNION ALL SELECT 1 FROM public.site_profile
    UNION ALL SELECT 1 FROM public.site_features
    UNION ALL SELECT 1 FROM public.assets
    UNION ALL SELECT 1 FROM public.restaurants
    UNION ALL SELECT 1 FROM public.menu_versions
    UNION ALL SELECT 1 FROM public.menu_categories
    UNION ALL SELECT 1 FROM public.menu_items
    UNION ALL SELECT 1 FROM public.menu_modifier_groups
    UNION ALL SELECT 1 FROM public.menu_modifier_options
    UNION ALL SELECT 1 FROM public.orders
    UNION ALL SELECT 1 FROM public.order_items
    UNION ALL SELECT 1 FROM public.order_status_history
    UNION ALL SELECT 1 FROM public.order_counter
    UNION ALL SELECT 1 FROM public.outbox_events
    UNION ALL SELECT 1 FROM public.notification_destinations
    UNION ALL SELECT 1 FROM public.notification_deliveries
) AS bootstrap_empty
\gset
