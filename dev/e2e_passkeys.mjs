// Passkeys end to end in headless Chrome with a virtual WebAuthn authenticator (DevTools protocol, AL-5388):
// sets up a passkey the way the app does (kc_action=webauthn-register-passwordless after a sign-in with the
// e-mailed code), signs in with it on the login page, reads it from the account API like the app, removes it
// (kc_action=delete_credential:<id>) and tries it once more. Prints one JSON line per step;
// dev/e2e_login_link.py runs it and checks the lines. Node 22+ (fetch, WebSocket), Chrome.
//   node dev/e2e_passkeys.mjs --keycloak http://localhost:8080 --mailpit http://localhost:8025 --realm autologg-dev \
//       --address passkey@autologg.local [--otp-secret <secret>] [--chrome <path>] [--screenshots <dir>] [--phone] [--dark]
import { spawn } from "node:child_process";
import { createHash, createHmac, randomBytes } from "node:crypto";
import { existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

const args = Object.fromEntries(process.argv.slice(2).reduce((pairs, arg, i, all) =>
  arg.startsWith("--") ? [...pairs, [arg.slice(2), all[i + 1]?.startsWith("--") || all[i + 1] === undefined ? true : all[i + 1]]] : pairs, []));
const KEYCLOAK = (args.keycloak || "http://localhost:8080").replace(/\/$/, "");
const MAILPIT = (args.mailpit || "http://localhost:8025").replace(/\/$/, "");
const REALM = args.realm || "autologg-dev";
const ADDRESS = args.address;
const CLIENT = "account-console";
const REDIRECT = `${KEYCLOAK}/realms/${REALM}/account/`;
const PHONE_UA = "Mozilla/5.0 (iPhone; CPU iPhone OS 26_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Mobile/15E148 Safari/604.1";

const report = (step, data) => console.log(JSON.stringify({ step, ...data }));
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function chromePath() {
  const candidates = [args.chrome, process.env.CHROME,
    "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe",
    "C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe",
    "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome", "/usr/bin/google-chrome", "/usr/bin/chromium"];
  return candidates.find((p) => p && existsSync(p));
}

// --- Chrome and the DevTools protocol -------------------------------------------------------------------
const profile = mkdtempSync(join(tmpdir(), "e2e-passkeys-"));
const chrome = spawn(chromePath(), ["--headless=new", "--remote-debugging-port=0", `--user-data-dir=${profile}`,
  "--no-first-run", "--no-default-browser-check", "about:blank"], { stdio: "ignore" });
let port;
for (let i = 0; i < 100 && !port; i++) {
  await sleep(100);
  const file = join(profile, "DevToolsActivePort");
  if (existsSync(file)) port = readFileSync(file, "utf8").split("\n")[0].trim();
}
const version = await (await fetch(`http://127.0.0.1:${port}/json/version`)).json();
const ws = new WebSocket(version.webSocketDebuggerUrl);
await new Promise((resolve, reject) => { ws.onopen = resolve; ws.onerror = reject; });

let nextId = 1;
const pending = new Map();
const listeners = [];
ws.onmessage = (message) => {
  const data = JSON.parse(message.data);
  if (data.id && pending.has(data.id)) {
    pending.get(data.id)(data);
    pending.delete(data.id);
  } else if (data.method) {
    listeners.forEach((listener) => listener(data));
  }
};
const send = (method, params = {}, sessionId) => new Promise((resolve) => {
  const id = nextId++;
  pending.set(id, resolve);
  ws.send(JSON.stringify({ id, method, params, ...(sessionId ? { sessionId } : {}) }));
});

const { result: { targetId } } = await send("Target.createTarget", { url: "about:blank" });
const { result: { sessionId } } = await send("Target.attachToTarget", { targetId, flatten: true });
const page = (method, params) => send(method, params, sessionId);
const evaluate = async (expression) =>
  (await page("Runtime.evaluate", { expression, returnByValue: true, awaitPromise: true, userGesture: true })).result?.result?.value;
const waitFor = async (expression, ms = 15000) => {
  for (const until = Date.now() + ms; Date.now() < until; await sleep(200)) {
    try {
      if (await evaluate(expression)) return true;
    } catch { /* navigating */ }
  }
  return false;
};
const url = () => evaluate("location.href");
const title = () => evaluate("document.querySelector('#kc-page-title')?.textContent.trim() || document.title");

let shots = 0;
async function screenshot(name) {
  if (!args.screenshots) return;
  const { result } = await page("Page.captureScreenshot", { format: "png", captureBeyondViewport: false });
  writeFileSync(join(args.screenshots, `${String(++shots).padStart(2, "0")}-${name}.png`), Buffer.from(result.data, "base64"));
}

// Keycloak asks for the passkey's name with window.prompt: take the suggested one, remember the question.
const dialogs = [];
listeners.push(async (event) => {
  if (event.method === "Page.javascriptDialogOpening" && event.sessionId === sessionId) {
    dialogs.push({ message: event.params.message, suggested: event.params.defaultPrompt });
    await page("Page.handleJavaScriptDialog", { accept: true, promptText: event.params.defaultPrompt });
  }
});

// The way back to the client is caught before the account console loads (its own script would take the code).
const callbacks = [];
listeners.push(async (event) => {
  if (event.method === "Fetch.requestPaused" && event.sessionId === sessionId) {
    callbacks.push(event.params.request.url);
    await page("Fetch.fulfillRequest", { requestId: event.params.requestId, responseCode: 200,
      responseHeaders: [{ name: "Content-Type", value: "text/html" }],
      body: Buffer.from("<!doctype html><title>callback</title>").toString("base64") });
  }
});

await page("Page.enable");
await page("Network.enable");
await page("Fetch.enable", { patterns: [{ urlPattern: `${REDIRECT}*`, requestStage: "Request" }] });
await page("Emulation.setEmulatedMedia",
  { features: [{ name: "prefers-color-scheme", value: args.dark ? "dark" : "light" }] });
await page("Emulation.setDeviceMetricsOverride", args.phone
  ? { width: 390, height: 844, deviceScaleFactor: 2, mobile: true }
  : { width: 1280, height: 860, deviceScaleFactor: 1, mobile: false });
if (args.phone) await page("Network.setUserAgentOverride", { userAgent: PHONE_UA, acceptLanguage: "de" });
else await page("Network.setUserAgentOverride", { userAgent: version["User-Agent"].replace("HeadlessChrome", "Chrome"), acceptLanguage: "de" });
await page("WebAuthn.enable", { enableUI: false });
const { result: { authenticatorId } } = await page("WebAuthn.addVirtualAuthenticator", {
  options: { protocol: "ctap2", ctap2Version: "ctap2_1", transport: "internal", hasResidentKey: true,
             hasUserVerification: true, isUserVerified: true, automaticPresenceSimulation: true } });

// --- Keycloak helpers -------------------------------------------------------------------------------------
function authorization(extra = {}) {
  const verifier = randomBytes(48).toString("base64url");
  const query = new URLSearchParams({
    client_id: CLIENT, redirect_uri: REDIRECT, response_type: "code", scope: "openid",
    state: randomBytes(9).toString("base64url"), nonce: randomBytes(9).toString("base64url"),
    code_challenge: createHash("sha256").update(verifier).digest("base64url"), code_challenge_method: "S256", ...extra,
  });
  return { url: `${KEYCLOAK}/realms/${REALM}/protocol/openid-connect/auth?${query}`, verifier };
}

async function open(address) {
  await page("Page.navigate", { url: address });
  await sleep(300);
  await waitFor("document.readyState === 'complete'");
}

// Back at the client: the callback's parameters, or null when the browser stopped somewhere else.
async function callback(seen) {
  for (const until = Date.now() + 15000; Date.now() < until && callbacks.length <= seen; await sleep(200)) { /* wait */ }
  if (callbacks.length <= seen) return null;
  const back = new URL(callbacks[callbacks.length - 1]);
  return Object.fromEntries(new URLSearchParams(back.search || back.hash.slice(1)));
}

async function tokens(code, verifier) {
  const response = await fetch(`${KEYCLOAK}/realms/${REALM}/protocol/openid-connect/token`, {
    method: "POST",
    body: new URLSearchParams({ grant_type: "authorization_code", client_id: CLIENT, code, redirect_uri: REDIRECT,
                                code_verifier: verifier }) });
  return response.json();
}

async function accountPasskeys(accessToken) {
  const response = await fetch(`${KEYCLOAK}/realms/${REALM}/account/credentials?type=webauthn-passwordless`,
    { headers: { Authorization: `Bearer ${accessToken}`, Accept: "application/json" } });
  return { status: response.status, body: response.ok ? await response.json() : await response.text() };
}

async function mails() {
  const data = await (await fetch(`${MAILPIT}/api/v1/messages?limit=50`)).json();
  return data.messages.filter((m) => m.To[0].Address === ADDRESS);
}

// Keycloak's TOTP (HmacSHA1, 6 digits, 30 s); the key is the secret's UTF-8 bytes.
function totp(secret) {
  const counter = Buffer.alloc(8);
  counter.writeBigUInt64BE(BigInt(Math.floor(Date.now() / 30000)));
  const digest = createHmac("sha1", Buffer.from(secret, "utf8")).update(counter).digest();
  const offset = digest[digest.length - 1] & 0x0f;
  return String((digest.readUInt32BE(offset) & 0x7fffffff) % 1000000).padStart(6, "0");
}

async function mailedCode(before) {
  for (let i = 0; i < 40; i++) {
    const found = await mails();
    if (found.length > before) {
      const message = await (await fetch(`${MAILPIT}/api/v1/message/${found[0].ID}`)).json();
      return message.Text.match(/\b(\d{3}) (\d{3})\b/).slice(1).join("");
    }
    await sleep(250);
  }
  return null;
}

// --- 1. sign in with the e-mailed code, then set up a passkey (the app's "Passkey einrichten") ----------
try {
  const setup = authorization({ kc_action: "webauthn-register-passwordless", login_hint: ADDRESS });
  await open(setup.url);
  await screenshot("login");
  report("login page", {
    passkeyButton: await evaluate("!!document.getElementById('authenticateWebAuthnButton')"),
    autofill: await evaluate("document.getElementById('username')?.getAttribute('autocomplete')"),
    buttonText: await evaluate("document.getElementById('authenticateWebAuthnButton')?.textContent.trim()"),
  });
  const before = (await mails()).length;
  await evaluate("document.getElementById('kc-login-link').click()");
  await waitFor("!!document.getElementById('kc-login-link-code-form')");
  const code = await mailedCode(before);
  await evaluate(`(() => { const form = document.getElementById('kc-login-link-code-form');
    form.querySelector('[name=loginLinkCode]').value = ${JSON.stringify(code)}; form.requestSubmit(); return true; })()`);
  // An account with an authenticator app enters its code after the e-mailed one (--otp-secret).
  const otpAfterCode = await waitFor("!!document.getElementById('registerWebAuthn') || !!document.getElementById('kc-otp-login-form')")
    && await evaluate("!!document.getElementById('kc-otp-login-form')");
  if (otpAfterCode && args["otp-secret"]) {
    await evaluate(`(() => { const form = document.getElementById('kc-otp-login-form');
      form.querySelector('[name=otp]').value = ${JSON.stringify(totp(args["otp-secret"]))}; form.requestSubmit(); return true; })()`);
  }
  // The buttons' listeners come from module scripts: they are there once the page has loaded.
  const registerPage = await waitFor("document.readyState === 'complete' && !!document.getElementById('registerWebAuthn')");
  await screenshot("register");
  report("register page", {
    mailedCode: !!code, otpAfterCode, shown: registerPage, title: await title(),
    intro: await evaluate("document.querySelector('.al-help')?.textContent.trim()"),
    button: await evaluate("document.getElementById('registerWebAuthn')?.textContent.trim()"),
  });
  let seen = callbacks.length;
  await evaluate("document.getElementById('registerWebAuthn').click()");
  const registered = await callback(seen);
  const { result: { credentials } } = await page("WebAuthn.getCredentials", { authenticatorId });
  report("registered", {
    status: registered?.kc_action_status ?? null, dialogs,
    onAuthenticator: credentials.length, residentKey: credentials[0]?.isResidentCredential ?? null,
    rpId: credentials[0]?.rpId ?? null,
  });
  const first = registered?.code ? await tokens(registered.code, setup.verifier) : {};
  const listed = first.access_token ? await accountPasskeys(first.access_token) : { status: null, body: null };
  const claims = first.access_token ? JSON.parse(Buffer.from(first.access_token.split(".")[1], "base64url")) : {};
  report("account api", { ...listed, audience: claims.aud ?? null });

  // --- 2. a new browser session: sign in with the passkey, no e-mail -----------------------------------
  async function signedInWithPasskey(name, signIn) {
    await page("Network.clearBrowserCookies");
    const mailsBefore = (await mails()).length;
    const before = callbacks.length;
    await signIn();
    const back = await callback(before);
    await sleep(300);
    report(name, {
      code: !!back?.code, stoppedAt: back ? null : await title(),
      otpAsked: !back && await evaluate("!!document.getElementById('kc-otp-login-form')"),
      mailsSent: (await mails()).length - mailsBefore,
    });
    if (!back) await screenshot(name.replace(/\W+/g, "-"));
    return back;
  }
  // The address field offers the passkey (conditional mediation); the virtual authenticator picks it at once.
  await signedInWithPasskey("passkey sign-in (autofill)", () => open(authorization().url));
  // "Mit Passkey anmelden" opens the browser's passkey dialog - as in a browser without the autofill. From
  // here on without the autofill: the virtual authenticator would answer it on every page at once.
  await page("Page.addScriptToEvaluateOnNewDocument", {
    source: "if (window.PublicKeyCredential) PublicKeyCredential.isConditionalMediationAvailable = async () => false;" });
  const passkeyButton = async () => {
    await open(authorization().url);
    await waitFor("document.readyState === 'complete' && !!document.getElementById('authenticateWebAuthnButton')");
    await evaluate("document.getElementById('authenticateWebAuthnButton').click()");
  };
  const signedIn = await signedInWithPasskey("passkey sign-in (button)", passkeyButton);

  // --- 3. remove it the way the app does ---------------------------------------------------------------
  const id = Array.isArray(listed.body) ? listed.body.find((t) => t.type === "webauthn-passwordless")
    ?.userCredentialMetadatas?.[0]?.credential?.id : null;
  if (id && signedIn) {
    const removal = authorization({ kc_action: `delete_credential:${id}` });
    await open(removal.url);
    const confirmPage = await waitFor("!!document.getElementById('kc-accept')");
    await screenshot("delete");
    report("delete page", { shown: confirmPage, title: await title(),
                            text: await evaluate("document.getElementById('kc-delete-text')?.textContent.trim()") });
    seen = callbacks.length;
    await evaluate("document.getElementById('kc-accept').click()");
    const removed = await callback(seen);
    const after = removed?.code ? await tokens(removed.code, removal.verifier) : {};
    report("removed", { status: removed?.kc_action_status ?? null,
                        left: after.access_token ? await accountPasskeys(after.access_token) : null });

    // --- 4. the passkey is still on the device, Keycloak no longer knows it -----------------------------
    await page("Network.clearBrowserCookies");
    seen = callbacks.length;
    await passkeyButton();
    await waitFor("document.readyState === 'complete' && !!document.querySelector('.kc-feedback-text')");
    await screenshot("unknown-passkey");
    report("removed passkey", { signedIn: callbacks.length > seen,
                                error: await evaluate("document.querySelector('.kc-feedback-text')?.textContent.trim()") });
  }
} catch (e) {
  report("error", { message: String(e?.stack || e), at: await url().catch(() => null) });
} finally {
  ws.close();
  chrome.kill();
  await sleep(300);
  try { rmSync(profile, { recursive: true, force: true }); } catch { /* Chrome still holds files on Windows */ }
}
process.exit(0);
