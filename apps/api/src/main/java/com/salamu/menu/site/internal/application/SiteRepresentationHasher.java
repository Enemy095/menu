package com.salamu.menu.site.internal.application;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Component;

@Component
class SiteRepresentationHasher {

    private final ObjectMapper objectMapper;

    SiteRepresentationHasher(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    String hash(SiteConfigurationData configuration) {
        try {
            byte[] canonical = objectMapper.writeValueAsBytes(configuration);
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (JacksonException | NoSuchAlgorithmException exception) {
            throw new SiteConfigurationCorruptException();
        }
    }
}
