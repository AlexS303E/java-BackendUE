package com.game.backend.common.network;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

/** Resolves a client address only through explicitly trusted reverse proxies. */
@Component
public class TrustedClientIpResolver {
    private static final String FORWARDED_FOR_HEADER = "X-Forwarded-For";

    private final TrustedProxyProperties properties;

    public TrustedClientIpResolver(TrustedProxyProperties properties) {
        this.properties = properties;
    }

    public String resolve(HttpServletRequest request) {
        String remoteAddress = request.getRemoteAddr();
        if (!isTrustedProxy(remoteAddress)) {
            return remoteAddress;
        }

        String forwardedFor = request.getHeader(FORWARDED_FOR_HEADER);
        if (forwardedFor == null || forwardedFor.isBlank()) {
            return remoteAddress;
        }

        List<String> chain = parseForwardedFor(forwardedFor);
        if (chain.isEmpty()) {
            return remoteAddress;
        }

        for (int index = chain.size() - 1; index >= 0; index--) {
            String address = chain.get(index);
            if (!isTrustedProxy(address)) {
                return address;
            }
        }
        return chain.getFirst();
    }

    private List<String> parseForwardedFor(String forwardedFor) {
        List<String> chain = new ArrayList<>();
        for (String value : forwardedFor.split(",")) {
            String address = value.trim();
            if (!isNumericAddress(address)) {
                return List.of();
            }
            chain.add(address);
        }
        return chain;
    }

    private boolean isTrustedProxy(String address) {
        return properties.getTrustedProxyCidrs().stream().anyMatch(cidr -> matchesCidr(address, cidr));
    }

    private boolean matchesCidr(String addressValue, String cidr) {
        if (cidr == null || cidr.isBlank()) {
            return false;
        }
        String value = cidr.trim();
        if (!value.contains("/")) {
            return value.equals(addressValue);
        }
        try {
            String[] parts = value.split("/", 2);
            if (!isNumericAddress(addressValue) || !isNumericAddress(parts[0])) {
                return false;
            }
            byte[] address = InetAddress.getByName(addressValue).getAddress();
            byte[] network = InetAddress.getByName(parts[0]).getAddress();
            if (address.length != network.length) {
                return false;
            }
            int prefix = Integer.parseInt(parts[1]);
            if (prefix < 0 || prefix > address.length * 8) {
                return false;
            }
            int fullBytes = prefix / 8;
            int remainingBits = prefix % 8;
            for (int index = 0; index < fullBytes; index++) {
                if (address[index] != network[index]) {
                    return false;
                }
            }
            if (remainingBits == 0) {
                return true;
            }
            int mask = (0xFF << (8 - remainingBits)) & 0xFF;
            return (address[fullBytes] & mask) == (network[fullBytes] & mask);
        } catch (Exception exception) {
            return false;
        }
    }

    private boolean isNumericAddress(String value) {
        return value != null
            && !value.isBlank()
            && value.matches("[0-9a-fA-F:.]+");
    }
}
