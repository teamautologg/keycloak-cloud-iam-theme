#!/usr/bin/env python3
"""
Puts the AutoLogg login-link forms into a realm's browser flow (AL-5388).

Copies the realm's current browser flow, replaces Keycloak's "Username Password
Form" / "Password Form" in the copy with the AutoLogg variants that also offer a
sign-in link by e-mail, and binds the copy as the browser flow. The original
flow is not touched: rolling back means binding it again (--bind-flow).

Dry run by default (reads and prints the plan). Changes only with --apply, which
shows the plan once more and asks before it changes anything (--yes skips that).
Needs the extension (theme jar with the login-link providers) deployed on the
server and SMTP configured in the realm; login and e-mail theme are set to
'autologg' if the realm uses another one (the link pages and e-mail live there).

Signing in to Keycloak (master realm; nothing is printed or stored):
  --admin-user <name>        password grant via admin-cli, password is prompted
                             (+ --otp when the admin has 2FA: code is prompted)
  --admin-client <clientId>  client credentials of a master-realm client, secret is prompted
  KC_ADMIN_TOKEN=<token>     ready access token (master tokens often expire after 60 s)

Examples
  python scripts/setup_login_link_flow.py --server http://localhost:8080 --realm autologg-dev --admin-user admin
  python scripts/setup_login_link_flow.py --server https://id.autologg.com/auth --realm staging-autologg \\
      --admin-user thomas.jetzinger --otp --apply
  python scripts/setup_login_link_flow.py ... --config excludedIdentityProviders=entra-acme --apply
  python scripts/setup_login_link_flow.py ... --config passwordLogin=true --apply    # password next to the link
  python scripts/setup_login_link_flow.py ... --config excludedDomains=pharmatechnik.de --apply

On a realm that already uses the login-link forms, --config changes the execution config.
A realm role microsoft-login-only is excluded automatically (excludedRoles).
  python scripts/setup_login_link_flow.py ... --bind-flow browser --apply      # roll back
"""
import argparse
import getpass
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

REPLACEMENTS = {
    "auth-username-password-form": "autologg-username-password-link",
    "auth-password-form": "autologg-password-link",
}
THEME = "autologg"
CONFIG_KEYS = {
    "linkTtlMinutes", "codeLength", "maxCodeAttempts", "resendCooldownSeconds",
    "maxSendsPerHour", "maxSendsPerIpPerHour", "excludedIdentityProviders", "markEmailVerified",
    "passwordLogin", "emailFirst", "excludedRoles", "excludedDomains",
}
# Users of this realm role sign in with Microsoft only (Tools/keycloak-entra/setup_microsoft_login_only.py).
MICROSOFT_ONLY_ROLE = "microsoft-login-only"


class KeycloakError(Exception):
    pass


class Auth:
    def __init__(self, args):
        self.args = args
        self.static = os.environ.get("KC_ADMIN_TOKEN")
        self.token = None
        self.refresh = None
        self.expires_at = 0
        self.password = None
        self.secret = None

    def _grant(self, form):
        url = f"{self.args.server}/realms/master/protocol/openid-connect/token"
        data = urllib.parse.urlencode(form).encode()
        try:
            with urllib.request.urlopen(urllib.request.Request(url, data=data, method="POST"), timeout=30) as resp:
                body = json.loads(resp.read())
        except urllib.error.HTTPError as e:
            detail = e.read().decode(errors="replace")
            raise KeycloakError(f"Sign-in to the master realm failed ({e.code}): {detail}") from None
        self.token = body["access_token"]
        self.refresh = body.get("refresh_token")
        self.expires_at = time.time() + int(body.get("expires_in", 60)) - 15

    def _login(self):
        if self.args.admin_client:
            if self.secret is None:
                self.secret = getpass.getpass(f"Client secret of {self.args.admin_client} (master): ")
            self._grant({"grant_type": "client_credentials", "client_id": self.args.admin_client,
                         "client_secret": self.secret})
        else:
            if self.password is None:
                self.password = getpass.getpass(f"Keycloak password of {self.args.admin_user} (master): ")
            form = {"grant_type": "password", "client_id": "admin-cli",
                    "username": self.args.admin_user, "password": self.password}
            if self.args.otp:
                # Direct grant reads "otp" in newer and "totp" in older versions; a code works once.
                code = getpass.getpass("OTP code from the authenticator app: ").strip()
                form.update({"otp": code, "totp": code})
            self._grant(form)

    def header(self):
        if self.static:
            return f"Bearer {self.static}"
        if self.token is None:
            self._login()
        elif time.time() >= self.expires_at:
            try:
                if not self.refresh:
                    raise KeycloakError("no refresh token")
                self._grant({"grant_type": "refresh_token", "client_id": self.args.admin_client or "admin-cli",
                             "refresh_token": self.refresh,
                             **({"client_secret": self.secret} if self.args.admin_client else {})})
            except KeycloakError:
                self._login()
        return f"Bearer {self.token}"


