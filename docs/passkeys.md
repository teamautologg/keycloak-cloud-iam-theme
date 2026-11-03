# Passkeys (AL-5388)

A passkey lets an AutoLogg user sign in with fingerprint, face or device PIN instead of waiting for the
e-mailed link. Keycloak does all of it on the AutoLogg ID page (id.autologg.com); the app only starts the
setup and the removal there and lists the passkeys from Keycloak's account API (profile -> "Konto" ->
"Passkeys", env `PASSKEY_SIGN_IN`).

## How it fits together

- **Sign-in.** Our form `autologg-username-password-link` extends Keycloak's Username Password Form and
  brings its passkey sign-in along. With "Passkeys" on in the realm's passwordless policy the address
  field offers the passkeys in the browser's autofill (`autocomplete="username webauthn"`, conditional
  mediation), and "Mit Passkey anmelden" below "oder" opens the browser's passkey dialog (our
  `passkeys.ftl`). The answer goes to the same form; Keycloak's WebAuthn Conditional UI authenticator
  checks it - a discoverable credential, so its user handle names the account and nobody types an
  address. The login link stays out of it (`LoginLinkFlow.isPasskeySubmission`).
- **Two-factor sign-in.** The passkey is two factors already (the device plus fingerprint, face or PIN),
  so the OTP step must not ask for the app code after it. That takes "Condition - credential"
  (credentials `webauthn-passwordless`, included `false`) in the OTP subflow. Keycloak's own flows have it
  since 26.4; flows copied from older realms - staging and prod - do not, and the setup script adds it.
- **Setting one up from the app.** An authorization request with `kc_action=webauthn-register-passwordless`
  (required action "Webauthn Register Passwordless"). Keycloak asks to sign in again when the last
  sign-in is older than 5 minutes (`max_auth_age`), then shows "Passkey einrichten" (our
  `webauthn-register.ftl`: two lines on what happens, the device - "iPhone", "Windows", ... - as the
  suggested name), the browser creates the passkey, Keycloak asks for its name and comes back with
  `kc_action_status=success|cancelled|error`.
- **Listing.** `GET /realms/{realm}/account/credentials?type=webauthn-passwordless` with the session's
  token (audience `account`): `userCredentialMetadatas[].credential` has `id`, `userLabel`,
  `createdDate` and `credentialData` (AAGUID, transports).
- **Removing.** `kc_action=delete_credential:<id>` -> "„<name>“ entfernen" -> confirm. The passkey itself
  stays in the device's password manager: the browser keeps offering it, and Keycloak then answers
  "Diesen Passkey kennt AutoLogg nicht" - the app tells the user to delete it there too.

## Realm settings

`python scripts/setup_login_link_flow.py ... --passkeys on` (dry run first, then `--apply`) sets:

| Setting | Value | Why |
|---|---|---|
| Passwordless policy "Passkeys" (`webAuthnPolicyPasswordlessPasskeysEnabled`) | on | sign-in with passkeys in the username(-password) form (feature `passkeys`, on by default in 26.7) |
| RP ID (`webAuthnPolicyPasswordlessRpId`) | host of `--server`: `id.autologg.com` | every passkey belongs to it; a realm that has one keeps it, `--passkey-rp-id` changes it (registered passkeys stop working) |
| RP name | `AutoLogg` | shown by some authenticators instead of "keycloak" |
| Resident key | `required` (+ deprecated "Require resident key" `Yes`) | discoverable: offered before anyone typed an address |
| User verification | `required` | fingerprint, face or device PIN at every use |
| Avoid same authenticator | on | one passkey per authenticator; a second one in the same keychain would replace the first there and leave a dead entry in Keycloak |
| Required action "Webauthn Register Passwordless" | enabled | `kc_action` from the app |
| OTP subflow | + "Condition - credential" | no app code after a passkey |

`--passkeys off` switches sign-in and new passkeys off; registered passkeys stay stored. The script shows
the passkey state in every run.

Trap: Keycloak rebuilds the whole passwordless policy from a realm PUT that names
`webAuthnPolicyPasswordlessRpEntityName` - missing fields fall back to their defaults, "Passkeys" to off -
and ignores the policy fields of a PUT without it. The script always sends the full policy.

## Where the app's login window runs

The app never handles a passkey itself: the passkey belongs to id.autologg.com and the browser uses it on
that page. So there is no native code and no entitlement for it in the app.

| Platform | Login window | Passkeys there |
|---|---|---|
| Web | flutter_web_auth_2 opens id.autologg.com in a new window (`window.open`), a top-level page and no iframe | as on the site itself: autofill and dialog; Chrome/Edge with Google Password Manager, Windows Hello or a phone via QR code, Safari with iCloud Keychain, Firefox |
| iOS | `ASWebAuthenticationSession` (Safari's engine, `prefersEphemeralWebBrowserSession` false, so Safari's cookies are shared) | passkeys from iCloud Keychain and, from iOS 17, other password managers; Face ID / Touch ID. Autofill in such a session had an iCloud-Keychain-only regression in iOS 17.4, so "Mit Passkey anmelden" - always shown - is the dependable way |
| Android | Auth Tab (Chrome 137+, androidx.browser 1.9) in the default browser, else a Custom Tab | WebAuthn as in that browser: Credential Manager with Google Password Manager (Android 9+) or another provider (Android 14+). An embedded WebView could not do it - the app does not use one |

The silent login in the web app (`prompt=none` in a hidden iframe) never needs a passkey: it only reuses
the Keycloak session.

Native passkeys in the app itself (Credential Manager, `ASAuthorizationPlatformPublicKeyCredentialProvider`)
would need `/.well-known/assetlinks.json` and `apple-app-site-association` (`webcredentials`) on
id.autologg.com, which the Cloud-IAM hosting does not serve; the login window does not need them.

Good to know:

- Staging and prod share the RP ID id.autologg.com: the browser offers a staging passkey on the prod page
  too (testers with accounts in both realms); the wrong one gets "Diesen Passkey kennt AutoLogg nicht".
- Never change the RP ID once passkeys exist.
- API logins (direct grant, api-v2 `/v2/user/login`) do not use passkeys.
- Demo accounts and companies that sign in with Microsoft only (address or realm role
  `microsoft-login-only`) get no "Passkeys" in the app.

## Testing

- `python dev/e2e_login_link.py` scenario [21]: the setup script on a flow without "Condition - credential"
  (dry run, `--passkeys on` twice, `--passkeys off`, policy kept).
- Scenario [22] runs `dev/e2e_passkeys.mjs`: headless Chrome with a virtual WebAuthn authenticator (DevTools
  protocol `WebAuthn.addVirtualAuthenticator`, user verification simulated). Sign-in with the e-mailed code
  and the app code, "Passkey einrichten" through `kc_action`, the name prompt with the device suggested,
  the account API answer, sign-in through the autofill and through the button without e-mail and without
  app code, removal through `delete_credential`, and the removed passkey refused. Needs Node 22+ and
  Chrome; `--screenshots <dir>` saves the pages.
- On real devices (staging): iPhone (Safari and the app), Android (Chrome and the app), Windows (Edge with
  Windows Hello), a computer without a platform authenticator via the phone's QR code.
