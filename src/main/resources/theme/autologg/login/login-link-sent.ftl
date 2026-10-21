<#import "template.ftl" as layout>
<#import "field.ftl" as field>
<#import "buttons.ftl" as buttons>
<@layout.registrationLayout displayMessage=!messagesPerField.existsError(loginLinkSent.codeParameter); section>
<!-- template: login-link-sent.ftl -->
    <#if section = "header">
        ${msg("loginLinkSentTitle")}
    <#elseif section = "form">
        <div id="al-login-link" class="al-link-sent"
             data-status-url="${loginLinkSent.statusUrl}"
             data-code-length="${loginLinkSent.codeLength?c}"
             data-expires-in="${loginLinkSent.expiresInSeconds?c}">

            <div class="al-link-hero">
                <span class="al-link-hero-icon" aria-hidden="true">
                    <svg viewBox="0 0 24 24"><path fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" d="M3.5 6.5h17v11h-17zM3.5 7.5l8.5 6.2 8.5-6.2"/></svg>
                </span>
                <p class="al-text">
                    <#if loginLinkSent.identifier?has_content>
                        ${msg("loginLinkSentTo")} <strong class="al-nowrap">${loginLinkSent.identifier}</strong>
                    <#else>
                        ${msg("loginLinkSentToAccount")}
                    </#if>
                </p>
                <p class="al-text al-muted">${msg("loginLinkSentHint")}</p>
            </div>

            <div id="al-login-link-status" class="al-status" role="status" aria-live="polite" data-state="waiting"
                 data-text-waiting="${msg("loginLinkWaiting")}"
                 data-text-confirmed="${msg("loginLinkConfirmedHere")}"
                 data-text-expired="${msg("loginLinkExpiredHere")}">
                <span class="al-status-dot" aria-hidden="true"></span>
                <span class="al-status-text">${msg("loginLinkWaiting")}</span>
            </div>

            <div class="al-divider"><span>${msg("loginLinkOrCode")}</span></div>

            <form id="kc-login-link-code-form" class="${properties.kcFormClass!}" action="${url.loginAction}" method="post" novalidate="novalidate">
                <@field.group name=loginLinkSent.codeParameter label=msg("loginLinkCodeLabel") error=messagesPerField.get(loginLinkSent.codeParameter)>
                    <span class="${properties.kcInputClass} al-code-input <#if messagesPerField.existsError(loginLinkSent.codeParameter)>${properties.kcError}</#if>">
                        <input id="${loginLinkSent.codeParameter}" name="${loginLinkSent.codeParameter}" type="text"
                               inputmode="numeric" autocomplete="one-time-code" pattern="[0-9 ]*" spellcheck="false"
                               maxlength="${(loginLinkSent.codeLength + 2)?c}" placeholder="${"0"?left_pad(loginLinkSent.codeLength, "0")}"
                               aria-invalid="<#if messagesPerField.existsError(loginLinkSent.codeParameter)>true</#if>"/>
                    </span>
                </@field.group>
                <@buttons.actionGroup>
                    <@buttons.button id="kc-login-link-code" name="loginLinkSubmit" label="loginLinkCodeSubmit" />
                </@buttons.actionGroup>
            </form>

            <form id="kc-login-link-check-form" action="${url.loginAction}" method="post" hidden>
                <input type="hidden" name="${loginLinkSent.checkParameter}" value="1"/>
            </form>

            <div class="al-link-help">
                <p class="al-text al-muted"><strong>${msg("loginLinkNoMail")}</strong> ${msg("loginLinkNoMailHint")}</p>
                <div class="al-link-actions">
                    <form id="kc-login-link-resend-form" action="${url.loginAction}" method="post">
                        <input type="hidden" name="${loginLinkSent.resendParameter}" value="1"/>
                        <button type="submit" id="kc-login-link-resend" class="${properties.kcButtonLinkClass!} al-inline-button"
                                data-wait="${loginLinkSent.resendAvailableInSeconds?c}"
                                data-label="${msg("loginLinkResend")}"
                                data-label-wait="${msg("loginLinkResendIn")}">${msg("loginLinkResend")}</button>
                    </form>
                    <form id="kc-login-link-cancel-form" action="${url.loginAction}" method="post">
                        <input type="hidden" name="${loginLinkSent.cancelParameter}" value="1"/>
                        <#if loginLinkSent.identifier?has_content>
                            <input type="hidden" name="username" value="${loginLinkSent.identifier}"/>
                        </#if>
                        <button type="submit" class="${properties.kcButtonLinkClass!} al-inline-button">
                            ${loginLinkSent.passwordFallback?then(msg("loginLinkUsePassword"), msg("loginLinkBack"))}
                        </button>
                    </form>
                </div>
            </div>
        </div>
        <script type="module" src="${url.resourcesPath}/js/login-link.js"></script>
    </#if>
</@layout.registrationLayout>
