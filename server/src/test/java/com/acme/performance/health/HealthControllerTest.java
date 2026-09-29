package com.acme.performance.health;

import com.acme.performance.common.web.RequestIdFilter;
import com.acme.performance.auth.service.TokenService;
import com.acme.performance.common.idempotency.IdempotencyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(HealthController.class)
class HealthControllerTest {
    @Autowired MockMvc mockMvc;
    @MockBean TokenService tokenService;
    @MockBean IdempotencyService idempotencyService;

    @Test
    void returnsHealthAndRequestId() throws Exception {
        mockMvc.perform(get("/api/health").header(RequestIdFilter.HEADER, "test-request-1"))
                .andExpect(status().isOk())
                .andExpect(header().string(RequestIdFilter.HEADER, "test-request-1"))
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.status").value("UP"))
                .andExpect(jsonPath("$.requestId").value("test-request-1"));
    }
}
