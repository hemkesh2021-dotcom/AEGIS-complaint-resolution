const { chromium } = require("playwright");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const { createServer } = require("node:http");

(async () => {
  const root = path.resolve(__dirname, "..");
  const server = createServer((req, res) => {
    const file = path.join(root, new URL(req.url, "http://localhost").pathname);
    if (
      !file.startsWith(root + path.sep) ||
      !fs.existsSync(file) ||
      fs.statSync(file).isDirectory()
    ) {
      res.writeHead(404);
      return res.end();
    }
    res.setHeader(
      "Content-Type",
      file.endsWith(".css")
        ? "text/css"
        : file.endsWith(".js")
          ? "text/javascript"
          : "text/html",
    );
    res.end(fs.readFileSync(file));
  });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  const base = `http://127.0.0.1:${server.address().port}`;
  const browser = await chromium.launch({
    headless: true,
    ...(process.env.CHROME_PATH
      ? { executablePath: process.env.CHROME_PATH }
      : {}),
    args: [
      "--enable-webgl",
      "--ignore-gpu-blocklist",
      "--use-angle=swiftshader",
    ],
  });
  const artifacts = process.env.AEGIS_TEST_ARTIFACTS || "/tmp/aegis-v4-browser";
  fs.mkdirSync(artifacts, { recursive: true });
  const errors = [];
  try {
    const page = await browser.newPage({
      viewport: { width: 1440, height: 1000 },
    });
    page.on("pageerror", (e) => errors.push(e.message));
    const fixture = [
      {
        complaintId: "CMP-1001",
        customerName: "Example customer",
        category: "Credit card",
        urgency: "CRITICAL",
        status: "IN_REVIEW",
        confidence: 0.95,
        escalate: true,
        resolutionDue: "2026-01-01T12:00",
        createdAt: "2026-01-01T10:00Z",
      },
      {
        complaintId: "CMP-1002",
        customerName: "Demo customer",
        category: "Mortgage",
        urgency: "NORMAL",
        status: "SENT",
        confidence: 0.89,
        escalate: false,
        resolutionDue: "2026-01-03T12:00",
        createdAt: "2026-01-02T10:00Z",
      },
    ];
    await page.route("http://localhost:8080/**", async (route) => {
      const url = route.request().url();
      const data = url.endsWith("/api/complaints")
        ? fixture
        : url.endsWith("/api/providers")
          ? {
              provider: "anthropic",
              model: "account-model",
              configured: true,
              successfulCalls: 4,
              failedCalls: 0,
              timeoutSeconds: 30,
            }
          : url.endsWith("/test")
            ? { ok: true }
            : { status: "UP" };
      await route.fulfill({ json: data });
    });
    await page.addInitScript(() => {
      localStorage.setItem("aegisKey", "fixture-key");
      localStorage.setItem("aegisTheme", "light");
    });
    await page.goto(base + "/index.html");
    await page.locator("#queueBody [data-case]").first().waitFor();
    assert.equal(await page.locator("#queueBody [data-case]").count(), 1);
    await page.locator('[data-f="all"]').click();
    assert.equal(await page.locator("#queueBody [data-case]").count(), 2);
    await page.locator("#queueSearch").fill("Mortgage");
    assert.equal(await page.locator("#queueBody [data-case]").count(), 1);
    await page.locator("#saveView").click();
    await page.locator("#viewName").fill("Mortgage review");
    await page.getByRole("button", { name: "Save view", exact: true }).click();
    await page
      .locator("#savedViews option")
      .nth(1)
      .waitFor({ state: "attached" });
    assert.equal(await page.locator("#savedViews option").count(), 2);
    const download = page.waitForEvent("download");
    await page.locator("#exportQueue").click();
    const file = await download;
    const csv = fs.readFileSync(await file.path(), "utf8");
    assert.ok(csv.includes("CMP-1002"));
    assert.ok(!csv.includes("Demo customer"));
    await page.locator("#queueSearch").fill("");
    await page.screenshot({
      path: path.join(artifacts, "workspace-light.png"),
    });
    await page.locator("[data-theme-toggle]").click();
    assert.equal(await page.locator("html").getAttribute("data-theme"), "dark");
    await page.screenshot({ path: path.join(artifacts, "workspace-dark.png") });
    await page.locator("#commandOpen").click();
    await page.locator("#commandSearch").fill("AI providers");
    await page.locator("#commandList button").click();
    await page.locator("#providerStatus h2").waitFor();
    assert.equal(
      await page.locator("#providerStatus h2").textContent(),
      "anthropic",
    );
    await page.locator("#testProvider").click();
    await page
      .getByText("Connection verified. No customer data was sent.")
      .waitFor();
    await page.screenshot({ path: path.join(artifacts, "providers.png") });
    await page.setViewportSize({ width: 390, height: 844 });
    await page.locator('[data-tab="queue"]').click();
    assert.equal(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
      true,
    );
    await page.screenshot({
      path: path.join(artifacts, "workspace-mobile.png"),
    });
    await page.goto(base + "/portal.html");
    assert.equal(await page.locator("#name").inputValue(), "");
    assert.equal(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
      true,
    );
    await page.screenshot({ path: path.join(artifacts, "portal-mobile.png") });
    for (const size of [
      { width: 1440, height: 1000 },
      { width: 390, height: 844 },
    ]) {
      await page.setViewportSize(size);
      await page.goto(base + "/architecture.html");
      await page.waitForFunction(
        () => typeof window.__AEGIS_VISUAL_DIAGNOSTICS__ === "function",
      );
      await page.waitForTimeout(3500);
      const diag = await page.evaluate(() =>
        window.__AEGIS_VISUAL_DIAGNOSTICS__(),
      );
      assert.equal(diag.quality, "MAXIMUM");
      assert.equal(diag.nodes, 13);
      assert.equal(diag.particles, 5000);
      assert.equal(
        await page.evaluate(
          () => document.documentElement.scrollWidth <= innerWidth,
        ),
        true,
      );
      await page.screenshot({
        path: path.join(artifacts, `map-${size.width}.png`),
      });
      await page.locator('[data-theme-toggle]').click();
      await page.screenshot({path: path.join(artifacts, `map-dark-${size.width}.png`)});
      const before = await page.locator("#scene").screenshot();
      await page.waitForTimeout(500);
      const after = await page.locator("#scene").screenshot();
      assert.ok(!before.equals(after), "scene must animate");
      await page.locator("#componentSelect").selectOption("identity");
      await page.locator("#panel").waitFor({ state: "visible" });
      assert.ok(
        (await page.locator("#panelBody").textContent()).includes(
          "Identity Vault",
        ),
      );
      await page.locator("#resetView").click();
      await page.locator("#motionToggle").click();
      const shot = page.waitForEvent("download");
      await page.locator("#mapCapture").click();
      assert.ok((await shot).suggestedFilename().endsWith(".png"));
    }
    await page.goto(base + "/architecture.html?quality=low");
    await page.waitForFunction(
      () => typeof window.__AEGIS_VISUAL_DIAGNOSTICS__ === "function",
    );
    assert.equal(
      (await page.evaluate(() => window.__AEGIS_VISUAL_DIAGNOSTICS__()))
        .postProcessing,
      false,
    );
    await page.emulateMedia({reducedMotion:'reduce'});
    await page.goto(base + '/architecture.html?quality=low');
    await page.waitForFunction(() => typeof window.__AEGIS_VISUAL_DIAGNOSTICS__ === 'function');
    assert.equal(await page.locator('#motionToggle').textContent(), 'Resume motion');
    assert.deepEqual(errors, []);
    console.log(
      "PASS: search, saved views, CSV, command palette, providers, themes, mobile layout, animated WebGL, component focus, capture and low-power renderer.",
    );
    console.log("Artifacts: " + artifacts);
  } finally {
    await browser.close();
    await new Promise((resolve) => server.close(resolve));
  }
})().catch((e) => {
  console.error(e);
  process.exitCode = 1;
});
