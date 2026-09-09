# AutoLogg Keycloak theme

Keycloak theme for the AutoLogg realms (login + email). Forked from the Cloud-IAM example theme.

The theme name is `autologg`, registered in `src/main/resources/META-INF/keycloak-themes.json`.

## Install dependencies

```
npm install
```

## Compile theme scss files

```
npm run build
```

## Build the theme extension

```
mvn package
```

The build number is taken from the `BuildNr` environment variable (see `pom.xml`), which the Azure pipeline sets to `$(Build.BuildNumber)`. The resulting `target/*.jar` is published as the `theme` artifact and uploaded to the hosted Keycloak.

## Theme Development Workflow

Start Keycloak locally through docker; `./src/main/resources/theme` is mounted as the Keycloak themes directory, so template and message changes are picked up without rebuilding (theme caching is disabled in `docker-compose.yml`).

```bash
docker compose up -d
```

Connect to the Keycloak console [http://localhost:8080](http://localhost:8080) (admin/password), open a realm, and select `autologg` as `Login Theme` and `Email Theme` under `Realm Settings` → `Themes`.

## Texts

All user-facing texts live in message bundles, not in the templates:

- `src/main/resources/theme/autologg/login/messages/messages_{de,en}.properties`
- `src/main/resources/theme/autologg/email/messages/messages_{de,en}.properties`

Notes:

- Keys missing in a bundle fall back to Keycloak's own bundle, which produces mixed-language pages — keep `de` and `en` in sync.
- Use `\uXXXX` escapes for non-ASCII characters, not HTML entities: entity text such as `&raquo;` is escaped by `kcSanitize()` and ends up visible on the page.
- Message values go through `MessageFormat`, so a literal apostrophe has to be doubled (`don''t`).

## Resources

- https://www.baeldung.com/spring-keycloak-custom-themes
- see also https://www.keycloakify.dev/ (https://github.com/InseeFrLab/keycloakify)
