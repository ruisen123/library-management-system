package com.library.filter;

import cn.hutool.json.JSONUtil;
import com.library.common.Result;
import com.library.common.ResultCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 请求体大小守卫
 *
 * 背景：spring.servlet.multipart.max-* 只约束 multipart 上传，对 application/json 等普通请求体
 * 完全无效（Tomcat 的 maxPostSize 也只作用于表单解析）。实测 25MB JSON 仍被正常受理，
 * 存在内存/带宽型 DoS 面，故对普通请求设 1MB 上限。
 * multipart（Excel 导入）由 Spring 的 max-file-size=5MB / max-request-size=20MB 约束，
 * 不能套用 1MB 的 JSON 上限——否则 1~5MB 的合法导入文件会在进入 Controller 前被误杀。
 *
 * 说明：优先按 Content-Length 判定；对 chunked（无 Content-Length）请求，
 * 先按上限 +1 字节缓冲读取，超出上限直接拒绝，未超限则以缓冲体继续传递——
 * 此前 chunked 请求完全绕过本守卫（实测 2MB chunked 被受理进入业务层），
 * 现改为两层都覆盖，不再依赖部署层兜底。
 */
@Component
@Order(Integer.MIN_VALUE)
public class RequestSizeLimitFilter extends OncePerRequestFilter {

    /** 单个请求体上限（字节）。取 1MB：远大于正常请求（公告正文上限 20000 字 ≈ 60KB），留足余量。 */
    private static final long MAX_BODY_BYTES = 1024L * 1024L;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // 文件上传交给 Spring multipart 的 5MB/20MB 限制；普通 JSON 才适用 1MB 守卫
        String contentType = request.getContentType();
        if (contentType != null && contentType.toLowerCase().startsWith("multipart/form-data")) {
            chain.doFilter(request, response);
            return;
        }
        long length = request.getContentLengthLong();
        if (length > MAX_BODY_BYTES) {
            writeTooLarge(response);
            return;
        }
        // chunked（无 Content-Length）无法预判大小：按上限+1 缓冲读取后再决定
        String transferEncoding = request.getHeader("Transfer-Encoding");
        boolean chunked = transferEncoding != null
                && transferEncoding.toLowerCase().contains("chunked");
        if (length < 0 && chunked) {
            byte[] body = readAtMost(request.getInputStream(), (int) (MAX_BODY_BYTES + 1));
            if (body.length > MAX_BODY_BYTES) {
                writeTooLarge(response);
                return;
            }
            chain.doFilter(new CachedBodyRequest(request, body), response);
            return;
        }
        chain.doFilter(request, response);
    }

    /** 与全站契约一致：HTTP 200 承载业务码，前端只需读取 code/msg */
    private void writeTooLarge(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(JSONUtil.toJsonStr(Result.fail(
                ResultCode.PAYLOAD_TOO_LARGE.getCode(), ResultCode.PAYLOAD_TOO_LARGE.getMsg())));
    }

    /** 最多读取 limit 字节（多读 1 字节用于判断是否超限），避免恶意超大 chunked 请求打满内存 */
    private static byte[] readAtMost(jakarta.servlet.ServletInputStream in, int limit) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(Math.min(limit, 8192));
        byte[] buf = new byte[8192];
        int total = 0;
        int n;
        while (total < limit && (n = in.read(buf, 0, Math.min(buf.length, limit - total))) != -1) {
            out.write(buf, 0, n);
            total += n;
        }
        return out.toByteArray();
    }

    /** 已缓冲请求体，供下游按普通请求读取（chunked 场景） */
    private static final class CachedBodyRequest extends jakarta.servlet.http.HttpServletRequestWrapper {

        private final byte[] body;

        CachedBodyRequest(jakarta.servlet.http.HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public jakarta.servlet.ServletInputStream getInputStream() {
            java.io.ByteArrayInputStream in = new java.io.ByteArrayInputStream(body);
            return new jakarta.servlet.ServletInputStream() {
                @Override
                public int read() {
                    return in.read();
                }

                @Override
                public int read(byte[] b, int off, int len) {
                    return in.read(b, off, len);
                }

                @Override
                public boolean isFinished() {
                    return in.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(jakarta.servlet.ReadListener listener) {
                    throw new UnsupportedOperationException("异步读取未启用");
                }
            };
        }

        @Override
        public java.io.BufferedReader getReader() {
            return new java.io.BufferedReader(new java.io.InputStreamReader(getInputStream(),
                    java.nio.charset.StandardCharsets.UTF_8));
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }
    }
}
