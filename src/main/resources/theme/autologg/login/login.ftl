<#import "template.ftl" as layout>
<#import "field.ftl" as field>
<#import "buttons.ftl" as buttons>
<#import "social-providers.ftl" as identityProviders>
<#import "passkeys.ftl" as passkeys>
<#-- step: "single" (address, password and link on one page; also Keycloak's own form without the
     login link), "email" (e-mail first, page one: the address only) or "choice" (page two: link or
     password for that address). Without the password (switched off in the execution config) the
     single page asks for the address only. -->
<#assign step = (loginLink??)?then(loginLink.step, "single")>
<#assign passwordShown = !(loginLink??) || loginLink.passwordAvailable>
<#assign mailIcon><svg class="al-link-button-icon" viewBox="0 0 24 24" aria-hidden="true"><path fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" d="M4 6h16v12H4zM4 7l8 6 8-6"/></svg></#assign>
<@layout.registrationLayout displayMessage=!messagesPerField.existsError('username','password') displayInfo=realm.password && realm.registrationAllowed && !registrationDisabled?? && step != "choice"; section>
<!-- template: login.ftl -->

    <#if section = "beforeHeader">
        <#if step == "choice">
            <form id="kc-login-change-address-form" action="${url.loginAction}" method="post" novalidate="novalidate">
                <button type="submit" class="al-back-link" name="${loginLink.changeAddressParameter}" value="1" formnovalidate>
                    <svg viewBox="0 0 24 24" aria-hidden="true"><path fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" d="M19 12H5M12 19l-7-7 7-7"/></svg>
                    ${msg("loginLinkChangeAddress")}
                </button>
            </form>
        </#if>
    <#elseif section = "header">
        <#if step == "choice">${msg("loginLinkChoiceTitle")}<#else>${msg("loginAccountTitle")}</#if>
    <#elseif section = "form">
        <div id="kc-form">
          <div id="kc-form-wrapper">
            <#if realm.password && step == "email">
                <form id="kc-form-login" class="${properties.kcFormClass!}" onsubmit="if (this.next) { this.next.disabled = true; } return true;" action="${url.loginAction}" method="post" novalidate="novalidate">
                    <@field.input name="username" label=msg("email") error=messagesPerField.getFirstError('username','password')
                        autofocus=true autocomplete="${(enableWebAuthnConditionalUI?has_content)?then('username webauthn', 'username')}" value=login.username!'' />
                    <input type="hidden" name="${loginLink.nextParameter}" value="1"/>
                    <@buttons.actionGroup>
                        <button class="${properties.kcButtonPrimaryClass!} ${properties.kcButtonBlockClass!} al-link-button" type="submit" id="kc-login-next" name="next">
                            ${msg("loginLinkNext")}
                            <svg class="al-link-button-icon" viewBox="0 0 24 24" aria-hidden="true"><path fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" d="M5 12h14M12 5l7 7-7 7"/></svg>
                        </button>
                    </@buttons.actionGroup>
                    <p class="al-help">${msg("loginLinkEmailStepHelp")}</p>
                </form>
            <#elseif realm.password && step == "choice">
                <p class="al-mail-pill">
                    <svg viewBox="0 0 24 24" aria-hidden="true"><path fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" d="M4 6h16v12H4zM4 7l8 6 8-6"/></svg>
                    <span>${loginLink.address}</span>
                </p>
                <#-- Two forms: Enter in the password field must log in with the password, not send a link. -->
                <form id="kc-login-link-form" class="${properties.kcFormClass!}" action="${url.loginAction}" method="post" novalidate="novalidate">
                    <input type="hidden" name="username" value="${loginLink.address}"/>
                    <#if realm.rememberMe><input type="hidden" id="al-link-remember" name="rememberMe" value="${(login.rememberMe??)?then('on', '')}"/></#if>
                    <@buttons.actionGroup>
                        <button class="${properties.kcButtonPrimaryClass!} ${properties.kcButtonBlockClass!} al-link-button" type="submit"
                                id="kc-login-link" name="${loginLink.sendParameter}" value="1" formnovalidate>
                            ${mailIcon}
                            ${msg("loginLinkSend")}
                        </button>
                    </@buttons.actionGroup>
                    <p class="al-help">${msg("loginLinkChoiceLinkHelp")}</p>
                </form>
                <#if passwordShown>
                    <div class="al-divider"><span>${msg("loginLinkOr")}</span></div>
                    <form id="kc-form-login" class="${properties.kcFormClass!}" onsubmit="if (this.login) { this.login.disabled = true; } return true;" action="${url.loginAction}" method="post" novalidate="novalidate">
                        <input type="hidden" name="username" value="${loginLink.address}"/>
                        <@field.password name="password" label=msg("password") error=messagesPerField.getFirstError('username','password') forgotPassword=realm.resetPasswordAllowed autocomplete="current-password">
                            <#if realm.rememberMe>
                                <@field.checkbox name="rememberMe" label=msg("rememberMe") value=login.rememberMe?? />
                            </#if>
                        </@field.password>
                        <input type="hidden" id="id-hidden-input" name="credentialId" <#if auth.selectedCredential?has_content>value="${auth.selectedCredential}"</#if>/>
                        <@buttons.actionGroup>
                            <@buttons.button id="kc-login" name="login" label="loginLinkPasswordLogin" type="secondary" />
                        </@buttons.actionGroup>
                    </form>
                    <#if realm.rememberMe>
                        <script>
                            (function () {
                                var box = document.querySelector('#kc-form-login input[name="rememberMe"]');
                                var copy = document.getElementById('al-link-remember');
                                if (box && copy) {
                                    box.addEventListener('change', function () { copy.value = box.checked ? 'on' : ''; });
                                }
                            })();
                        </script>
                    </#if>
                <#elseif realm.rememberMe>
                    <div class="al-remember-only">
                        <@field.checkbox name="rememberMeLink" label=msg("rememberMe") value=login.rememberMe?? />
                    </div>
                    <script>
                        (function () {
                            var box = document.querySelector('input[name="rememberMeLink"]');
                            var copy = document.getElementById('al-link-remember');
                            if (box && copy) {
                                box.addEventListener('change', function () { copy.value = box.checked ? 'on' : ''; });
                            }
                        })();
                    </script>
                </#if>
            <#elseif realm.password>
                <form id="kc-form-login" class="${properties.kcFormClass!}" onsubmit="if (this.login) { this.login.disabled = true; } return true;" action="${url.loginAction}" method="post" novalidate="novalidate">
                    <#if !passwordShown>
                        <p class="al-text al-muted">${msg("loginLinkPasswordOffIntro")}</p>
                    </#if>
                    <#if !usernameHidden??>
                        <#assign label>
                            <#if !realm.loginWithEmailAllowed>${msg("username")}<#elseif !realm.registrationEmailAsUsername>${msg("usernameOrEmail")}<#else>${msg("email")}</#if>
                        </#assign>
                        <@field.input name="username" label=label error=messagesPerField.getFirstError('username','password')
                            autofocus=true autocomplete="${(enableWebAuthnConditionalUI?has_content)?then('username webauthn', 'username')}" value=login.username!'' />
                        <#if passwordShown>
                            <@field.password name="password" label=msg("password") error="" forgotPassword=realm.resetPasswordAllowed autofocus=usernameHidden?? autocomplete="current-password">
                                <#if realm.rememberMe && !usernameHidden??>
                                    <@field.checkbox name="rememberMe" label=msg("rememberMe") value=login.rememberMe?? />
                                </#if>
                            </@field.password>
                        <#elseif realm.rememberMe>
                            <@field.checkbox name="rememberMe" label=msg("rememberMe") value=login.rememberMe?? />
                        </#if>
                    <#elseif passwordShown>
                        <@field.password name="password" label=msg("password") forgotPassword=realm.resetPasswordAllowed autofocus=usernameHidden?? autocomplete="current-password">
                            <#if realm.rememberMe && !usernameHidden??>
                                <@field.checkbox name="rememberMe" label=msg("rememberMe") value=login.rememberMe?? />
                            </#if>
                        </@field.password>
                    </#if>

                    <input type="hidden" id="id-hidden-input" name="credentialId" <#if auth.selectedCredential?has_content>value="${auth.selectedCredential}"</#if>/>
                    <@buttons.actionGroup>
                        <#if passwordShown>
                            <@buttons.button id="kc-login" name="login" label="doLogIn" />
                        </#if>
                        <#if loginLink??>
                            <button class="${passwordShown?then(properties.kcButtonSecondaryClass!, properties.kcButtonPrimaryClass!)} ${properties.kcButtonBlockClass!} al-link-button" type="submit"
                                    id="kc-login-link" name="${loginLink.sendParameter}" value="1" formnovalidate>
                                ${mailIcon}
                                ${msg("loginLinkSend")}
                            </button>
                        </#if>
                    </@buttons.actionGroup>
                </form>
            </#if>
            </div>
        </div>
        <@passkeys.conditionalUIData />
    <#elseif section = "socialProviders" >
        <#if realm.password && social.providers?? && social.providers?has_content && step != "choice">
            <#if step == "email">
                <@identityProviders.show social=social label=msg("loginLinkSocialLabel") columns=2/>
            <#else>
                <@identityProviders.show social=social/>
            </#if>
        </#if>
    <#elseif section = "info" >
        <#if realm.password && realm.registrationAllowed && !registrationDisabled??>
            <div id="kc-registration-container">
                <div id="kc-registration">
                    <span>${msg("noAccount")} <a href="${url.registrationUrl}">${msg("doRegister")}</a></span>
                </div>
            </div>
        </#if>
    </#if>

</@layout.registrationLayout>
