#!/usr/bin/env python3
"""
End-to-end check of the login link against the local docker-compose Keycloak
(realm from dev/realm, flows set up with scripts/setup_login_link_flow.py).

Plays two browsers with separate cookie jars - the one that waits on the login
page and "another device" that opens the e-mailed link - and reads the e-mails
from Mailpit. Standard library only.

  python dev/e2e_login_link.py                      # realm autologg-dev (Username Password Form)
  python dev/e2e_login_link.py --identifier-first   # sets up realm autologg-dev-idf (Username Form + Password Form)
  python dev/e2e_login_link.py --organization       # sets up realm autologg-dev-org (organizations on, like staging-autologg)
  python dev/e2e_login_link.py --plain              # realm autologg-dev-plain: our theme, Keycloak's own flow (like prod)

[22] (passkeys) runs dev/e2e_passkeys.mjs in headless Chrome with a virtual WebAuthn authenticator; it needs
Node 22+ and Chrome (--chrome <path> if not in the usual place) and is skipped without Node.
"""
import argparse
import base64
import hashlib
import hmac
import html
import http.cookiejar
import json
import os
import re
import secrets
import shutil
import struct
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from html.parser import HTMLParser

KEYCLOAK = "http://localhost:8080"
MAILPIT = "http://localhost:8025"
CHROME = None  # [22] finds Chrome itself unless --chrome says where
DB_CONTAINER = "al5388-keycloak-db-1"  # docker compose -p al5388
CLIENT = "account-console"
DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"
PHONE_UA = "Mozilla/5.0 (iPhone; CPU iPhone OS 26_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Mobile/15E148 Safari/604.1"
USER_WITH_PASSWORD = ("test@autologg.local", "Test1234!")
USER_WITHOUT_PASSWORD = "ohnepasswort@autologg.local"

failures = []


def check(condition, label):
    print(("  ok   " if condition else "  FAIL ") + label)
    if not condition:
        failures.append(label)


class Forms(HTMLParser):
    """Collects forms with their inputs and submit buttons, plus a few page markers."""

    def __init__(self):
        super().__init__()
        self.forms = {}
        self.current = None
        self.title = ""
        self.in_title = False
        self.data_state = None

    def handle_starttag(self, tag, attrs):
        a = dict(attrs)
        if tag == "form":
            self.current = {"action": html.unescape(a.get("action", "")), "fields": {}, "buttons": {}}
            self.forms[a.get("id") or f"form{len(self.forms)}"] = self.current
        elif tag == "input" and self.current is not None and a.get("name"):
            if a.get("type") not in ("checkbox", "submit") or "checked" in a:
                self.current["fields"][a["name"]] = a.get("value", "")
        elif tag == "button" and self.current is not None and a.get("name"):
            self.current["buttons"][a["name"]] = a.get("value", "")
        elif tag == "h1" and a.get("id") == "kc-page-title":
            self.in_title = True
        if "data-state" in a and self.data_state is None:
            self.data_state = a["data-state"]

    def handle_endtag(self, tag):
        if tag == "form":
            self.current = None
        elif tag == "h1":
            self.in_title = False

    def handle_data(self, data):
        if self.in_title:
            self.title += data.strip()


class Browser:
    def __init__(self, name, user_agent):
        self.name = name
        # Keycloak marks its cookies Secure; browsers send them to http://localhost anyway, urllib does not.
        self.jar = http.cookiejar.CookieJar(http.cookiejar.DefaultCookiePolicy(secure_protocols=("https", "wss", "http")))
        self.redirects = []
        browser = self

        class Recorder(urllib.request.HTTPRedirectHandler):
            def redirect_request(self, req, fp, code, msg, headers, newurl):
                browser.redirects.append(newurl)
                return super().redirect_request(req, fp, code, msg, headers, newurl)

        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.jar), Recorder)
        self.opener.addheaders = [("User-Agent", user_agent), ("Accept-Language", "de")]
        self.url = None
        self.html = ""

    def open(self, url, data=None):
        body = None if data is None else urllib.parse.urlencode(data).encode()
        self.redirects = []
        try:
            with self.opener.open(url, data=body, timeout=30) as resp:
                self.url = resp.geturl()
                self.html = resp.read().decode("utf-8", errors="replace")
        except urllib.error.HTTPError as e:
            self.url = e.geturl()
            self.html = e.read().decode("utf-8", errors="replace")
        return self.page()

    def page(self):
        parser = Forms()
        parser.feed(self.html)
        return parser

    def submit(self, form, extra=None, button=None):
        data = dict(form["fields"])
        if button:
            data[button] = form["buttons"].get(button, "")
        data.update(extra or {})
        return self.open(urllib.parse.urljoin(self.url, form["action"]), data)

    def json(self, url):
        with self.opener.open(url, timeout=30) as resp:
            return json.loads(resp.read())

    def code_from_redirects(self):
        for url in self.redirects:
            query = urllib.parse.parse_qs(urllib.parse.urlparse(url).query)
            if "code" in query:
                return query["code"][0]
        return None


def start_login(browser, realm, extra=None):
    verifier = secrets.token_urlsafe(48)
    challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode()
    params = {
        "client_id": CLIENT,
        "redirect_uri": f"{KEYCLOAK}/realms/{realm}/account/",
        "response_type": "code",
        "scope": "openid",
        "state": secrets.token_urlsafe(12),
        "nonce": secrets.token_urlsafe(12),
        "code_challenge": challenge,
        "code_challenge_method": "S256",
        **(extra or {}),
    }
    return browser.open(f"{KEYCLOAK}/realms/{realm}/protocol/openid-connect/auth?" + urllib.parse.urlencode(params))


def mails_to(address):
    data = json.loads(urllib.request.urlopen(f"{MAILPIT}/api/v1/messages?limit=50", timeout=10).read())
    return [m for m in data["messages"] if m["To"][0]["Address"] == address]


def clear_mails():
    urllib.request.urlopen(urllib.request.Request(f"{MAILPIT}/api/v1/messages", method="DELETE"), timeout=10).read()


def latest_mail(address, wait=10):
    deadline = time.time() + wait
    while time.time() < deadline:
        found = mails_to(address)
        if found:
            message = json.loads(urllib.request.urlopen(f"{MAILPIT}/api/v1/message/{found[0]['ID']}", timeout=10).read())
            link = html.unescape(re.search(r'href="([^"]*autologg-login-link/confirm[^"]*)"', message["HTML"]).group(1))
            code = re.search(r"\b(\d{3} \d{3})\b", message["Text"]).group(1).replace(" ", "")
            return link, code, message
        time.sleep(0.5)
    return None, None, None


def link_key(link):
    return urllib.parse.parse_qs(urllib.parse.urlparse(link).query)["key"][0]


COOLDOWN_SECONDS = 3


def configure_execution(token, realm, extra=None):
    """Short resend cooldown and a high hourly cap, so the scenarios can send one e-mail after another."""
    base = f"{KEYCLOAK}/admin/realms/{realm}"

    def call(method, path, body=None):
        req = urllib.request.Request(base + path, method=method,
                                     data=None if body is None else json.dumps(body).encode())
        req.add_header("Authorization", f"Bearer {token}")
        req.add_header("Content-Type", "application/json")
        with urllib.request.urlopen(req, timeout=30) as resp:
            raw = resp.read()
            return json.loads(raw) if raw else None

    flow = call("GET", "")["browserFlow"]
    executions = call("GET", f"/authentication/flows/{urllib.parse.quote(flow, safe='')}/executions")
    ours = next(e for e in executions if e.get("providerId", "").startswith("autologg-"))
    config = {"resendCooldownSeconds": str(COOLDOWN_SECONDS), "maxSendsPerHour": "100", "maxSendsPerIpPerHour": "100",
              "passwordLogin": "true", "maxRegistrationsPerHour": "1000", **(extra or {})}
    if ours.get("authenticationConfig"):
        current = call("GET", f"/authentication/config/{ours['authenticationConfig']}")
        current["config"].update(config)
        call("PUT", f"/authentication/config/{ours['authenticationConfig']}", current)
    else:
        call("POST", f"/authentication/executions/{ours['id']}/config", {"alias": f"e2e-{realm}", "config": config})


def pause_for_cooldown():
    time.sleep(COOLDOWN_SECONDS + 0.5)


def email_first(page):
    """Page one of the e-mail-first login: the address only, posted with loginLinkNext."""
    form = page.forms.get("kc-form-login")
    return form is not None and "loginLinkNext" in form["fields"]


def choose(browser, page, address):
    """E-mail first: posts the address on page one and returns the choice page."""
    return browser.submit(page.forms["kc-form-login"], {"username": address}, "next")


def password_login(browser, realm, address, password, identifier_first):
    page = start_login(browser, realm)
    if identifier_first:
        page = browser.submit(page.forms["kc-form-login"], {"username": address}, "login")
        return browser.submit(page.forms["kc-form-login"], {"password": password}, "login")
    if email_first(page):
        page = choose(browser, page, address)
        if "kc-form-login" not in page.forms:
            return page  # account without password: page two offers the link only
        return browser.submit(page.forms["kc-form-login"], {"password": password}, "login")
    return browser.submit(page.forms["kc-form-login"], {"username": address, "password": password}, "login")


def send_link(browser, realm, address, identifier_first=False, extra=None):
    pause_for_cooldown()
    page = start_login(browser, realm, extra)
    if identifier_first:
        page = browser.submit(page.forms["kc-form-login"], {"username": address}, "login")
    elif email_first(page):
        page = choose(browser, page, address)
        return browser.submit(page.forms["kc-login-link-form"], None, "loginLink")
    return browser.submit(page.forms["kc-form-login"], None if identifier_first else {"username": address}, "loginLink")


