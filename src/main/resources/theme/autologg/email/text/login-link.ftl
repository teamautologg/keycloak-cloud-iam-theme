<#ftl output_format="plainText">
<#assign device = "">
<#if browser?? && operatingSystem??>
  <#assign device = msg("loginLinkEmailDevice", browser, operatingSystem)>
<#elseif browser??>
  <#assign device = browser>
<#elseif operatingSystem??>
  <#assign device = operatingSystem>
</#if>
<#if user.firstName?has_content>${msg("loginLinkEmailGreeting", user.firstName)}<#else>${msg("loginLinkEmailGreetingAnonymous")}</#if>

${msg("loginLinkEmailIntro")}

${link}

${msg("loginLinkEmailCodeIntro")} ${code}

${msg("loginLinkEmailValidity", expirationMinutes?c)}
<#if device?has_content>${msg("loginLinkEmailRequestedFrom", device)}
</#if>

${msg("loginLinkEmailNotYou")}

${msg("loginLinkEmailSignature")}
