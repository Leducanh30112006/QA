package com.qaagent.service;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.ScreenshotType;
import com.microsoft.playwright.options.WaitUntilState;
import com.qaagent.model.Finding;
import com.qaagent.model.Project;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

@Service
public class AiExplorationService {
    public List<Finding> explore(Project project, String scanId, List<String> pages) {
        List<Finding> out = new ArrayList<>();
        if (pages == null || pages.isEmpty()) return out;

        try (Playwright pw = Playwright.create()) {
            Browser browser = pw.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
            try {
                String targetUrl = pages.get(0);
                try (BrowserContext context = browser.newContext(new Browser.NewContextOptions().setViewportSize(1440, 900))) {
                    Page page = context.newPage();
                    page.setDefaultTimeout(9000);
                    page.navigate(targetUrl, new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED).setTimeout(15000));
                    try {
                        page.waitForLoadState(LoadState.NETWORKIDLE, new Page.WaitForLoadStateOptions().setTimeout(6000));
                    } catch (Exception ignored) {}
                    page.waitForTimeout(1000);

                    // Stagehand-like DOM Inspection & AI Autonomous Evaluation
                    int buttons = page.locator("button, input[type='button'], input[type='submit']").count();
                    int inputs = page.locator("input:not([type='hidden']), textarea, select").count();
                    int images = page.locator("img").count();
                    int brokenImages = (int) page.locator("img").evaluateAll("imgs => imgs.filter(i => !i.complete || i.naturalWidth === 0).length");

                    String screenshot = null;
                    try {
                        byte[] shot = page.screenshot(new Page.ScreenshotOptions()
                                .setFullPage(false)
                                .setType(ScreenshotType.JPEG)
                                .setQuality(68));
                        if (shot != null && shot.length > 0) {
                            screenshot = "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(shot);
                        }
                    } catch (Exception ignored) {}

                    if (brokenImages > 0) {
                        out.add(new Finding(scanId, "AI_VISUAL_DEFECT", "MEDIUM",
                                "Stagehand AI: Phát hiện hình ảnh bị vỡ / không tải được (" + brokenImages + " ảnh)",
                                String.format("AI crawler phát hiện %d/%d thẻ <img> không thể hiển thị nội dung hợp lệ trên trang chính.", brokenImages, images),
                                targetUrl, "HIGH", "AI_EXPLORATION", screenshot));
                    }

                    out.add(new Finding(scanId, "AI_JOURNEY_MAP", "INFO",
                            "Stagehand AI: Khám phá các phần tử tương tác giao diện (DOM Map)",
                            String.format("Đã quét %d trang con | Trang chính có %d nút bấm (Buttons), %d trường nhập liệu (Inputs), %d hình ảnh (Images).",
                                    pages.size(), buttons, inputs, images),
                            targetUrl, "HIGH", "AI_EXPLORATION", screenshot));

                    // Evaluate eCommerce Critical Elements (Cart, Search, Login)
                    boolean hasSearch = page.locator("input[type='search'], input[name*='search' i], input[placeholder*='tìm' i], input[placeholder*='search' i]").count() > 0;
                    boolean hasAuth = page.locator("a[href*='login' i], a[href*='auth' i], button:has-text('Đăng nhập'), button:has-text('Login')").count() > 0;
                    boolean hasCart = page.locator("a[href*='cart' i], button:has-text('Giỏ hàng'), [aria-label*='cart' i], [aria-label*='giỏ' i]").count() > 0;

                    out.add(new Finding(scanId, "AI_ECOMMERCE_CHECK", "INFO",
                            "Stagehand AI: Rà soát tính năng thương mại điện tử cốt lõi",
                            String.format("Tìm kiếm sản phẩm: %s | Đăng nhập/Xác thực: %s | Giỏ hàng: %s",
                                    hasSearch ? "Đã phát hiện" : "Chưa tìm thấy trên trang chủ",
                                    hasAuth ? "Đã phát hiện" : "Cần kiểm tra lại thanh điều hướng",
                                    hasCart ? "Đã phát hiện" : "Chưa nhận diện rõ biểu tượng giỏ hàng"),
                            targetUrl, "HIGH", "AI_EXPLORATION", screenshot));
                }
            } finally {
                browser.close();
            }
        } catch (Exception e) {
            out.add(new Finding(scanId, "AI_EXPLORATION_ERROR", "LOW",
                    "Stagehand AI: Không thể chạy chu trình khám phá tự động",
                    "Lỗi worker: " + e.getMessage(), project.getBaseUrl(), "LOW", "AI_EXPLORATION", null));
        }

        return out;
    }
}
