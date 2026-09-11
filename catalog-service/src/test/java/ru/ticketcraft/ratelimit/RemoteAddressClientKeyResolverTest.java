package ru.ticketcraft.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import jakarta.servlet.http.HttpServletRequest;

class RemoteAddressClientKeyResolverTest {

    private final RemoteAddressClientKeyResolver resolver = new RemoteAddressClientKeyResolver();

    @Test
    void shouldUseRemoteAddressAsClientKey() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        request.setRemoteAddr("203.0.113.42");

        String clientKey = resolver.resolve(request);

        assertThat(clientKey).isEqualTo("203.0.113.42");
    }

    @Test
    void shouldIgnoreXForwardedForHeader() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        request.setRemoteAddr("203.0.113.42");

        request.addHeader("X-Forwarded-For", "198.51.100.10");

        String clientKey = resolver.resolve(request);

        assertThat(clientKey).isEqualTo("203.0.113.42");
    }

    @Test
    void shouldReturnUnknownForBlankRemoteAddress() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        request.setRemoteAddr(" ");

        String clientKey = resolver.resolve(request);

        assertThat(clientKey).isEqualTo("unknown");
    }

    @Test
    void shouldReturnUnknownForNullRemoteAddress() {
        HttpServletRequest request = mock(HttpServletRequest.class);

        when(request.getRemoteAddr()).thenReturn(null);

        String clientKey = resolver.resolve(request);

        assertThat(clientKey).isEqualTo("unknown");
    }
}