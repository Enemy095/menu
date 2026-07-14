package com.salamu.menu.site.api;

public enum SocialLinkType {
    WEBSITE("website"),
    INSTAGRAM("instagram"),
    TELEGRAM("telegram"),
    VK("vk"),
    FACEBOOK("facebook"),
    OTHER("other");

    private final String contractValue;

    SocialLinkType(String contractValue) {
        this.contractValue = contractValue;
    }

    public String contractValue() {
        return contractValue;
    }

    public static SocialLinkType fromContractValue(String value) {
        for (var candidate : values()) {
            if (candidate.contractValue.equals(value)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("Unsupported social link type");
    }
}
