<#-- Branded frame for every HTML e-mail of the realm: logo, white card, footer. Inline styles only (mail clients). -->
<#macro emailLayout preheader="">
<!DOCTYPE html>
<html lang="${locale.language}" dir="${(ltr)?then('ltr','rtl')}">
<head>
<meta http-equiv="Content-Type" content="text/html; charset=UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="color-scheme" content="light">
<meta name="supported-color-schemes" content="light">
<title>${subject!""}</title>
<style>
  p { margin: 0 0 16px 0; }
  a { color: #1668B8; }
</style>
</head>
<body style="margin:0;padding:0;background-color:#EEF2F9;">
<#if preheader?has_content>
<div style="display:none;max-height:0;max-width:0;overflow:hidden;opacity:0;mso-hide:all;font-size:1px;line-height:1px;color:#EEF2F9;">${preheader}</div>
</#if>
<table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" style="background-color:#EEF2F9;">
  <tr>
    <td align="center" style="padding:32px 16px;">
      <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" style="max-width:560px;">
        <tr>
          <td style="padding:0 4px 20px 4px;">
            <#if url?? && url.resourcesUrl??>
              <img src="${url.resourcesUrl}/img/autologg-logo.png" width="112" height="48" alt="AutoLogg" style="display:block;border:0;outline:none;text-decoration:none;width:112px;height:auto;">
            <#else>
              <span style="font-family:Arial,Helvetica,sans-serif;font-size:22px;font-weight:bold;color:#1EA0F0;">AutoLogg</span>
            </#if>
          </td>
        </tr>
        <tr>
          <td style="background-color:#FFFFFF;border:1px solid #E8EDF4;border-radius:18px;padding:32px 28px;font-family:'Plus Jakarta Sans',-apple-system,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;font-size:15px;line-height:1.6;color:#1F2937;">
            <#nested>
          </td>
        </tr>
        <tr>
          <td style="padding:20px 4px 0 4px;font-family:-apple-system,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;font-size:12px;line-height:1.5;color:#64748B;">
            ${msg("emailFooterText")}
          </td>
        </tr>
      </table>
    </td>
  </tr>
</table>
</body>
</html>
</#macro>
