<#import "template.ftl" as layout>
<#assign device = "">
<#if browser?? && operatingSystem??>
  <#assign device = msg("loginLinkEmailDevice", browser, operatingSystem)>
<#elseif browser??>
  <#assign device = browser>
<#elseif operatingSystem??>
  <#assign device = operatingSystem>
</#if>
<@layout.emailLayout preheader=msg("loginLinkEmailPreheader", code)>
<p style="margin:0 0 16px 0;"><#if user.firstName?has_content>${msg("loginLinkEmailGreeting", user.firstName)}<#else>${msg("loginLinkEmailGreetingAnonymous")}</#if></p>
<p style="margin:0 0 24px 0;">${msg("loginLinkEmailIntro")}</p>
<table role="presentation" cellpadding="0" cellspacing="0" border="0" style="margin:0 0 28px 0;">
  <tr>
    <td align="center" bgcolor="#0E9BF5" style="border-radius:12px;background-color:#0E9BF5;">
      <a href="${link}" target="_blank" style="display:inline-block;padding:14px 32px;font-family:'Plus Jakarta Sans',-apple-system,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;font-size:16px;font-weight:700;line-height:20px;color:#FFFFFF;text-decoration:none;border-radius:12px;">${msg("loginLinkEmailButton")}</a>
    </td>
  </tr>
</table>
<p style="margin:0 0 6px 0;font-size:14px;color:#64748B;">${msg("loginLinkEmailCodeIntro")}</p>
<p style="margin:0 0 24px 0;font-size:30px;line-height:38px;font-weight:800;letter-spacing:4px;color:#0F172A;">${code}</p>
<p style="margin:0 0 6px 0;font-size:14px;color:#64748B;">${msg("loginLinkEmailValidity", expirationMinutes?c)}</p>
<#if device?has_content>
<p style="margin:0 0 6px 0;font-size:14px;color:#64748B;">${msg("loginLinkEmailRequestedFrom", device)}</p>
</#if>
<p style="margin:16px 0 24px 0;font-size:14px;color:#64748B;">${msg("loginLinkEmailNotYou")}</p>
<p style="margin:0 0 24px 0;">${msg("loginLinkEmailSignature")}</p>
<p style="margin:0;padding-top:16px;border-top:1px solid #E8EDF4;font-size:12px;line-height:1.5;color:#94A3B8;">${msg("loginLinkEmailFallback")}<br><a href="${link}" target="_blank" style="color:#1668B8;word-break:break-all;">${link}</a></p>
</@layout.emailLayout>