def scenarios(realm, identifier_first, organization=False):
    clear_mails()

    print("\n[1] password login still works")
    desk = Browser("desk", DESKTOP_UA)
    page = start_login(desk, realm)
    if identifier_first:
        check("password" not in page.forms["kc-form-login"]["fields"], "first step asks for the address only")
        page = desk.submit(page.forms["kc-form-login"], {"username": USER_WITH_PASSWORD[0]}, "login")
        check("username" not in page.forms["kc-form-login"]["fields"], "second step hides the address")
        check("password" in page.forms["kc-form-login"]["fields"] or "loginLink" in page.forms["kc-form-login"]["buttons"],
              "identifier-first: second step offers password and link")
        desk.submit(page.forms["kc-form-login"], {"password": USER_WITH_PASSWORD[1]}, "login")
    else:
        check(email_first(page) and "password" not in page.forms["kc-form-login"]["fields"],
              "e-mail first: page one asks for the address only")
        check("kc-social-providers" in desk.html, "page one shows the social logins")
        page = choose(desk, page, USER_WITH_PASSWORD[0])
        check("loginLink" in page.forms["kc-login-link-form"]["buttons"]
              and "password" in page.forms["kc-form-login"]["fields"]
              and USER_WITH_PASSWORD[0] in desk.html,
              "page two offers link and password for the address")
        check("kc-social-providers" not in desk.html, "page two shows no social logins")
        page = desk.submit(page.forms["kc-form-login"], {"password": "falsch"}, "login")
        check("kc-login-link-form" in page.forms and "kc-form-login" in page.forms,
              "wrong password stays on page two")
        time.sleep(1.2)
        desk.submit(page.forms["kc-form-login"], {"password": USER_WITH_PASSWORD[1]}, "login")
    check(desk.code_from_redirects() is not None, "password login returns an authorization code")

    if not identifier_first:
        print("\n[1b] other address")
        desk = Browser("desk", DESKTOP_UA)
        page = choose(desk, start_login(desk, realm), USER_WITH_PASSWORD[0])
        page = desk.submit(page.forms["kc-login-change-address-form"], None, "loginLinkChangeAddress")
        check(email_first(page) and page.forms["kc-form-login"]["fields"].get("username") == USER_WITH_PASSWORD[0],
              "back to page one with the address filled in")
        page = choose(desk, page, "niemand@autologg.local")
        check("password" in page.forms["kc-form-login"]["fields"] and "niemand@autologg.local" in desk.html,
              "unknown address gets page two like an account with password")

    print("\n[2] link confirmed on another device finishes the waiting login")
    desk = Browser("desk", DESKTOP_UA)
    page = send_link(desk, realm, USER_WITH_PASSWORD[0], identifier_first)
    check("kc-login-link-code-form" in page.forms, "waiting page shown")
    sent = re.search(r'data-status-url="([^"]+)"', desk.html)
    status_url = html.unescape(sent.group(1))
    check(desk.json(status_url)["state"] == "pending", "status is pending before the click")
    link, code, message = latest_mail(USER_WITH_PASSWORD[0])
    check(link is not None and code is not None, "e-mail with link and code arrived")
    check("Chrome auf Windows" in message["Text"], "e-mail names the requesting browser")
    phone = Browser("phone", PHONE_UA)
    confirm = phone.open(link)
    check(confirm.data_state == "confirm", "GET of the link only asks for confirmation")
    check('name="match"' not in phone.html, "one button, no numbers to pick")
    check(desk.json(status_url)["state"] == "pending", "a GET (mail scanner) does not confirm")
    done = phone.submit(confirm.forms["kc-login-link-confirm-form"], None, "confirm")
    check(done.data_state == "done", "POST confirms")
    check(desk.json(status_url)["state"] == "confirmed", "waiting page sees the confirmation")
    again = phone.open(urllib.parse.urljoin(phone.url, confirm.forms["kc-login-link-confirm-form"]["action"]),
                       {"key": link_key(link)})
    check(again.data_state == "invalid", "link works only once")
    desk.submit(page.forms["kc-login-link-check-form"])
    check(desk.code_from_redirects() is not None, "waiting browser is logged in (authorization code)")
    check(phone.code_from_redirects() is None and not any("KEYCLOAK_IDENTITY" in c.name for c in phone.jar),
          "the other device is not logged in")

    print("\n[3] code from the e-mail")
    clear_mails()
    desk = Browser("desk", DESKTOP_UA)
    page = send_link(desk, realm, USER_WITH_PASSWORD[0], identifier_first)
    link, code, _ = latest_mail(USER_WITH_PASSWORD[0])
    wrong = str((int(code) + 1) % 1000000).zfill(6)
    page = desk.submit(page.forms["kc-login-link-code-form"], {"loginLinkCode": wrong})
    check("Der Code stimmt nicht" in desk.html, "wrong code is rejected with a message")
    desk.submit(page.forms["kc-login-link-code-form"], {"loginLinkCode": code[:3] + " " + code[3:]})
    check(desk.code_from_redirects() is not None, "right code (typed with a space) logs in")
    phone = Browser("phone", PHONE_UA)
    check(phone.open(link).data_state == "invalid", "link of a finished login is void")

    print("\n[4] wrong codes lock the code")
    clear_mails()
    desk = Browser("desk", DESKTOP_UA)
    page = send_link(desk, realm, USER_WITH_PASSWORD[0], identifier_first)
    _, code, _ = latest_mail(USER_WITH_PASSWORD[0])
    wrong = str((int(code) + 1) % 1000000).zfill(6)
    for _ in range(5):
        # Human pace: two failures within a second trip Keycloak's quick-login lockout (1 min).
        time.sleep(1.2)
        page = desk.submit(page.forms["kc-login-link-code-form"], {"loginLinkCode": wrong})
    check("Zu viele falsche Versuche" in desk.html, "fifth wrong code locks")
    desk.submit(page.forms["kc-login-link-code-form"], {"loginLinkCode": code})
    check(desk.code_from_redirects() is None, "right code no longer works after the lock")

    print("\n[5] resend cooldown, back to password")
    desk = Browser("desk", DESKTOP_UA)
    page = send_link(desk, realm, USER_WITH_PASSWORD[0], identifier_first)
    page = desk.submit(page.forms["kc-login-link-resend-form"])
    check("gerade erst eine E-Mail" in desk.html, "immediate resend is refused")
    time.sleep(COOLDOWN_SECONDS + 0.5)
    clear_mails()
    page = desk.submit(page.forms["kc-login-link-resend-form"])
    check(latest_mail(USER_WITH_PASSWORD[0])[0] is not None, "resend after the cooldown sends a new e-mail")
    page = desk.submit(page.forms["kc-login-link-cancel-form"])
    check(any("loginLink" in f["buttons"] for f in page.forms.values()),
          "cancel returns to the login page")
    if not identifier_first:
        check(page.forms["kc-form-login"]["fields"].get("username") == USER_WITH_PASSWORD[0],
              "address stays filled in")

    if not identifier_first or organization:
        # The Username Form of the identifier-first flow rejects unknown addresses itself; the
        # organization step passes them on with the address field hidden, like known ones.
        print("\n[6] unknown address looks the same, gets the registration e-mail")
        clear_mails()
        desk = Browser("desk", DESKTOP_UA)
        if organization:
            page = desk.submit(start_login(desk, realm).forms["kc-form-login"], {"username": "niemand@autologg.local"}, "login")
            check("username" not in page.forms["kc-form-login"]["fields"] and "loginLink" in page.forms["kc-form-login"]["buttons"],
                  "unknown address reaches the second step with the address hidden")
        page = send_link(desk, realm, "niemand@autologg.local", identifier_first)
        check("kc-login-link-code-form" in page.forms, "waiting page shown for unknown address")
        subject, _ = latest_mail_message("niemand@autologg.local")
        check(subject == "Dein AutoLogg-Konto anlegen", "unknown address gets the e-mail to create an account")
        _, code, _ = latest_mail("niemand@autologg.local")
        wrong = str((int(code) + 1) % 1000000).zfill(6)
        desk.submit(page.forms["kc-login-link-code-form"], {"loginLinkCode": wrong})
        check("Der Code stimmt nicht" in desk.html, "a wrong code is rejected")
        check(not admin_call(realm, "GET", "/users?email=niemand%40autologg.local&exact=true"), "and creates nothing")

    print("\n[7] account without password")
    clear_mails()
    desk = Browser("desk", DESKTOP_UA)
    page = start_login(desk, realm)
    if identifier_first:
        page = desk.submit(page.forms["kc-form-login"], {"username": USER_WITHOUT_PASSWORD}, "login")
        if not organization:
            check("password" not in page.forms["kc-form-login"]["fields"], "no password field for an account without password")
        page = desk.submit(page.forms["kc-form-login"], None, "loginLink")
    else:
        page = choose(desk, page, USER_WITHOUT_PASSWORD)
        check("kc-form-login" not in page.forms, "no password field for an account without password")
        page = desk.submit(page.forms["kc-login-link-form"], None, "loginLink")
    _, code, _ = latest_mail(USER_WITHOUT_PASSWORD)
    desk.submit(page.forms["kc-login-link-code-form"], {"loginLinkCode": code})
    check(desk.code_from_redirects() is not None, "account without password logs in with the code")


def refused(realm, identifier_first):
    print("\n[10] \"that wasn't me\"")
    address = USER_WITH_PASSWORD[0]
    clear_mails()
    desk = Browser("desk", DESKTOP_UA)
    page = send_link(desk, realm, address, identifier_first)
    status_url = html.unescape(re.search(r'data-status-url="([^"]+)"', desk.html).group(1))
    link, code, _ = latest_mail(address)
    phone = Browser("phone", PHONE_UA)
    confirm = phone.open(link)
    done = phone.submit(confirm.forms["kc-login-link-deny-form"], None, "deny")
    check(done.data_state == "denied", "\"that wasn't me\" cancels the login")
    check(desk.json(status_url)["state"] == "denied", "the waiting page learns about it")
    desk.submit(page.forms["kc-login-link-code-form"], {"loginLinkCode": code})
    check(desk.code_from_redirects() is None, "the code of a cancelled login no longer works")


