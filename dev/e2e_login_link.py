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
"""
import argparse
import base64
import hashlib
import html
import http.cookiejar
import json
import os
import re
import secrets
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from html.parser import HTMLParser

KEYCLOAK = "http://localhost:8080"
MAILPIT = "http://localhost:8025"
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


def start_login(browser, realm):
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
              **(extra or {})}
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


def match_number(browser):
    """The number the waiting page asks to pick on the confirm page."""
    found = re.search(r'id="al-match-number"[^>]*>\s*(\d+)\s*<', browser.html)
    return found.group(1) if found else None


def confirm_on_phone(phone, link, number):
    """Opens the e-mailed link on another device and picks the number; returns the page after the POST."""
    confirm = phone.open(link)
    return phone.submit(confirm.forms["kc-login-link-confirm-form"], {"match": number})


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


def send_link(browser, realm, address, identifier_first=False):
    pause_for_cooldown()
    page = start_login(browser, realm)
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
    number = match_number(desk)
    check(number is not None, "waiting page shows the number to pick")
    phone = Browser("phone", PHONE_UA)
    confirm = phone.open(link)
    check(confirm.data_state == "confirm", "GET of the link only asks for confirmation")
    choices = re.findall(r'name="match" value="(\d+)"', phone.html)
    check(len(choices) == 3 and number in choices, "confirm page offers three numbers, one of them right")
    check(desk.json(status_url)["state"] == "pending", "a GET (mail scanner) does not confirm")
    done = phone.submit(confirm.forms["kc-login-link-confirm-form"], {"match": number})
    check(done.data_state == "done", "POST with the right number confirms")
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
        print("\n[6] unknown address looks the same, sends nothing")
        clear_mails()
        desk = Browser("desk", DESKTOP_UA)
        if organization:
            page = desk.submit(start_login(desk, realm).forms["kc-form-login"], {"username": "niemand@autologg.local"}, "login")
            check("username" not in page.forms["kc-form-login"]["fields"] and "loginLink" in page.forms["kc-form-login"]["buttons"],
                  "unknown address reaches the second step with the address hidden")
        page = send_link(desk, realm, "niemand@autologg.local", identifier_first)
        check("kc-login-link-code-form" in page.forms, "waiting page shown for unknown address")
        time.sleep(2)
        check(not mails_to("niemand@autologg.local"), "no e-mail for unknown address")
        desk.submit(page.forms["kc-login-link-code-form"], {"loginLinkCode": "123456"})
        check("Der Code stimmt nicht" in desk.html, "any code is wrong for unknown address")

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


def number_matching(realm, identifier_first):
    print("\n[10] wrong number and \"that wasn't me\"")
    address = USER_WITH_PASSWORD[0]
    clear_mails()
    desk = Browser("desk", DESKTOP_UA)
    page = send_link(desk, realm, address, identifier_first)
    status_url = html.unescape(re.search(r'data-status-url="([^"]+)"', desk.html).group(1))
    number = match_number(desk)
    link, code, _ = latest_mail(address)
    phone = Browser("phone", PHONE_UA)
    confirm = phone.open(link)
    wrong = next(c for c in re.findall(r'name="match" value="(\d+)"', phone.html) if c != number)
    done = phone.submit(confirm.forms["kc-login-link-confirm-form"], {"match": wrong})
    check(done.data_state == "denied", "a wrong number cancels the login")
    check(desk.json(status_url)["state"] == "denied", "the waiting page learns about it")
    desk.submit(page.forms["kc-login-link-code-form"], {"loginLinkCode": code})
    check(desk.code_from_redirects() is None, "the code of a cancelled login no longer works")

    clear_mails()
    desk = Browser("desk", DESKTOP_UA)
    send_link(desk, realm, address, identifier_first)
    status_url = html.unescape(re.search(r'data-status-url="([^"]+)"', desk.html).group(1))
    link, _, _ = latest_mail(address)
    phone = Browser("phone", PHONE_UA)
    confirm = phone.open(link)
    done = phone.submit(confirm.forms["kc-login-link-deny-form"], None, "deny")
    check(done.data_state == "denied" and desk.json(status_url)["state"] == "denied",
          "\"that wasn't me\" cancels the login")


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
        from_ip(USER_WITH_PASSWORD[0], "198.51.100.9" if not ip.endswith(".9") else "198.51.100.10")
        check(latest_mail(USER_WITH_PASSWORD[0])[0] is not None, "another IP still gets its e-mail")
    finally:
        admin_call(realm, "PUT", "", {"sslRequired": ssl_required})
        configure_execution(admin_token(), realm, {"maxSendsPerIpPerHour": "100"})


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


def admin_token():
    form = urllib.parse.urlencode({"grant_type": "password", "client_id": "admin-cli",
                                   "username": "admin", "password": "password"}).encode()
    with urllib.request.urlopen(f"{KEYCLOAK}/realms/master/protocol/openid-connect/token", data=form, timeout=30) as r:
        return json.loads(r.read())["access_token"]


def main():
    global KEYCLOAK
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--identifier-first", action="store_true")
    p.add_argument("--organization", action="store_true")
    p.add_argument("--keycloak", default=KEYCLOAK, help=f"Keycloak base URL (default {KEYCLOAK})")
    args = p.parse_args()
    KEYCLOAK = args.keycloak.rstrip("/")

    realm = "autologg-dev"
    token = admin_token()
    if args.identifier_first or args.organization:
        realm = setup_identifier_first(token) if args.identifier_first else setup_organization(token)
        script = os.path.join(os.path.dirname(__file__), "..", "scripts", "setup_login_link_flow.py")
        out = subprocess.run([sys.executable, script, "--server", KEYCLOAK, "--realm", realm, "--apply", "--yes"],
                             env={**os.environ, "KC_ADMIN_TOKEN": token}, capture_output=True, text=True)
        print(out.stdout[-1200:], out.stderr[-600:])
    configure_execution(token, realm)

    print(f"Realm {realm}")
    two_step = args.identifier_first or args.organization
    scenarios(realm, two_step, args.organization)
    password_off(realm, two_step, args.organization)
    update_password_dropped(realm, two_step)
    number_matching(realm, two_step)
    pre_hijacking(realm, two_step)
    ip_limit(realm, two_step)
    print(f"\n{'All checks passed' if not failures else str(len(failures)) + ' check(s) failed'}")
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()
