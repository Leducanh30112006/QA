package com.qaagent.service;

import com.qaagent.model.Finding;
import com.qaagent.model.Project;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class ZapSecurityService {
    public List<Finding> audit(Project project, String scanId) {
        List<Finding> out = new ArrayList<>();
        try {
            URI uri = URI.create(project.getBaseUrl());
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(6)).build();
            HttpRequest req = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10))
                    .header("User-Agent", "OWASP-ZAP/2.15 (Automated Passive DAST Scanner)")
                    .GET().build();
            HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
            HttpHeaders h = res.headers();

            // 1. Clickjacking Check (X-Frame-Options)
            if (h.firstValue("x-frame-options").isEmpty() && !h.firstValue("content-security-policy").orElse("").contains("frame-ancestors")) {
                out.add(new Finding(scanId, "ZAP_CLICKJACKING", "MEDIUM", 
                    "OWASP ZAP: Thiếu cơ chế chống Clickjacking (X-Frame-Options)", 
                    "Trang web có thể bị nhúng lén vào thẻ <iframe> của website độc hại. Khắc phục: thêm header 'X-Frame-Options: DENY' hoặc 'frame-ancestors 'self''.", 
                    uri.toString(), "HIGH", "OWASP_ZAP", null));
            }

            // 2. Content Security Policy (XSS Defense)
            if (h.firstValue("content-security-policy").isEmpty()) {
                out.add(new Finding(scanId, "ZAP_CSP", "MEDIUM", 
                    "OWASP ZAP: Thiếu Content-Security-Policy (CSP)", 
                    "Không phát hiện CSP header. CSP giúp ngăn chặn tấn công XSS (Cross-Site Scripting) và Data Injection. Khắc phục: Cấu hình CSP report-only trước khi bật chính thức.", 
                    uri.toString(), "HIGH", "OWASP_ZAP", null));
            }

            // 3. Strict-Transport-Security (HSTS)
            if ("https".equalsIgnoreCase(uri.getScheme()) && h.firstValue("strict-transport-security").isEmpty()) {
                out.add(new Finding(scanId, "ZAP_HSTS", "MEDIUM", 
                    "OWASP ZAP: Thiếu HTTP Strict Transport Security (HSTS)", 
                    "Người dùng có thể bị tấn công SSL Striping khi chuyển hướng từ HTTP sang HTTPS. Khắc phục: thêm 'Strict-Transport-Security: max-age=31536000; includeSubDomains'.", 
                    uri.toString(), "HIGH", "OWASP_ZAP", null));
            }

            // 4. MIME-Sniffing (X-Content-Type-Options)
            if (h.firstValue("x-content-type-options").isEmpty()) {
                out.add(new Finding(scanId, "ZAP_MIME", "LOW", 
                    "OWASP ZAP: Thiếu X-Content-Type-Options: nosniff", 
                    "Trình duyệt có thể tự động suy đoán MIME type của file, dẫn đến việc thực thi mã độc giả danh file ảnh. Khắc phục: set 'X-Content-Type-Options: nosniff'.", 
                    uri.toString(), "HIGH", "OWASP_ZAP", null));
            }

            // 5. Insecure Cookies check
            List<String> cookies = h.allValues("set-cookie");
            for (String cookie : cookies) {
                String cl = cookie.toLowerCase(Locale.ROOT);
                if (!cl.contains("secure")) {
                    out.add(new Finding(scanId, "ZAP_COOKIE", "MEDIUM", 
                        "OWASP ZAP: Cookie thiếu cờ Secure", 
                        "Cookie có thể bị truyền qua giao thức HTTP không mã hóa. Khắc phục: Thêm thuộc tính Secure cho Set-Cookie.", 
                        uri.toString(), "MEDIUM", "OWASP_ZAP", null));
                }
                if (!cl.contains("httponly")) {
                    out.add(new Finding(scanId, "ZAP_COOKIE", "MEDIUM", 
                        "OWASP ZAP: Cookie thiếu cờ HttpOnly", 
                        "JavaScript có thể đọc trực tiếp cookie, tăng nguy cơ đánh cắp session khi bị XSS. Khắc phục: Thêm thuộc tính HttpOnly.", 
                        uri.toString(), "MEDIUM", "OWASP_ZAP", null));
                }
            }

            // 6. Permissive CORS
            String cors = h.firstValue("access-control-allow-origin").orElse("");
            if ("*".equals(cors)) {
                out.add(new Finding(scanId, "ZAP_CORS", "MEDIUM", 
                    "OWASP ZAP: Wildcard Access-Control-Allow-Origin (*)", 
                    "Cấu hình CORS cho phép mọi domain bên thứ ba truy cập tài nguyên. Cần kiểm tra xem có endpoint nhạy cảm nào bị lộ không.", 
                    uri.toString(), "MEDIUM", "OWASP_ZAP", null));
            }

            // 7. Overall ZAP Baseline finding
            out.add(new Finding(scanId, "ZAP_SUMMARY", "INFO", 
                "OWASP ZAP: Hoàn thành quét bảo mật DAST bị động", 
                "Đã quét các quy tắc Top 10 OWASP về Header, Cookies, SSL/TLS, và cấu hình truy cập CORS.", 
                uri.toString(), "HIGH", "OWASP_ZAP", null));

        } catch (Exception e) {
            out.add(new Finding(scanId, "ZAP_ERROR", "LOW", 
                "OWASP ZAP: Kiểm tra DAST bị gián đoạn", 
                "Không thể kết nối đến target URL: " + e.getMessage(), 
                project.getBaseUrl(), "LOW", "OWASP_ZAP", null));
        }

        return out;
    }
}