def pre_hijacking(realm, identifier_first):
    print("\n[11] account registered by someone else before the owner verified it")
    address, attacker_password = "vorab@autologg.local", "Angreifer1!"
    for old in admin_call(realm, "GET", f"/users?email={urllib.parse.quote(address)}&exact=true"):
        admin_call(realm, "DELETE", f"/users/{old['id']}")
    admin_call(realm, "POST", "/users", {
        "username": address, "email": address, "firstName": "Vorab", "lastName": "ANGREIFER",
        "enabled": True, "emailVerified": False,
        "credentials": [{"type": "password", "value": attacker_password, "temporary": False}]})
    user = admin_call(realm, "GET", f"/users?email={urllib.parse.quote(address)}&exact=true")[0]
    try:
        attacker = Browser("attacker", DESKTOP_UA)
        password_login(attacker, realm, address, attacker_password, identifier_first)
        check(attacker.code_from_redirects() is not None, "the other person can sign in before (realm without verify e-mail)")

        clear_mails()
        owner = Browser("owner", DESKTOP_UA)
        page = send_link(owner, realm, address, identifier_first)
        _, code, _ = latest_mail(address)
        owner.submit(page.forms["kc-login-link-code-form"], {"loginLinkCode": code})
        check(owner.code_from_redirects() is not None, "the owner signs in with the code")

        after = admin_call(realm, "GET", f"/users/{user['id']}")
        credentials = admin_call(realm, "GET", f"/users/{user['id']}/credentials")
        sessions = admin_call(realm, "GET", f"/users/{user['id']}/sessions")
        check(after["emailVerified"], "e-mail is verified now")
        check(not any(c["type"] == "password" for c in credentials), "the password set before is gone")
        check(len(sessions) == 1, "only the owner's session is left")
        time.sleep(1.2)
        attacker = Browser("attacker", DESKTOP_UA)
        password_login(attacker, realm, address, attacker_password, identifier_first)
        check(attacker.code_from_redirects() is None, "the old password no longer signs in")
    finally:
        admin_call(realm, "DELETE", f"/users/{user['id']}")


def pre_hijacking_otp(realm, identifier_first):
    print("\n[11b] unverified address with an authenticator app: the app stays and its code is asked for")
    address, secret = "vorab-otp@autologg.local", "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXQ"
    before = otp_requirement(realm)
    if before != "CONDITIONAL":
        run_setup_script(realm, "--otp-step", "on", "--apply", "--yes")
    events = admin_call(realm, "GET", "/events/config")
    admin_call(realm, "PUT", "/events/config", {**events, "eventsEnabled": True, "enabledEventTypes": sorted(
        set(events.get("enabledEventTypes") or []) | {"VERIFY_EMAIL"})})
    delete_users(realm, address)
    # E.g. an address changed since (the server marks it unverified): password, passkey and app set up before.
    admin_call(realm, "POST", "/partialImport", {"ifResourceExists": "FAIL", "users": [{
        "username": address, "email": address, "firstName": "Vorab", "lastName": "OTP", "enabled": True,
        "emailVerified": False, "realmRoles": [f"default-roles-{realm}"],
        "credentials": [
            {"type": "password", "value": "Vorher1234!", "temporary": False},
            {"type": "webauthn-passwordless", "userLabel": "Passkey", "secretData": "{}",
             "credentialData": json.dumps({"aaguid": "00000000-0000-0000-0000-000000000000", "credentialId": "dGVzdA",
                                           "counter": 0, "attestationStatementFormat": "none"})},
            {"type": "otp", "userLabel": "Testtelefon", "secretData": json.dumps({"value": secret}),
             "credentialData": json.dumps({"subType": "totp", "digits": 6, "counter": 0, "period": 30,
                                           "algorithm": "HmacSHA1"})}]}]})
    user = find_users(realm, address)[0]
    try:
        clear_mails()
        desk = Browser("desk", DESKTOP_UA)
        page = send_link(desk, realm, address, identifier_first)
        _, code, _ = latest_mail(address)
        page = desk.submit(page.forms["kc-login-link-code-form"], {"loginLinkCode": code})
        check("kc-otp-login-form" in page.forms and desk.code_from_redirects() is None,
              "after the code from the e-mail the app code is asked for")
        types = sorted(c["type"] for c in admin_call(realm, "GET", f"/users/{user['id']}/credentials"))
        check(types == ["otp"], f"the app stays, password and passkey are gone ({types})")
        check(admin_call(realm, "GET", f"/users/{user['id']}")["emailVerified"], "address verified")
        verified = admin_call(realm, "GET", f"/events?type=VERIFY_EMAIL&user={user['id']}") or [{}]
        details = verified[0].get("details") or {}
        check(details.get("login_link_otp_kept") == "1" and (details.get("login_link_reset") or "").startswith("2 credentials"),
              f"VERIFY_EMAIL event records what was removed and that the app was kept ({details})")
        if "kc-otp-login-form" in page.forms:
            desk.submit(page.forms["kc-otp-login-form"], {"otp": totp(secret)})
            check(desk.code_from_redirects() is not None, "right app code signs in")
    finally:
        delete_users(realm, address)
        admin_call(realm, "PUT", "/events/config", events)
        if before and before != "CONDITIONAL":
            run_setup_script(realm, "--otp-step", "off", "--apply", "--yes")


def ip_limit(realm, identifier_first):
    print("\n[12] e-mails per IP address and hour")
    configure_execution(admin_token(), realm, {"maxSendsPerIpPerHour": "2"})
    # A public client address over plain http trips the realm's "SSL required: external requests".
    ssl_required = admin_call(realm, "GET", "")["sslRequired"]
    admin_call(realm, "PUT", "", {"sslRequired": "none"})
    try:
        ip = f"203.0.113.{secrets.randbelow(250) + 1}"  # TEST-NET-3: public, never routed

        def from_ip(address, client_ip):
            browser = Browser("desk", DESKTOP_UA)
            browser.opener.addheaders.append(("X-Forwarded-For", client_ip))
            send_link(browser, realm, address, identifier_first)
            return browser

        # Known accounts: the Username Form of the identifier-first flow stops unknown ones before us.
        for address in (USER_WITHOUT_PASSWORD, USER_WITH_PASSWORD[0]):
            from_ip(address, ip)
        clear_mails()
        third = from_ip(USER_WITH_PASSWORD[0], ip)
        check("aus diesem Netzwerk" in third.html, "third request from the same IP is refused with a message")
        time.sleep(2)
        check(not mails_to(USER_WITH_PASSWORD[0]), "and sends nothing")
        from_ip(USER_WITH_PASSWORD[0], f"198.51.100.{secrets.randbelow(250) + 1}")  # TEST-NET-2, fresh each run
        check(latest_mail(USER_WITH_PASSWORD[0])[0] is not None, "another IP still gets its e-mail")
    finally:
        admin_call(realm, "PUT", "", {"sslRequired": ssl_required})
        configure_execution(admin_token(), realm, {"maxSendsPerIpPerHour": "100"})


def open_mailbox(realm, identifier_first):
    print("\n[13] \"Gmail öffnen\" on the waiting page")

    def waiting(address, user_agent):
        """(button target, opens in a new tab, other links to a mailbox) of the waiting page."""
        browser = Browser("desk", user_agent)
        send_link(browser, realm, address, identifier_first)
        main = re.search(r'<a id="al-open-mailbox"[^>]*href="([^"]+)"([^>]*)>', browser.html)
        others = len(re.findall(r'href="https://(?:outlook|mail\.google)', browser.html)) - (1 if main and main.group(1).startswith("https://") else 0)
        return (html.unescape(main.group(1)), 'target="_blank"' in main.group(2), others) if main else (None, None, others)

    # Unknown addresses: the page is the same as for accounts; with registration they get its e-mail (Mailpit).
    main, new_tab, _ = waiting("niemand-al5388@gmail.com", DESKTOP_UA)
    check((main or "").startswith("https://mail.google.com/") and new_tab, "Gmail address: button opens Gmail in a new tab")
    main, new_tab, others = waiting("niemand-al5388@autologg.com", DESKTOP_UA)
    check(main == "ms-outlook:" and not new_tab and others == 0,
          "Microsoft 365 (MX) on Windows: button opens the Outlook app, no browser link")
    check(waiting("niemand-al5388@gmail.com", PHONE_UA)[0] is None, "no button on a phone (in-app browser)")
    check(waiting("niemand@autologg.local", DESKTOP_UA)[0] is None, "no button for an unknown provider")


def latest_mail_message(address, wait=10):
    """Subject and text of the newest e-mail to the address (Mailpit)."""
    deadline = time.time() + wait
    while time.time() < deadline:
        found = mails_to(address)
        if found:
            message = json.loads(urllib.request.urlopen(f"{MAILPIT}/api/v1/message/{found[0]['ID']}", timeout=10).read())
            return message["Subject"], message["Text"]
        time.sleep(0.5)
    return None, None


def finish_profile(browser, page, first="Neu", last="KONTO"):
    """Answers Keycloak's update-profile page shown after a registration; returns True when it was shown."""
    form = page.forms.get("kc-update-profile-form")
    if not form:
        return False
    browser.submit(form, {"firstName": first, "lastName": last})
    return True


def terms_stored(realm, user):
    """terms_and_conditions on the account: admin API, else (the organization realm hides unmanaged
    attributes there) the local docker-compose database."""
    if (user.get("attributes") or {}).get("terms_and_conditions"):
        return True
    try:
        rows = subprocess.run(["docker", "exec", DB_CONTAINER, "psql", "-U", "admin", "-d", "keycloak", "-tAc",
                               "select count(*) from user_attribute where name = 'terms_and_conditions' "
                               f"and user_id = '{user['id']}'"], capture_output=True, text=True, timeout=30).stdout.strip()
        return rows == "1"
    except Exception:
        return False


