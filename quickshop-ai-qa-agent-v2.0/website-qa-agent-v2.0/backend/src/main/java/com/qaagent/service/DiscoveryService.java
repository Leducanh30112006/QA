package com.qaagent.service;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitUntilState;
import com.qaagent.model.Project;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.*;

@Service
public class DiscoveryService {
    public List<String> discover(Project project) {
        LinkedHashSet<String> found = new LinkedHashSet<>();
        ArrayDeque<String> queue = new ArrayDeque<>();
        int cap = Math.min(Math.max(project.getMaxPages(), 1), 12);
        queue.add(normalizeUrl(project.getBaseUrl()));

        try (Playwright pw = Playwright.create()) {
            Browser browser = pw.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
            try {
                while (!queue.isEmpty() && found.size() < cap) {
                    String url = queue.removeFirst();
                    if (!ScopePolicy.inScope(project, url) || !found.add(url)) continue;

                    try (BrowserContext context = browser.newContext(new Browser.NewContextOptions().setViewportSize(1440, 900))) {
                        context.route("**/*", route -> {
                            Request request = route.request();
                            String rt = request.resourceType();
                            boolean safeAsset = "GET".equalsIgnoreCase(request.method())
                                    && ("image".equals(rt) || "stylesheet".equals(rt) || "font".equals(rt) || "media".equals(rt));
                            if (ScopePolicy.inScope(project, request.url()) || safeAsset) route.resume();
                            else route.abort();
                        });
                        Page page = context.newPage();
                        page.setDefaultTimeout(8000);
                        page.navigate(url, new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED).setTimeout(15000));
                        try {
                            page.waitForLoadState(LoadState.NETWORKIDLE, new Page.WaitForLoadStateOptions().setTimeout(6000));
                        } catch (Exception ignored) {}
                        page.waitForTimeout(1200);

                        // Extract standard and SPA-rendered links
                        Object raw = page.locator("a[href]").evaluateAll("els => els.map(e => e.href)");
                        if (raw instanceof List<?> links) {
                            for (Object item : links) {
                                String link = normalizeUrl(String.valueOf(item));
                                if (isValidCrawlLink(project, link) && !found.contains(link) && !queue.contains(link) && found.size() + queue.size() < cap) {
                                    queue.addLast(link);
                                }
                            }
                        }

                        // If SPA uses button/click navigation in header/nav, probe up to 5 safe nav items on homepage
                        if (found.size() == 1) {
                            try {
                                Locator navBtns = page.locator("header button, nav button, header [role='button'], nav [role='button']");
                                int btnCount = Math.min(navBtns.count(), 6);
                                for (int i = 0; i < btnCount && found.size() + queue.size() < cap; i++) {
                                    try {
                                        String txt = navBtns.nth(i).innerText().trim().toLowerCase();
                                        if (txt.isBlank() || txt.contains("logout") || txt.contains("đăng xuất") || txt.contains("delete") || txt.contains("xóa")) continue;
                                        String before = page.url();
                                        navBtns.nth(i).click(new Locator.ClickOptions().setTimeout(2500));
                                        page.waitForTimeout(600);
                                        String after = normalizeUrl(page.url());
                                        if (!after.equals(normalizeUrl(before)) && isValidCrawlLink(project, after) && !found.contains(after) && !queue.contains(after)) {
                                            queue.addLast(after);
                                        }
                                        if (!after.equals(normalizeUrl(url))) {
                                            page.navigate(url, new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED).setTimeout(10000));
                                            page.waitForTimeout(700);
                                        }
                                    } catch (Exception ignored) {}
                                }
                            } catch (Exception ignored) {}
                        }
                    } catch (Exception ignored) { /* Record page-level failures in BrowserAuditService. */ }
                }
            } finally {
                browser.close();
            }
        }
        return new ArrayList<>(found);
    }

    private boolean isValidCrawlLink(Project project, String link) {
        if (link == null || link.isBlank()) return false;
        String lower = link.toLowerCase();
        if (lower.startsWith("mailto:") || lower.startsWith("tel:") || lower.startsWith("javascript:")) return false;
        if (lower.contains("logout") || lower.contains("signout") || lower.contains("dang-xuat")) return false;
        if (lower.matches(".*\\.(png|jpg|jpeg|gif|svg|webp|pdf|zip|css|js|ico)(\\?.*)?$")) return false;
        return ScopePolicy.inScope(project, link);
    }

    private String normalizeUrl(String raw) {
        if (raw == null) return "";
        try {
            URI u = URI.create(raw.trim());
            String path = (u.getPath() == null || u.getPath().isEmpty()) ? "/" : u.getPath();
            if (path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length() - 1);
            return new URI(u.getScheme(), u.getAuthority(), path, u.getQuery(), null).toString();
        } catch (Exception e) {
            return raw.split("#")[0];
        }
    }
}
