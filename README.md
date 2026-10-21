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

1. The user types their address and presses *Anmeldelink per E-Mail senden* (in identifier-first
   flows on the password step).
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
the confirm page shows when and from which browser the login was requested.

### Providers

| ID | Admin console name | Use instead of |
|---|---|---|
| `autologg-username-password-link` | Username Password Form with Login Link | Username Password Form |
| `autologg-password-link` | Password Form with Login Link | Password Form (after Username Form) |
| `autologg-login-link` (realm REST extension) | - | serves `/realms/{realm}/autologg-login-link/confirm` and `/status` |

Both forms keep everything of Keycloak's forms (password, remember me, passkeys, brute force
protection). Users without a password get the e-mail option only on the password step.

Password login can be switched off per execution (`passwordLogin=false`): the login page then asks
for the address only, the link button becomes the main button, and posted passwords are ignored -
every post sends the link. This covers the browser login only; the API's password grant (direct
grant flow) and the registration form are separate.

### Limits and safety

- Link: 256 random bits, stored hashed in Keycloak's single-use store, works once and only for the
  waiting login. A new e-mail voids the previous link and code.
- Code: 6 digits; 5 wrong codes per e-mail, at most 20 wrong codes per user and hour. Wrong codes
  count for the realm's brute force protection like wrong passwords.
- E-mails: at most one per address every 30 seconds and 10 per hour.
- Unknown addresses, disabled or locked users, users without e-mail and users of excluded identity
  providers get no e-mail. The page looks the same, so it does not tell which addresses have an
  account (sending makes the response slower, as with Keycloak's password reset).
- A login through the e-mail proves the address: it is marked verified and a pending
  *Verify Email* action is dropped.
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
| `excludedIdentityProviders` | - | IdP aliases whose users keep signing in through the IdP (e.g. company Entra ID tenants) |
| `markEmailVerified` | true | mark the address verified after a login through the e-mail |
| `passwordLogin` | true | `false`: the pages ask for the address only and send the link; the form accepts no password (passkeys still work) |

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
   `--config key=value` for an execution config.

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
```

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