def show_unmanaged_attributes(realm):
    """terms_and_conditions is no user profile attribute: let the admin API show it (test realm only)."""
    profile = admin_call(realm, "GET", "/users/profile")
    if profile.get("unmanagedAttributePolicy") not in ("ADMIN_VIEW", "ADMIN_EDIT", "ENABLED"):
        profile["unmanagedAttributePolicy"] = "ADMIN_VIEW"
        admin_call(realm, "PUT", "/users/profile", profile)


def find_users(realm, address):
    return admin_call(realm, "GET", f"/users?email={urllib.parse.quote(address)}&exact=true") or []


def delete_users(realm, address):
    for user in find_users(realm, address):
        admin_call(realm, "DELETE", f"/users/{user['id']}")


def link_only(realm, identifier_first, organization=False):
    print("\n[14] default: sign in and register through the link only")
    configure_execution(admin_token(), realm, {"passwordLogin": ""})  # back to the default
    try:
        two_step = identifier_first or organization
        desk = Browser("desk", DESKTOP_UA)
        page = start_login(desk, realm)
        if two_step:  # page one is Keycloak's (Username Form or organization step)
            page = desk.submit(page.forms["kc-form-login"], {"username": USER_WITH_PASSWORD[0]}, "login")
        form = page.forms["kc-form-login"]
        check("password" not in form["fields"] and "loginLink" in form["buttons"], "no password field, link button")
        if not two_step:
            check("login-actions/registration" not in desk.html, "no link to the registration form")
            check("legen wir dein Konto an" in desk.html, "page says new users just type their address")

        if identifier_first:
            return  # its Username Form stops unknown addresses before us: no registration there

        print("\n[15] registration through the link")
        show_unmanaged_attributes(realm)
        address = f"max.neu+{secrets.token_hex(3)}@autologg.local"
        clear_mails()
        desk = Browser("desk", DESKTOP_UA)
        page = send_link(desk, realm, address, two_step)
        check("kc-login-link-code-form" in page.forms, "unknown address: the same waiting page")
        subject, _ = latest_mail_message(address)
        check(subject == "Dein AutoLogg-Konto anlegen", "e-mail asks to create the account")
        check(not admin_call(realm, "GET", f"/users?email={urllib.parse.quote(address)}&exact=true"),
              "no account before the confirmation")
        link, _, _ = latest_mail(address)
        phone = Browser("phone", PHONE_UA)
        confirm = phone.open(link)
        check("Ja, Konto anlegen" in phone.html and 'name="terms"' in phone.html,
              "confirm page: create the account, terms box")
        form = confirm.forms["kc-login-link-confirm-form"]
        again = phone.submit(form, None, "confirm")  # box not ticked
        check("Bitte akzeptiere die AGB" in phone.html and again.data_state == "confirm",
              "without the terms: asked again, nothing created")
        check(desk.json(html.unescape(re.search(r'data-status-url="([^"]+)"', desk.html).group(1)))["state"] == "pending"
              and not admin_call(realm, "GET", f"/users?email={urllib.parse.quote(address)}&exact=true"),
              "the link still works, the waiting login still waits")
        done = phone.submit(again.forms["kc-login-link-confirm-form"], {"terms": "on"}, "confirm")
        check(done.data_state == "done", "terms ticked: confirmed")
        page = desk.submit(page.forms["kc-login-link-check-form"])
        check(not finish_profile(desk, page) and desk.code_from_redirects() is not None,
              "logged in at once, no name form")
        users = admin_call(realm, "GET", f"/users?email={urllib.parse.quote(address)}&exact=true")
        user = users[0] if users else {}
        check(len(users) == 1 and user.get("emailVerified"), "account exists, address verified")
        check(user.get("firstName") == "Max" and user.get("lastName") == "NEU",
              "name from the address: Max NEU")
        check(bool(user) and terms_stored(realm, user), "terms acceptance stored")
        credentials = admin_call(realm, "GET", f"/users/{user['id']}/credentials") if users else []
        check(not credentials, "no password")
        delete_users(realm, address)

        address = f"neu-{secrets.token_hex(3)}@autologg.local"
        clear_mails()
        desk = Browser("desk", DESKTOP_UA)
        page = send_link(desk, realm, address, two_step)
        _, code, _ = latest_mail(address)
        page = desk.submit(page.forms["kc-login-link-code-form"], {"loginLinkCode": code})
        check("kc-login-link-terms-form" in page.forms and desk.code_from_redirects() is None
              and not admin_call(realm, "GET", f"/users?email={urllib.parse.quote(address)}&exact=true"),
              "right code: terms page first, no account yet")
        page = desk.submit(page.forms["kc-login-link-terms-form"], None, "register")
        check("Bitte akzeptiere die AGB" in desk.html and "kc-login-link-terms-form" in page.forms,
              "without the terms: asked again")
        desk.submit(page.forms["kc-login-link-terms-form"], {"terms": "on"}, "register")
        users = admin_call(realm, "GET", f"/users?email={urllib.parse.quote(address)}&exact=true")
        check(desk.code_from_redirects() is not None and len(users) == 1 and terms_stored(realm, users[0]),
              "terms ticked: account created, logged in")
        delete_users(realm, address)

        address = f"neu-{secrets.token_hex(3)}@autologg.local"
        admin_call(realm, "PUT", "", {"registrationAllowed": False})
        try:
            clear_mails()
            desk = Browser("desk", DESKTOP_UA)
            page = send_link(desk, realm, address, two_step)
            time.sleep(2)
            check("kc-login-link-code-form" in page.forms and not mails_to(address),
                  "realm without registration: same page, no e-mail")
        finally:
            admin_call(realm, "PUT", "", {"registrationAllowed": True})
    finally:
        configure_execution(admin_token(), realm, {"passwordLogin": "true"})


def registration_binding(realm, identifier_first, organization=False):
    if identifier_first:
        return  # its Username Form stops unknown addresses before us: no registration there
    configure_execution(admin_token(), realm, {"passwordLogin": ""})  # the default: link only
    try:
        if not organization:  # there the address comes from the organization step, not from our form
            print("\n[15b] a right code proves its own e-mail and address only")
            mine, other = (f"{name}-{secrets.token_hex(3)}@autologg.local" for name in ("eigene", "andere"))
            clear_mails()
            desk = Browser("desk", DESKTOP_UA)
            page = send_link(desk, realm, mine)
            _, code, _ = latest_mail(mine)
            page = desk.submit(page.forms["kc-login-link-code-form"], {"loginLinkCode": code})
            check("kc-login-link-terms-form" in page.forms, "right code for the own address: terms page")
            pause_for_cooldown()
            # A post the page does not offer: the same login asks for an e-mail to another address.
            page = desk.open(urllib.parse.urljoin(desk.url, page.forms["kc-login-link-terms-form"]["action"]),
                             {"username": other, "loginLink": "1"})
            check("kc-login-link-code-form" in page.forms and latest_mail(other)[0] is not None,
                  "e-mail to the other address, waiting page")
            page = desk.open(urllib.parse.urljoin(desk.url, page.forms["kc-login-link-code-form"]["action"]),
                             {"loginLinkTerms": "1", "terms": "on"})
            check(desk.code_from_redirects() is None and not find_users(realm, other) and not find_users(realm, mine),
                  "terms posted now: no account for either address, nobody signed in")
            check("kc-login-link-code-form" in page.forms, "the waiting page again")
            delete_users(realm, other)
            delete_users(realm, mine)

        print("\n[15c] an account the address got after the registration e-mail is never signed in")
        for via in ("code", "link"):
            address = f"spaeter-{secrets.token_hex(3)}@autologg.local"
            clear_mails()
            desk = Browser("desk", DESKTOP_UA)
            page = send_link(desk, realm, address, organization)
            link, code, _ = latest_mail(address)
            admin_call(realm, "POST", "/users", {"username": address, "email": address, "firstName": "Spaeter",
                                                 "lastName": "ANGELEGT", "enabled": True, "emailVerified": True})
            try:
                if via == "code":
                    page = desk.submit(page.forms["kc-login-link-code-form"], {"loginLinkCode": code})
                    page = desk.submit(page.forms["kc-login-link-terms-form"], {"terms": "on"}, "register")
                else:
                    phone = Browser("phone", PHONE_UA)
                    confirm = phone.open(link)
                    phone.submit(confirm.forms["kc-login-link-confirm-form"], {"terms": "on"}, "confirm")
                    page = desk.submit(page.forms["kc-login-link-check-form"])
                users = find_users(realm, address)
                check(desk.code_from_redirects() is None and len(users) == 1 and users[0].get("firstName") == "Spaeter",
                      f"{via}: terms accepted, but no sign-in to the account created after the e-mail")
                # The organization step hides the address field, and with it the field's message.
                check("kc-form-login" in page.forms and (organization or "melde dich noch einmal an" in desk.html),
                      f"{via}: back to the login page" + ("" if organization else ", \"Bitte melde dich noch einmal an.\""))
            finally:
                delete_users(realm, address)
    finally:
        configure_execution(admin_token(), realm, {"passwordLogin": "true"})


