package com.acme.performance.common.idempotency;

import com.acme.performance.auth.model.CurrentUser;
import com.acme.performance.auth.web.AuthenticationFilter;
import com.acme.performance.common.api.ApiResponse;
import com.acme.performance.common.web.RequestIdFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.util.Set;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class IdempotencyFilter extends OncePerRequestFilter {
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");
    private final IdempotencyService service;
    private final ObjectMapper mapper;

    public IdempotencyFilter(IdempotencyService service, ObjectMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return SAFE_METHODS.contains(request.getMethod()) || !path.startsWith("/api/")
                || path.startsWith("/api/auth/") || path.equals("/api/health");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        CurrentUser user = (CurrentUser) request.getAttribute(AuthenticationFilter.CURRENT_USER);
        if (user == null) { chain.doFilter(request, response); return; }
        String key = request.getHeader("Idempotency-Key");
        if (key == null || key.isBlank()) key = request.getHeader("X-Idempotency-Key");
        if (key == null || key.isBlank() || key.length() > 128) {
            failure(response, request, 400, "IDEMPOTENCY_KEY_REQUIRED", "写操作必须提供有效的Idempotency-Key");
            return;
        }
        String normalizedKey = key.trim();
        String hash;
        try {
            var digest=java.security.MessageDigest.getInstance("SHA-256");
            digest.update((java.util.Objects.toString(request.getQueryString(),"")+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            if(request.getContentType()!=null&&request.getContentType().startsWith("multipart/")) {
                var parts=new java.util.ArrayList<>(request.getParts());
                parts.sort(java.util.Comparator.comparing(jakarta.servlet.http.Part::getName).thenComparing(p->java.util.Objects.toString(p.getSubmittedFileName(),"")));
                for(var part:parts){digest.update((part.getName()+":"+part.getSubmittedFileName()+":"+part.getSize()+":"+part.getContentType()+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));try(var in=part.getInputStream()){byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1)digest.update(buf,0,n);}}
            } else {
                byte[] bytes=request.getInputStream().readNBytes(2*1024*1024+1);
                if(bytes.length>2*1024*1024){failure(response,request,413,"REQUEST_TOO_LARGE","请求内容过大");return;}
                digest.update(bytes);
                request=new ReplayRequest(request,bytes);
            }
            hash=java.util.HexFormat.of().formatHex(digest.digest());
        } catch(java.security.NoSuchAlgorithmException ex){throw new ServletException(ex);}
        catch(IllegalStateException ex){failure(response,request,413,"REQUEST_TOO_LARGE","上传内容超过允许大小");return;}
        catch(ServletException ex){failure(response,request,400,"INVALID_MULTIPART","上传格式不正确，请重新选择文件");return;}
        var start = service.start(user.userId(), normalizedKey, request.getMethod(), request.getRequestURI(),hash);
        if (start.state() == IdempotencyService.StartState.COMPLETED) {
            var stored = start.response();
            response.setStatus(stored.responseStatus());
            response.setContentType(stored.contentType());
            response.setHeader("Idempotency-Replayed", "true");
            response.getOutputStream().write(stored.responseBody());
            return;
        }
        if (start.state() == IdempotencyService.StartState.PROCESSING) {
            failure(response, request, 409, "IDEMPOTENCY_IN_PROGRESS", "相同请求正在处理中，请稍后重试");
            return;
        }
        if (start.state() == IdempotencyService.StartState.CONFLICT) {
            failure(response, request, 409, "IDEMPOTENCY_KEY_REUSED", "该请求标识已用于不同接口或内容，请重新发起操作");
            return;
        }
        ContentCachingResponseWrapper wrapper = new ContentCachingResponseWrapper(response);
        try {
            chain.doFilter(request, wrapper);
            byte[] body = wrapper.getContentAsByteArray();
            if (wrapper.getStatus() >= 200 && wrapper.getStatus() < 300) {
                service.complete(user.userId(), normalizedKey, wrapper.getStatus(), wrapper.getContentType(), body);
            } else {
                service.abandon(user.userId(), normalizedKey);
            }
            wrapper.copyBodyToResponse();
        } catch (Exception ex) {
            service.abandon(user.userId(), normalizedKey);
            throw ex;
        }
    }

    private void failure(HttpServletResponse response, HttpServletRequest request, int status, String code, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        mapper.writeValue(response.getWriter(), ApiResponse.failure(code, message,
                String.valueOf(request.getAttribute(RequestIdFilter.ATTRIBUTE))));
    }

    private static class ReplayRequest extends jakarta.servlet.http.HttpServletRequestWrapper {
        private final byte[] bytes;
        ReplayRequest(HttpServletRequest request,byte[] bytes){super(request);this.bytes=bytes;}
        @Override public jakarta.servlet.ServletInputStream getInputStream(){
            var input=new java.io.ByteArrayInputStream(bytes);
            return new jakarta.servlet.ServletInputStream(){
                public int read(){return input.read();}
                public boolean isFinished(){return input.available()==0;}
                public boolean isReady(){return true;}
                public void setReadListener(jakarta.servlet.ReadListener listener){throw new UnsupportedOperationException();}
            };
        }
        @Override public java.io.BufferedReader getReader(){return new java.io.BufferedReader(new java.io.InputStreamReader(getInputStream(),java.nio.charset.StandardCharsets.UTF_8));}
    }
}
