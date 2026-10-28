<#-- Shared parts of the login-link pages. -->

<#-- "I accept the terms and have read the privacy information" - required before an account is created. -->
<#macro termsCheckbox name missing=false>
    <div class="al-terms<#if missing> al-terms-missing</#if>">
        <input class="pf-v5-c-check__input al-terms-box" type="checkbox" id="al-terms" name="${name}" value="on" required
               <#if missing>aria-invalid="true" aria-describedby="al-terms-error"</#if>/>
        <label class="al-terms-label" for="al-terms">
            ${msg("loginLinkTermsAccept1")} <a href="${msg("loginLinkTermsUrl")}" target="_blank" rel="noopener noreferrer">${msg("loginLinkTermsLink")}</a>
            ${msg("loginLinkTermsAccept2")} <a href="${msg("loginFooterPrivacyUrl")}" target="_blank" rel="noopener noreferrer">${msg("loginLinkPrivacyLink")}</a>${msg("loginLinkTermsAccept3")}
        </label>
    </div>
    <#if missing>
        <p id="al-terms-error" class="al-terms-error" role="alert">${msg("loginLinkTermsRequired")}</p>
    </#if>
</#macro>
