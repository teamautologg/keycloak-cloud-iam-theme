<#-- Keycloak's passkey sign-in (base theme), with the button set apart by "oder" and a key in front of
     it, like the link button above it. -->
<#macro conditionalUIData>
    <#if enableWebAuthnConditionalUI?has_content>
        <form id="webauth" action="${url.loginAction}" method="post">
            <input type="hidden" id="clientDataJSON" name="clientDataJSON"/>
            <input type="hidden" id="authenticatorData" name="authenticatorData"/>
            <input type="hidden" id="signature" name="signature"/>
            <input type="hidden" id="credentialId" name="credentialId"/>
            <input type="hidden" id="userHandle" name="userHandle"/>
            <input type="hidden" id="error" name="error"/>
        </form>
        <#if authenticators??>
            <form id="authn_select" class="${properties.kcFormClass!}">
                <#list authenticators.authenticators as authenticator>
                    <input type="hidden" name="authn_use_chk" value="${authenticator.credentialId}"/>
                </#list>
            </form>
        </#if>
        <script type="module">
           <#outputformat "JavaScript">
           import { authenticateByWebAuthn } from "${url.resourcesPath}/js/webauthnAuthenticate.js";
           import { initAuthenticate } from "${url.resourcesPath}/js/passkeysConditionalAuth.js";

           const args = {
               isUserIdentified : ${isUserIdentified},
               challenge : ${challenge?c},
               userVerification : ${userVerification?c},
               rpId : ${rpId?c},
               createTimeout : ${createTimeout?c},
               mediation : ${(mediation!'conditional')?c},
               authenticatorAttachment : ${authenticatorAttachment?c},
           };

           document.addEventListener("DOMContentLoaded", (event) => initAuthenticate({errmsg : ${msg("passkey-unsupported-browser-text")?c}, ...args}));
           const authButton = document.getElementById('authenticateWebAuthnButton');
           if (authButton) {
               authButton.addEventListener("click", (event) => {
                   event.preventDefault();
                   authenticateByWebAuthn({errmsg : ${msg("webauthn-unsupported-browser-text")?c}, ...args});
               }, { once: true });
           }
           </#outputformat>
        </script>
        <div class="al-divider"><span>${msg("loginLinkOr")}</span></div>
        <a id="authenticateWebAuthnButton" href="#" class="${properties.kcButtonSecondaryClass!} ${properties.kcButtonBlockClass!} al-link-button">
            <svg class="al-link-button-icon" viewBox="0 0 24 24" aria-hidden="true"><g fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="8" cy="15" r="4"/><path d="M10.85 12.15 20 3M17 6l2 2M14.5 8.5l2 2"/></g></svg>
            ${msg("webauthn-doAuthenticate")}
        </a>
    </#if>
</#macro>
