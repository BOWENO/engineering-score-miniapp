package com.acme.performance.common.idempotency;

import com.acme.performance.auth.model.CurrentUser;
import com.acme.performance.auth.web.AuthenticationFilter;
import com.acme.performance.common.web.RequestIdFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class IdempotencyFilterTest {
    private final IdempotencyService service=mock(IdempotencyService.class);
    private final IdempotencyFilter filter=new IdempotencyFilter(service,new ObjectMapper());

    @Test void rejectsWriteWithoutKey() throws Exception {
        MockHttpServletRequest request=request(); MockHttpServletResponse response=new MockHttpServletResponse();
        filter.doFilter(request,response,mock(FilterChain.class));
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString()).contains("IDEMPOTENCY_KEY_REQUIRED");
    }

    @Test void storesSuccessfulResponse() throws Exception {
        MockHttpServletRequest request=request();request.addHeader("Idempotency-Key","request-key");
        MockHttpServletResponse response=new MockHttpServletResponse();
        when(service.start(any(),eq("request-key"),eq("POST"),eq("/api/incidents"),anyString()))
                .thenReturn(new IdempotencyService.StartResult(IdempotencyService.StartState.STARTED,null));
        FilterChain chain=(req,res)->{res.setContentType("application/json");res.getOutputStream().write("{\"code\":\"OK\"}".getBytes(StandardCharsets.UTF_8));};
        filter.doFilter(request,response,chain);
        assertThat(response.getStatus()).isEqualTo(200); assertThat(response.getContentAsString()).contains("OK");
        verify(service).complete(any(),eq("request-key"),eq(200),contains("application/json"),any(byte[].class));
    }

    @Test void acceptsWechatCompatibleFallbackHeader() throws Exception {
        MockHttpServletRequest request=request();request.addHeader("X-Idempotency-Key","wechat-request-key");
        MockHttpServletResponse response=new MockHttpServletResponse();
        when(service.start(any(),eq("wechat-request-key"),eq("POST"),eq("/api/incidents"),anyString()))
                .thenReturn(new IdempotencyService.StartResult(IdempotencyService.StartState.STARTED,null));
        FilterChain chain=(req,res)->{res.setContentType("application/json");res.getOutputStream().write("{\"code\":\"OK\"}".getBytes(StandardCharsets.UTF_8));};
        filter.doFilter(request,response,chain);
        assertThat(response.getStatus()).isEqualTo(200);
        verify(service).complete(any(),eq("wechat-request-key"),eq(200),any(),any(byte[].class));
    }

    @Test void fingerprintsMultipartBytesAndPreservesParts() throws Exception {
        when(service.start(any(),anyString(),anyString(),anyString(),anyString()))
            .thenReturn(new IdempotencyService.StartResult(IdempotencyService.StartState.STARTED,null));
        for(byte value:new byte[]{1,2}){
            var request=request();request.addHeader("Idempotency-Key","multipart");
            request.setContentType("multipart/form-data; boundary=test");
            request.addPart(new org.springframework.mock.web.MockPart("file","pixel.png",new byte[]{value}));
            filter.doFilter(request,new MockHttpServletResponse(),(req,res)->{
                assertThat(((jakarta.servlet.http.HttpServletRequest)req).getPart("file").getInputStream().read()).isEqualTo(value);
            });
        }
        var hashes=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(service,times(2)).start(any(),eq("multipart"),anyString(),anyString(),hashes.capture());
        assertThat(hashes.getAllValues().get(0)).isNotEqualTo(hashes.getAllValues().get(1));
    }

    @Test void passesJsonBodyToController() throws Exception {
        when(service.start(any(),anyString(),anyString(),anyString(),anyString()))
            .thenReturn(new IdempotencyService.StartResult(IdempotencyService.StartState.STARTED,null));
        var request=request();request.addHeader("Idempotency-Key","body");request.setContentType("application/json");
        request.setContent("{\"value\":7}".getBytes(StandardCharsets.UTF_8));
        filter.doFilter(request,new MockHttpServletResponse(),(req,res)->assertThat(new String(req.getInputStream().readAllBytes(),StandardCharsets.UTF_8)).isEqualTo("{\"value\":7}"));
    }

    private MockHttpServletRequest request(){MockHttpServletRequest request=new MockHttpServletRequest("POST","/api/incidents");request.setAttribute(RequestIdFilter.ATTRIBUTE,"request-1");request.setAttribute(AuthenticationFilter.CURRENT_USER,new CurrentUser(UUID.randomUUID(),"T001","测试人员",UUID.randomUUID(),Set.of("TECHNICIAN"),false));return request;}
}
