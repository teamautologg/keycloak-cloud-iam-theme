<#import "template.ftl" as layout>
<#import "field.ftl" as field>
<#import "buttons.ftl" as buttons>
<#import "passkeys.ftl" as passkeys>
<#assign passwordAvailable = !(loginLink??) || loginLink.passwordAvailable>
<@layout.registrationLayout displayMessage=!messagesPerField.existsError('password'); section>
<!-- template: login-password.ftl -->
    <#if section = "header">
        ${msg("doLogIn")}
    <#elseif section = "form">
        <div id="kc-form">
            <div id="kc-form-wrapper">
                <form id="kc-form-login" class="${properties.kcFormClass!}" onsubmit="if (window.login) { login.disabled = true; } return true;" action="${url.loginAction}" method="post">
                    <#if passwordAvailable>
                        <@field.password name="password" label=msg("password") forgotPassword=realm.resetPasswordAllowed autofocus=true autocomplete="current-password" />
                    <#else>
                        <p class="al-text">${msg("loginLinkOnlyHint")}</p>
                    </#if>
                    <@buttons.actionGroup>
                        <#if passwordAvailable>
                            <@buttons.button id="kc-login" name="login" label="doLogIn" />
                        </#if>
                        <#if loginLink??>
                            <button class="${passwordAvailable?then(properties.kcButtonSecondaryClass!, properties.kcButtonPrimaryClass!)} ${properties.kcButtonBlockClass!} al-link-button" type="submit"
                                    id="kc-login-link" name="${loginLink.sendParameter}" value="1" formnovalidate>
                                <svg class="al-link-button-icon" viewBox="0 0 24 24" aria-hidden="true"><path fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" d="M4 6h16v12H4zM4 7l8 6 8-6"/></svg>
                                ${msg("loginLinkSend")}
                            </button>
                        </#if>
                    </@buttons.actionGroup>
                </form>
            </div>
        </div>
        <@passkeys.conditionalUIData />
    </#if>

</@layout.registrationLayout>
