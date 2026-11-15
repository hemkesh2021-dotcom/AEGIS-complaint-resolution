const { chromium } = require("playwright");
const assert = require("node:assert/strict");
const { createHash } = require("node:crypto");
const fs = require("node:fs");
const path = require("node:path");
const { createServer } = require("node:http");

const root = path.resolve(__dirname, "..");

// Preserve original markup, styles and business logic; allow the isolated motion layer.
const original = {
  "index.html":
    "bf2e58aad16536208bf0bca3223360e4a72b990bdd7a285b437e05efa1b88864",
  "portal.html":
    "9ab856a56a81cbc51c4b0bfd28fefbaf62989f4989eedfaf26a010998274cb8d",
  "inbox.html":
    "d3744eda1b67b2b66437ce096e0b4a8e8ccbbb59c348e91469b177e4cecbe4e1",
  "architecture.html":
    "a6925ce8ec64a88fd9e5fedb64b5253494b085435d480d5a86cae9711960badf",
};
for (const [name, hash] of Object.entries(original)) {
  let source = fs.readFileSync(path.join(root, name), "utf8");
  source = source
    .replace('<link rel="stylesheet" href="motion.css">\n', "")
    .replace('<script src="motion.js" defer></script>\n', "");
  if (name === "architecture.html")
    source = source.slice(0, source.indexOf("<script>"));
  assert.equal(
    createHash("sha256").update(source).digest("hex"),
    hash,
    `${name}: original interface must remain unchanged`,
  );
}

async function canvasPixels(page) {
  return page.evaluate(
    () =>
      new Promise((resolve) =>
        requestAnimationFrame(() => {
          const source = document.querySelector("#scene");
          const canvas = document.createElement("canvas");
          canvas.width = 180;
          canvas.height = 120;
          const ctx = canvas.getContext("2d");
          ctx.drawImage(source, 0, 0, canvas.width, canvas.height);
          const data = ctx.getImageData(0, 0, canvas.width, canvas.height).data;
          const colors = new Set();
          let bright = 0;
          for (let i = 0; i < data.length; i += 4) {
            colors.add(
              `${data[i] >> 4},${data[i + 1] >> 4},${data[i + 2] >> 4}`,
            );
            if (Math.max(data[i], data[i + 1], data[i + 2]) > 70) bright++;
          }
          resolve({ colors: colors.size, bright });
        }),
      ),
  );
}

