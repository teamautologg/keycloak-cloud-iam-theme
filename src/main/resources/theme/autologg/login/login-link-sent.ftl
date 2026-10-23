<#import "template.ftl" as layout>
<#import "field.ftl" as field>
<#import "buttons.ftl" as buttons>
<@layout.registrationLayout displayMessage=!messagesPerField.existsError(loginLinkSent.codeParameter); section>
<!-- template: login-link-sent.ftl -->
    <#if section = "header">
        ${msg("loginLinkSentTitle")}
    <#elseif section = "form">
        <div id="al-login-link" class="al-link-sent<#if loginLinkSent.denied> al-ended</#if>"
             data-status-url="${loginLinkSent.statusUrl}"
             data-code-length="${loginLinkSent.codeLength?c}"
             data-expires-in="${loginLinkSent.expiresInSeconds?c}">

            <div class="al-link-hero">
                <span class="al-link-hero-icon" aria-hidden="true">
                    <svg viewBox="0 0 24 24"><path fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" d="M22 13V6a2 2 0 0 0-2-2H4a2 2 0 0 0-2 2v12c0 1.1.9 2 2 2h8M22 7l-8.97 5.7a1.94 1.94 0 0 1-2.06 0L2 7M16 19l2 2 4-4"/></svg>
                </span>
                <p class="al-text">
                    <#assign minutes = ((loginLinkSent.expiresInSeconds + 59) / 60)?floor>
                    <#if loginLinkSent.identifier?has_content>
                        ${msg("loginLinkSentTo")} <strong class="al-nowrap">${loginLinkSent.identifier}</strong>${msg("loginLinkSentToEnd")} ${msg("loginLinkSentValid", minutes?c)}
                    <#else>
                        ${msg("loginLinkSentToAccount")} ${msg("loginLinkSentValid", minutes?c)}
                    </#if>
                </p>
            </div>

            <#if loginLinkSent.matchNumber?has_content>
                <div class="al-match" aria-describedby="al-match-hint">
                    <span class="al-match-label">${msg("loginLinkMatchLabel")}</span>
                    <span id="al-match-number" class="al-match-number">${loginLinkSent.matchNumber}</span>
                    <span id="al-match-hint" class="al-match-hint">${msg("loginLinkMatchHint")}</span>
                </div>
            <#else>
                <p class="al-text al-muted al-center">${msg("loginLinkSentHint")}</p>
            </#if>

            <div id="al-login-link-status" class="al-status" role="status" aria-live="polite"
                 data-state="${loginLinkSent.denied?then('denied', 'waiting')}"
                 data-text-waiting="${msg("loginLinkWaiting")}"
                 data-text-confirmed="${msg("loginLinkConfirmedHere")}"
                 data-text-expired="${msg("loginLinkExpiredHere")}"
                 data-text-denied="${msg("loginLinkDeniedHere")}">
                <span class="al-status-dot" aria-hidden="true"></span>
                <span class="al-status-text">${loginLinkSent.denied?then(msg("loginLinkDeniedHere"), msg("loginLinkWaiting"))}</span>
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
                <form id="kc-login-link-resend-form" class="al-help al-resend-line" action="${url.loginAction}" method="post">
                    <input type="hidden" name="${loginLinkSent.resendParameter}" value="1"/>
                    <span>${msg("loginLinkNoMailHint")}</span>
                    <button type="submit" id="kc-login-link-resend" class="${properties.kcButtonLinkClass!} al-inline-button"
                            data-wait="${loginLinkSent.resendAvailableInSeconds?c}"
                            data-label="${msg("loginLinkResend")}"
                            data-label-wait="${msg("loginLinkResendIn")}">${msg("loginLinkResend")}</button>
                </form>
                <#if loginLinkSent.back != "none">
                    <div class="al-separator"></div>
                    <form id="kc-login-link-cancel-form" action="${url.loginAction}" method="post">
                        <input type="hidden" name="${loginLinkSent.cancelParameter}" value="1"/>
                        <#if loginLinkSent.identifier?has_content>
                            <input type="hidden" name="username" value="${loginLinkSent.identifier}"/>
                        </#if>
                        <button type="submit" class="al-ghost-button">
                            ${(loginLinkSent.back == "password")?then(msg("loginLinkUsePasswordInstead"), msg("loginLinkOtherAddress"))}
                        </button>
                    </form>
                </#if>
            </div>
        </div>
        <script type="module" src="${url.resourcesPath}/js/login-link.js?v=${properties.assetVersion!''}"></script>
    </#if>
</@layout.registrationLayout>
