package com.acme.performance.auth.web;

import com.acme.performance.auth.model.CurrentUser;
import com.acme.performance.auth.service.TokenService;
import com.acme.performance.common.web.RequestIdFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthenticationFilterTest {
    private final TokenService tokenService = mock(TokenService.class);
    private final AuthenticationFilter filter = new AuthenticationFilter(tokenService, new ObjectMapper());

    @Test
    void rejectsMissingToken() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/me");
        request.setAttribute(RequestIdFilter.ATTRIBUTE, "request-1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        when(tokenService.authenticate(null)).thenReturn(Optional.empty());
        filter.doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("UNAUTHORIZED").contains("request-1");
    }

    @Test
    void attachesAuthenticatedUser() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/me");
        request.addHeader("Authorization", "Bearer valid-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        CurrentUser user = new CurrentUser(UUID.randomUUID(), "T001", "测试技术员", UUID.randomUUID(), Set.of("TECHNICIAN"), false);
        when(tokenService.authenticate("valid-token")).thenReturn(Optional.of(user));
        filter.doFilter(request, response, chain);
        assertThat(request.getAttribute(AuthenticationFilter.CURRENT_USER)).isEqualTo(user);
        verify(chain).doFilter(request, response);
    }
}
