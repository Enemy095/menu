package com.salamu.menu.tenancy.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class HostNormalizerTests {

    private final HostNormalizer normalizer = new HostNormalizer();

    @Test
    void normalizesCasePortTrailingDotAndIdn() {
        assertThat(normalizer.normalize("BÜCHER.Example.:8443"))
                .isEqualTo("xn--bcher-kva.example");
        assertThat(normalizer.normalize("EXAMPLE.TEST:443"))
                .isEqualTo("example.test");
    }

    @Test
    void acceptsCanonicalIpv4AndBracketedIpv6() {
        assertThat(normalizer.normalize("127.0.0.1:8080")).isEqualTo("127.0.0.1");
        assertThat(normalizer.normalize("[::1]:8080")).isEqualTo("0:0:0:0:0:0:0:1");
    }

    @Test
    void rejectsNumericShorthandAndNonCanonicalIpv4() {
        assertInvalid("127.1");
        assertInvalid("1.2.3");
        assertInvalid("1.2.3.4.5");
        assertInvalid("010.0.0.1");
        assertInvalid("256.0.0.1");
    }

    @Test
    void rejectsUnsafeOrInvalidHosts() {
        assertInvalid("tenant.example.test.evil.test/path");
        assertInvalid("user@tenant.example.test");
        assertInvalid("tenant..example.test");
        assertInvalid("-tenant.example.test");
        assertInvalid("tenant.example.test:99999");
        assertInvalid("tenant.example.test,evil.test");
        assertInvalid(" ");
    }

    private void assertInvalid(String value) {
        assertThatThrownBy(() -> normalizer.normalize(value))
                .isInstanceOf(HostNormalizer.InvalidHostException.class);
    }
}
