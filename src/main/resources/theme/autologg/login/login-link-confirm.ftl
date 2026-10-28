<#import "template.ftl" as layout>
<#import "buttons.ftl" as buttons>
<@layout.registrationLayout; section>
<!-- template: login-link-confirm.ftl -->
    <#if section = "header">
        <#if loginLinkConfirm.state == "confirm">
            ${msg(loginLinkConfirm.register?then("loginLinkRegisterConfirmTitle", "loginLinkConfirmTitle"))}
        <#elseif loginLinkConfirm.state == "done">
            ${msg("loginLinkConfirmedTitle")}
        <#elseif loginLinkConfirm.state == "denied">
            ${msg("loginLinkDeniedTitle")}
        <#else>
            ${msg("loginLinkInvalidTitle")}
        </#if>
    <#elseif section = "form">
        <div class="al-link-confirm" data-state="${loginLinkConfirm.state}">
        <#if loginLinkConfirm.state == "confirm">
            <p class="al-text">${msg(loginLinkConfirm.register?then("loginLinkRegisterConfirmText", "loginLinkConfirmText"))} <strong class="al-nowrap">${loginLinkConfirm.email}</strong></p>
            <dl class="al-request">
                <div>
                    <dt>${msg("loginLinkConfirmRequested")}</dt>
                    <dd><#if loginLinkConfirm.minutesAgo lt 1>${msg("loginLinkConfirmJustNow")}<#else>${msg("loginLinkConfirmMinutesAgo", loginLinkConfirm.minutesAgo?c)}</#if></dd>
                </div>
                <div>
                    <dt>${msg("loginLinkConfirmDevice")}</dt>
                    <dd>
                        <#if loginLinkConfirm.browser?has_content && loginLinkConfirm.operatingSystem?has_content>
                            ${msg("loginLinkDevice", loginLinkConfirm.browser, loginLinkConfirm.operatingSystem)}
                        <#elseif loginLinkConfirm.browser?has_content>
                            ${loginLinkConfirm.browser}
                        <#elseif loginLinkConfirm.operatingSystem?has_content>
                            ${loginLinkConfirm.operatingSystem}
                        <#else>
                            ${msg("loginLinkDeviceUnknown")}
                        </#if>
                    </dd>
                </div>
            </dl>
            <form id="kc-login-link-confirm-form" class="${properties.kcFormClass!}" action="${loginLinkConfirm.actionUrl}" method="post"
                  onsubmit="this.querySelector('button').disabled = true; return true;">
                <input type="hidden" name="key" value="${loginLinkConfirm.key}"/>
                <@buttons.actionGroup>
                    <@buttons.button id="kc-login-link-confirm" name="confirm"
                                     label=loginLinkConfirm.register?then("loginLinkRegisterConfirmButton", "loginLinkConfirmButton") />
                </@buttons.actionGroup>
            </form>
            <div class="al-separator"></div>
            <form id="kc-login-link-deny-form" action="${loginLinkConfirm.actionUrl}" method="post">
                <input type="hidden" name="key" value="${loginLinkConfirm.key}"/>
                <button type="submit" class="al-ghost-button al-ghost-danger" name="${loginLinkConfirm.denyParameter}" value="1">${msg("loginLinkDenyButton")}</button>
            </form>
        <#elseif loginLinkConfirm.state == "done">
            <div class="al-link-hero">
                <span class="al-link-hero-icon al-success" aria-hidden="true">
                    <svg viewBox="0 0 24 24"><path fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" d="M5 12.5l4.5 4.5L19 7.5"/></svg>
                </span>
                <p class="al-text">${msg("loginLinkConfirmedText")}</p>
                <p class="al-text al-muted">${msg("loginLinkConfirmedHint")}</p>
            </div>
        <#elseif loginLinkConfirm.state == "denied">
            <div class="al-link-hero">
                <span class="al-link-hero-icon al-warning" aria-hidden="true">
                    <svg viewBox="0 0 24 24"><path fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" d="M12 3l8 3v6c0 4.5-3.4 8.3-8 9-4.6-.7-8-4.5-8-9V6l8-3zM9.5 9.5l5 5M14.5 9.5l-5 5"/></svg>
                </span>
                <p class="al-text">${msg(loginLinkConfirm.reason)}</p>
                <p class="al-text al-muted">${msg("loginLinkDeniedHint")}</p>
            </div>
        <#else>
            <div class="al-link-hero">
                <span class="al-link-hero-icon al-warning" aria-hidden="true">
                    <svg viewBox="0 0 24 24"><path fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" d="M12 8v5M12 16.5v.01M10.3 3.9L2.6 17.3A2 2 0 0 0 4.3 20.3h15.4a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0z"/></svg>
                </span>
                <p class="al-text">${msg(loginLinkConfirm.reason)}</p>
            </div>
        </#if>
        </div>
    </#if>
</@layout.registrationLayout>
