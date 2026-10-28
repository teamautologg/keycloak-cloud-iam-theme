<#import "template.ftl" as layout>
<#import "field.ftl" as field>
<#import "buttons.ftl" as buttons>
<#assign codeError = messagesPerField.existsError(loginLinkSent.codeParameter)>
<@layout.registrationLayout displayMessage=!codeError; section>
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
                <#assign minutes = ((loginLinkSent.expiresInSeconds + 59) / 60)?floor>
                <#if loginLinkSent.identifier?has_content>
                    <p class="al-text">${msg("loginLinkSentTo")}</p>
                    <p class="al-sent-address">${loginLinkSent.identifier}</p>
                <#else>
                    <p class="al-text">${msg("loginLinkSentToAccount")}</p>
                </#if>
                <p class="al-help al-tight">${msg("loginLinkSentValid", minutes?c)}</p>
            </div>

            <#if loginLinkSent.mailboxUrl?has_content>
                <div class="al-mailbox">
                    <#if loginLinkSent.mailboxWebUrl?has_content>
                        <#-- The desktop app (ms-outlook:); a custom scheme leaves this page where it is. -->
                        <a id="al-open-mailbox" class="${properties.kcButtonPrimaryClass!} ${properties.kcButtonBlockClass!} al-mailbox-button"
                           href="${loginLinkSent.mailboxUrl}">
                            <svg class="al-link-button-icon" viewBox="0 0 24 24" aria-hidden="true"><path fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" d="M4 6h16v12H4zM4 7l8 6 8-6"/></svg>
                            ${msg("loginLinkOpenMailbox", loginLinkSent.mailboxName)}
                        </a>
                        <p class="al-help al-tight">
                            <a id="al-open-mailbox-web" href="${loginLinkSent.mailboxWebUrl}" target="_blank" rel="noopener noreferrer">${msg("loginLinkOpenMailboxWeb", loginLinkSent.mailboxName)}</a>
                        </p>
                    <#else>
                        <#-- New tab: this page has to stay open to finish the login. -->
                        <a id="al-open-mailbox" class="${properties.kcButtonPrimaryClass!} ${properties.kcButtonBlockClass!} al-mailbox-button"
                           href="${loginLinkSent.mailboxUrl}" target="_blank" rel="noopener noreferrer">
                            <svg class="al-link-button-icon" viewBox="0 0 24 24" aria-hidden="true"><path fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" d="M4 6h16v12H4zM4 7l8 6 8-6"/></svg>
                            ${msg("loginLinkOpenMailbox", loginLinkSent.mailboxName)}
                            <svg class="al-link-button-icon al-external" viewBox="0 0 24 24" aria-hidden="true"><path fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" d="M14 4h6v6M20 4l-9 9M18 14v5a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1h5"/></svg>
                        </a>
                    </#if>
                </div>
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
            <p class="al-help al-status-hint">${msg("loginLinkSentHint")}</p>

            <details class="al-code"<#if codeError> open</#if>>
                <summary>${msg("loginLinkEnterCode")}</summary>
                <form id="kc-login-link-code-form" class="${properties.kcFormClass!}" action="${url.loginAction}" method="post" novalidate="novalidate">
                    <@field.group name=loginLinkSent.codeParameter label=msg("loginLinkCodeLabel") error=messagesPerField.get(loginLinkSent.codeParameter)>
                        <span class="${properties.kcInputClass} al-code-input <#if codeError>${properties.kcError}</#if>">
                            <input id="${loginLinkSent.codeParameter}" name="${loginLinkSent.codeParameter}" type="text"
                                   inputmode="numeric" autocomplete="one-time-code" pattern="[0-9 ]*" spellcheck="false"
                                   maxlength="${(loginLinkSent.codeLength + 2)?c}" placeholder="${"0"?left_pad(loginLinkSent.codeLength, "0")}"
                                   aria-invalid="<#if codeError>true</#if>"/>
                        </span>
                    </@field.group>
                    <@buttons.actionGroup>
                        <@buttons.button id="kc-login-link-code" name="loginLinkSubmit" label="loginLinkCodeSubmit" type="secondary" />
                    </@buttons.actionGroup>
                </form>
            </details>

            <form id="kc-login-link-check-form" action="${url.loginAction}" method="post" hidden>
                <input type="hidden" name="${loginLinkSent.checkParameter}" value="1"/>
            </form>

            <div class="al-link-help">
                <p class="al-help al-tight">${msg("loginLinkNoMailHint")}</p>
                <div class="al-link-actions">
                    <form id="kc-login-link-resend-form" action="${url.loginAction}" method="post">
                        <input type="hidden" name="${loginLinkSent.resendParameter}" value="1"/>
                        <button type="submit" id="kc-login-link-resend" class="al-text-link"
                                data-wait="${loginLinkSent.resendAvailableInSeconds?c}"
                                data-label="${msg("loginLinkResend")}"
                                data-label-wait="${msg("loginLinkResendIn")}">${msg("loginLinkResend")}</button>
                    </form>
                    <#if loginLinkSent.back != "none">
                        <span class="al-dot" aria-hidden="true">·</span>
                        <form id="kc-login-link-cancel-form" action="${url.loginAction}" method="post">
                            <input type="hidden" name="${loginLinkSent.cancelParameter}" value="1"/>
                            <#if loginLinkSent.identifier?has_content>
                                <input type="hidden" name="username" value="${loginLinkSent.identifier}"/>
                            </#if>
                            <button type="submit" class="al-text-link">
                                ${(loginLinkSent.back == "password")?then(msg("loginLinkUsePasswordInstead"), msg("loginLinkOtherAddress"))}
                            </button>
                        </form>
                    </#if>
                </div>
            </div>
        </div>
        <script type="module" src="${url.resourcesPath}/js/login-link.js?v=${properties.assetVersion!''}"></script>
    </#if>
</@layout.registrationLayout>
