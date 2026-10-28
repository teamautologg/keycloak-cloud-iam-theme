# AutoLogg Keycloak theme and login link

Keycloak extension for the AutoLogg realms, one jar:

- **Theme `autologg`** (login + email), registered in `src/main/resources/META-INF/keycloak-themes.json`.
  It builds on Keycloak's `keycloak.v2` theme (PatternFly 5) and looks like the app's login screen:
  Plus Jakarta Sans, accent `#0E9BF5`, a centred card, dark mode. E-mails get a
  branded frame (logo, card, footer).
- **Login link** ("magic link", AL-5388): the login and password pages also offer
  *Anmeldelink per E-Mail senden*. Java sources in `src/main/java/at/autologg/keycloak/loginlink`.

## Login link

### How it works

1. The user types their address and presses *Weiter*; the next page offers *Anmeldelink per E-Mail
   senden* (primary) or the password ("e-mail first", execution option `emailFirst`, default on;
   unknown addresses get the same page, accounts without password see no password field). With
   `emailFirst=false` address, password and link share one page; in identifier-first flows and
   behind *Organization Identity-First Login* the button sits on the password step.
2. Keycloak sends an e-mail with a link and a 6-digit code (valid 15 minutes, single use) and
   shows a waiting page.
3. Either the user opens the link - in any browser, on any device - and presses *Ja, anmelden*;
   the waiting page notices (it polls `/realms/{realm}/autologg-login-link/status`) and finishes the
   login by itself. Or the user types the code on the waiting page (iOS and Android offer it from the
   mail app).

The login always finishes in the window that started it. The app needs exactly that: its login
runs in a popup (web) or an in-app browser sheet (iOS/Android), while a link tapped in the mail app
opens in a different browser. The browser that confirms is not logged in.

Opening the link does nothing by itself; *Ja, anmelden* is a POST. Mail scanners that open links
in advance (Defender Safe Links etc.) can therefore not complete a login someone else started, and
the confirm page shows when and from which browser the login was requested. *Das war ich nicht*
below the button ends that login at once; the waiting page says so. (Number matching was tried on
28.10.2026 and taken out again: too complicated.) The confirm button posts in the background, so the
tab keeps a single history entry and closes itself after "Dieses Fenster schließt sich gleich."
(Chromium 88+, Firefox; iOS Safari does not let pages close tabs - there it says the page can be
closed). Without JavaScript the form posts as usual.

On desktops the waiting page offers *Gmail öffnen* / *Outlook öffnen* as its main button for the address
(Outlook on Windows and macOS: the app through `ms-outlook:` - the new Outlook for Windows and Outlook
for Mac register it, classic Outlook does not, so there the button does nothing (no browser link by
decision, 28.10.2026); else the web mailbox in a new tab):
known consumer domains directly, company domains by their MX records (Microsoft 365, Google
Workspace; cached 6 h). A lookup runs only for an e-mail that passed the sending limits, at most four
at a time, with a 0.3 s timeout (under a second with its retry); when all four are busy the page
simply has no button - made-up domains in a flood of requests cannot tie up Keycloak's threads. Gmail opens on a search for our sender, spam
included. Not on phones: there the login runs in the app's in-app browser, where a link would leave
the waiting page. The button depends on the domain only, not on whether an account exists.

### Providers

| ID | Admin console name | Use instead of |
|---|---|---|
| `autologg-username-password-link` | Username Password Form with Login Link | Username Password Form |
| `autologg-password-link` | Password Form with Login Link | Password Form (after Username Form) |
| `autologg-login-link` (realm REST extension) | - | serves `/realms/{realm}/autologg-login-link/confirm` and `/status` |

Both forms keep everything of Keycloak's forms (password, remember me, passkeys, brute force
protection). Users without a password get the e-mail option only on the password step.

Where the login link is active, signing in and registering go through the link only (Thomas,
28.10.2026): by default (`passwordLogin=false`) the login page asks for the address only, the link
button is the main button, and posted passwords are ignored - every post sends the link. Social
logins and passkeys stay. `passwordLogin=true` offers the password next to the link (e-mail first:
page two with both). This covers the browser login only; the API's password grant (direct grant
flow) is separate.

