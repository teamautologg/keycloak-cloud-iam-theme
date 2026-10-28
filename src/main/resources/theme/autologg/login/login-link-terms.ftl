<#import "template.ftl" as layout>
<#import "buttons.ftl" as buttons>
<#import "login-link-macros.ftl" as linkParts>
<#-- After the right code for an address without account: the terms come before the account. -->
<@layout.registrationLayout; section>
<!-- template: login-link-terms.ftl -->
    <#if section = "header">
        ${msg("loginLinkRegisterConfirmTitle")}
    <#elseif section = "form">
        <div class="al-link-confirm" data-state="terms">
            <p class="al-text">${msg("loginLinkRegisterConfirmText")} <strong class="al-nowrap">${loginLinkTerms.address}</strong></p>
            <form id="kc-login-link-terms-form" class="${properties.kcFormClass!}" action="${url.loginAction}" method="post"
                  onsubmit="this.querySelector('button[type=submit]').disabled = true; return true;">
                <input type="hidden" name="${loginLinkTerms.submitParameter}" value="1"/>
                <@linkParts.termsCheckbox name=loginLinkTerms.termsParameter missing=loginLinkTerms.termsMissing />
                <@buttons.actionGroup>
                    <@buttons.button id="kc-login-link-terms" name="register" label="loginLinkRegisterConfirmButton" />
                </@buttons.actionGroup>
            </form>
            <div class="al-link-help">
                <form id="kc-login-link-cancel-form" action="${url.loginAction}" method="post">
                    <input type="hidden" name="${loginLinkTerms.cancelParameter}" value="1"/>
                    <button type="submit" class="al-text-link">${msg("loginLinkOtherAddress")}</button>
                </form>
            </div>
        </div>
    </#if>
</@layout.registrationLayout>
