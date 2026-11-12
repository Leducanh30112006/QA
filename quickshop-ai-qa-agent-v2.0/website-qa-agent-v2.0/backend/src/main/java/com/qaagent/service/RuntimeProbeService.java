package com.qaagent.service;

import com.qaagent.model.*;
import org.springframework.stereotype.Service;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

@Service
public class RuntimeProbeService {
    public List<Finding> probe(Project p, String scanId) {
        List<Finding> out = new ArrayList<>();
        try {
            URI uri = ScopePolicy.validateProjectUrl(p.getBaseUrl());
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NEVER).build();
            HttpRequest req = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(12)).header("User-Agent","QuickShop-QA-Agent/2.0 (+authorized passive QA)").GET().build();
            long start = System.nanoTime();
            HttpResponse<String> res = client.send(req,HttpResponse.BodyHandlers.ofString());
            long ms = (System.nanoTime()-start)/1_000_000;
            var h = res.headers();
            if (!"https".equalsIgnoreCase(uri.getScheme())) out.add(f(scanId,"TLS","HIGH","Target does not use HTTPS","The configured base URL is not HTTPS. Enforce HTTPS and redirect HTTP to HTTPS.",uri.toString(),"HIGH"));
            checkHeader(h,"strict-transport-security","HSTS header missing","Enable HSTS after confirming HTTPS works for all intended hosts.","MEDIUM",uri,scanId,out);
            checkHeader(h,"content-security-policy","Content-Security-Policy missing","Define a CSP appropriate to the frontend; start with report-only mode to tune it.","MEDIUM",uri,scanId,out);
            checkHeader(h,"x-content-type-options","X-Content-Type-Options missing","Set X-Content-Type-Options: nosniff.","LOW",uri,scanId,out);
            checkHeader(h,"referrer-policy","Referrer-Policy missing","Set a restrictive Referrer-Policy such as strict-origin-when-cross-origin.","LOW",uri,scanId,out);
            checkHeader(h,"permissions-policy","Permissions-Policy missing","Explicitly disable browser capabilities not used by the application.","INFO",uri,scanId,out);
            String server=h.firstValue("server").orElse(""); if (!server.isBlank()) out.add(f(scanId,"INFO_DISCLOSURE","INFO","Server header reveals implementation","Review whether the Server header exposes unnecessary product/version details: " + server,uri.toString(),"HIGH"));
            String cors=h.firstValue("access-control-allow-origin").orElse("");
            if (cors.equals("*")) out.add(f(scanId,"CORS","MEDIUM","Wildcard Access-Control-Allow-Origin","Verify that public wildcard access is intentional and no credentialed/private data endpoints inherit it.",uri.toString(),"MEDIUM"));
            if (h.firstValue("set-cookie").isPresent()) {
                String cookie=h.firstValue("set-cookie").orElse("").toLowerCase(Locale.ROOT);
                if (!cookie.contains("secure")) out.add(f(scanId,"COOKIE","MEDIUM","Cookie missing Secure attribute","Review cookies set on HTTPS pages and add Secure where applicable.",uri.toString(),"MEDIUM"));
                if (!cookie.contains("httponly")) out.add(f(scanId,"COOKIE","MEDIUM","Cookie missing HttpOnly attribute","Set HttpOnly for session/authentication cookies unless JavaScript access is explicitly required.",uri.toString(),"MEDIUM"));
                if (!cookie.contains("samesite")) out.add(f(scanId,"COOKIE","LOW","Cookie missing SameSite attribute","Set an explicit SameSite policy and validate cross-site login/payment callback behavior.",uri.toString(),"MEDIUM"));
            }
            if (ms > 2500) out.add(f(scanId,"PERFORMANCE","LOW","Slow base-page response","Initial GET took " + ms + " ms. Repeat measurements from a controlled region and inspect server-side traces before attributing cause.",uri.toString(),"HIGH"));
            if (res.statusCode() >= 500) out.add(f(scanId,"AVAILABILITY","HIGH","Base page returned server error","HTTP status " + res.statusCode() + " from the configured base URL.",uri.toString(),"HIGH"));
            else out.add(f(scanId,"RUNTIME","INFO","Base URL reachable","HTTP " + res.statusCode() + " in " + ms + " ms. This is a single passive sample, not a load test.",uri.toString(),"HIGH"));
            if (res.statusCode() >= 300 && res.statusCode() < 400) {
                String loc=h.firstValue("location").orElse("");
                if (!loc.isBlank() && !ScopePolicy.inScope(p,uri.resolve(loc).toString())) out.add(f(scanId,"SCOPE","MEDIUM","Base URL redirects outside configured scope","Redirect target was not followed because it is outside the exact host/port/path scope.",loc,"HIGH"));
            }
        } catch (Exception e) {
            out.add(f(scanId,"RUNTIME","MEDIUM","Runtime baseline request failed","The passive GET probe failed. Check DNS, TLS, network reachability and worker logs.",p.getBaseUrl(),"LOW"));
        }
        return out;
    }
    private void checkHeader(HttpHeaders h,String key,String title,String desc,String severity,URI uri,String scan,List<Finding> out) {
        if (h.firstValue(key).isEmpty()) out.add(f(scan,"SECURITY_HEADERS",severity,title,desc,uri.toString(),"HIGH"));
    }
    private Finding f(String scan,String cat,String sev,String title,String desc,String url,String confidence) { return new Finding(scan,cat,sev,title,desc,url,confidence); }
}
