package com.winlator.contentdialog;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.text.Html;
import android.text.method.LinkMovementMethod;
import android.view.View;
import android.widget.TextView;

import com.winlator.R;
import com.winlator.core.DefaultVersion;
import com.winlator.core.WineInfo;

public class AboutDialog extends ContentDialog {
    public AboutDialog(Context context) {
        super(context, R.layout.about_dialog);
        findViewById(R.id.LLBottomBar).setVisibility(View.GONE);

        try {
            final PackageInfo pInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);

            TextView tvWebpage = findViewById(R.id.TVWebpage);
            tvWebpage.setText(Html.fromHtml(
                "<a href=\"https://teknoparrot.com\">teknoparrot.com</a>",
                Html.FROM_HTML_MODE_LEGACY));
            tvWebpage.setMovementMethod(LinkMovementMethod.getInstance());

            ((TextView)findViewById(R.id.TVAppVersion)).setText(context.getString(R.string.version)+" "+pInfo.versionName);

            String creditsAndThirdPartyAppsHTML = String.join("<br />",
                "Based on <a href=\"https://github.com/brunodev85/winlator\">Winlator</a> 11.1 by brunodev85",
                "Arcade compatibility by <a href=\"https://github.com/teknogods/OpenParrot\">OpenParrot</a>",
                "GLIBC Patches by (<a href=\"https://github.com/termux-pacman/glibc-packages\">Termux Pacman</a>)",
                "Wine " + WineInfo.MAIN_WINE_VERSION + " (<a href=\"https://www.winehq.org\">WineHQ</a>)",
                "Box64 " + DefaultVersion.BOX64 + " by " +
                    "<a href=\"https://github.com/ptitSeb/box64\">ptitseb</a>",
                "ffdshow tryouts rev4532 and its FFmpeg library for title-scoped Indeo 5 playback " +
                    "(<a href=\"https://sourceforge.net/projects/ffdshow-tryout/\">ffdshow</a>)",
                "Mesa Turnip " + DefaultVersion.TURNIP + ", Zink and VirGL " + DefaultVersion.VIRGL +
                    " (<a href=\"https://www.mesa3d.org\">Mesa 3D</a>)",
                "DXVK " + DefaultVersion.MAJOR_DXVK + " / " + DefaultVersion.MINOR_DXVK +
                    " and D8VK " + DefaultVersion.D8VK +
                    " (<a href=\"https://github.com/doitsujin/dxvk\">DXVK</a>)",
                "VKD3D " + DefaultVersion.VKD3D +
                    " (<a href=\"https://gitlab.winehq.org/wine/vkd3d\">WineHQ</a>)",
                "CNC DDraw " + DefaultVersion.CNC_DDRAW +
                    " (<a href=\"https://github.com/FunkyFr3sh/cnc-ddraw\">FunkyFr3sh</a>)",
                "Android ALSA and PulseAudio compatibility components",
                "All third-party components remain subject to their respective licenses."
            );

            TextView tvCreditsAndThirdPartyApps = findViewById(R.id.TVCreditsAndThirdPartyApps);
            tvCreditsAndThirdPartyApps.setText(Html.fromHtml(creditsAndThirdPartyAppsHTML, Html.FROM_HTML_MODE_LEGACY));
            tvCreditsAndThirdPartyApps.setMovementMethod(LinkMovementMethod.getInstance());
        }
        catch (PackageManager.NameNotFoundException e) {}
    }
}
