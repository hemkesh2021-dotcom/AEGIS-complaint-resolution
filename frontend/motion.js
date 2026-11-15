(() => {
  "use strict";
  const preference = matchMedia("(prefers-reduced-motion: reduce)");
  const enabled = new URLSearchParams(location.search).get("motion") !== "off";
  document.documentElement.dataset.aegisMotion = enabled ? "enhanced" : "off";
  const surface = document.querySelector(".app .reader")
    ? "mail"
    : document.querySelector(".layout #workspace")
      ? "console"
      : document.querySelector(".wrap #ackWrap")
        ? "portal"
        : null;
  if (surface) document.documentElement.dataset.aegisSurface = surface;
  const active = new Map();
  const presses = new Map();
  const seenMessages = new Set();
  const finishedDetails = new WeakSet();
  const canMove = () => enabled && !preference.matches && !document.hidden;
  const controls =
    "button,a.btn,.navbtn,.filterchip,.folder,.abtn,.phase,.playbtn";

  let syncReader = () => {};
  if (surface === "mail") {
    const reader = document.querySelector("#reader");
    const narrow = matchMedia("(max-width: 1000px)");
    const back = document.createElement("button");
    back.type = "button";
    back.className = "aegis-reader-back";
    back.title = "Back to inbox";
    back.innerHTML =
      '<img src="assets/icons/arrow-left.svg" alt="">Back to inbox';
    reader.prepend(back);
    back.addEventListener("click", () => reader.classList.remove("open"));
    let modalOpen = false;
    syncReader = () => {
      const open = narrow.matches && reader.classList.contains("open");
      if (open === modalOpen) return;
      modalOpen = open;
      document
        .querySelectorAll(".app > .rail,.app > .list")
        .forEach((element) => {
          element.inert = open;
        });
      if (open) {
        reader.setAttribute("role", "dialog");
        reader.setAttribute("aria-modal", "true");
        reader.setAttribute("aria-label", "Message reader");
        back.focus({ preventScroll: true });
      } else {
        reader.removeAttribute("role");
        reader.removeAttribute("aria-modal");
        reader.removeAttribute("aria-label");
        document.querySelector(".msg.sel")?.focus({ preventScroll: true });
      }
    };
    narrow.addEventListener("change", syncReader);
    document.addEventListener("keydown", (event) => {
      if (event.target.matches(".msg") && ["Enter", " "].includes(event.key)) {
        event.preventDefault();
        event.target.click();
      }
    });
  }

  function prepareMessages(root) {
    if (surface !== "mail") return;
    const messages = root.matches?.(".msg")
      ? [root]
      : root.querySelectorAll(".msg");
    messages.forEach((message) => {
      message.tabIndex = 0;
      message.setAttribute("role", "button");
      message.setAttribute(
        "aria-label",
        `Read ${message.querySelector(".subj")?.textContent || "message"}`,
      );
    });
  }
  prepareMessages(document.body);

  function animate(element, frames, options) {
    if (!canMove() || !element.isConnected || !element.getClientRects().length)
      return;
    active.get(element)?.cancel();
    const animation = element.animate(frames, options);
    active.set(element, animation);
    const done = () => {
      if (active.get(element) === animation) active.delete(element);
    };
    animation.onfinish = done;
    animation.oncancel = done;
  }
  function reveal(element, delay = 0) {
    if (element.closest(".hide")) return;
    // Polling replaces mail rows. Animate genuinely new messages, not every refresh.
    if (element.matches(".msg")) {
      const key = element.getAttribute("onclick");
      if (key && seenMessages.has(key)) return;
      if (key) seenMessages.add(key);
      if (seenMessages.size > 256)
        seenMessages.delete(seenMessages.values().next().value);
    }
    animate(element, [{ opacity: 0.55 }, { opacity: 1 }], {
      duration: 320,
      delay,
      easing: "cubic-bezier(.16,1,.3,1)",
    });
  }
  function finishDetail(element) {
    if (finishedDetails.has(element)) return;
    finishedDetails.add(element);
    if (element.matches(".conf > div")) {
      animate(
        element,
        [
          { transform: "scaleX(0.04)", transformOrigin: "left" },
          { transform: "scaleX(1)", transformOrigin: "left" },
        ],
        { duration: 540, easing: "cubic-bezier(.16,1,.3,1)" },
      );
    } else {
      const length = element.getTotalLength();
      animate(
        element,
        [
          { strokeDasharray: `${length}`, strokeDashoffset: `${length}` },
          { strokeDasharray: `${length}`, strokeDashoffset: "0" },
        ],
        { duration: 460, delay: 80, easing: "cubic-bezier(.2,.8,.2,1)" },
      );
    }
  }
  function clearPress(element) {
    clearTimeout(presses.get(element));
    presses.delete(element);
    element.classList.remove("aegis-press");
    element.style.removeProperty("--press-x");
    element.style.removeProperty("--press-y");
  }
  function press(element, x, y) {
    if (!canMove() || element.matches(":disabled,[aria-disabled='true']"))
      return;
    animate(
      element,
      [{ filter: "brightness(1.12)" }, { filter: "brightness(1)" }],
      {
        duration: 380,
        easing: "cubic-bezier(.16,1,.3,1)",
      },
    );
    if (!element.classList.contains("btn")) return;
    clearPress(element);
    const rect = element.getBoundingClientRect();
    element.style.setProperty(
      "--press-x",
      `${x == null ? rect.width / 2 : x - rect.left}px`,
    );
    element.style.setProperty(
      "--press-y",
      `${y == null ? rect.height / 2 : y - rect.top}px`,
    );
    element.classList.add("aegis-press");
    presses.set(
      element,
      setTimeout(() => clearPress(element), 480),
    );
  }
  document.addEventListener(
    "pointerdown",
    (event) => {
      const element = event.target.closest(controls);
      if (element && event.isPrimary && event.button === 0)
        press(element, event.clientX, event.clientY);
    },
    { passive: true },
  );
  document.addEventListener("click", (event) => {
    const element = event.target.closest(controls);
    if (element && event.detail === 0) press(element);
  });

  // Observe existing view changes without wrapping, replacing or intercepting controls.
  const observer = new MutationObserver((records) => {
    syncReader();
    for (const record of records) {
      for (const node of record.addedNodes) {
        if (node instanceof Element) prepareMessages(node);
      }
    }
    if (!canMove()) return;
    const candidates = new Set();
    const details = new Set();
    const revealSelector =
      ".fade,.ack,.msg,.wshead,table,.paper,#rhead > .subj,#panelBody > h2";
    const detailSelector = ".conf > div,.ack .tick svg path";
    for (const record of records) {
      if (record.type === "attributes") {
        if (record.target.matches("section[id^='tab-']:not(.hide)"))
          candidates.add(record.target);
        continue;
      }
      for (const node of record.addedNodes) {
        if (!(node instanceof Element)) continue;
        if (node.matches(revealSelector)) candidates.add(node);
        node
          .querySelectorAll(revealSelector)
          .forEach((element) => candidates.add(element));
        if (node.matches(detailSelector)) details.add(node);
        node
          .querySelectorAll(detailSelector)
          .forEach((element) => details.add(element));
      }
    }
    [...candidates]
      .filter(
        (element) =>
          ![...candidates].some(
            (parent) => parent !== element && parent.contains(element),
          ),
      )
      .slice(0, 12)
      .forEach((element, i) => reveal(element, Math.min(i * 24, 120)));
    details.forEach(finishDetail);
  });
  const observeOptions = {
    childList: true,
    subtree: true,
    attributes: true,
    attributeFilter: ["class"],
  };
  observer.observe(document.body, observeOptions);
  function cancelMotion() {
    if (canMove()) return;
    active.forEach((animation) => animation.cancel());
    active.clear();
    [...presses.keys()].forEach(clearPress);
  }
  preference.addEventListener("change", cancelMotion);
  document.addEventListener("visibilitychange", cancelMotion);
  window.addEventListener("pagehide", () => {
    observer.disconnect();
    active.forEach((animation) => animation.cancel());
    active.clear();
    [...presses.keys()].forEach(clearPress);
  });
  window.addEventListener("pageshow", (event) => {
    if (!event.persisted) return;
    observer.observe(document.body, observeOptions);
    prepareMessages(document.body);
    syncReader();
  });
  window.__AEGIS_MOTION_DIAGNOSTICS__ = () => ({
    enabled,
    surface,
    reducedMotion: preference.matches,
    activeAnimations: active.size,
    activePresses: presses.size,
  });
})();