Registration: if the realm lets people register, an unknown address gets an e-mail *Dein
AutoLogg-Konto anlegen* instead of nothing - same waiting page; the link opens *Konto anlegen*
with a required box "Ich akzeptiere die AGB und habe die Datenschutzinformation gelesen" before
*Ja, Konto anlegen* (the server checks it; without it the link stays unused). With the code
instead, the same box comes after the right code. Only then the account is created the way
Keycloak's registration form would (default roles and required actions, REGISTER event with
`register_method=login_link`): address verified, no password, the acceptance time in the user
attribute `terms_and_conditions` (as Keycloak's own terms action keeps it), and the name taken
from the address - thomas.jetzinger@... becomes Thomas / JETZINGER (Thomas, 28.10.2026: no name
form; people correct it in the app, whose profile edit writes it back to Keycloak). Nothing is
created before that. The login page no longer links to Keycloak's registration form.

Excluded: users of the realm role in `excludedRoles` (the setup script sets `microsoft-login-only`
when the realm has it) get no e-mail; addresses of `excludedDomains` (e.g. `pharmatechnik.de`) get
neither a login nor a registration e-mail, and the page tells them to use *Mit Microsoft anmelden*
(depends on the domain only, not on an account).

### Limits and safety

- Link: 256 random bits, stored hashed in Keycloak's single-use store, works once and only for the
  waiting login. A new e-mail voids the previous link and code.
- Code: 6 digits; 5 wrong codes per e-mail, at most 20 wrong codes per user and hour. Wrong codes
  count for the realm's brute force protection like wrong passwords.
- E-mails: at most one per address every 30 seconds and 10 per hour, and at most 30 requests per
  public IP address and hour across all addresses (private addresses - a proxy without forwarded
  headers - are not limited; Keycloak logs that once).
- Registration e-mails (unknown addresses): at most 100 per realm and hour from all addresses and
  networks together (`maxRegistrationsPerHour`). Above it unknown addresses get no e-mail - the page
  looks the same - and Keycloak logs a warning once per hour; logins of existing accounts go on.
  Against someone who mails made-up or third-party addresses through the registration from many IPs.
- Disabled or locked users, users without e-mail, users of excluded identity providers or roles,
  and unknown addresses when the realm does not allow registration get no e-mail. The page looks the same, so it does not tell which addresses have an
  account (sending makes the response slower, as with Keycloak's password reset).
- A login through the e-mail proves the address: it is marked verified and a pending
  *Verify Email* action is dropped. If the address was not verified before, everything set up on
  the account until then is removed - passwords, OTP, passkeys, identity provider links - and all
  its sessions end (account pre-hijacking: someone registered the address before its owner and
  waits for them to verify it). The LOGIN event then carries `login_link_reset`. A pending *Update Password* action is dropped as well - the
  login needed no password; a password login still asks for the new one.
- The LOGIN event carries `login_link=link` or `login_link=code`.

### Configuration

Optional, on the execution (Authentication -> flow -> gear icon). Without a config the defaults apply.

| Key | Default | Meaning |
|---|---|---|
| `linkTtlMinutes` | 15 | lifetime of link and code, capped by the realm's login timeout |
| `codeLength` | 6 | digits of the code (6-10) |
| `maxCodeAttempts` | 5 | wrong codes per e-mail |
| `resendCooldownSeconds` | 30 | minimum time between two e-mails to one address |
| `maxSendsPerHour` | 10 | e-mails per address and hour |
| `maxSendsPerIpPerHour` | 30 | e-mail requests per public IP address and hour |
| `maxRegistrationsPerHour` | 100 | registration e-mails per realm and hour, all addresses together; 0 stops them |
| `excludedIdentityProviders` | - | IdP aliases whose users keep signing in through the IdP (e.g. company Entra ID tenants) |
| `markEmailVerified` | true | mark the address verified after a login through the e-mail |
| `passwordLogin` | false | `false`: the pages ask for the address only and send the link; the form accepts no password (passkeys still work). `true`: password next to the link |
| `emailFirst` | true | with `passwordLogin=true`: page one asks for the address, page two offers link or password |
| `excludedRoles` | - | realm roles whose users get no e-mail (e.g. `microsoft-login-only`) |
| `excludedDomains` | - | e-mail domains (subdomains included) without login or registration e-mail |

### Two-factor sign-in (authenticator app)

The flow's OTP subflow (*Browser - Conditional OTP*, in newer realms *Browser - Conditional 2FA*)
follows our form: with `--otp-step on` the setup script sets it to CONDITIONAL, so accounts that set
up an authenticator app enter its code after the link, the code from the e-mail or the password;
accounts without one are not asked. `--otp-step off` disables it again. (Staging had it disabled in
its flow; the script prints the state.)

- Set up from the app: an authorization request with `kc_action=CONFIGURE_TOTP`. Keycloak asks to
  sign in again when the last sign-in is older than 5 minutes (required action config
  `max_auth_age`) - our form then shows the address fixed and sends the link - and comes back with
  `kc_action_status=success|cancelled|error`.
- Remove: `kc_action=delete_credential:<credential id>` (Keycloak asks to confirm); the account REST
  API `/realms/{realm}/account/credentials` lists the ids.
- Keycloak's OTP pages with our styles. Logins through Google, Apple or Microsoft skip the step -
  there the provider's own protection applies. The pre-hijacking reset also removes an
  authenticator set up before the address was verified.

### Rollout per realm

1. Build the jar (Azure pipeline or `./dev/build-provider.sh`) and upload it to Cloud-IAM as a new
   release of the theme extension (*My Extensions*). Declare the SPIs `authenticator` and
   `realm-restapi-extension` and the Keycloak version of the deployment (26.7), then install the
   release on the deployment (rolling redeploy, 5-10 minutes).
2. Realm settings: login and e-mail theme `autologg`, working SMTP settings.
3. Swap the forms in the browser flow - dry run first, then `--apply`:

   ```
   python scripts/setup_login_link_flow.py --server https://id.autologg.com/auth --realm staging-autologg --admin-user <you> --otp
   ```

   The script copies the bound browser flow, replaces the password forms in the copy and binds the
   copy. The old flow stays as it is; roll back with `--bind-flow <old alias> --apply`. Pass
   `--config key=value` for an execution config and `--otp-step on` for two-factor sign-in; on a
   realm that already uses the forms both change only that.

## Development

```bash
docker compose up -d
```

- Keycloak 26.7.5 on [http://localhost:8080](http://localhost:8080) (admin/password) with the realm
  `autologg-dev` from `dev/realm` (test users `test@autologg.local` / `Test1234!` and
  `ohnepasswort@autologg.local` without password).
- Mailpit on [http://localhost:8025](http://localhost:8025) catches every e-mail.
- `./src/main/resources/theme` is mounted as theme directory; template, CSS and message changes show
  up on reload.
- Java: `./dev/build-provider.sh` builds with Maven in Docker and puts a provider-only jar into
  `target/dev-providers`; then `docker compose restart keycloak`.

Set up the flow once and run the end-to-end check (two browsers, e-mails from Mailpit):

```bash
python scripts/setup_login_link_flow.py --server http://localhost:8080 --realm autologg-dev --admin-user admin --apply
python dev/e2e_login_link.py
python dev/e2e_login_link.py --identifier-first
python dev/e2e_login_link.py --organization
python dev/e2e_login_link.py --plain
```

`--identifier-first` sets up realm `autologg-dev-idf` (Username Form + Password Form),
`--organization` realm `autologg-dev-org` with organizations switched on, as in `staging-autologg`:
there *Organization Identity-First Login* asks for the address first and the login page after it
hides the address field, for unknown addresses too.
`--plain` checks the theme without the login link (Keycloak's own browser flow, as in prod):
login, wrong password, password reset, registration, two-factor sign-in. The other variants also
switch the OTP step off and on with the setup script and set up an authenticator through
`kc_action=CONFIGURE_TOTP` (scenario 18).

Each run types a few wrong codes on purpose; three runs within an hour reach the limit of 20 wrong
codes per account and hour, and the code scenarios fail. `docker compose -p al5388 restart keycloak`
empties the counters.

Unit tests run with the Maven build.

## Build

```
mvn package
```

The build number is taken from the `BuildNr` environment variable (see `pom.xml`), which the Azure
pipeline sets to `$(Build.BuildNumber)`. The resulting `target/*.jar` is published as the `theme`
artifact and uploaded to the hosted Keycloak.

## Texts

All user-facing texts live in message bundles, not in the templates:

- `src/main/resources/theme/autologg/login/messages/messages_{de,en}.properties`
- `src/main/resources/theme/autologg/email/messages/messages_{de,en}.properties`

Notes:

- Keys missing in a bundle fall back to Keycloak's own bundle, which produces mixed-language pages - keep `de` and `en` in sync.
- Use `\uXXXX` escapes for non-ASCII characters, not HTML entities: entity text such as `&raquo;` is escaped by `kcSanitize()` and ends up visible on the page.
- Message values go through `MessageFormat`, so a literal apostrophe has to be doubled (`don''t`).

## Resources

- https://www.keycloak.org/ui-customization/themes
- https://www.keycloak.org/docs/latest/server_development/#_auth_spi
