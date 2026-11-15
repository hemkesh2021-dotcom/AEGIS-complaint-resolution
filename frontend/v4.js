/* v4 workspace enhancements; case mutations continue through the existing approval API. */
(() => {
  const icons = () => window.lucide?.createIcons();
  const read = (key, fallback) => {
    try {
      return JSON.parse(localStorage.getItem(key)) ?? fallback;
    } catch {
      return fallback;
    }
  };
  let rows = [],
    visible = [],
    requestNumber = 0;
  const notice = document.createElement("div");
  notice.id = "notice";
  notice.setAttribute("role", "status");
  document.body.append(notice);
  let noticeTimer;
  function notify(message) {
    notice.textContent = message;
    clearTimeout(noticeTimer);
    noticeTimer = setTimeout(() => (notice.textContent = ""), 5000);
  }
  const deadline = (c) => Date.parse(c.resolutionDue) || Infinity;
  const overdue = (c) => c.status !== "SENT" && deadline(c) < Date.now();
  const dueSoon = (c) =>
    c.status !== "SENT" &&
    deadline(c) >= Date.now() &&
    deadline(c) < Date.now() + 86400000;
  function renderRows() {
    const q = $("#queueSearch").value.trim().toLowerCase();
    visible = rows.filter(
      (c) =>
        (FILTER === "all" ||
          (FILTER === "review" && c.status !== "SENT") ||
          (FILTER === "sent" && c.status === "SENT") ||
          (FILTER === "escalated" && c.escalate) ||
          (FILTER === "overdue" && overdue(c))) &&
        [c.complaintId, c.customerName, c.category].some((x) =>
          String(x || "")
            .toLowerCase()
            .includes(q),
        ),
    );
    const sort = $("#queueSort").value;
    visible.sort(
      (a, b) =>
        (sort === "deadline"
          ? deadline(a) - deadline(b)
          : sort === "newest"
            ? 0
            : uRank(a.urgency) - uRank(b.urgency)) ||
        Date.parse(b.createdAt) - Date.parse(a.createdAt),
    );
    $("#queueResultCount").textContent =
      `${visible.length} of ${rows.length} recent cases (latest 50)`;
    $("#queueMetrics").innerHTML = [
      [
        "Awaiting review",
        rows.filter((c) => c.status !== "SENT").length,
        "Ready for a human decision",
      ],
      ["Overdue", rows.filter(overdue).length, "Past resolution deadline"],
      [
        "Due in 24 hours",
        rows.filter(dueSoon).length,
        "Upcoming resolution deadlines",
      ],
      [
        "Responses sent",
        rows.filter((c) => c.status === "SENT").length,
        "Approved by your team",
      ],
    ]
      .map(
        ([label, value, sub]) =>
          `<div class="queue-metric"><span>${label}</span><b>${value}</b><small>${sub}</small></div>`,
      )
      .join("");
    $("#exportQueue").disabled = !visible.length;
    if (!visible.length) {
      $("#queueBody").innerHTML =
        '<div class="empty"><i data-lucide="inbox"></i><b>No cases in this view</b><br>Change your search or select All to see other cases.</div>';
      icons();
      return;
    }
    $("#queueBody").innerHTML =
      `<table><thead><tr><th>Case / Customer</th><th>Category</th><th>Urgency</th><th>Status</th><th>Resolution due</th><th>Confidence</th></tr></thead><tbody>${visible.map((c) => `<tr class="clickable" data-case="${esc(c.complaintId)}" tabindex="0" role="button" aria-label="Open case ${esc(c.complaintId)}"><td><span class="case-id">${esc(c.complaintId)}</span><span class="case-customer">${esc(c.customerName || "Customer")}</span></td><td>${esc(c.category)}</td><td>${urgencyChip(c.urgency)}</td><td>${statusChip(c.status)}</td><td><span style="color:${overdue(c) ? "var(--danger)" : "var(--muted)"}">${fmt(c.resolutionDue)}</span>${overdue(c) ? "<br><small>Overdue</small>" : ""}</td><td>${Math.round((c.confidence || 0) * 100)}%</td></tr>`).join("")}</tbody></table>`;
    $("#queueBody")
      .querySelectorAll("[data-case]")
      .forEach((row) => {
        row.onclick = () => openCase(row.dataset.case);
        row.onkeydown = (e) => {
          if (e.key === "Enter" || e.key === " ") {
            e.preventDefault();
            openCase(row.dataset.case);
          }
        };
      });
    const pending = rows.filter((c) => c.status !== "SENT").length;
    $("#qCount").textContent = pending;
    $("#qCount").classList.toggle("hide", !pending);
  }
  loadQueue = async function () {
    const current = ++requestNumber;
    rows = [];
    visible = [];
    $("#exportQueue").disabled = true;
    if (!KEY() && !ssoToken()) {
      $("#queueMetrics").innerHTML = "";
      $("#queueResultCount").textContent = "Not connected";
      $("#queueBody").innerHTML =
        '<div class="empty"><i data-lucide="key-round"></i><b>Your workspace is ready</b><br>Connect your operator account to review cases.<br><button class="btn ghost" id="connectWorkspace" style="width:auto;margin:16px auto">Connect workspace</button></div>';
      $("#connectWorkspace").onclick = () => {
        document.querySelector("aside").classList.add("connection-open");
        $("#apiKey").focus();
        $("#apiKey").scrollIntoView({ block: "center" });
      };
      icons();
      return;
    }
    $("#queueBody").innerHTML =
      '<div class="empty" role="status">Loading cases...</div>';
    try {
      const response = await apiFetch(api() + "/api/complaints");
      if (!response.ok)
        throw new Error(
          response.status === 401
            ? "Session expired or invalid operator key."
            : `Server returned ${response.status}.`,
        );
      const data = await response.json();
      if (current !== requestNumber) return;
      if (!Array.isArray(data)) throw new Error("Unexpected queue response.");
      rows = data;
      renderRows();
    } catch (e) {
      if (current !== requestNumber) return;
      $("#queueMetrics").innerHTML = "";
      $("#queueResultCount").textContent = "Unavailable";
      $("#queueBody").innerHTML =
        `<div class="empty"><b>Unable to load cases</b><br>${esc(e.message)}<br>Check your connection and refresh.</div>`;
    }
  };
  $("#queueSearch").addEventListener("input", renderRows);
  $("#queueSort").addEventListener("change", renderRows);
  const overdueButton = document.createElement("button");
  overdueButton.className = "filterchip";
  overdueButton.dataset.f = "overdue";
  overdueButton.textContent = "Overdue";
  $("#filters").insertBefore(overdueButton, $("#filters").lastElementChild);
  document
    .querySelectorAll(".filterchip")
    .forEach((button) => (button.onclick = () => setFilter(button.dataset.f)));
  function setFilter(value) {
    FILTER = value;
    document
      .querySelectorAll(".filterchip")
      .forEach((b) => b.classList.toggle("active", b.dataset.f === value));
    renderRows();
  }
  let views = read("aegisSavedViewsV4", []);
  if (!Array.isArray(views)) views = [];
  function renderViews() {
    $("#savedViews").innerHTML =
      '<option value="">Select a view</option>' +
      views
        .map((v, i) => `<option value="${i}">${esc(v.name)}</option>`)
        .join("");
  }
  const saveDialog = document.createElement("dialog");
  saveDialog.innerHTML =
    '<form method="dialog"><h2>Save queue view</h2><label for="viewName">Name</label><input id="viewName" maxlength="60" required placeholder="e.g. Morning review"><div class="two"><button class="btn ghost" value="cancel" formnovalidate>Cancel</button><button class="btn" value="save">Save view</button></div></form>';
  document.body.append(saveDialog);
  $("#saveView").onclick = () => {
    $("#viewName").value = "";
    saveDialog.showModal();
  };
  saveDialog.addEventListener("close", () => {
    if (saveDialog.returnValue !== "save") return;
    const name = $("#viewName").value.trim();
    if (!name) return;
    views.push({
      name,
      filter: FILTER,
      query: $("#queueSearch").value,
      sort: $("#queueSort").value,
    });
    views = views.slice(-20);
    try {
      localStorage.setItem("aegisSavedViewsV4", JSON.stringify(views));
      renderViews();
      notify("View saved on this browser.");
    } catch {
      notify("Browser storage is unavailable.");
    }
  });
  $("#savedViews").onchange = (e) => {
    if (e.target.value === "") return;
    const v = views[Number(e.target.value)];
    if (!v) return;
    $("#queueSearch").value = v.query;
    $("#queueSort").value = v.sort;
    setFilter(v.filter);
  };
  $("#deleteView").onclick = () => {
    if ($("#savedViews").value === "") return;
    views.splice(Number($("#savedViews").value), 1);
    localStorage.setItem("aegisSavedViewsV4", JSON.stringify(views));
    renderViews();
    notify("Saved view removed.");
  };
  $("#exportQueue").onclick = () => {
    const cell = (value) =>
      '"' +
      String(value ?? "")
        .replace(/^[\s]*[=+@-]/, "'$&")
        .replaceAll('"', '""') +
      '"';
    const fields = [
      "complaintId",
      "category",
      "urgency",
      "status",
      "resolutionDue",
      "confidence",
    ];
    const csv = [fields, ...visible.map((c) => fields.map((f) => c[f]))]
      .map((row) => row.map(cell).join(","))
      .join("\r\n");
    const url = URL.createObjectURL(
      new Blob(["\uFEFF" + csv], { type: "text/csv;charset=utf-8" }),
    );
    const a = document.createElement("a");
    a.href = url;
    a.download =
      "aegis-queue-" + new Date().toISOString().slice(0, 10) + ".csv";
    a.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
    notify("Filtered queue exported. Customer text and tokens are excluded.");
  };
  const providerTab = document.createElement("section");
  providerTab.id = "tab-providers";
  providerTab.className = "hide";
  providerTab.innerHTML = `<div class="provider-layout"><div><div class="provider-summary" id="providerStatus" aria-live="polite">Loading provider status...</div><button class="btn ghost" id="testProvider">Test connection (uses provider tokens)</button><p id="testResult" role="status"></p></div><div><h2 style="font-size:19px">Bring your own model</h2><p style="color:var(--muted);font-size:13px">Configure credentials in the server environment, then restart the API. Keys never enter this browser.</p><label for="providerChoice">Provider</label><select id="providerChoice"></select><label for="providerModel">Model ID</label><input id="providerModel" placeholder="Model available in your provider account" maxlength="160"><pre id="providerSnippet"></pre><button class="btn ghost" id="copyConfig">Copy configuration</button><div class="provider-list"><span>Redacted prompts</span><span>Human approval</span><span>Template fallback</span></div></div></div>`;
  document.querySelector("main").append(providerTab);
  const providerNav = document.createElement("button");
  providerNav.className = "navbtn";
  providerNav.dataset.tab = "providers";
  providerNav.innerHTML =
    '<i data-lucide="plug-zap" style="width:17px"></i>AI providers';
  providerNav.onclick = () => activate("providers");
  document.querySelector("nav").append(providerNav);
  const providers = {
    anthropic: "ANTHROPIC_API_KEY",
    nim: "NIM_API_KEY",
    openai: "OPENAI_API_KEY",
    gemini: "GEMINI_API_KEY",
    groq: "GROQ_API_KEY",
    openrouter: "OPENROUTER_API_KEY",
    ollama: "OLLAMA_API_KEY",
    custom: "AEGIS_LLM_API_KEY",
    template: null,
  };
  $("#providerChoice").innerHTML = Object.keys(providers)
    .map((p) => `<option>${p}</option>`)
    .join("");
  function snippet() {
    const p = $("#providerChoice").value,
      m = $("#providerModel")
        .value.trim()
        .replace(/[^a-zA-Z0-9_./:@-]/g, "");
    $("#providerSnippet").textContent =
      `AEGIS_LLM_PROVIDER=${p}` +
      (p !== "template" ? `\nAEGIS_LLM_MODEL=${m || "your-model-id"}` : "") +
      (providers[p] && p !== "ollama"
        ? `\n${providers[p]}=your-server-side-key`
        : "") +
      (p === "custom"
        ? "\nAEGIS_LLM_BASE_URL=https://your-host/v1"
        : p === "ollama"
          ? "\nAEGIS_LLM_BASE_URL=http://host.docker.internal:11434/v1\nAEGIS_LLM_ALLOW_HTTP=true"
          : "");
  }
  $("#providerChoice").onchange = snippet;
  $("#providerModel").oninput = snippet;
  snippet();
  $("#copyConfig").onclick = async () => {
    try {
      await navigator.clipboard.writeText($("#providerSnippet").textContent);
      notify("Configuration copied.");
    } catch {
      notify("Clipboard unavailable; select the configuration text.");
    }
  };
  async function loadProvider() {
    $("#testProvider").disabled = true;
    try {
      const r = await apiFetch(api() + "/api/providers");
      if (!r.ok) throw new Error("Sign in to view server configuration.");
      const d = await r.json();
      $("#providerStatus").innerHTML =
        `<span class="chip ${d.configured ? "ok" : "gold"}">${d.configured ? "Configured" : "Template fallback"}</span><h2>${esc(d.provider)}</h2><p>${esc(d.model || "No external model selected")}</p><div class="kv"><span>Successful calls</span><b>${Number(d.successfulCalls) || 0}</b></div><div class="kv"><span>Failed calls</span><b>${Number(d.failedCalls) || 0}</b></div><div class="kv"><span>Timeout</span><b>${Number(d.timeoutSeconds)}s</b></div><p>Counters cover this API process since startup. Configured does not imply connectivity.</p>`;
      $("#testProvider").disabled = !d.configured;
    } catch (e) {
      $("#providerStatus").textContent = e.message;
    }
  }
  $("#testProvider").onclick = async () => {
    $("#testProvider").disabled = true;
    $("#testResult").textContent = "Testing...";
    try {
      const r = await apiFetch(api() + "/api/providers/test", {
        method: "POST",
      });
      const d = await r.json();
      $("#testResult").textContent = r.ok
        ? "Connection verified. No customer data was sent."
        : d.error || "Connection failed.";
    } catch {
      $("#testResult").textContent = "API unavailable.";
    } finally {
      await loadProvider();
    }
  };
  const titles = {
    queue: ["Resolution workspace", "Every case. A clear next step."],
    esc: ["Escalations", "Cases requiring senior review."],
    ins: ["Operational insights", "Patterns, deadlines and review quality."],
    new: ["New case", "Create a case for assisted review."],
    sys: ["System architecture", "Services, trust boundaries and data flow."],
    providers: ["AI providers", "Your infrastructure. Your choice of model."],
  };
  document.addEventListener("aegis:view", (e) => {
    const pair = titles[e.detail];
    if (pair) {
      $("#viewTitle").textContent = pair[0];
      $("#viewSubtitle").textContent = pair[1];
    }
    if (e.detail === "providers") loadProvider();
  });
  const command = document.createElement("dialog");
  command.setAttribute("aria-label", "Workspace commands");
  command.innerHTML =
    '<h2>Go to</h2><input id="commandSearch" type="search" placeholder="Find a view or action" aria-label="Find a command"><div class="command-list" id="commandList"></div><form method="dialog"><button class="btn ghost">Close</button></form>';
  document.body.append(command);
  const actions = [
    ...Object.entries(titles).map(([id, [title]]) => ({
      title,
      run: () => activate(id),
    })),
    {
      title: "Toggle light / dark mode",
      run: () => document.querySelector("[data-theme-toggle]").click(),
    },
    { title: "Refresh queue", run: () => loadQueue() },
    {
      title: "Connection settings",
      run: () => {
        document.querySelector("aside").classList.add("connection-open");
        $("#apiKey").focus();
        $("#apiKey").scrollIntoView({ block: "center" });
      },
    },
  ];
  function commands() {
    const q = $("#commandSearch").value.toLowerCase();
    $("#commandList").replaceChildren();
    actions
      .filter((a) => a.title.toLowerCase().includes(q))
      .forEach((action) => {
        const b = document.createElement("button");
        b.textContent = action.title;
        b.onclick = () => {
          command.close();
          action.run();
        };
        $("#commandList").append(b);
      });
  }
  $("#commandOpen").onclick = () => {
    $("#commandSearch").value = "";
    commands();
    command.showModal();
    $("#commandSearch").focus();
  };
  $("#commandSearch").oninput = commands;
  document.addEventListener("keydown", (e) => {
    if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === "k") {
      e.preventDefault();
      $("#commandOpen").click();
    }
  });
  document.querySelector("#sysFrame").parentElement.classList.add("map-frame");
  renderViews();
  icons();
  ssoHandleCallback().then(() => {
    renderSso();
    checkStatus();
    loadQueue();
  });
})();
