package com.salamu.menu.site.internal.web;

import java.time.format.DateTimeFormatter;
import java.util.Locale;

import com.salamu.menu.site.api.SiteConfigurationSnapshot;

import org.springframework.stereotype.Component;

@Component
class PublicConfigurationResponseMapper {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);

    PublicConfigurationResponse map(SiteConfigurationSnapshot source) {
        var branding = source.branding();
        var hero = branding.hero();
        var tokens = branding.designTokens();
        var seo = source.seo();
        var profile = source.profile();
        var features = source.features();
        var limits = source.limits();
        var schedule = source.schedule();
        var delivery = source.deliveryConfiguration();
        return new PublicConfigurationResponse(
                source.restaurantId().value(),
                new PublicConfigurationResponse.Branding(
                        branding.logoUrl(),
                        branding.logoAlt(),
                        branding.faviconUrl(),
                        new PublicConfigurationResponse.Hero(
                                hero.imageUrl(),
                                hero.imageAlt(),
                                hero.eyebrow(),
                                hero.title(),
                                hero.subtitle(),
                                hero.ctaLabel(),
                                hero.benefits().stream()
                                        .map(value -> new PublicConfigurationResponse.HeroBenefit(
                                                value.icon(), value.text()))
                                        .toList()),
                        new PublicConfigurationResponse.DesignTokens(
                                tokens.primaryColor(),
                                tokens.secondaryColor(),
                                tokens.accentColor(),
                                tokens.backgroundColor(),
                                tokens.surfaceColor(),
                                tokens.textColor(),
                                tokens.mutedTextColor(),
                                tokens.borderRadius(),
                                tokens.fontFamily()),
                        branding.localizedTexts().stream()
                                .map(value -> new PublicConfigurationResponse.LocalizedText(
                                        value.locale(),
                                        value.displayName(),
                                        value.shortName(),
                                        value.businessTypeLabel(),
                                        value.cuisineText(),
                                        value.slogan(),
                                        value.introText(),
                                        value.menuSectionTitle(),
                                        value.cartTitle(),
                                        value.orderTypeLegend()))
                                .toList()),
                new PublicConfigurationResponse.SeoMetadata(
                        seo.title(), seo.description(), seo.canonicalUrl(), seo.openGraphImageUrl()),
                new PublicConfigurationResponse.PublicProfile(
                        profile.publicPhone(),
                        profile.publicPhoneLabel(),
                        profile.publicEmail(),
                        profile.address(),
                        profile.location() == null
                                ? null
                                : new PublicConfigurationResponse.GeoLocation(
                                        profile.location().latitude(),
                                        profile.location().longitude(),
                                        profile.location().label()),
                        profile.socialLinks().stream()
                                .map(value -> new PublicConfigurationResponse.SocialLink(
                                        value.type().contractValue(), value.label(), value.url()))
                                .toList()),
                new PublicConfigurationResponse.Features(
                        features.orders(),
                        features.contacts(),
                        features.geoLocation(),
                        features.socialLinks(),
                        features.delivery(),
                        features.pickup(),
                        features.preorder(),
                        features.customTheme(),
                        features.whatsappFallback(),
                        features.promotions(),
                        features.loyalty(),
                        features.reservations(),
                        features.deliveryBlocks()),
                source.orderTypes().stream()
                        .map(value -> new PublicConfigurationResponse.AvailableOrderType(
                                value.code().contractValue(), value.label(), value.isDefault()))
                        .toList(),
                new PublicConfigurationResponse.Limits(
                        limits.maxItemQuantity(),
                        limits.maxTotalItems(),
                        limits.maxNameLength(),
                        limits.maxPhoneLength(),
                        limits.maxAddressLength(),
                        limits.maxCommentLength(),
                        limits.delayedOrderConfirmationMinutes()),
                new PublicConfigurationResponse.WeeklySchedule(
                        schedule.timezone(),
                        schedule.displayText(),
                        schedule.days().stream()
                                .map(day -> new PublicConfigurationResponse.WeeklyScheduleDay(
                                        day.dayOfWeek().name().toLowerCase(Locale.ROOT),
                                        day.intervals().stream()
                                                .map(interval -> new PublicConfigurationResponse.OpeningInterval(
                                                        TIME.format(interval.opensAt()),
                                                        TIME.format(interval.closesAt())))
                                                .toList()))
                                .toList()),
                new PublicConfigurationResponse.DeliveryConfiguration(
                        delivery.enabled(),
                        delivery.addressRequired(),
                        delivery.feePolicy().contractValue()),
                source.locale(),
                source.currency(),
                source.timezone());
    }
}
