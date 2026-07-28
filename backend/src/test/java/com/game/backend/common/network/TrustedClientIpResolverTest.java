package com.game.backend.common.network;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TrustedClientIpResolverTest {
    @Test
    void shouldUseRemoteAddressForDirectRequests() {
        TrustedClientIpResolver resolver = resolver(List.of("10.0.0.0/8"));
        MockHttpServletRequest request = request("192.168.1.10", "203.0.113.1");

        assertThat(resolver.resolve(request)).isEqualTo("192.168.1.10");
    }

    @Test
    void shouldUseRightMostUntrustedAddressFromTrustedProxyChain() {
        TrustedClientIpResolver resolver = resolver(List.of("10.0.0.0/8"));
        MockHttpServletRequest request = request("10.1.1.1", "203.0.113.1, 198.51.100.10");

        assertThat(resolver.resolve(request)).isEqualTo("198.51.100.10");
    }

    @Test
    void shouldRejectMalformedForwardedFor() {
        TrustedClientIpResolver resolver = resolver(List.of("10.0.0.0/8"));
        MockHttpServletRequest request = request("10.1.1.1", "203.0.113.1, example.com");

        assertThat(resolver.resolve(request)).isEqualTo("10.1.1.1");
    }

    private TrustedClientIpResolver resolver(List<String> cidrs) {
        TrustedProxyProperties properties = new TrustedProxyProperties();
        properties.setTrustedProxyCidrs(cidrs);
        return new TrustedClientIpResolver(properties);
    }

    private MockHttpServletRequest request(String remoteAddress, String forwardedFor) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddress);
        request.addHeader("X-Forwarded-For", forwardedFor);
        return request;
    }
}