class Api:
    def __init__(self, server, realm, auth):
        self.base = f"{server}/admin/realms/{urllib.parse.quote(realm, safe='')}"
        self.auth = auth

    def call(self, method, path, body=None):
        data = None if body is None else json.dumps(body).encode()
        req = urllib.request.Request(self.base + path, data=data, method=method)
        req.add_header("Authorization", self.auth.header())
        req.add_header("Accept", "application/json")
        if data is not None:
            req.add_header("Content-Type", "application/json")
        try:
            with urllib.request.urlopen(req, timeout=30) as resp:
                raw = resp.read()
                return json.loads(raw) if raw else None, resp.headers
        except urllib.error.HTTPError as e:
            detail = e.read().decode(errors="replace")
            raise KeycloakError(f"{method} {path} -> {e.code}: {detail}") from None

    def get(self, path):
        return self.call("GET", path)[0]

    def get_or_none(self, path):
        try:
            return self.get(path)
        except KeycloakError as e:
            if "-> 404" in str(e):
                return None
            raise

    def executions(self, flow_alias):
        return self.get(f"/authentication/flows/{quote(flow_alias)}/executions")


def quote(alias):
    return urllib.parse.quote(alias, safe="")


def parent_alias(executions, index, top_alias):
    """Alias of the (sub)flow the execution at `index` of the flattened list belongs to."""
    level = executions[index]["level"]
    if level == 0:
        return top_alias
    for candidate in reversed(executions[:index]):
        if candidate["level"] == level - 1 and candidate.get("authenticationFlow"):
            return candidate["displayName"]
    raise KeycloakError("parent flow not found for " + executions[index]["displayName"])


def siblings(executions, parent, top_alias):
    return [e for i, e in enumerate(executions) if parent_alias(executions, i, top_alias) == parent]


def print_flow(executions):
    for e in executions:
        kind = "flow" if e.get("authenticationFlow") else e.get("providerId", "?")
        print(f"    {'  ' * e['level']}- {e['displayName']} [{kind}] {e['requirement']}")


def parse_config(pairs):
    config = {}
    for pair in pairs or []:
        key, sep, value = pair.partition("=")
        if not sep or key not in CONFIG_KEYS:
            raise SystemExit(f"--config expects key=value with key in {sorted(CONFIG_KEYS)}, got {pair!r}")
        config[key] = value
    return config


def bind(api, realm_alias, flow_alias, apply):
    flows = {f["alias"] for f in api.get("/authentication/flows")}
    if flow_alias not in flows:
        raise KeycloakError(f"flow {flow_alias!r} does not exist")
    print(f"Bind browser flow of {realm_alias}: {flow_alias}")
    if apply:
        api.call("PUT", "", {"browserFlow": flow_alias})
        print("  done")


def realm_changes(realm):
    """Settings the login link depends on: its pages and its e-mail live in the 'autologg' theme."""
    smtp = realm.get("smtpServer") or {}
    if not smtp.get("host"):
        raise KeycloakError("the realm has no SMTP server - the login e-mail could not be sent")
    changes = {}
    for key in ("loginTheme", "emailTheme"):
        if realm.get(key) != THEME:
            changes[key] = THEME
    return changes


