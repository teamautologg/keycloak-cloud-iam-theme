<#import "template.ftl" as layout>
<#import "password-commons.ftl" as passwordCommons>
<#import "buttons.ftl" as buttons>
<#-- keycloak.v2's passkey registration (kc_action=webauthn-register-passwordless from the app) with a few
     words on what happens next, and the device as the suggested name: the app lists passkeys by it. -->

<@layout.registrationLayout; section>
    <#if section = "title">
        title
    <#elseif section = "header">
        ${msg("webauthn-registration-title")}
    <#elseif section = "form">
    <div class="${properties.kcFormClass!}">
        <p class="al-help">${msg("passkeyRegisterIntro")}</p>
        <form id="register" action="${url.loginAction}" method="post" >
            <div class="${properties.kcFormGroupClass!}">
                <input type="hidden" id="clientDataJSON" name="clientDataJSON"/>
                <input type="hidden" id="attestationObject" name="attestationObject"/>
                <input type="hidden" id="publicKeyCredentialId" name="publicKeyCredentialId"/>
                <input type="hidden" id="authenticatorLabel" name="authenticatorLabel"/>
                <input type="hidden" id="transports" name="transports"/>
                <input type="hidden" id="authenticatorAttachment" name="authenticatorAttachment"/>
                <input type="hidden" id="error" name="error"/>
                <@passwordCommons.logoutOtherSessions/>
            </div>
        </form>

        <script type="module">
            <#outputformat "JavaScript">
            import { registerByWebAuthn } from "${url.resourcesPath}/js/webauthnRegister.js";

            // The name Keycloak asks for after the passkey is created, suggested from the device.
            function deviceName(fallback) {
                const agent = navigator.userAgent;
                if (/iPhone/.test(agent)) return "iPhone";
                if (/iPad/.test(agent) || (/Macintosh/.test(agent) && navigator.maxTouchPoints > 1)) return "iPad";
                if (/Android/.test(agent)) return "Android";
                if (/Macintosh/.test(agent)) return "Mac";
                if (/Windows/.test(agent)) return "Windows";
                if (/CrOS/.test(agent)) return "Chromebook";
                if (/Linux/.test(agent)) return "Linux";
                return fallback;
            }

            const registerButton = document.getElementById('registerWebAuthn');
            registerButton.addEventListener("click", function() {
                const input = {
                    challenge : ${challenge?c},
                    userid : ${userid?c},
                    username : ${username?c},
                    signatureAlgorithms : [<#list signatureAlgorithms as sigAlg>${sigAlg?c},</#list>],
                    rpEntityName : ${rpEntityName?c},
                    rpId : ${rpId?c},
                    attestationConveyancePreference : ${attestationConveyancePreference?c},
                    authenticatorAttachment : ${authenticatorAttachment?c},
                    requireResidentKey : ${requireResidentKey?c},
                    residentKey : ${residentKey?c},
                    userVerificationRequirement : ${userVerificationRequirement?c},
                    createTimeout : ${createTimeout?c},
                    excludeCredentialIds : ${excludeCredentialIds?c},
                    initLabel : deviceName(${msg("webauthn-registration-init-label")?c}),
                    initLabelPrompt : ${msg("webauthn-registration-init-label-prompt")?c},
                    errmsg : ${msg("webauthn-unsupported-browser-text")?c}
                };
                registerByWebAuthn(input);
            },  { once: true });
            </#outputformat>
        </script>

            <@buttons.actionGroup horizontal=true>
                <@buttons.button id="registerWebAuthn" label="doRegisterSecurityKey"/>
                <#if !isSetRetry?has_content && isAppInitiatedAction?has_content>
                    <form class="${properties.kcFormClass!}" action="${url.loginAction}"
                          id="kc-webauthn-settings-form" method="post">
                        <@buttons.button id="cancelWebAuthnAIA" name="cancel-aia" label="doCancel" type="secondary"/>
                    </form>
                </#if>
            </@buttons.actionGroup>
        <p class="al-help">${msg("passkeyRegisterOtherDevice")}</p>
    </div>
    </#if>
</@layout.registrationLayout>