def exclusions(realm, identifier_first, organization=False):
    print("\n[16] excluded domain and excluded role")
    configure_execution(admin_token(), realm, {"excludedDomains": "sperre.example", "excludedRoles": "microsoft-login-only"})
    role = admin_call(realm, "GET", "/roles/microsoft-login-only") if any(
        r["name"] == "microsoft-login-only" for r in admin_call(realm, "GET", "/roles")) else None
    if role is None:
        admin_call(realm, "POST", "/roles", {"name": "microsoft-login-only"})
        role = admin_call(realm, "GET", "/roles/microsoft-login-only")
    address = "nurmicrosoft@autologg.local"
    delete_users(realm, address)
    admin_call(realm, "POST", "/users", {"username": address, "email": address, "firstName": "Nur", "lastName": "MICROSOFT",
                                         "enabled": True, "emailVerified": True})
    user = admin_call(realm, "GET", f"/users?email={urllib.parse.quote(address)}&exact=true")[0]
    admin_call(realm, "POST", f"/users/{user['id']}/role-mappings/realm", [role])
    try:
        if not identifier_first:
            clear_mails()
            desk = Browser("desk", DESKTOP_UA)
            page = send_link(desk, realm, "jemand@abteilung.sperre.example", organization)
            check("Mit Microsoft anmelden" in desk.html and "kc-login-link-code-form" not in page.forms,
                  "excluded domain: hint to the company login, no waiting page")
            time.sleep(2)
            check(not mails_to("jemand@abteilung.sperre.example"), "and no e-mail")
        clear_mails()
        desk = Browser("desk", DESKTOP_UA)
        page = send_link(desk, realm, address, identifier_first or organization)
        check("kc-login-link-code-form" in page.forms, "user with the excluded role: the usual waiting page")
        time.sleep(2)
        check(not mails_to(address), "but no e-mail")
    finally:
        delete_users(realm, address)
        configure_execution(admin_token(), realm, {"excludedDomains": "", "excludedRoles": ""})


def registration_cap(realm, identifier_first, organization=False):
    print("\n[17] cap on registration e-mails")
    if identifier_first:
        return  # no registration there
    configure_execution(admin_token(), realm, {"maxRegistrationsPerHour": "0", "passwordLogin": ""})
    try:
        address = f"neu+{secrets.token_hex(3)}@autologg.local"
        clear_mails()
        desk = Browser("desk", DESKTOP_UA)
        page = send_link(desk, realm, address, organization)
        time.sleep(2)
        check("kc-login-link-code-form" in page.forms and not mails_to(address),
              "cap reached: unknown address gets the same page, no e-mail")
        clear_mails()
        desk = Browser("desk", DESKTOP_UA)
        send_link(desk, realm, USER_WITH_PASSWORD[0], organization)
        check(latest_mail(USER_WITH_PASSWORD[0])[0] is not None, "logins of existing accounts still get their link")
    finally:
        configure_execution(admin_token(), realm, {"maxRegistrationsPerHour": "1000", "passwordLogin": "true"})


def run_setup_script(realm, *options):
    script = os.path.join(os.path.dirname(__file__), "..", "scripts", "setup_login_link_flow.py")
    return subprocess.run([sys.executable, script, "--server", KEYCLOAK, "--realm", realm, *options],
                          env={**os.environ, "KC_ADMIN_TOKEN": admin_token(), "PYTHONIOENCODING": "utf-8"},
                          capture_output=True, text=True, encoding="utf-8")


def conditional_requirement(realm, flow, provider):
    """Requirement of the flow's subflow that holds `provider` next to "Condition - user configured"."""
    executions = admin_call(realm, "GET", f"/authentication/flows/{urllib.parse.quote(flow, safe='')}/executions")
    for i, e in enumerate(executions):
        if e.get("authenticationFlow"):
            children = set()
            for child in executions[i + 1:]:
                if child["level"] <= e["level"]:
                    break
                if child["level"] == e["level"] + 1:
                    children.add(child.get("providerId"))
            if {provider, "conditional-user-configured"} <= children:
                return e["requirement"]
    return None


def otp_requirement(realm, flow=None):
    """The conditional OTP step of the flow, by default the bound browser flow."""
    return conditional_requirement(realm, flow or admin_call(realm, "GET", "")["browserFlow"], "auth-otp-form")


def reset_otp_requirement(realm):
    """The step of the bound reset-credentials flow that removes the authenticator on a password reset."""
    return conditional_requirement(realm, admin_call(realm, "GET", "")["resetCredentialsFlow"], "reset-otp")