def print_identity_providers(api, config):
    excluded = {alias.strip().lower() for alias in config.get("excludedIdentityProviders", "").replace(";", ",").split(",")
                if alias.strip()}
    providers = api.get("/identity-provider/instances") or []
    print("\nIdentity providers (users linked to an excluded one get no login e-mail):")
    for idp in providers:
        hidden = idp.get("hideOnLogin") or (idp.get("config") or {}).get("hideOnLoginPage") == "true"
        marks = [m for m, on in (("hidden on the login page", hidden), ("disabled", not idp.get("enabled", True)),
                                 ("EXCLUDED", idp["alias"].lower() in excluded)) if on]
        print(f"    - {idp['alias']} ({idp['providerId']}){' - ' + ', '.join(marks) if marks else ''}")
    if not providers:
        print("    (none)")
    unknown = excluded - {idp["alias"].lower() for idp in providers}
    if unknown:
        print(f"    note: excluded aliases not in this realm: {sorted(unknown)}")


def setup(api, args):
    realm = api.get("")
    current = realm["browserFlow"]
    executions = api.executions(current)
    print(f"Realm {args.realm}: browser flow {current!r}, login theme {realm.get('loginTheme')!r}, "
          f"e-mail theme {realm.get('emailTheme')!r}")
    print_flow(executions)
    config = parse_config(args.config)
    if "excludedRoles" not in config and api.get_or_none(f"/roles/{quote(MICROSOFT_ONLY_ROLE)}") is not None:
        config["excludedRoles"] = MICROSOFT_ONLY_ROLE
        print(f"\nRealm role {MICROSOFT_ONLY_ROLE} exists: its users get no login e-mail (excludedRoles).")
    print_identity_providers(api, config)
    print(f"\nRegistration: {'new addresses register through the link' if realm.get('registrationAllowed') else 'off (realm)'}")

    ours = [e for e in executions if e.get("providerId") in REPLACEMENTS.values()]
    if ours:
        update_config(api, args, ours, config)
        return
    targets = [(i, e) for i, e in enumerate(executions) if e.get("providerId") in REPLACEMENTS]
    if not targets:
        raise KeycloakError("no Username Password Form / Password Form in the bound browser flow")

    new_alias = args.new_flow or f"{current} with login link"
    if new_alias in {f["alias"] for f in api.get("/authentication/flows")}:
        raise KeycloakError(f"flow {new_alias!r} exists already; bind it with --bind-flow or pass --new-flow")
    changes = realm_changes(realm)

    print("\nPlan:")
    for key, value in changes.items():
        print(f"  0. set {key} to {value!r} (was {realm.get(key)!r})")
    print(f"  1. copy {current!r} to {new_alias!r}")
    for _, e in targets:
        print(f"  2. in the copy replace {e['displayName']!r} ({e['providerId']}, {e['requirement']}) "
              f"with {REPLACEMENTS[e['providerId']]}")
    if config:
        print(f"  3. execution config: {config}")
    print(f"  4. bind {new_alias!r} as browser flow (roll back: --bind-flow {current!r})")
    if not args.apply:
        print("\nDry run - nothing changed. Run again with --apply.")
        return
    if not args.yes:
        answer = input(f"\nApply this plan to realm {args.realm}? [y/N] ").strip().lower()
        if answer not in ("y", "yes", "j", "ja"):
            print("Nothing changed.")
            return

    if changes:
        api.call("PUT", "", changes)
        print(f"  set {', '.join(changes)}")
    api.call("POST", f"/authentication/flows/{quote(current)}/copy", {"newName": new_alias})
    try:
        replace_forms(api, new_alias, config)
    except KeycloakError:
        # Not bound yet, so the half-done copy can simply go.
        copy = next((f for f in api.get("/authentication/flows") if f["alias"] == new_alias), None)
        if copy:
            api.call("DELETE", f"/authentication/flows/{copy['id']}")
            print(f"  removed the unfinished copy {new_alias!r}")
        raise

    bind(api, args.realm, new_alias, True)
    print("\nNew flow:")
    print_flow(api.executions(new_alias))


