package dev.bakku.launchglass;

import static dev.bakku.launchglass.UrlPolicy.Destination.BLOCKED;
import static dev.bakku.launchglass.UrlPolicy.Destination.EXTERNAL;
import static dev.bakku.launchglass.UrlPolicy.Destination.INTERNAL;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class UrlPolicyTest {
    private static final String EXAMPLE_ORIGIN = "https://service.example";

    private static UrlPolicy.Destination classify(String url) {
        return UrlPolicy.classify(url, EXAMPLE_ORIGIN);
    }

    @Test
    public void allowsExactConfiguredOrigin() {
        assertEquals(INTERNAL, classify("https://service.example"));
        assertEquals(INTERNAL, classify("https://service.example/path?query=yes#part"));
        assertEquals(INTERNAL, classify("https://SERVICE.EXAMPLE:443/path"));
    }

    @Test
    public void routesOtherWebOriginsExternally() {
        assertEquals(EXTERNAL, classify("https://example.com/docs"));
        assertEquals(EXTERNAL, classify("http://service.example"));
        assertEquals(EXTERNAL, classify("https://service.example:8443"));
    }

    @Test
    public void usesTheConfiguredServiceOrigin() {
        assertEquals(INTERNAL,
                UrlPolicy.classify("https://dashboard.example/view", "https://dashboard.example"));
        assertEquals(EXTERNAL,
                UrlPolicy.classify("https://service.example", "https://dashboard.example"));
    }

    @Test
    public void rejectsDeceptiveHostsAsInternal() {
        assertEquals(EXTERNAL, classify("https://service.example.example.com"));
        assertEquals(EXTERNAL, classify("https://service.example.evil.test"));
        assertEquals(EXTERNAL,
                classify("https://evil.test?next=https://service.example"));
        assertEquals(EXTERNAL,
                classify("https://service.example@evil.test/"));
    }

    @Test
    public void blocksUnexpectedSchemesAndMalformedUrls() {
        assertEquals(BLOCKED, classify("file:///etc/passwd"));
        assertEquals(BLOCKED, classify("content://provider/document/1"));
        assertEquals(BLOCKED, classify("javascript:alert(1)"));
        assertEquals(BLOCKED, classify("intent://service.example"));
        assertEquals(BLOCKED, classify("mailto:user@example.com"));
        assertEquals(BLOCKED, classify("not a url"));
        assertEquals(BLOCKED, classify(null));
        assertEquals(BLOCKED, UrlPolicy.classify("https://service.example", "not an origin"));
    }
}