def totp(secret, at=None):
    """Keycloak's TOTP (HmacSHA1, 6 digits, 30 s); the key is the secret's UTF-8 bytes (field totpSecret)."""
    counter = int((time.time() if at is None else at) // 30)
    digest = hmac.new(secret.encode(), struct.pack(">Q", counter), hashlib.sha1).digest()
    offset = digest[-1] & 0x0F
    return f"{(int.from_bytes(digest[offset:offset + 4], 'big') & 0x7FFFFFFF) % 1000000:06d}"


def wrong_totp(secret):
    near = {totp(secret, time.time() + d) for d in (-30, 0, 30)}
    return next(c for c in ("000000", "111111", "222222", "333333") if c not in near)


def two_factor(realm, identifier_first, organization=False):
    print("\n[18] two-factor sign-in with an authenticator app")
    before = otp_requirement(realm)
    out = run_setup_script(realm, "--otp-step", "off", "--apply", "--yes")
    check(out.returncode == 0 and otp_requirement(realm) == "DISABLED", "setup script --otp-step off: OTP step disabled")
    out = run_setup_script(realm, "--otp-step", "on", "--apply", "--yes")
    check(out.returncode == 0 and otp_requirement(realm) == "CONDITIONAL", "--otp-step on: OTP step conditional")
    check(reset_otp_requirement(realm) == "DISABLED",
          "and a password reset by e-mail no longer removes the authenticator")
    if out.returncode != 0:
        print(out.stdout[-800:], out.stderr[-800:])
    address = "zweifaktor@autologg.local"
    two_step = identifier_first or organization
    delete_users(realm, address)
    admin_call(realm, "POST", "/users", {"username": address, "email": address, "firstName": "Zwei", "lastName": "FAKTOR",
                                         "enabled": True, "emailVerified": True})
    user = admin_call(realm, "GET", f"/users?email={urllib.parse.quote(address)}&exact=true")[0]
    try:
        clear_mails()
        desk = Browser("desk", DESKTOP_UA)
        page = send_link(desk, realm, address, two_step, {"kc_action": "CONFIGURE_TOTP"})
        _, code, _ = latest_mail(address)
        page = desk.submit(page.forms["kc-login-link-code-form"], {"loginLinkCode": code})
        form = page.forms.get("kc-totp-settings-form")
        check(form is not None and desk.code_from_redirects() is None and "autologg.css" in desk.html,
              "kc_action=CONFIGURE_TOTP: after the link the authenticator setup page (AutoLogg theme)")
        if form is None:
            return
        secret = form["fields"]["totpSecret"]
        page = desk.submit(form, {"totp": wrong_totp(secret), "userLabel": "Testtelefon"})
        check("kc-totp-settings-form" in page.forms and desk.code_from_redirects() is None, "wrong app code: asked again")
        desk.submit(page.forms["kc-totp-settings-form"], {"totp": totp(secret), "userLabel": "Testtelefon"})
        check(desk.code_from_redirects() is not None, "right app code: saved, logged in")
        credentials = admin_call(realm, "GET", f"/users/{user['id']}/credentials")
        check(any(c["type"] == "otp" for c in credentials), "account has the authenticator (otp credential)")

        clear_mails()
        desk = Browser("desk", DESKTOP_UA)
        page = send_link(desk, realm, address, two_step)
        link, _, _ = latest_mail(address)
        phone = Browser("phone", PHONE_UA)
        confirm = phone.open(link)
        phone.submit(confirm.forms["kc-login-link-confirm-form"], None, "confirm")
        page = desk.submit(page.forms["kc-login-link-check-form"])
        check("kc-otp-login-form" in page.forms and desk.code_from_redirects() is None,
              "next login: after the link the app code is asked for")
        page = desk.submit(page.forms["kc-otp-login-form"], {"otp": wrong_totp(secret)})
        check("kc-otp-login-form" in page.forms and desk.code_from_redirects() is None, "wrong app code is rejected")
        desk.submit(page.forms["kc-otp-login-form"], {"otp": totp(secret, time.time() + 30)})
        check(desk.code_from_redirects() is not None, "right app code logs in")

        clear_mails()
        desk = Browser("desk", DESKTOP_UA)
        page = send_link(desk, realm, address, two_step)
        _, code, _ = latest_mail(address)
        page = desk.submit(page.forms["kc-login-link-code-form"], {"loginLinkCode": code})
        check("kc-otp-login-form" in page.forms and desk.code_from_redirects() is None,
              "with the code from the e-mail too")
    finally:
        delete_users(realm, address)
        if before and before != "CONDITIONAL":
            run_setup_script(realm, "--otp-step", "off", "--apply", "--yes")


def setup_script_copy(identifier_first, organization=False):
    print("\n[19] setup script on a realm without the forms (the way prod will go)")
    if identifier_first or organization:
        return  # once is enough; it runs on a fresh copy of the dev realm
    realm = "autologg-dev-copy"
    call = admin_caller(admin_token())

    def drop_realm():
        try:
            admin_caller(admin_token())("DELETE", f"/{realm}")
        except urllib.error.HTTPError:
            pass  # not there

    drop_realm()
    copy_dev_realm(call, realm, "AutoLogg Dev (Setup-Kopie)")
    try:
        admin_call(realm, "POST", "/roles", {"name": "microsoft-login-only"})
        # Prod's flow is older than Keycloak 26.4: a copy of the built-in one (which cannot be changed)
        # without "Condition - credential".
        original = "browser before 26.4"
        admin_call(realm, "POST", "/authentication/flows/browser/copy", {"newName": original})
        drop_passkey_conditions(realm, original)
        admin_call(realm, "PUT", "", {"browserFlow": original})
        out = run_setup_script(realm, "--otp-step", "off", "--password", "off", "--passkeys", "on", "--apply", "--yes")
        bound = admin_call(realm, "GET", "")["browserFlow"]
        check(out.returncode == 0 and bound == f"{original} with login link", f"copy bound ({bound!r})")
        check(otp_requirement(realm) == "DISABLED" and otp_requirement(realm, original) == "CONDITIONAL",
              "--otp-step off set in the copy before binding; the original flow is untouched")
        check(reset_otp_requirement(realm) == "CONDITIONAL", "off leaves the reset flow as it was")
        check(passkey_conditions(realm) and not passkey_conditions(realm, original)
              and admin_call(realm, "GET", "").get("webAuthnPolicyPasswordlessPasskeysEnabled") is True,
              "--passkeys on: \"Condition - credential\" in the copy only, passkeys in the policy")
        flow = admin_call(realm, "GET", f"/authentication/flows/{urllib.parse.quote(bound, safe='')}/executions")
        ours = next(e for e in flow if (e.get("providerId") or "").startswith("autologg-"))
        cfg = admin_call(realm, "GET", f"/authentication/config/{ours['authenticationConfig']}")["config"]
        check(cfg.get("excludedRoles") == "microsoft-login-only", "role microsoft-login-only excluded automatically")
        check(cfg.get("passwordLogin") == "false" and admin_call(realm, "GET", "")["resetPasswordAllowed"] is False,
              "--password off: forms without password, \"Forgot password\" off after binding")

        rep = admin_call(realm, "GET", f"/authentication/config/{ours['authenticationConfig']}")
        rep["config"]["excludedRoles"] = "andere-rolle"
        admin_call(realm, "PUT", f"/authentication/config/{ours['authenticationConfig']}", rep)
        out = run_setup_script(realm, "--otp-step", "on", "--apply", "--yes")
        cfg = admin_call(realm, "GET", f"/authentication/config/{ours['authenticationConfig']}")["config"]
        check(out.returncode == 0 and otp_requirement(realm) == "CONDITIONAL" and reset_otp_requirement(realm) == "DISABLED",
              "--otp-step on on the forms: OTP step conditional, reset keeps the authenticator")
        check(cfg.get("excludedRoles") == "andere-rolle,microsoft-login-only",
              f"excludedRoles extended, not replaced ({cfg.get('excludedRoles')!r})")
        if out.returncode != 0:
            print(out.stdout[-800:], out.stderr[-800:])
    finally:
        drop_realm()


def password_switch(realm, identifier_first, organization=False):
    print("\n[20] setup script --password: password and \"Forgot password\" in one switch")
    reset_page = f"{KEYCLOAK}/realms/{realm}/login-actions/reset-credentials?client_id={CLIENT}"

    def forgot_password():
        return admin_call(realm, "GET", "")["resetPasswordAllowed"]

    def password_login_values():
        flow = admin_call(realm, "GET", "")["browserFlow"]
        executions = admin_call(realm, "GET", f"/authentication/flows/{urllib.parse.quote(flow, safe='')}/executions")
        return {admin_call(realm, "GET", f"/authentication/config/{e['authenticationConfig']}")["config"].get("passwordLogin")
                if e.get("authenticationConfig") else None
                for e in executions if (e.get("providerId") or "").startswith("autologg-")}

    def reset_form_shown():
        page = Browser("desk", DESKTOP_UA).open(reset_page)
        return any("username" in form["fields"] for form in page.forms.values())

    before = forgot_password()
    try:
        out = run_setup_script(realm, "--password", "off", "--apply", "--yes")
        check(out.returncode == 0 and password_login_values() == {"false"} and forgot_password() is False,
              "--password off: no password in the login-link forms, \"Forgot password\" off in the realm")
        check(not reset_form_shown(), "no reset page, not even by its address")
        desk = Browser("desk", DESKTOP_UA)
        start_login(desk, realm)
        check("login-actions/reset-credentials" not in desk.html, "login page without \"Passwort zurücksetzen\"")
        out = run_setup_script(realm, "--password", "off")
        check(out.returncode == 0 and "nothing to do" in out.stdout, "a second run has nothing to do")

        out = run_setup_script(realm, "--password", "on", "--apply", "--yes")
        check(out.returncode == 0 and password_login_values() == {"true"} and forgot_password() is True,
              "--password on: both back in one run")
        check(reset_form_shown(), "reset page there again")
        if not (identifier_first or organization):
            desk = Browser("desk", DESKTOP_UA)
            page = choose(desk, start_login(desk, realm), USER_WITH_PASSWORD[0])
            check("password" in page.forms.get("kc-form-login", {}).get("fields", {})
                  and "login-actions/reset-credentials" in desk.html,
                  "password step with \"Passwort zurücksetzen\" again")

        out = run_setup_script(realm, "--password", "on", "--config", "passwordLogin=false")
        check(out.returncode != 0 and "contradicts" in out.stderr, "--password and --config passwordLogin must agree")
        bound = admin_call(realm, "GET", "")["browserFlow"]
        out = run_setup_script(realm, "--bind-flow", bound, "--password", "off", "--apply")
        check(out.returncode == 0 and forgot_password() is False, "--bind-flow (roll back) switches \"Forgot password\" too")
        if out.returncode != 0:
            print(out.stdout[-800:], out.stderr[-800:])
    finally:
        admin_call(realm, "PUT", "", {"resetPasswordAllowed": before})
        configure_execution(admin_token(), realm, {"passwordLogin": "true"})


PASSKEY_ACTION = "webauthn-register-passwordless"


def flow_executions(realm, flow=None):
    flow = flow or admin_call(realm, "GET", "")["browserFlow"]
    return admin_call(realm, "GET", f"/authentication/flows/{urllib.parse.quote(flow, safe='')}/executions")


def passkey_conditions(realm, flow=None):
    """Configs of the flow's "Condition - credential" steps."""
    return [admin_call(realm, "GET", f"/authentication/config/{e['authenticationConfig']}")["config"]
            for e in flow_executions(realm, flow)
            if e.get("providerId") == "conditional-credential" and e.get("authenticationConfig")]


def drop_passkey_conditions(realm, flow=None):
    """Makes the flow look like one from before Keycloak 26.4 (staging, prod): no "Condition - credential"."""
    for e in flow_executions(realm, flow):
        if e.get("providerId") == "conditional-credential":
            admin_call(realm, "DELETE", f"/authentication/executions/{e['id']}")


def passkey_required_action(realm):
    return next((a for a in admin_call(realm, "GET", "/authentication/required-actions") if a["alias"] == PASSKEY_ACTION),
                None)


def passkey_switch(realm, identifier_first, organization=False):
    print("\n[21] setup script --passkeys: passkey sign-in, setting one up from the app, no OTP code after a passkey")
    before = admin_call(realm, "GET", "").get("webAuthnPolicyPasswordlessPasskeysEnabled")
    drop_passkey_conditions(realm)
    try:
        out = run_setup_script(realm, "--passkeys", "on")
        check(out.returncode == 0 and "OTP code after a passkey sign-in: asked as well" in out.stdout
              and 'gets "Condition - credential"' in out.stdout,
              "dry run on a flow without the check: the code would be asked after a passkey, the plan adds the condition")
        out = run_setup_script(realm, "--passkeys", "on", "--apply", "--yes")
        rep = admin_call(realm, "GET", "")
        check(out.returncode == 0 and rep.get("webAuthnPolicyPasswordlessPasskeysEnabled") is True
              and rep["webAuthnPolicyPasswordlessRpId"] == urllib.parse.urlparse(KEYCLOAK).hostname
              and rep["webAuthnPolicyPasswordlessResidentKey"] == "required"
              and rep["webAuthnPolicyPasswordlessUserVerificationRequirement"] == "required",
              "--passkeys on: passwordless policy with passkeys, RP ID of the server, resident key, user verification")
        check(rep["webAuthnPolicyPasswordlessRpEntityName"] == "AutoLogg"
              and rep["webAuthnPolicyPasswordlessAvoidSameAuthenticatorRegister"] is True,
              "named AutoLogg, one passkey per authenticator")
        check((passkey_required_action(realm) or {}).get("enabled") is True, f"required action {PASSKEY_ACTION} on")
        check({"credentials": "webauthn-passwordless", "included": "false"} in passkey_conditions(realm),
              "the OTP step gets \"Condition - credential\": no code after a passkey")
        if out.returncode != 0:
            print(out.stdout[-800:], out.stderr[-800:])
        desk = Browser("desk", DESKTOP_UA)
        start_login(desk, realm)
        check('id="authenticateWebAuthnButton"' in desk.html and 'autocomplete="username webauthn"' in desk.html,
              "login page offers the passkey: autofill in the address field and \"Mit Passkey anmelden\"")
        out = run_setup_script(realm, "--passkeys", "on")
        check(out.returncode == 0 and "nothing to do" in out.stdout and "sign-in on" in out.stdout,
              "a second run has nothing to do")

        rp_id = rep["webAuthnPolicyPasswordlessRpId"]
        out = run_setup_script(realm, "--passkeys", "off", "--apply", "--yes")
        rep = admin_call(realm, "GET", "")
        check(out.returncode == 0 and rep.get("webAuthnPolicyPasswordlessPasskeysEnabled") is False
              and (passkey_required_action(realm) or {}).get("enabled") is False,
              "--passkeys off: no passkey sign-in, no new passkeys")
        check(rep["webAuthnPolicyPasswordlessRpId"] == rp_id and rep["webAuthnPolicyPasswordlessResidentKey"] == "required",
              "the rest of the policy stays (Keycloak rebuilds it from every PUT that names it)")
        desk = Browser("desk", DESKTOP_UA)
        start_login(desk, realm)
        check("authenticateWebAuthnButton" not in desk.html, "login page without passkey")
        out = run_setup_script(realm, "--passkey-rp-id", "example.com")
        check(out.returncode != 0 and "--passkeys on" in out.stderr, "--passkey-rp-id only with --passkeys on")
    finally:
        run_setup_script(realm, "--passkeys", "on" if before else "off", "--apply", "--yes")


def passkeys_in_browser(identifier_first, organization=False):
    print("\n[22] passkey set up, used and removed in Chrome (virtual WebAuthn authenticator)")
    if identifier_first or organization:
        return  # once is enough; the browser script plays the one-page form of staging
    node = shutil.which("node")
    if not node:
        print("  skip: no node")
        return
    script = os.path.join(os.path.dirname(__file__), "e2e_passkeys.mjs")
    realm, address, secret = "autologg-dev", "passkey@autologg.local", "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP"
    before = admin_call(realm, "GET", "").get("webAuthnPolicyPasswordlessPasskeysEnabled")
    run_setup_script(realm, "--passkeys", "on", "--apply", "--yes")
    configure_execution(admin_token(), realm, {"passwordLogin": "false"})
    delete_users(realm, address)
    # With an authenticator app (two-factor sign-in): a passkey must not ask for its code. Credentials come in
    # through the partial import only, and imported users get no default roles (no audience "account").
    admin_call(realm, "POST", "/partialImport", {"ifResourceExists": "FAIL", "users": [{
        "username": address, "email": address, "firstName": "Pass", "lastName": "KEY", "enabled": True,
        "emailVerified": True, "realmRoles": [f"default-roles-{realm}"],
        "credentials": [{"type": "otp", "userLabel": "Testtelefon", "secretData": json.dumps({"value": secret}),
                         "credentialData": json.dumps({"subType": "totp", "digits": 6, "counter": 0, "period": 30,
                                                       "algorithm": "HmacSHA1"})}]}]})
    try:
        clear_mails()
        run = subprocess.run([node, script, "--keycloak", KEYCLOAK, "--mailpit", MAILPIT, "--realm", realm,
                              "--address", address, "--otp-secret", secret, *(["--chrome", CHROME] if CHROME else [])],
                             capture_output=True, text=True, encoding="utf-8", timeout=240)
        steps = {}
        for line in run.stdout.splitlines():
            if line.startswith("{"):
                step = json.loads(line)
                steps[step["step"]] = step
        if "error" in steps or "login page" not in steps:
            print("  " + (steps.get("error", {}).get("message") or run.stderr[-800:] or run.stdout[-800:]))
        login, register = steps.get("login page", {}), steps.get("register page", {})
        check(login.get("passkeyButton") and login.get("autofill") == "username webauthn"
              and login.get("buttonText") == "Mit Passkey anmelden", "login page: passkey autofill and \"Mit Passkey anmelden\"")
        check(register.get("mailedCode") and register.get("otpAfterCode") and register.get("title") == "Passkey einrichten",
              "kc_action=webauthn-register-passwordless: after the e-mailed and the app code the page \"Passkey einrichten\"")
        registered = steps.get("registered", {})
        dialog = (registered.get("dialogs") or [{}])[0]
        check(registered.get("status") == "success" and registered.get("onAuthenticator") == 1
              and registered.get("residentKey") is True
              and registered.get("rpId") == urllib.parse.urlparse(KEYCLOAK).hostname,
              "passkey created (discoverable, for the RP ID), back with kc_action_status=success")
        check((dialog.get("message") or "").startswith("Wie soll der Passkey")
              and dialog.get("suggested") not in (None, "", "Passkey"),
              f"its name asked for, the device suggested ({dialog.get('suggested')!r})")
        listed = steps.get("account api", {})
        types = listed.get("body") if isinstance(listed.get("body"), list) else []
        credential = next((m["credential"] for t in types if t["type"] == "webauthn-passwordless"
                           for m in t.get("userCredentialMetadatas", [])), {})
        check(listed.get("status") == 200 and credential.get("id") and credential.get("userLabel") == dialog.get("suggested")
              and isinstance(credential.get("createdDate"), int),
              "account API lists it the way the app reads it (type webauthn-passwordless: id, name, date)")
        for name in ("passkey sign-in (autofill)", "passkey sign-in (button)"):
            step = steps.get(name, {})
            check(step.get("code") is True and step.get("otpAsked") is False and step.get("mailsSent") == 0,
                  f"{name.removeprefix('passkey sign-in ')}: signed in with the passkey - no e-mail, no app code")
        check(steps.get("delete page", {}).get("title") == f"„{dialog.get('suggested')}“ entfernen",
              "kc_action=delete_credential: „<name>“ entfernen")
        removed = steps.get("removed", {})
        left = ((removed.get("left") or {}).get("body") or [{}])
        check(removed.get("status") == "success" and not left[0].get("userCredentialMetadatas"), "removed, the list is empty")
        gone = steps.get("removed passkey", {})
        check(gone.get("signedIn") is False and "kennt AutoLogg nicht" in (gone.get("error") or ""),
              "the removed passkey no longer signs in, the page says why")

        desk = Browser("desk", DESKTOP_UA)
        page = send_link(desk, realm, address)
        _, code, _ = latest_mail(address)
        page = desk.submit(page.forms["kc-login-link-code-form"], {"loginLinkCode": code})
        check("kc-otp-login-form" in page.forms and desk.code_from_redirects() is None,
              "with the link the app code is still asked")
    finally:
        delete_users(realm, address)
        configure_execution(admin_token(), realm, {"passwordLogin": "true"})
        if not before:
            run_setup_script(realm, "--passkeys", "off", "--apply", "--yes")


def admin_call(realm, method, path, body=None):
    req = urllib.request.Request(f"{KEYCLOAK}/admin/realms/{realm}{path}", method=method,
                                 data=None if body is None else json.dumps(body).encode())
    req.add_header("Authorization", f"Bearer {admin_token()}")
    req.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(req, timeout=30) as resp:
        raw = resp.read()
        return json.loads(raw) if raw else None


def update_password_dropped(realm, identifier_first):
    print("\n[9] pending password update")
    address, password = USER_WITH_PASSWORD
    user = admin_call(realm, "GET", f"/users?email={urllib.parse.quote(address)}&exact=true")[0]
    admin_call(realm, "PUT", f"/users/{user['id']}", {"requiredActions": ["UPDATE_PASSWORD"]})
    try:
        desk = Browser("desk", DESKTOP_UA)
        page = start_login(desk, realm)
        if identifier_first:
            page = desk.submit(page.forms["kc-form-login"], {"username": address}, "login")
            desk.submit(page.forms["kc-form-login"], {"password": password}, "login")
        else:
            page = choose(desk, page, address)
            desk.submit(page.forms["kc-form-login"], {"password": password}, "login")
        check(desk.code_from_redirects() is None and "password-new" in desk.html,
              "password login still asks for a new password")

        clear_mails()
        desk = Browser("desk", DESKTOP_UA)
        page = send_link(desk, realm, address, identifier_first)
        _, code, _ = latest_mail(address)
        desk.submit(page.forms["kc-login-link-code-form"], {"loginLinkCode": code})
        check(desk.code_from_redirects() is not None, "link login goes straight through")
        actions = admin_call(realm, "GET", f"/users/{user['id']}")["requiredActions"]
        check("UPDATE_PASSWORD" not in actions, "UPDATE_PASSWORD is gone from the account")
    finally:
        admin_call(realm, "PUT", f"/users/{user['id']}", {"requiredActions": []})


def password_off(realm, identifier_first, organization=False):
    print("\n[8] password login switched off")
    # Admin tokens of the master realm live 60 s; the scenarios before took longer.
    configure_execution(admin_token(), realm, {"passwordLogin": "false"})
    try:
        clear_mails()
        desk = Browser("desk", DESKTOP_UA)
        page = start_login(desk, realm)
        if identifier_first:
            page = desk.submit(page.forms["kc-form-login"], {"username": USER_WITH_PASSWORD[0]}, "login")
        form = page.forms["kc-form-login"]
        check("password" not in form["fields"] and "login" not in form["buttons"], "no password field and no password button")
        check("loginLink" in form["buttons"], "link button offered")
        # A hand-made post with the right password must not log in; it sends the link instead.
        pause_for_cooldown()
        posted = {"password": USER_WITH_PASSWORD[1]}
        if not identifier_first:
            posted["username"] = USER_WITH_PASSWORD[0]
        page = desk.submit(form, posted)
        check(desk.code_from_redirects() is None, "correct password is not accepted")
        check("kc-login-link-code-form" in page.forms, "the post sent the link instead")
        if identifier_first:
            check("kc-login-link-cancel-form" not in page.forms, "waiting page offers no way back to a password")
        else:
            check("Andere E-Mail-Adresse" in desk.html and "Mit Passwort anmelden" not in desk.html,
                  "waiting page offers another address instead of the password")
        _, code, _ = latest_mail(USER_WITH_PASSWORD[0])
        desk.submit(page.forms["kc-login-link-code-form"], {"loginLinkCode": code})
        check(desk.code_from_redirects() is not None, "code from the e-mail still logs in")
        if organization:
            known = set(page.forms)
            desk = Browser("desk", DESKTOP_UA)
            page = send_link(desk, realm, "niemand@autologg.local", identifier_first)
            check(set(page.forms) == known, "unknown address gets the same waiting page")
    finally:
        configure_execution(admin_token(), realm, {"passwordLogin": "true"})


def admin_caller(token):
    """Admin REST calls below /admin/realms; they return (json, headers), or (None, None) on 409."""
    base = f"{KEYCLOAK}/admin/realms"

    def call(method, path, body=None):
        req = urllib.request.Request(base + path, method=method,
                                     data=None if body is None else json.dumps(body).encode())
        req.add_header("Authorization", f"Bearer {token}")
        req.add_header("Content-Type", "application/json")
        try:
            with urllib.request.urlopen(req, timeout=30) as resp:
                raw = resp.read()
                return json.loads(raw) if raw else None, resp.headers
        except urllib.error.HTTPError as e:
            if e.code == 409:
                return None, None
            raise

    return call


def copy_dev_realm(call, realm, display_name, **settings):
    """Creates the realm from dev/realm/autologg-dev-realm.json unless it exists."""
    rep = json.load(open(os.path.join(os.path.dirname(__file__), "realm", "autologg-dev-realm.json"), encoding="utf-8"))
    rep.update(realm=realm, displayName=display_name, **settings)
    for user in rep["users"]:
        user["realmRoles"] = [f"default-roles-{realm}"]
    call("POST", "", rep)


def setup_identifier_first(token):
    """Realm autologg-dev-idf: copy of autologg-dev with Username Form + Password Form, then the setup script."""
    realm = "autologg-dev-idf"
    call = admin_caller(token)
    copy_dev_realm(call, realm, "AutoLogg Dev (identifier first)")
    if call("GET", f"/{realm}")[0]["browserFlow"] != "browser":
        return realm  # already set up by an earlier run
    flow = "browser idf"
    call("POST", f"/{realm}/authentication/flows/browser/copy", {"newName": flow})
    forms = f"{flow} forms"
    q = urllib.parse.quote
    for provider in ("auth-username-form", "auth-password-form"):
        _, headers = call("POST", f"/{realm}/authentication/flows/{q(forms)}/executions/execution", {"provider": provider})
        new_id = headers["Location"].rsplit("/", 1)[-1]
        executions = call("GET", f"/{realm}/authentication/flows/{q(forms)}/executions")[0]
        for _ in range(len(executions)):
            call("POST", f"/{realm}/authentication/executions/{new_id}/raise-priority")
        executions = call("GET", f"/{realm}/authentication/flows/{q(forms)}/executions")[0]
        mine = next(e for e in executions if e["id"] == new_id)
        mine["requirement"] = "REQUIRED"
        call("PUT", f"/{realm}/authentication/flows/{q(forms)}/executions", mine)
    executions = call("GET", f"/{realm}/authentication/flows/{q(forms)}/executions")[0]
    # Order: Username Form, Password Form, then the rest; drop the one-step form.
    ids = {e["providerId"]: e["id"] for e in executions if e.get("providerId")}
    call("POST", f"/{realm}/authentication/executions/{ids['auth-username-form']}/raise-priority")
    call("DELETE", f"/{realm}/authentication/executions/{ids['auth-username-password-form']}")
    call("PUT", f"/{realm}", {"browserFlow": flow})
    return realm


def setup_organization(token):
    """Realm autologg-dev-org: copy of autologg-dev with organizations on and one organization, as in
    staging-autologg. Its Organization Identity-First Login asks for the address first and hides the
    field on the next page, for unknown addresses too."""
    realm = "autologg-dev-org"
    call = admin_caller(token)
    copy_dev_realm(call, realm, "AutoLogg Dev (organization)", organizationsEnabled=True)
    if not call("GET", f"/{realm}/organizations")[0]:
        call("POST", f"/{realm}/organizations",
             {"name": "Beispiel GmbH", "alias": "beispiel", "domains": [{"name": "beispiel.example"}]})
    return realm


def setup_plain(token):
    """Realm autologg-dev-plain: the theme without the login link - Keycloak's own browser flow, as in prod."""
    realm = "autologg-dev-plain"
    copy_dev_realm(admin_caller(token), realm, "AutoLogg Dev (ohne Login-Link)")
    return realm


def plain_scenarios(realm):
    """Without the login link everything works as with Keycloak's own forms."""
    address, password = USER_WITH_PASSWORD
    clear_mails()

    print("\n[P1] login page: Keycloak's one-page form, no link")
    desk = Browser("desk", DESKTOP_UA)
    page = start_login(desk, realm)
    form = page.forms["kc-form-login"]
    check({"username", "password"} <= set(form["fields"]) and "loginLink" not in form["buttons"]
          and "loginLinkNext" not in form["fields"], "address and password on one page, no link button")
    check("kc-social-providers" not in desk.html or "al-social-two" not in desk.html, "social logins in one column")
    page = desk.submit(form, {"username": address, "password": "falsch"}, "login")
    check("kc-form-login" in page.forms and desk.code_from_redirects() is None
          and page.forms["kc-form-login"]["fields"].get("username") == address,
          "wrong password: same page with an error, address kept")
    time.sleep(1.2)
    desk.submit(page.forms["kc-form-login"], {"username": address, "password": password}, "login")
    check(desk.code_from_redirects() is not None, "right password logs in")

    print("\n[P2] forgot password")
    desk = Browser("desk", DESKTOP_UA)
    start_login(desk, realm)
    reset = re.search(r'href="([^"]*login-actions/reset-credentials[^"]*)"', desk.html)
    check(reset is not None, "login page links to \"Passwort zurücksetzen\"")
    page = desk.open(urllib.parse.urljoin(desk.url, html.unescape(reset.group(1))))
    reset_form = next(iter(page.forms.values()))
    desk.submit(reset_form, {"username": address})
    time.sleep(2)
    check(any("Passwort" in m.get("Subject", "") or "password" in m.get("Subject", "").lower()
              for m in mails_to(address)), "reset e-mail arrives")

    print("\n[P3] registration")
    desk = Browser("desk", DESKTOP_UA)
    start_login(desk, realm)
    register = re.search(r'href="([^"]*login-actions/registration[^"]*)"', desk.html)
    check(register is not None, "login page links to the registration")
    page = desk.open(urllib.parse.urljoin(desk.url, html.unescape(register.group(1))))
    new_address = f"neu-{secrets.token_hex(3)}@autologg.local"
    reg_form = page.forms.get("kc-register-form") or next(iter(page.forms.values()))
    desk.submit(reg_form, {"email": new_address, "firstName": "Neu", "lastName": "TEST",
                           "password": "Neu12345!", "password-confirm": "Neu12345!"})
    check(desk.code_from_redirects() is not None, "registration logs the new user in")
    created = admin_call(realm, "GET", f"/users?email={urllib.parse.quote(new_address)}&exact=true")
    for user in created:
        admin_call(realm, "DELETE", f"/users/{user['id']}")

    print("\n[P4] two-factor sign-in with an authenticator app")
    address, password = "zweifaktor@autologg.local", "Zwei1234!"
    delete_users(realm, address)
    admin_call(realm, "POST", "/users", {"username": address, "email": address, "firstName": "Zwei", "lastName": "FAKTOR",
                                         "enabled": True, "emailVerified": True,
                                         "credentials": [{"type": "password", "value": password, "temporary": False}]})
    try:
        desk = Browser("desk", DESKTOP_UA)
        page = start_login(desk, realm, {"kc_action": "CONFIGURE_TOTP"})
        page = desk.submit(page.forms["kc-form-login"], {"username": address, "password": password}, "login")
        form = page.forms.get("kc-totp-settings-form")
        check(form is not None and "autologg.css" in desk.html, "kc_action=CONFIGURE_TOTP: setup page (AutoLogg theme)")
        if form is not None:
            desk.submit(form, {"totp": totp(form["fields"]["totpSecret"]), "userLabel": "Testtelefon"})
            check(desk.code_from_redirects() is not None, "right app code: saved, logged in")
            desk = Browser("desk", DESKTOP_UA)
            page = start_login(desk, realm)
            page = desk.submit(page.forms["kc-form-login"], {"username": address, "password": password}, "login")
            check("kc-otp-login-form" in page.forms and desk.code_from_redirects() is None,
                  "next login asks for the app code")
            desk.submit(page.forms["kc-otp-login-form"], {"otp": totp(form["fields"]["totpSecret"], time.time() + 30)})
            check(desk.code_from_redirects() is not None, "right app code logs in")
    finally:
        delete_users(realm, address)


def admin_token():
    form = urllib.parse.urlencode({"grant_type": "password", "client_id": "admin-cli",
                                   "username": "admin", "password": "password"}).encode()
    with urllib.request.urlopen(f"{KEYCLOAK}/realms/master/protocol/openid-connect/token", data=form, timeout=30) as r:
        return json.loads(r.read())["access_token"]


def main():
    global KEYCLOAK, MAILPIT, CHROME, DB_CONTAINER
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--identifier-first", action="store_true")
    p.add_argument("--organization", action="store_true")
    p.add_argument("--plain", action="store_true", help="theme only, Keycloak's own flow (like prod)")
    p.add_argument("--keycloak", default=KEYCLOAK, help=f"Keycloak base URL (default {KEYCLOAK})")
    p.add_argument("--mailpit", default=MAILPIT, help=f"Mailpit base URL (default {MAILPIT})")
    p.add_argument("--db-container", default=DB_CONTAINER, help=f"Keycloak's database container (default {DB_CONTAINER})")
    p.add_argument("--chrome", help="Chrome for [22] (default: the usual install paths)")
    args = p.parse_args()
    KEYCLOAK = args.keycloak.rstrip("/")
    MAILPIT = args.mailpit.rstrip("/")
    DB_CONTAINER = args.db_container
    CHROME = args.chrome

    realm = "autologg-dev"
    token = admin_token()
    if args.plain:
        realm = setup_plain(token)
        print(f"Realm {realm}")
        plain_scenarios(realm)
        print(f"\n{'All checks passed' if not failures else str(len(failures)) + ' check(s) failed'}")
        sys.exit(1 if failures else 0)
    if args.identifier_first or args.organization:
        realm = setup_identifier_first(token) if args.identifier_first else setup_organization(token)
        out = run_setup_script(realm, "--apply", "--yes")
        print(out.stdout[-1200:], out.stderr[-600:])
    configure_execution(token, realm)

    print(f"Realm {realm}")
    two_step = args.identifier_first or args.organization
    scenarios(realm, two_step, args.organization)
    password_off(realm, two_step, args.organization)
    update_password_dropped(realm, two_step)
    refused(realm, two_step)
    pre_hijacking(realm, two_step)
    pre_hijacking_otp(realm, two_step)
    ip_limit(realm, two_step)
    if not args.identifier_first:  # its Username Form stops the unknown test addresses before us
        open_mailbox(realm, two_step)
    link_only(realm, args.identifier_first, args.organization)
    registration_binding(realm, args.identifier_first, args.organization)
    exclusions(realm, args.identifier_first, args.organization)
    registration_cap(realm, args.identifier_first, args.organization)
    two_factor(realm, args.identifier_first, args.organization)
    password_switch(realm, args.identifier_first, args.organization)
    passkey_switch(realm, args.identifier_first, args.organization)
    passkeys_in_browser(args.identifier_first, args.organization)
    setup_script_copy(args.identifier_first, args.organization)
    print(f"\n{'All checks passed' if not failures else str(len(failures)) + ' check(s) failed'}")
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()
