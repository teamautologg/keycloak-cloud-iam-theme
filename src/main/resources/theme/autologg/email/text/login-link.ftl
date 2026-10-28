<#ftl output_format="plainText">
<#assign reg = (register!false)>
<#assign device = "">
<#if browser?? && operatingSystem??>
  <#assign device = msg("loginLinkEmailDevice", browser, operatingSystem)>
<#elseif browser??>
  <#assign device = browser>
<#elseif operatingSystem??>
  <#assign device = operatingSystem>
</#if>
<#if user.firstName?has_content>${msg("loginLinkEmailGreeting", user.firstName)}<#else>${msg("loginLinkEmailGreetingAnonymous")}</#if>

${msg(reg?then("loginLinkRegisterEmailIntro", "loginLinkEmailIntro"))}

${link}

${msg("loginLinkEmailCodeIntro")} ${code}

${msg("loginLinkEmailValidity", expirationMinutes?c)}
<#if device?has_content>${msg("loginLinkEmailRequestedFrom", device)}
</#if>

${msg(reg?then("loginLinkRegisterEmailNotYou", "loginLinkEmailNotYou"))}

${msg("loginLinkEmailSignature")}
