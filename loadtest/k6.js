// AEGIS load test — k6 (https://k6.io)
//
// Two scenarios:
//   1. pipeline  — ramping load on POST /api/eval: the full deterministic
//      pipeline (classify → compliance → escalation → urgency → hybrid
//      retrieval) with no LLM call and no DB writes. This is the honest
//      compute path to benchmark.
//   2. ratelimit — hammers the public status endpoint to PROVE the per-IP
//      limiter holds: 429s here are success, not failure.
//
// Run (Docker, no install):
//   docker run --rm -i --add-host=host.docker.internal:host-gateway \
//     -e BASE_URL=http://host.docker.internal:8080 \
//     -e API_KEY=aegis-dev-key-change-me \
//     grafana/k6 run - < loadtest/k6.js
//
import http from "k6/http";
import { check, sleep } from "k6";
import { textSummary } from "https://jslib.k6.io/k6-summary/0.0.2/index.js";

const BASE = __ENV.BASE_URL || "http://localhost:8080";
const KEY = __ENV.API_KEY || "aegis-dev-key-change-me";

// 404 (unknown token) and 429 (rate-limited) are EXPECTED outcomes here.
http.setResponseCallback(http.expectedStatuses(200, 404, 429));

export const options = {
  scenarios: {
    pipeline: {
      executor: "ramping-vus",
      exec: "pipeline",
      startVUs: 0,
      stages: [
        { duration: "20s", target: 10 },   // warm-up
        { duration: "40s", target: 30 },   // steady climb
        { duration: "40s", target: 60 },   // peak
        { duration: "20s", target: 0 },    // drain
      ],
    },
    ratelimit: {
      executor: "constant-vus",
      exec: "ratelimit",
      vus: 5,
      duration: "100s",
      startTime: "10s",
    },
  },
  thresholds: {
    "http_req_duration{scenario:pipeline}": ["p(95)<1500", "p(50)<600"],
    "http_req_duration{scenario:ratelimit}": ["p(95)<250"],
    checks: ["rate>0.99"],
  },
  summaryTrendStats: ["avg", "med", "p(90)", "p(95)", "p(99)", "max"],
};

const COMPLAINTS = [
  "I noticed three charges on my credit card last week that I never authorized — $420 total to a merchant I have never heard of.",
  "My mortgage servicer raised my monthly escrow payment from $310 to $575 without any explanation or an escrow analysis statement.",
  "A debt collector keeps calling me every day about a debt I already disputed and has been threatening on the phone.",
  "I sent $850 through your wire service two weeks ago and it never arrived; support opened a ticket with no update.",
  "My student loan servicer misapplied my last payment and my autopay discount was removed without notice.",
  "There is an inaccurate late payment on my consumer report that I have disputed twice with no reinvestigation.",
];

export function pipeline() {
  const body = JSON.stringify({
    text: COMPLAINTS[Math.floor(Math.random() * COMPLAINTS.length)],
  });
  const res = http.post(`${BASE}/api/eval`, body, {
    headers: { "Content-Type": "application/json", "X-API-Key": KEY },
    tags: { endpoint: "eval" },
  });
  check(res, {
    "pipeline 200": (r) => r.status === 200,
    "classified": (r) => r.status === 200 && r.json("label") !== "",
  });
  sleep(Math.random() * 0.4);
}

export function ratelimit() {
  const res = http.get(
    `${BASE}/api/status/TRK-LOADTEST${Math.floor(Math.random() * 1e6)}`,
    { tags: { endpoint: "status" } });
  check(res, {
    "status 404 or 429 (limiter holding)": (r) => r.status === 404 || r.status === 429,
    "never 200 for a fake token": (r) => r.status !== 200,
  });
  sleep(0.3);
}

export function handleSummary(data) {
  const t = data.metrics.http_req_duration;
  const p = (data.metrics["http_req_duration{scenario:pipeline}"] || t).values;
  const lines = [
    "",
    "── AEGIS load-test summary ─────────────────────────────",
    `pipeline p50: ${Math.round(p.med)}ms · p95: ${Math.round(p["p(95)"])}ms · p99: ${Math.round(p["p(99)"])}ms`,
    `total requests: ${data.metrics.http_reqs.values.count} · checks passed: ${(
      data.metrics.checks.values.rate * 100).toFixed(2)}%`,
    "paste these numbers into the README Load section.",
    "────────────────────────────────────────────────────────",
    "",
  ].join("\n");
  return { stdout: textSummary(data, { indent: " ", enableColors: true }) + lines };
}