(async () => {
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
          : file.endsWith(".svg")
            ? "image/svg+xml"
            : "text/html",
    );
    res.end(fs.readFileSync(file));
  });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  const base = `http://127.0.0.1:${server.address().port}`;
  let browser;
  try {
    browser = await chromium.launch({
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
    const artifacts =
      process.env.AEGIS_TEST_ARTIFACTS || "/tmp/aegis-motion-browser";
    fs.mkdirSync(artifacts, { recursive: true });
    await require("./portals.cjs")({ browser, base, artifacts });
    const errors = [];
    const page = await browser.newPage({
      viewport: { width: 1440, height: 1000 },
    });
    page.setDefaultTimeout(20000);
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
        createdAt: "2026-01-02T10:00Z",
      },
    ];
    await page.route("http://localhost:8080/**", (route) => {
      const url = route.request().url();
      return route.fulfill({
        json: url.endsWith("/api/complaints")
          ? fixture
          : url.endsWith("/api/escalations")
            ? [fixture[0]]
            : { status: "UP" },
      });
    });
    await page.route("http://localhost:8025/**", (route) =>
      route.fulfill({ json: { total: 0, messages: [] } }),
    );
    await page.addInitScript(() => {
      localStorage.setItem("aegisKey", "fixture-key");
      Object.defineProperty(navigator, "hardwareConcurrency", { get: () => 8 });
    });

    await page.goto(base + "/index.html");
    await page.locator("#queueBody tr.clickable").first().waitFor();
    assert.equal(await page.locator("#queueBody tr.clickable").count(), 1);
    assert.deepEqual(
      await page
        .locator(".navbtn")
        .evaluateAll((nodes) => nodes.map((n) => n.dataset.tab)),
      ["queue", "esc", "ins", "new", "sys"],
    );
    await page.locator('[data-f="all"]').click();
    await page.waitForFunction(
      () => document.querySelectorAll("#queueBody tr.clickable").length === 2,
    );
    await page.locator('[data-f="sent"]').click();
    await page.waitForFunction(
      () => document.querySelectorAll("#queueBody tr.clickable").length === 1,
    );
    assert.ok(
      (await page.locator("#queueBody").textContent()).includes("CMP-1002"),
    );
    await page.locator('[data-tab="new"]').click();
    await page.locator("#ex").selectOption("1");
    assert.ok((await page.locator("#text").inputValue()).includes("mortgage"));
    await page.locator('[data-tab="queue"]').click();
    await page.screenshot({
      path: path.join(artifacts, "original-console.png"),
    });

    for (const size of [
      { width: 1440, height: 1000 },
      { width: 390, height: 844 },
    ]) {
      await page.setViewportSize(size);
      await page.goto(base + "/portal.html");
      assert.equal(await page.locator("#name").inputValue(), "A. Rao");
      assert.equal(await page.locator("#send").isVisible(), true);
      await page.screenshot({
        path: path.join(artifacts, `original-portal-${size.width}.png`),
      });

      await page.goto(base + "/architecture.html");
      await page.waitForFunction(
        () => typeof window.__AEGIS_VISUAL_DIAGNOSTICS__ === "function",
      );
      await page.waitForTimeout(4000);
      const diag = await page.evaluate(() =>
        window.__AEGIS_VISUAL_DIAGNOSTICS__(),
      );
      const constrained = size.width < 760;
      assert.equal(diag.quality, constrained ? "BALANCED" : "ULTRA");
      assert.equal(diag.nodes, 13);
      assert.equal(diag.particles, constrained ? 1100 : 5000);
      assert.equal(diag.shadowMapSize, constrained ? 1024 : 4096);
      assert.equal(diag.reflectionSize, constrained ? 256 : 1024);
      assert.equal(diag.screenTextureScale, constrained ? 1 : 2);
      assert.deepEqual(
        await page.evaluate(() =>
          nodes.classifier.group.userData.neural.layers.map(
            (layer) => layer.length,
          ),
        ),
        [4, 6, 11],
      );
      assert.ok(diag.visibleLabels > 3);
      for (let i = 0; i < diag.labelBounds.length; i++) {
        for (let j = i + 1; j < diag.labelBounds.length; j++) {
          const a = diag.labelBounds[i],
            b = diag.labelBounds[j];
          assert.ok(
            !(
              a.left < b.right &&
              a.right > b.left &&
              a.top < b.bottom &&
              a.bottom > b.top
            ),
            "3D labels must not overlap",
          );
        }
      }
      assert.equal(diag.postProcessing, true);
      assert.ok(diag.drawCalls > 0 && diag.triangles > 0);
      assert.equal(await page.locator(".phase").count(), 9);
      assert.equal(
        await page.evaluate(
          () => document.documentElement.scrollWidth <= innerWidth,
        ),
        true,
      );
      const pixels = await canvasPixels(page);
      assert.ok(
        pixels.colors > 20 && pixels.bright > 50,
        `nonblank canvas: ${JSON.stringify(pixels)}`,
      );
      await page.screenshot({
        path: path.join(artifacts, `graphics-${size.width}.png`),
      });
      const before = await page.locator("#scene").screenshot();
      await page.waitForTimeout(500);
      assert.ok(
        !before.equals(await page.locator("#scene").screenshot()),
        "scene must animate",
      );
      await page.locator("#play").click();
      assert.ok(
        (await page.locator("#play").textContent()).includes("play journey"),
      );
      await page.locator(".phase").nth(2).click();
      assert.ok(
        (await page.locator(".phase").nth(2).getAttribute("class")).includes(
          "lit",
        ),
      );
      await page.mouse.move(size.width / 2, size.height / 2);
      const startTheta = await page.evaluate(() => theta);
      await page.mouse.down();
      await page.mouse.move(size.width / 2 + 60, size.height / 2 + 20, {
        steps: 5,
      });
      await page.mouse.up();
      await page.waitForTimeout(650);
      assert.ok(
        Math.abs((await page.evaluate(() => theta)) - startTheta) > 0.2,
        "original orbit control works",
      );
      await page.mouse.wheel(0, -100);
      await page.waitForFunction(() => radiusGoal < HOME_RADIUS);
      await page
        .locator("#scene")
        .dblclick({ position: { x: size.width / 2, y: size.height / 2 } });
      assert.equal(await page.evaluate(() => radiusGoal === HOME_RADIUS), true);
      await page.waitForTimeout(1000);
      const classifierPoint = await page.evaluate(() => {
        const point = nodes.classifier.mesh
          .getWorldPosition(new THREE.Vector3())
          .project(camera);
        return {
          x: ((point.x + 1) * innerWidth) / 2,
          y: ((1 - point.y) * innerHeight) / 2,
        };
      });
      await page.mouse.click(classifierPoint.x, classifierPoint.y);
      await page.locator("#panel h2").waitFor();
      assert.equal(
        await page.locator("#panel h2").textContent(),
        "Neural Classifier",
      );
      const signalBefore = await page.evaluate(() =>
        nodes.classifier.group.userData.neural.signals[0].marker.position.toArray(),
      );
      await page.waitForTimeout(400);
      assert.notDeepEqual(
        await page.evaluate(() =>
          nodes.classifier.group.userData.neural.signals[0].marker.position.toArray(),
        ),
        signalBefore,
      );
      await page.screenshot({
        path: path.join(artifacts, `classifier-focus-${size.width}.png`),
      });
      await page.keyboard.press("Escape");
      assert.equal(await page.locator("#panel").isVisible(), false);
      console.log(`${size.width}px: ${JSON.stringify({ ...diag, pixels })}`);
    }

    // A missing optional postprocessing dependency must retain native WebGL rendering.
    await page.route("**/FXAAShader.js", (route) => route.abort());
    await page.goto(base + "/architecture.html");
    await page.waitForFunction(
      () => typeof window.__AEGIS_VISUAL_DIAGNOSTICS__ === "function",
    );
    assert.equal(
      (await page.evaluate(() => window.__AEGIS_VISUAL_DIAGNOSTICS__()))
        .postProcessing,
      false,
    );
    await page.waitForTimeout(3000);
    assert.ok((await canvasPixels(page)).colors > 20);

    await page.emulateMedia({ reducedMotion: "reduce" });
    await page.goto(base + "/architecture.html");
    await page.waitForFunction(
      () => typeof window.__AEGIS_VISUAL_DIAGNOSTICS__ === "function",
    );
    await page.waitForTimeout(500);
    const still = await page.evaluate(() => ({
      theta,
      motionTime,
      auto,
      signal:
        nodes.classifier.group.userData.neural.signals[0].marker.position.toArray(),
    }));
    await page.waitForTimeout(500);
    assert.deepEqual(
      await page.evaluate(() => ({
        theta,
        motionTime,
        auto,
        signal:
          nodes.classifier.group.userData.neural.signals[0].marker.position.toArray(),
      })),
      still,
      "reduced-motion stops idle camera and scene animation",
    );
    assert.equal(still.auto, false);
    await page.emulateMedia({ reducedMotion: "no-preference" });
    await page.waitForTimeout(300);
    assert.ok(
      await page.evaluate(() => motionTime > 0),
      "motion preference changes are respected without reloading",
    );

    const geometry = () =>
      [
        ...document.querySelectorAll(
          "aside,main,.card,.toolbar,button,input,textarea,select,.hero,.brand,.app,.msgs,.reader",
        ),
      ]
        .filter((e) => e.getClientRects().length)
        .map((e) => {
          const b = e.getBoundingClientRect(),
            s = getComputedStyle(e);
          return {
            tag: e.tagName,
            id: e.id,
            text: e.tagName === "BUTTON" ? e.textContent.trim() : null,
            rect: [b.x, b.y, b.width, b.height].map(
              (n) => Math.round(n * 100) / 100,
            ),
            color: s.color,
            font: s.font,
            fontSize: s.fontSize,
            borderRadius: s.borderRadius,
          };
        });
    for (const width of [1440, 390]) {
      await page.setViewportSize({ width, height: 1000 });
      for (const file of ["index.html", "portal.html", "inbox.html"]) {
        const measures = [];
        for (const disabled of [false, true]) {
          if (disabled)
            await page.route("**/motion.*", (route) => route.abort());
          await page.goto(base + "/" + file);
          await page.mouse.move(0, 0);
          await page.evaluate(() => document.fonts.ready);
          if (file === "index.html")
            await page.locator("#queueBody tr.clickable").first().waitFor();
          if (file === "inbox.html")
            await page.waitForFunction(
              () =>
                !document
                  .querySelector("#msgs")
                  .textContent.includes("Connecting"),
            );
          await page.waitForTimeout(700);
          measures.push(await page.evaluate(geometry));
          if (!disabled)
            await page.screenshot({
              path: path.join(artifacts, `${file}-${width}.png`),
            });
          if (disabled) await page.unroute("**/motion.*");
        }
        if (file === "index.html" && width < 821) {
          const withoutGeometry = (items) =>
            items.map(({ rect, ...item }) => item);
          assert.deepEqual(
            withoutGeometry(measures[0]),
            withoutGeometry(measures[1]),
            "mobile console containment must retain the original type, colors and controls",
          );
        } else if (!(file === "inbox.html" && width < 1001)) {
          assert.deepEqual(
            measures[0],
            measures[1],
            `${file} ${width}px: visual layer must not change original layout, typography, colors or controls`,
          );
        }
      }
    }

    await page.goto(base + "/index.html");
    await page.locator('[data-tab="new"]').click();
    await page.waitForFunction(
      () => window.__AEGIS_MOTION_DIAGNOSTICS__().activeAnimations > 0,
    );
    await page.locator("#apiKey").focus();
    assert.equal(
      await page
        .locator("#apiKey")
        .evaluate((e) => getComputedStyle(e).outlineStyle),
      "solid",
    );
    await page.emulateMedia({ reducedMotion: "reduce" });
    await page.waitForFunction(
      () =>
        window.__AEGIS_MOTION_DIAGNOSTICS__().reducedMotion &&
        window.__AEGIS_MOTION_DIAGNOSTICS__().activeAnimations === 0,
    );
    assert.equal(
      await page.evaluate(
        () => window.__AEGIS_MOTION_DIAGNOSTICS__().activeAnimations,
      ),
      0,
    );
    await page.emulateMedia({ reducedMotion: "no-preference" });

    await page.setViewportSize({ width: 1440, height: 1000 });
    await page.goto(base + "/inbox.html");
    await page.waitForFunction(
      () => !document.querySelector("#msgs").textContent.includes("Connecting"),
    );
    assert.equal(await page.locator(".folder").count(), 4);
    await page.screenshot({ path: path.join(artifacts, "original-inbox.png") });
    assert.deepEqual(errors, []);
    console.log(
      "PASS: original UI fingerprints and layout comparisons, motion feedback, reduced motion, queue filters, portal/inbox, desktop/mobile graphics, label collisions, orbit/zoom/reset, journey and rendering fallback.",
    );
    console.log("Artifacts: " + artifacts);
  } finally {
    if (browser) await browser.close();
    await new Promise((resolve) => server.close(resolve));
  }
})().catch((e) => {
  console.error(e);
  process.exitCode = 1;
});
