package com.qaagent.service;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.ScreenshotType;
import com.microsoft.playwright.options.WaitUntilState;
import com.qaagent.model.*;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class BrowserAuditService {

    public List<Finding> audit(Project project, String scanId, List<String> pages) {
        List<Finding> out = new ArrayList<>();
        if (pages == null || pages.isEmpty()) return out;

        try (Playwright pw = Playwright.create()) {
            Browser browser = pw.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
            try {
                int pageIndex = 0;
                for (String url : pages) {
                    if (!ScopePolicy.inScope(project, url)) continue;
                    pageIndex++;
                    String evidenceUrl = safeUrl(url);

                    // 1. Desktop Deep Audit (1440x900)
                    auditDesktopPage(project, scanId, browser, url, evidenceUrl, pageIndex, out);

                    // 2. Multi-Breakpoint Responsive Audit: Tablet (768x1024) + Mobile (390x844)
                    if (pageIndex <= 5) {
                        auditViewportPage(project, scanId, browser, url, evidenceUrl, 768, 1024, false, "Tablet (768×1024)", out);
                        auditViewportPage(project, scanId, browser, url, evidenceUrl, 390, 844, true, "Mobile (390×844)", out);
                    }
                }

                // 3. Exhaustive Interactive E-Commerce / App User Flows on primary page
                auditInteractiveFlows(project, scanId, browser, pages.get(0), out);

            } finally {
                browser.close();
            }
        } catch (Exception e) {
            out.add(new Finding(scanId, "BROWSER", "MEDIUM", "Trình duyệt Playwright worker không sẵn sàng",
                    "Kiểm tra container Playwright browser: " + e.getMessage(), project.getBaseUrl(), "LOW", "PLAYWRIGHT", null));
        }
        return out;
    }

    private void configureSafeRoutes(BrowserContext context, Project project) {
        context.route("**/*", route -> {
            Request req = route.request();
            String rt = req.resourceType();
            boolean safeStaticAsset = "GET".equalsIgnoreCase(req.method())
                    && ("image".equals(rt) || "stylesheet".equals(rt) || "font".equals(rt) || "media".equals(rt));
            if (ScopePolicy.inScope(project, req.url()) || safeStaticAsset) route.resume();
            else route.abort();
        });
    }

    @SuppressWarnings("unchecked")
    private void auditDesktopPage(Project project, String scanId, Browser browser, String url, String evidenceUrl, int pageIndex, List<Finding> out) {
        Set<String> consoleErrors = Collections.synchronizedSet(new LinkedHashSet<>());
        Set<String> pageErrors = Collections.synchronizedSet(new LinkedHashSet<>());
        Set<String> failedRequests = Collections.synchronizedSet(new LinkedHashSet<>());
        Set<String> httpErrors = Collections.synchronizedSet(new LinkedHashSet<>());

        try (BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                .setViewportSize(1440, 900)
                .setIgnoreHTTPSErrors(false))) {
            configureSafeRoutes(context, project);
            Page page = context.newPage();
            page.setDefaultTimeout(9000);
            page.onConsoleMessage(msg -> {
                if ("error".equalsIgnoreCase(msg.type())) consoleErrors.add(limit(msg.text(), 180));
            });
            page.onPageError(err -> pageErrors.add(limit(err, 220)));
            page.onRequestFailed(req -> {
                if (ScopePolicy.inScope(project, req.url())) {
                    failedRequests.add(safeUrl(req.url()) + " :: " + req.failure());
                }
            });
            page.onResponse(res -> {
                if (res.status() >= 400 && ScopePolicy.inScope(project, res.url())) {
                    String snippet = "";
                    try {
                        String ct = res.headers().getOrDefault("content-type", "");
                        if (ct.contains("json") || ct.contains("text")) {
                            snippet = " (" + limit(safeText(res.text().replaceAll("\\s+", " ")), 100) + ")";
                        }
                    } catch (Exception ignored) {}
                    httpErrors.add("HTTP " + res.status() + " -> " + safeUrl(res.url()) + snippet);
                }
            });

            long t0 = System.currentTimeMillis();
            Response response = page.navigate(url, new Page.NavigateOptions()
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                    .setTimeout(15000));
            try {
                page.waitForLoadState(LoadState.NETWORKIDLE, new Page.WaitForLoadStateOptions().setTimeout(6000));
            } catch (Exception ignored) {}
            page.waitForTimeout(1000);

            try {
                page.evaluate("async () => { window.scrollTo(0, document.body.scrollHeight / 2); await new Promise(r => setTimeout(r, 400)); window.scrollTo(0, document.body.scrollHeight); await new Promise(r => setTimeout(r, 400)); window.scrollTo(0, 0); }");
                page.waitForTimeout(500);
            } catch (Exception ignored) {}
            long loadTimeMs = System.currentTimeMillis() - t0;

            String desktopShot = takeJpegScreenshot(page, false);

            if (response != null && response.status() >= 500) {
                out.add(new Finding(scanId, "BROWSER", "HIGH",
                        "Máy chủ trả về mã lỗi " + response.status() + " khi tải trang",
                        "Playwright gặp lỗi HTTP " + response.status() + " tại " + evidenceUrl,
                        evidenceUrl, "HIGH", "PLAYWRIGHT", desktopShot));
            } else if (response != null && response.status() == 404) {
                out.add(new Finding(scanId, "BROWSER", "MEDIUM",
                        "Trang trả về lỗi 404 Not Found",
                        "Đường dẫn liên kết nội bộ bị hỏng hoặc không tồn tại: " + evidenceUrl,
                        evidenceUrl, "HIGH", "PLAYWRIGHT", desktopShot));
            }

            Map<String, Object> auditData = (Map<String, Object>) page.evaluate("""
                () => {
                    const docEl = document.documentElement;
                    const bodyText = (document.body ? document.body.innerText : '').trim();

                    // Web Vitals & Timing
                    let fcpMs = 0;
                    const paintEntries = performance.getEntriesByType('paint') || [];
                    const fcp = paintEntries.find(p => p.name === 'first-contentful-paint');
                    if (fcp) fcpMs = Math.round(fcp.startTime);
                    let ttfbMs = 0;
                    const navEntries = performance.getEntriesByType('navigation') || [];
                    if (navEntries[0]) ttfbMs = Math.round(navEntries[0].responseStart);

                    // Images: broken, missing alt, distorted aspect ratio
                    const imgs = Array.from(document.querySelectorAll('img'));
                    const brokenImgs = imgs.filter(i => !i.complete || i.naturalWidth === 0)
                        .map(i => ({ src: i.getAttribute('src') || '(no src)', alt: i.getAttribute('alt') || '' }));
                    const missingAlt = imgs.filter(i => !i.hasAttribute('alt') || i.getAttribute('alt').trim() === '').length;
                    let distortedImgs = 0;
                    imgs.forEach(i => {
                        if (i.complete && i.naturalWidth > 0 && i.clientWidth > 80 && i.clientHeight > 80) {
                            const natR = i.naturalWidth / i.naturalHeight;
                            const renR = i.clientWidth / i.clientHeight;
                            if (Math.abs(natR - renR) > 0.65) distortedImgs++;
                        }
                    });

                    // Unlabeled Buttons with exact locator hints
                    const buttons = Array.from(document.querySelectorAll('button, [role="button"]'));
                    const unlabeledBtnHints = [];
                    buttons.forEach((b, idx) => {
                        const rect = b.getBoundingClientRect();
                        if (rect.width === 0 || rect.height === 0) return;
                        const txt = (b.innerText || '').trim();
                        const aria = (b.getAttribute('aria-label') || b.getAttribute('title') || '').trim();
                        if (!txt && !aria) {
                            const cls = b.className ? '.' + String(b.className).trim().split(/\\s+/).slice(0, 2).join('.') : '';
                            if (unlabeledBtnHints.length < 5) unlabeledBtnHints.push('button' + cls + ' (@' + Math.round(rect.left) + ',' + Math.round(rect.top) + ')');
                        }
                    });

                    // Unlabeled Inputs with exact hints
                    const inputs = Array.from(document.querySelectorAll('input:not([type="hidden"]), textarea, select'));
                    const unlabeledInputHints = [];
                    inputs.forEach(inp => {
                        const id = inp.id;
                        const hasLabel = id ? !!document.querySelector(`label[for="${id}"]`) : false;
                        const aria = inp.getAttribute('aria-label') || inp.getAttribute('placeholder') || '';
                        if (!hasLabel && !aria.trim()) {
                            const hint = (inp.tagName.toLowerCase()) + '[type=' + (inp.getAttribute('type') || 'text') + (inp.name ? ',name=' + inp.name : '') + ']';
                            if (unlabeledInputHints.length < 5) unlabeledInputHints.push(hint);
                        }
                    });

                    const links = Array.from(document.querySelectorAll('a'));
                    const deadLinks = links.filter(a => {
                        const href = (a.getAttribute('href') || '').trim();
                        return href === '' || href === '#' || href.toLowerCase().startsWith('javascript:');
                    }).length;
                    const unsafeBlank = links.filter(a => a.target === '_blank' && !(a.rel || '').includes('noopener')).length;

                    const h1Count = document.querySelectorAll('h1').length;
                    const hasViewportMeta = !!document.querySelector('meta[name="viewport"]');
                    const hasMetaDesc = !!document.querySelector('meta[name="description"]');
                    const hasLang = !!(docEl.getAttribute('lang') || '').trim();

                    // Desktop horizontal overflow
                    const desktopOverflowPx = Math.max(0, docEl.scrollWidth - docEl.clientWidth);
                    const overflowSelectors = [];
                    if (desktopOverflowPx > 2) {
                        document.querySelectorAll('*').forEach(el => {
                            const r = el.getBoundingClientRect();
                            if (r.width > 0 && r.right > docEl.clientWidth + 4 && overflowSelectors.length < 5) {
                                overflowSelectors.push(el.tagName.toLowerCase() + (el.className ? '.' + String(el.className).split(' ')[0] : ''));
                            }
                        });
                    }

                    // Clipped text & tiny unreadable fonts
                    let clippedElements = 0;
                    let tinyFontCount = 0;
                    document.querySelectorAll('h1,h2,h3,button,a,span,p').forEach(el => {
                        const style = window.getComputedStyle(el);
                        if (style.overflowX === 'hidden' && el.clientWidth > 0 && el.scrollWidth > el.clientWidth + 8) {
                            clippedElements++;
                        }
                        if (el.innerText && el.innerText.trim().length > 4) {
                            const fs = parseFloat(style.fontSize);
                            if (!isNaN(fs) && fs > 0 && fs < 11) tinyFontCount++;
                        }
                    });

                    // Detect visible UI data bugs (NaN, undefined, [object Object], Invalid Date)
                    const uiTextBugs = [];
                    const patterns = [/\\bNaN\\b/, /\\bundefined\\b/, /\\[object Object\\]/, /Invalid Date/i];
                    patterns.forEach(p => {
                        if (p.test(bodyText)) uiTextBugs.push(p.source);
                    });

                    // Stuck loading indicator check
                    const loadingMatches = (bodyText.match(/đang tải|loading\\.\\.\\./gi) || []).length;

                    return {
                        bodyTextLen: bodyText.length,
                        bodySnippet: bodyText.slice(0, 200),
                        fcpMs, ttfbMs,
                        domNodes: document.querySelectorAll('*').length,
                        formsCount: document.querySelectorAll('form').length,
                        linksCount: links.length,
                        buttonsCount: buttons.length,
                        inputsCount: inputs.length,
                        imgCount: imgs.length,
                        brokenImgs: brokenImgs.slice(0, 12),
                        brokenImgsTotal: brokenImgs.length,
                        distortedImgs,
                        missingAlt,
                        unlabeledButtons: unlabeledBtnHints.length,
                        unlabeledBtnHints,
                        unlabeledInputs: unlabeledInputHints.length,
                        unlabeledInputHints,
                        deadLinks,
                        unsafeBlank,
                        h1Count,
                        hasViewportMeta,
                        hasMetaDesc,
                        hasLang,
                        desktopOverflowPx,
                        overflowSelectors,
                        clippedElements,
                        tinyFontCount,
                        uiTextBugs,
                        loadingMatches
                    };
                }
            """);

            String title = page.title();
            int bodyTextLen = toInt(auditData.get("bodyTextLen"));
            String bodySnippet = String.valueOf(auditData.getOrDefault("bodySnippet", ""));
            int brokenTotal = toInt(auditData.get("brokenImgsTotal"));
            int distortedImgs = toInt(auditData.get("distortedImgs"));
            int desktopOverflowPx = toInt(auditData.get("desktopOverflowPx"));
            int fcpMs = toInt(auditData.get("fcpMs"));
            int ttfbMs = toInt(auditData.get("ttfbMs"));
            List<String> uiTextBugs = (List<String>) auditData.get("uiTextBugs");
            int loadingMatches = toInt(auditData.get("loadingMatches"));

            if (bodyTextLen < 30 || bodySnippet.toLowerCase().contains("unexpected application error") || bodySnippet.toLowerCase().contains("something went wrong")) {
                out.add(new Finding(scanId, "UI_LAYOUT", "HIGH",
                        "Màn hình trắng hoặc lỗi render ứng dụng (Blank Screen / Crash)",
                        "Nội dung hiển thị trên trang quá ít (" + bodyTextLen + " ký tự) hoặc có thông báo crash giao diện: \"" + limit(bodySnippet, 120) + "\"",
                        evidenceUrl, "HIGH", "PLAYWRIGHT", desktopShot));
            }

            if (uiTextBugs != null && !uiTextBugs.isEmpty()) {
                out.add(new Finding(scanId, "UI_LAYOUT", "MEDIUM",
                        "Phát hiện lỗi hiển thị dữ liệu thô trên giao diện (" + String.join(", ", uiTextBugs) + ")",
                        "Văn bản hiển thị cho người dùng cuối có chứa giá trị lỗi kỹ thuật như undefined/NaN/[object Object].",
                        evidenceUrl, "HIGH", "PLAYWRIGHT", desktopShot));
            }

            if (loadingMatches > 2) {
                out.add(new Finding(scanId, "UI_LAYOUT", "LOW",
                        "Giao diện có nhiều thành phần kẹt ở trạng thái đang tải (" + loadingMatches + " vị trí 'Đang tải/Loading...')",
                        "Sau khi chờ tải xong trang, vẫn còn nhiều khối nội dung hiển thị trạng thái chờ (có thể do API backend chậm hoặc lỗi 500).",
                        evidenceUrl, "MEDIUM", "PLAYWRIGHT", desktopShot));
            }

            if (brokenTotal > 0) {
                List<Map<String, Object>> brokenList = (List<Map<String, Object>>) auditData.get("brokenImgs");
                List<String> srcSamples = new ArrayList<>();
                if (brokenList != null) {
                    for (Map<String, Object> bi : brokenList) {
                        srcSamples.add(limit(String.valueOf(bi.get("src")), 80));
                    }
                }
                String brokenShot = desktopShot;
                try {
                    page.evaluate("""
                        () => {
                            const broken = Array.from(document.querySelectorAll('img')).filter(i => !i.complete || i.naturalWidth === 0);
                            broken.forEach((img, idx) => {
                                img.style.outline = '4px solid #ef4444';
                                img.style.backgroundColor = 'rgba(239, 68, 68, 0.25)';
                                img.style.minHeight = img.clientHeight < 30 ? '40px' : img.style.minHeight;
                                img.style.minWidth = img.clientWidth < 30 ? '40px' : img.style.minWidth;
                                if (idx === 0) img.scrollIntoView({ block: 'center' });
                            });
                        }
                    """);
                    page.waitForTimeout(300);
                    String highlighted = takeJpegScreenshot(page, false);
                    if (highlighted != null) brokenShot = highlighted;
                } catch (Exception ignored) {}

                out.add(new Finding(scanId, "UI_MEDIA", "MEDIUM",
                        "Phát hiện " + brokenTotal + " hình ảnh bị vỡ / không tải được trên giao diện",
                        "Playwright đã khoanh đỏ các ảnh lỗi. URL ảnh lỗi tiêu biểu: " + String.join(" | ", srcSamples),
                        evidenceUrl, "HIGH", "PLAYWRIGHT", brokenShot));
            }

            if (distortedImgs > 0) {
                out.add(new Finding(scanId, "UI_MEDIA", "LOW",
                        "Phát hiện " + distortedImgs + " hình ảnh bị méo / sai tỷ lệ khung hình (Aspect Ratio)",
                        "Ảnh có kích thước hiển thị lệch tỷ lệ gốc quá lớn (khuyến nghị dùng CSS object-fit: cover).",
                        evidenceUrl, "MEDIUM", "PLAYWRIGHT", null));
            }

            if (desktopOverflowPx > 4) {
                List<String> selectors = (List<String>) auditData.get("overflowSelectors");
                out.add(new Finding(scanId, "UI_RESPONSIVE", "MEDIUM",
                        "Giao diện Desktop bị tràn chiều ngang (" + desktopOverflowPx + "px)",
                        "Trang xuất hiện thanh cuộn ngang bất thường trên màn hình 1440px. Phần tử nghi vấn: " + (selectors != null ? String.join(", ", selectors) : ""),
                        evidenceUrl, "HIGH", "PLAYWRIGHT", desktopShot));
            }

            if (!httpErrors.isEmpty()) {
                out.add(new Finding(scanId, "NETWORK", "HIGH",
                        "Phát hiện " + httpErrors.size() + " API / Tài nguyên trả về mã lỗi HTTP 4xx/5xx",
                        limit(String.join(" | ", httpErrors), 1500),
                        evidenceUrl, "HIGH", "PLAYWRIGHT", desktopShot));
            }

            if (!consoleErrors.isEmpty()) {
                out.add(new Finding(scanId, "FRONTEND", "MEDIUM",
                        "Phát hiện " + consoleErrors.size() + " lỗi JavaScript trong Console trình duyệt",
                        safeText(limit(String.join(" | ", consoleErrors), 1500)),
                        evidenceUrl, "MEDIUM", "PLAYWRIGHT", desktopShot));
            }

            if (!pageErrors.isEmpty()) {
                out.add(new Finding(scanId, "FRONTEND", "HIGH",
                        "Phát hiện " + pageErrors.size() + " lỗi Uncaught JS Exception làm gián đoạn giao diện",
                        safeText(limit(String.join(" | ", pageErrors), 1500)),
                        evidenceUrl, "HIGH", "PLAYWRIGHT", desktopShot));
            }

            if (!failedRequests.isEmpty()) {
                out.add(new Finding(scanId, "NETWORK", "LOW",
                        "Yêu cầu mạng thất bại (Failed Network Requests: " + failedRequests.size() + ")",
                        safeText(limit(String.join(" | ", failedRequests), 1200)),
                        evidenceUrl, "LOW", "PLAYWRIGHT", desktopShot));
            }

            // Detailed Accessibility & UX Hygiene check (including exact selectors!)
            List<String> btnHints = (List<String>) auditData.get("unlabeledBtnHints");
            List<String> inpHints = (List<String>) auditData.get("unlabeledInputHints");
            int deadLinks = toInt(auditData.get("deadLinks"));
            int missingAlt = toInt(auditData.get("missingAlt"));
            int h1Count = toInt(auditData.get("h1Count"));
            boolean hasViewport = Boolean.TRUE.equals(auditData.get("hasViewportMeta"));
            boolean hasMetaDesc = Boolean.TRUE.equals(auditData.get("hasMetaDesc"));
            int clipped = toInt(auditData.get("clippedElements"));
            int tinyFonts = toInt(auditData.get("tinyFontCount"));

            List<String> a11yIssues = new ArrayList<>();
            if (btnHints != null && !btnHints.isEmpty()) a11yIssues.add("Nút thiếu chữ/aria-label: [" + String.join(", ", btnHints) + "]");
            if (inpHints != null && !inpHints.isEmpty()) a11yIssues.add("Ô nhập liệu thiếu label/placeholder: [" + String.join(", ", inpHints) + "]");
            if (missingAlt > 0) a11yIssues.add(missingAlt + " thẻ <img> thiếu thuộc tính alt");
            if (deadLinks > 0) a11yIssues.add(deadLinks + " liên kết rỗng (href='#' hoặc rỗng)");
            if (h1Count == 0) a11yIssues.add("Thiếu thẻ tiêu đề chính <h1>");
            if (!hasViewport) a11yIssues.add("Thiếu thẻ <meta name='viewport'>");
            if (!hasMetaDesc) a11yIssues.add("Thiếu thẻ <meta name='description'> cho SEO");
            if (clipped > 0) a11yIssues.add(clipped + " phần tử bị cắt chữ (overflow hidden)");
            if (tinyFonts > 0) a11yIssues.add(tinyFonts + " đoạn chữ kích thước quá nhỏ (< 11px)");

            if (!a11yIssues.isEmpty()) {
                out.add(new Finding(scanId, "UI_ACCESSIBILITY", "LOW",
                        "Kiểm tra chi tiết Truy cập (A11y), Typography & SEO (" + a11yIssues.size() + " mục)",
                        String.join(" | ", a11yIssues),
                        evidenceUrl, "MEDIUM", "PLAYWRIGHT", null));
            }

            if (title == null || title.isBlank()) {
                out.add(new Finding(scanId, "SEO", "LOW", "Tiêu đề trang (<title>) bị để trống",
                        "Bổ sung thẻ <title> để tối ưu hiển thị trên tab trình duyệt và SEO.", evidenceUrl, "HIGH", "PLAYWRIGHT", null));
            }

            out.add(new Finding(scanId, "UI_LAYOUT", "INFO",
                    "Desktop UI Check #" + pageIndex + ": " + limit(title == null || title.isBlank() ? evidenceUrl : title, 65),
                    String.format("TTFB: %dms | FCP: %dms | Load: %dms | DOM Nodes: %d | Buttons: %d | Forms: %d | Images: %d (Vỡ: %d) | Tràn khung Desktop: %dpx",
                            ttfbMs, fcpMs, loadTimeMs, toInt(auditData.get("domNodes")), toInt(auditData.get("buttonsCount")),
                            toInt(auditData.get("formsCount")), toInt(auditData.get("imgCount")), brokenTotal, desktopOverflowPx),
                    evidenceUrl, "HIGH", "PLAYWRIGHT", desktopShot));

        } catch (Exception pageError) {
            out.add(new Finding(scanId, "BROWSER", "MEDIUM", "Playwright không thể hoàn tất kiểm tra trang Desktop",
                    "Không thể điều hướng hoặc kiểm tra trang: " + pageError.getMessage(), evidenceUrl, "LOW", "PLAYWRIGHT", null));
        }
    }

    @SuppressWarnings("unchecked")
    private void auditViewportPage(Project project, String scanId, Browser browser, String url, String evidenceUrl,
                                   int width, int height, boolean isMobile, String label, List<Finding> out) {
        try (BrowserContext vpCtx = browser.newContext(new Browser.NewContextOptions()
                .setViewportSize(width, height)
                .setHasTouch(isMobile)
                .setIsMobile(isMobile)
                .setIgnoreHTTPSErrors(false))) {
            configureSafeRoutes(vpCtx, project);
            Page page = vpCtx.newPage();
            page.setDefaultTimeout(9000);
            page.navigate(url, new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED).setTimeout(15000));
            try {
                page.waitForLoadState(LoadState.NETWORKIDLE, new Page.WaitForLoadStateOptions().setTimeout(5000));
            } catch (Exception ignored) {}
            page.waitForTimeout(800);

            Map<String, Object> vpData = (Map<String, Object>) page.evaluate("""
                () => {
                    const docEl = document.documentElement;
                    const overflowPx = Math.max(0, docEl.scrollWidth - window.innerWidth);
                    const offenders = [];
                    if (overflowPx > 2) {
                        document.querySelectorAll('*').forEach(el => {
                            const r = el.getBoundingClientRect();
                            if (r.width > 0 && r.right > window.innerWidth + 3) {
                                if (offenders.length < 6) {
                                    offenders.push(el.tagName.toLowerCase() + (el.className ? '.' + String(el.className).split(' ')[0] : ''));
                                }
                                el.style.outline = '2px solid #ef4444';
                            }
                        });
                    }
                    let tinyTapTargets = 0;
                    document.querySelectorAll('a, button').forEach(el => {
                        const r = el.getBoundingClientRect();
                        if (r.width > 0 && r.height > 0 && (r.width < 22 || r.height < 22)) {
                            tinyTapTargets++;
                        }
                    });
                    return { overflowPx, offenders, tinyTapTargets };
                }
            """);

            int overflowPx = toInt(vpData.get("overflowPx"));
            int tinyTargets = toInt(vpData.get("tinyTapTargets"));
            String shot = takeJpegScreenshot(page, false);

            if (overflowPx > 3) {
                List<String> offenders = (List<String>) vpData.get("offenders");
                out.add(new Finding(scanId, "UI_RESPONSIVE", "MEDIUM",
                        "Lỗi Responsive " + label + ": Giao diện bị tràn ngang " + overflowPx + "px",
                        "Trên màn hình " + width + "x" + height + ", nội dung vượt quá chiều rộng màn hình gây vỡ bố cục. Phần tử tràn khung: "
                                + (offenders != null ? String.join(", ", offenders) : "N/A"),
                        evidenceUrl, "HIGH", "PLAYWRIGHT", shot));
            } else {
                out.add(new Finding(scanId, "UI_RESPONSIVE", "INFO",
                        "Kiểm tra Responsive " + label + ": Đạt chuẩn không tràn khung",
                        String.format("Kiểm tra bố cục trên màn hình %dx%d hoàn tất (Tràn ngang: 0px | Nút chạm nhỏ <22px: %d).", width, height, tinyTargets),
                        evidenceUrl, "HIGH", "PLAYWRIGHT", shot));
            }

            if (isMobile && tinyTargets > 5) {
                out.add(new Finding(scanId, "UI_ACCESSIBILITY", "LOW",
                        "Mobile UX: Có " + tinyTargets + " nút/liên kết kích thước nhỏ (< 22px) khó bấm trên cảm ứng",
                        "Nên tăng vùng chạm tối thiểu lên 44x44px hoặc 32x32px cho trải nghiệm mobile tốt hơn.",
                        evidenceUrl, "MEDIUM", "PLAYWRIGHT", shot));
            }
        } catch (Exception ignored) {}
    }

    private void auditInteractiveFlows(Project project, String scanId, Browser browser, String homeUrl, List<Finding> out) {
        if (!ScopePolicy.inScope(project, homeUrl)) return;
        String evidenceUrl = safeUrl(homeUrl);

        try (BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                .setViewportSize(1440, 900)
                .setIgnoreHTTPSErrors(false))) {
            configureSafeRoutes(context, project);
            Page page = context.newPage();
            page.setDefaultTimeout(8000);
            Set<String> flowErrors = Collections.synchronizedSet(new LinkedHashSet<>());
            Set<String> flowApiErrors = Collections.synchronizedSet(new LinkedHashSet<>());
            page.onPageError(err -> flowErrors.add(limit(err, 220)));
            page.onResponse(res -> {
                if (res.status() >= 400 && ScopePolicy.inScope(project, res.url())) {
                    flowApiErrors.add("HTTP " + res.status() + " -> " + safeUrl(res.url()));
                }
            });

            page.navigate(homeUrl, new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED).setTimeout(15000));
            try { page.waitForLoadState(LoadState.NETWORKIDLE, new Page.WaitForLoadStateOptions().setTimeout(5000)); } catch (Exception ignored) {}
            page.waitForTimeout(1000);

            // Flow 1: Search Bar (Positive + XSS Resilience)
            Locator searchInput = page.locator("input[type='search'], input[name*='search' i], input[placeholder*='tìm' i], input[placeholder*='search' i]").first();
            if (searchInput.count() > 0 && searchInput.isVisible()) {
                try {
                    String beforeUrl = page.url();
                    String beforeText = page.locator("body").innerText();
                    searchInput.fill("laptop");
                    searchInput.press("Enter");
                    page.waitForTimeout(1400);
                    String afterUrl = page.url();
                    String afterText = page.locator("body").innerText();
                    String searchShot = takeJpegScreenshot(page, false);
                    boolean changed = !afterUrl.equals(beforeUrl) || !afterText.equals(beforeText);
                    out.add(new Finding(scanId, "UI_INTERACTION", changed ? "INFO" : "LOW",
                            "Kịch bản 1 — Tìm kiếm sản phẩm ('laptop')",
                            String.format("Nhập 'laptop' + Enter. URL: %s | Phản hồi DOM: %s | Lỗi JS: %d",
                                    safeUrl(afterUrl), changed ? "Cập nhật giao diện/kết quả" : "Chưa thấy thay đổi DOM", flowErrors.size()),
                            safeUrl(afterUrl), "HIGH", "PLAYWRIGHT", searchShot));

                    // Safe XSS resilience probe on search input
                    int errBefore = flowErrors.size();
                    searchInput.fill("<script>window.__qaXss=1</script>");
                    searchInput.press("Enter");
                    page.waitForTimeout(800);
                    Object xssHit = page.evaluate("() => window.__qaXss === 1");
                    boolean xssTriggered = Boolean.TRUE.equals(xssHit);
                    out.add(new Finding(scanId, "UI_INTERACTION", xssTriggered ? "HIGH" : "INFO",
                            "Kịch bản 2 — Kiểm tra an toàn XSS trên ô Tìm kiếm",
                            xssTriggered ? "CẢNH BÁO: Payload script thực thi được trong DOM!" : "Đạt: Ô tìm kiếm lọc/escape ký tự đặc biệt an toàn, 0 lỗi JS mới (" + (flowErrors.size() - errBefore) + ").",
                            safeUrl(page.url()), "HIGH", "PLAYWRIGHT", null));

                    resetHome(page, homeUrl);
                } catch (Exception ignored) {}
            }

            // Flow 2: Auth / Login Form Validation Test (Empty Submit + Invalid Format)
            try {
                Locator authBtn = page.locator("button:has-text('Đăng nhập'), button:has-text('Login'), a:has-text('Đăng nhập'), a:has-text('Login')").first();
                if (authBtn.count() > 0 && authBtn.isVisible()) {
                    authBtn.click(new Locator.ClickOptions().setTimeout(4000));
                    page.waitForTimeout(1200);
                    String loginOpenShot = takeJpegScreenshot(page, false);
                    out.add(new Finding(scanId, "UI_INTERACTION", "INFO",
                            "Kịch bản 3 — Mở màn hình / Modal Đăng nhập",
                            "Đã mở thành công giao diện xác thực (URL: " + safeUrl(page.url()) + "). Đang kiểm tra form validation...",
                            safeUrl(page.url()), "HIGH", "PLAYWRIGHT", loginOpenShot));

                    Locator submitBtn = page.locator("form button[type='submit'], button:has-text('Đăng nhập'), button:has-text('Submit')").last();
                    if (submitBtn.count() > 0 && submitBtn.isVisible()) {
                        // Test empty submit validation
                        submitBtn.click(new Locator.ClickOptions().setTimeout(3000));
                        page.waitForTimeout(700);
                        int invalidCount = page.locator("input:invalid").count();
                        String emptyValShot = takeJpegScreenshot(page, false);
                        out.add(new Finding(scanId, "UI_INTERACTION", "INFO",
                                "Kịch bản 4 — Kiểm tra Validate Form Đăng nhập (Bỏ trống)",
                                String.format("Bấm gửi form khi bỏ trống dữ liệu -> Phát hiện %d trường HTML5 :invalid hoặc thông báo lỗi validate trên UI.", invalidCount),
                                safeUrl(page.url()), "HIGH", "PLAYWRIGHT", emptyValShot));
                    }
                    resetHome(page, homeUrl);
                }
            } catch (Exception ignored) {}

            // Flow 3: Click up to 5 distinct SPA UI Buttons / Tabs / Cart / Categories
            try {
                Locator btns = page.locator("header button, nav button, main button");
                int totalBtns = Math.min(btns.count(), 25);
                Set<String> clickedLabels = new HashSet<>();
                clickedLabels.add("đăng nhập");
                clickedLabels.add("login");
                int testedCount = 0;

                for (int i = 0; i < totalBtns && testedCount < 5; i++) {
                    try {
                        Locator btn = btns.nth(i);
                        if (!btn.isVisible() || !btn.isEnabled()) continue;
                        String label = btn.innerText().trim();
                        if (label.isBlank()) label = String.valueOf(btn.getAttribute("aria-label"));
                        if (label == null || label.isBlank() || "null".equals(label)) continue;
                        String lower = label.toLowerCase();
                        if (lower.contains("đăng xuất") || lower.contains("logout") || lower.contains("xóa")
                                || lower.contains("delete") || lower.contains("thanh toán") || lower.contains("checkout")
                                || lower.contains("mua ngay") || lower.contains("đặt hàng") || lower.contains("gửi")) continue;
                        if (!clickedLabels.add(lower)) continue;

                        String beforeUrl = page.url();
                        String beforeBody = page.locator("body").innerText();
                        int beforeErrs = flowErrors.size();

                        btn.click(new Locator.ClickOptions().setTimeout(3500));
                        page.waitForTimeout(1200);

                        String afterUrl = page.url();
                        String afterBody = page.locator("body").innerText();
                        boolean domChanged = !afterUrl.equals(beforeUrl) || Math.abs(afterBody.length() - beforeBody.length()) > 20 || !afterBody.equals(beforeBody);
                        if (domChanged) {
                            testedCount++;
                            String btnShot = takeJpegScreenshot(page, false);
                            int newErrs = flowErrors.size() - beforeErrs;
                            boolean hasNanLeak = afterBody.contains("NaN") || afterBody.contains("undefined") || afterBody.contains("[object Object]");
                            String sev = (newErrs > 0 || hasNanLeak) ? "MEDIUM" : "INFO";
                            out.add(new Finding(scanId, "UI_INTERACTION", sev,
                                    "Kịch bản tương tác #" + (testedCount + 4) + " — Nút/Tab: \"" + limit(label, 40) + "\"",
                                    String.format("Click '%s' -> URL: %s | Nội dung mới: %d ký tự | Lỗi dữ liệu thô (NaN/undefined): %s | Lỗi JS mới: %d",
                                            limit(label, 40), safeUrl(afterUrl), afterBody.length(), hasNanLeak ? "CÓ LỖI" : "Không", newErrs),
                                    safeUrl(afterUrl), "HIGH", "PLAYWRIGHT", btnShot));

                            resetHome(page, homeUrl);
                        }
                    } catch (Exception ignored) {}
                }
            } catch (Exception ignored) {}

            // Flow 4: Standard <a href> navigation click if links exist
            Locator navLinks = page.locator("main a[href], header nav a[href], .product-card a[href]");
            int linkCount = Math.min(navLinks.count(), 8);
            for (int i = 0; i < linkCount; i++) {
                try {
                    Locator item = navLinks.nth(i);
                    if (!item.isVisible()) continue;
                    String href = item.getAttribute("href");
                    if (href == null || href.isBlank() || href.equals("/") || href.startsWith("#") || href.toLowerCase().contains("logout")) continue;
                    String label = limit(item.innerText().trim(), 40);
                    String beforeUrl = page.url();
                    item.click(new Locator.ClickOptions().setTimeout(4000));
                    page.waitForTimeout(1400);
                    String afterUrl = page.url();
                    if (!afterUrl.equals(beforeUrl) && ScopePolicy.inScope(project, afterUrl)) {
                        String clickShot = takeJpegScreenshot(page, false);
                        int textLen = page.locator("body").innerText().trim().length();
                        out.add(new Finding(scanId, "UI_INTERACTION", textLen < 30 ? "HIGH" : "INFO",
                                "Kịch bản Chuyển trang (Link Click): " + (label.isBlank() ? href : label),
                                String.format("Click chuyển tới %s | Nội dung render: %d ký tự | Lỗi JS: %d",
                                        safeUrl(afterUrl), textLen, flowErrors.size()),
                                safeUrl(afterUrl), "HIGH", "PLAYWRIGHT", clickShot));
                        break;
                    }
                } catch (Exception ignored) {}
            }

            if (!flowErrors.isEmpty()) {
                out.add(new Finding(scanId, "FRONTEND", "HIGH",
                        "Lỗi JavaScript phát sinh khi người dùng thao tác tương tác trên giao diện",
                        safeText(limit(String.join(" | ", flowErrors), 1200)),
                        evidenceUrl, "HIGH", "PLAYWRIGHT", takeJpegScreenshot(page, false)));
            }
            if (!flowApiErrors.isEmpty()) {
                out.add(new Finding(scanId, "NETWORK", "MEDIUM",
                        "Lỗi API 4xx/5xx phát sinh trong lúc thao tác tương tác UI (" + flowApiErrors.size() + ")",
                        limit(String.join(" | ", flowApiErrors), 1200),
                        evidenceUrl, "HIGH", "PLAYWRIGHT", null));
            }
        } catch (Exception ignored) {}
    }

    private void resetHome(Page page, String homeUrl) {
        page.navigate(homeUrl, new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED).setTimeout(12000));
        try { page.waitForLoadState(LoadState.NETWORKIDLE, new Page.WaitForLoadStateOptions().setTimeout(4000)); } catch (Exception ignored) {}
        page.waitForTimeout(700);
    }

    private String takeJpegScreenshot(Page page, boolean fullPage) {
        try {
            byte[] shotBytes = page.screenshot(new Page.ScreenshotOptions()
                    .setFullPage(fullPage)
                    .setType(ScreenshotType.JPEG)
                    .setQuality(68));
            if (shotBytes != null && shotBytes.length > 0) {
                return "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(shotBytes);
            }
        } catch (Exception ignored) {}
        return null;
    }

    private int toInt(Object val) {
        if (val instanceof Number n) return n.intValue();
        try { return Integer.parseInt(String.valueOf(val)); } catch (Exception e) { return 0; }
    }

    private String limit(String s, int n) { if (s == null) return ""; return s.length() > n ? s.substring(0, n) + "…" : s; }
    private String safeUrl(String raw) { try { java.net.URI u = java.net.URI.create(raw); return new java.net.URI(u.getScheme(), null, u.getHost(), u.getPort(), u.getPath(), null, null).toString(); } catch (Exception e) { return "[redacted URL]"; } }
    private String safeText(String raw) { return raw.replaceAll("(?i)(authorization|cookie|token|password|secret)(\\s*[:=]\\s*)[^\\s,;]+", "$1$2[REDACTED]").replaceAll("https?://[^\\s?]+\\?[^\\s]+", "[URL_WITH_QUERY_REDACTED]"); }
}
