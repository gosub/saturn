package it.lo.exp.saturn;

import android.content.Context;
import android.content.res.Configuration;

import java.util.Locale;

/** Applies the in-app language preference as the context locale, so string
 *  resources resolve against values-&lt;lang&gt; regardless of the device
 *  locale. Plain framework API, no AndroidX. Activities call {@link #wrap} from
 *  attachBaseContext; background components wrap their own context before
 *  reading user-facing strings. */
public class LocaleHelper {

    static String language(Context base) {
        return base.getSharedPreferences("saturn", Context.MODE_PRIVATE)
            .getString("language", "en");
    }

    static Context wrap(Context base) {
        Locale locale = new Locale(language(base));
        Locale.setDefault(locale);
        Configuration config = new Configuration(base.getResources().getConfiguration());
        config.setLocale(locale);
        return base.createConfigurationContext(config);
    }
}
