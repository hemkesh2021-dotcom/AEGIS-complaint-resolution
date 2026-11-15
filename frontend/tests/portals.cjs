const assert = require("node:assert/strict");
const path = require("node:path");

// All writes below are intercepted fixtures, never real cases or outgoing mail.
module.exports = async function verifyPortals({ browser, base, artifacts }) {
  for (const width of [1440, 390, 320]) {
    const context = await browser.newContext({
      viewport: { width, height: 960 },
      reducedMotion: "no-preference",
    });
    const page = await context.newPage();
    const errors = [];
    page.on("pageerror", (error) => errors.push(error.message));
    const id = "CMP-7FB5F5C5-4066-4E82-AD8D-4F13FCE7A18E";
    // Preserve tracking-code length without credential-like random fixture data.
    const token = "TRK-" + "0".repeat(32);
    const record = {
      complaintId: id,
      customerName: "Preview customer",
      category: "Credit card",
      urgency: "NORMAL",
      status: "IN_REVIEW",
      confidence: 0.94,
      escalate: false,
      createdAt: "2026-11-15T09:00:00Z",
      ackDue: "2026-11-16T09:00:00Z",
      resolutionDue: "2026-11-20T09:00:00Z",
      complaintText:
        "Synthetic preview: please review an unexpected account fee.",
      clause: "Account fee review",
      draftSubject: "Your account fee enquiry",
      draftBody:
        "Thank you for contacting us. A specialist is reviewing your account fee enquiry.",
    };
    let submitted = 0;
    let responded = false;
    let rejectSubmission = false;
    const messages = [
      {
        ID: "preview-ack",
        Subject: `We received your complaint ${id}`,
        Read: false,
      },
      { ID: "preview-response", Subject: `Response to ${id}`, Read: false },
      { ID: "preview-update", Subject: `Update on ${id}`, Read: true },
    ].map((message) => ({
      ...message,
      From: { Name: "AEGIS Support", Address: "support@example.test" },
      To: [{ Address: "preview@example.test" }],
      Created: "2026-11-15T09:00:00Z",
      Snippet: "Your case is with our review team. We will keep you updated.",
    }));
    await context.addInitScript(() =>
      localStorage.setItem("aegisKey", "fixture-key"),
    );
    await context.route("http://localhost:8080/**", async (route) => {
      const url = new URL(route.request().url());
      if (url.pathname.endsWith("/stream")) return route.abort();
      if (url.pathname === "/api/intake") {
        submitted++;
        await new Promise((resolve) => setTimeout(resolve, 180));
        if (rejectSubmission)
          return route.fulfill({
            status: 503,
            json: { error: "fixture unavailable" },
          });
        return route.fulfill({
          json: {
            ...record,
            trackingToken: token,
            message: "Your complaint has been received.",
          },
        });
      }
      if (url.pathname.startsWith("/api/status/")) {
        return route.fulfill({
          json: {
            ...record,
            status: responded ? "SENT" : "IN_REVIEW",
            reply: responded
              ? {
                  subject: record.draftSubject,
                  body: record.draftBody,
                  summary: "Our review is complete.",
                }
              : null,
            followUps: responded
              ? [
                  {
                    body: "An update from your review team.",
                    sentAt: record.createdAt,
                  },
                ]
              : [],
          },
        });
      }
      if (url.pathname.endsWith("/similar"))
        return route.fulfill({ json: { similar: [], customerCaseCount: 1 } });
      if (url.pathname === `/api/complaints/${id}`)
        return route.fulfill({
          json: {
            caseRecord: record,
            auditTrail: [
              {
                phase: "classify",
                detail: "Classification completed locally",
                createdAt: record.createdAt,
              },
            ],
            followUps: [],
          },
        });
      return route.fulfill({
        json:
          url.pathname === "/api/complaints"
            ? [record]
            : url.pathname === "/api/escalations"
              ? []
              : { status: "UP" },
      });
    });
    await context.route("http://localhost:8025/**", (route) => {
      const url = new URL(route.request().url());
      if (route.request().method() === "PUT") {
        const data = route.request().postDataJSON();
        messages.forEach((message) => {
          if (data.IDs.includes(message.ID)) message.Read = data.Read;
        });
        return route.fulfill({ json: {} });
      }
      if (url.pathname.startsWith("/api/v1/message/")) {
        const message = messages.find((item) => url.pathname.endsWith(item.ID));
        return route.fulfill({
          json: { ...message, Text: record.draftBody + "\n\nReference: " + id },
        });
      }
      return route.fulfill({
        json: {
          total: messages.length,
          unread: messages.filter((message) => !message.Read).length,
          messages,
        },
      });
    });
    const fits = async (selector) => {
      const bounds = await page.locator(selector).evaluateAll((elements) =>
        elements
          .filter((element) => element.getClientRects().length)
          .map((element) => {
            const rect = element.getBoundingClientRect();
            return {
              selector: element.className,
              left: rect.left,
              right: rect.right,
              width: innerWidth,
              scroll: element.scrollWidth,
              client: element.clientWidth,
            };
          }),
      );
      for (const rect of bounds) {
        assert.ok(
          rect.left >= -1 && rect.right <= rect.width + 1,
          `viewport containment: ${JSON.stringify(rect)}`,
        );
        assert.ok(
          rect.scroll <= rect.client + 1,
          `content containment: ${JSON.stringify(rect)}`,
        );
      }
    };
    try {
      await page.goto(base + "/index.html");
      await page.locator("tr.clickable").first().click();
      await page.locator("#apBody").waitFor();
      await page.waitForTimeout(800);
      assert.equal(
        await page.locator("#apBody").inputValue(),
        record.draftBody,
      );
      assert.equal(
        await page
          .locator(".conf > div")
          .evaluate((element) => element.style.width),
        "94%",
      );
      await page.locator("#apBody").fill(record.draftBody + " Reviewed.");
      await page.locator("#apBody").focus();
      assert.equal(
        await page
          .locator("#apBody")
          .evaluate((element) => getComputedStyle(element).outlineStyle),
        "solid",
      );
      await fits("main, #workspace .card, #apBody, .wstitle");
      assert.equal(
        await page.evaluate(
          () => document.documentElement.scrollWidth <= innerWidth,
        ),
        true,
      );
      await page.screenshot({
        path: path.join(artifacts, `polished-console-${width}.png`),
        fullPage: true,
      });

      await page.goto(base + "/portal.html");
      await page.locator("#send").click();
      await page.waitForFunction(
        () => document.querySelector("#send").disabled,
      );
      await page.locator("#ackWrap .ref").waitFor();
      await page.waitForTimeout(800);
      assert.equal(submitted, 1);
      assert.equal(await page.locator("#trackId").inputValue(), token);
      assert.equal(await page.locator("#send").isEnabled(), true);
      await fits("#ackWrap .ack, #ackWrap .ref, #ackWrap .kv");
      await page.screenshot({
        path: path.join(artifacts, `polished-portal-${width}.png`),
        fullPage: true,
      });
      responded = true;
      await page
        .getByRole("button", { name: "Check status", exact: true })
        .click();
      await page.locator("#trackWrap .summary").waitFor();
      assert.ok(
        (await page.locator("#trackWrap").textContent()).includes(
          "An update from your review team.",
        ),
      );
      await fits("#trackWrap .ack, #trackWrap .email, #trackWrap .ref");
      rejectSubmission = true;
      await page.locator("#send").click();
      await page
        .locator("#ackWrap")
        .getByText(/couldn't submit/)
        .waitFor();
      assert.equal(await page.locator("#send").isEnabled(), true);

      await page.goto(base + "/inbox.html");
      await page.locator(".msg").first().waitFor();
      await page.waitForTimeout(500);
      await fits(".app, .rail, .list, .folder, .msg, .msg .meta");
      await page.screenshot({
        path: path.join(artifacts, `polished-inbox-${width}.png`),
      });
      await page.locator('[data-f="resp"]').click();
      assert.equal(await page.locator(".msg").count(), 1);
      await page.locator('[data-f="all"]').click();
      await page.locator("#q").fill("Update on");
      assert.equal(await page.locator(".msg").count(), 1);
      await page.locator("#q").fill("");
      await page.locator(".msg").first().focus();
      await page.keyboard.press("Enter");
      await page.locator(".paper pre").waitFor();
      assert.ok(
        (await page.locator(".paper").textContent()).includes(record.draftBody),
      );
      await page.waitForTimeout(600);
      await fits(".rhead, .rhead .subj, .paper, .paper pre");
      await page.screenshot({
        path: path.join(artifacts, `polished-reader-${width}.png`),
      });
      if (width < 1001) {
        assert.equal(
          await page.locator("#reader").getAttribute("aria-modal"),
          "true",
        );
        assert.equal(
          await page.locator(".list").evaluate((element) => element.inert),
          true,
        );
        const icon = page.locator(".aegis-reader-back img");
        await icon.evaluate((element) => element.decode());
        assert.ok(await icon.evaluate((element) => element.naturalWidth > 0));
        await page.getByRole("button", { name: "Back to inbox" }).click();
        assert.equal(await page.locator("#reader").isVisible(), false);
        assert.equal(
          await page.locator(".list").evaluate((element) => element.inert),
          false,
        );
        assert.equal(
          await page
            .locator(".msg.sel")
            .evaluate((element) => element === document.activeElement),
          true,
        );
      } else {
        assert.equal(
          await page.locator(".aegis-reader-back").isVisible(),
          false,
        );
      }
      await page.waitForTimeout(600);
      await page.evaluate(() => refresh());
      assert.equal(
        await page
          .locator(".msg")
          .evaluateAll((elements) =>
            elements.reduce(
              (sum, element) => sum + element.getAnimations().length,
              0,
            ),
          ),
        0,
        "unchanged mail refresh must not replay entry animations",
      );
      await page.emulateMedia({ reducedMotion: "reduce" });
      await page.locator(".msg").first().click();
      await page.locator(".paper pre").waitFor();
      assert.equal(
        await page.evaluate(
          () => window.__AEGIS_MOTION_DIAGNOSTICS__().activeAnimations,
        ),
        0,
      );
      if (width < 1001) {
        await page.getByRole("button", { name: "Back to inbox" }).click();
        assert.equal(await page.locator("#reader").isVisible(), false);
      }
      assert.deepEqual(errors, []);
      console.log(
        `PASS: ${width}px portal submission/tracking/error, console editing, mail filtering/reading, responsive containment and reduced motion.`,
      );
    } finally {
      await context.close();
    }
  }
};