def update_config(api, args, executions, config):
    """The bound flow already uses our forms: merge --config (and the automatic keys) into their config."""
    plan = []
    for execution in executions:
        current = {}
        if execution.get("authenticationConfig"):
            current = (api.get(f"/authentication/config/{execution['authenticationConfig']}") or {}).get("config") or {}
        changed = {k: v for k, v in config.items() if current.get(k) != v}
        print(f"\n{execution['displayName']}: config {current or '(defaults)'}")
        if changed:
            plan.append((execution, changed))
    if not plan:
        print("\nThe bound flow already uses the login-link forms with this config; nothing to do.")
        return
    print("\nPlan:")
    for execution, changed in plan:
        print(f"  - {execution['displayName']}: set {changed}")
    if not args.apply:
        print("\nDry run - nothing changed. Run again with --apply.")
        return
    if not args.yes:
        answer = input(f"\nApply this plan to realm {args.realm}? [y/N] ").strip().lower()
        if answer not in ("y", "yes", "j", "ja"):
            print("Nothing changed.")
            return
    for execution, changed in plan:
        if execution.get("authenticationConfig"):
            rep = api.get(f"/authentication/config/{execution['authenticationConfig']}")
            rep["config"] = {**(rep.get("config") or {}), **changed}
            api.call("PUT", f"/authentication/config/{execution['authenticationConfig']}", rep)
        else:
            api.call("POST", f"/authentication/executions/{execution['id']}/config",
                     {"alias": f"login-link-{execution['id'][:8]}", "config": changed})
        print(f"  {execution['displayName']}: config updated")


def replace_forms(api, new_alias, config):
    for provider, replacement in REPLACEMENTS.items():
        while True:
            copied = api.executions(new_alias)
            index = next((i for i, e in enumerate(copied) if e.get("providerId") == provider), None)
            if index is None:
                break
            old = copied[index]
            parent = parent_alias(copied, index, new_alias)
            _, headers = api.call("POST", f"/authentication/flows/{quote(parent)}/executions/execution",
                                  {"provider": replacement})
            new_id = headers["Location"].rstrip("/").rsplit("/", 1)[-1]

            # The new execution is appended to the subflow; move it right above the old one.
            def position(execution_id):
                listed = siblings(api.executions(new_alias), parent, new_alias)
                return next(n for n, e in enumerate(listed) if e["id"] == execution_id)
            while position(new_id) > position(old["id"]):
                api.call("POST", f"/authentication/executions/{new_id}/raise-priority")

            fresh = next(e for e in api.executions(new_alias) if e["id"] == new_id)
            fresh["requirement"] = old["requirement"]
            api.call("PUT", f"/authentication/flows/{quote(parent)}/executions", fresh)
            if config:
                api.call("POST", f"/authentication/executions/{new_id}/config",
                         {"alias": f"login-link-{new_id[:8]}", "config": config})
            api.call("DELETE", f"/authentication/executions/{old['id']}")
            print(f"  replaced {old['displayName']!r} in {parent!r}")


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--server", required=True, help="Keycloak base URL, e.g. https://id.autologg.com/auth")
    p.add_argument("--realm", required=True)
    g = p.add_mutually_exclusive_group()
    g.add_argument("--admin-user", help="admin user in the master realm (password is prompted)")
    g.add_argument("--admin-client", help="confidential client in the master realm (secret is prompted)")
    p.add_argument("--otp", action="store_true", help="admin user has 2FA: code is prompted")
    p.add_argument("--new-flow", help="alias of the copy (default: '<current> with login link')")
    p.add_argument("--config", action="append", metavar="KEY=VALUE",
                   help="execution config, e.g. linkTtlMinutes=15, passwordLogin=false or excludedIdentityProviders=entra-acme")
    p.add_argument("--bind-flow", metavar="ALIAS", help="only bind this flow as browser flow (roll back)")
    p.add_argument("--apply", action="store_true", help="make the changes after a confirmation (default: dry run)")
    p.add_argument("--yes", action="store_true", help="with --apply: do not ask before changing anything")
    args = p.parse_args()
    args.server = args.server.rstrip("/")
    if not (args.admin_user or args.admin_client or os.environ.get("KC_ADMIN_TOKEN")):
        p.error("pass --admin-user, --admin-client or KC_ADMIN_TOKEN")

    api = Api(args.server, args.realm, Auth(args))
    try:
        if args.bind_flow:
            bind(api, args.realm, args.bind_flow, args.apply)
            if not args.apply:
                print("Dry run - nothing changed. Run again with --apply.")
        else:
            setup(api, args)
    except KeycloakError as e:
        print(f"Error: {e}", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
