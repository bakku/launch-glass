package dev.bakku.launchglass;

import java.net.URI;
import java.net.URISyntaxException;

final class UrlPolicy {
    enum Destination {
        INTERNAL,
        EXTERNAL,
        BLOCKED
    }

    private UrlPolicy() {}

    static Destination classify(String rawUrl, String allowedOrigin) {
        if (rawUrl == null || allowedOrigin == null) {
            return Destination.BLOCKED;
        }

        final URI uri;
        final URI allowed;
        try {
            uri = new URI(rawUrl);
            allowed = new URI(allowedOrigin);
        } catch (URISyntaxException exception) {
            return Destination.BLOCKED;
        }

        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null || host == null
                || allowed.getScheme() == null || allowed.getHost() == null) {
            return Destination.BLOCKED;
        }

        if (sameOrigin(uri, allowed)) {
            return Destination.INTERNAL;
        }

        if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
            return Destination.EXTERNAL;
        }

        return Destination.BLOCKED;
    }

    private static boolean sameOrigin(URI first, URI second) {
        return first.getScheme().equalsIgnoreCase(second.getScheme())
                && first.getHost().equalsIgnoreCase(second.getHost())
                && effectivePort(first) == effectivePort(second);
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() != -1) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }
}
