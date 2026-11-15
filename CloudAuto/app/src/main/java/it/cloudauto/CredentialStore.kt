package it.cloudauto

import android.content.Context

data class Account(val login: String, val password: String)

/** Salva login e password per app in SharedPreferences private dell'app. */
object CredentialStore {
    private const val FILE = "cloudauto"
    private const val KEY_LOGIN = "yandex_login"
    private const val KEY_PASSWORD = "yandex_app_password"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun get(ctx: Context): Account? {
        val p = prefs(ctx)
        val login = p.getString(KEY_LOGIN, null)
        val password = p.getString(KEY_PASSWORD, null)
        return if (login.isNullOrBlank() || password.isNullOrBlank()) null else Account(login, password)
    }

    fun set(ctx: Context, account: Account?) {
        prefs(ctx).edit().apply {
            if (account == null) {
                remove(KEY_LOGIN)
                remove(KEY_PASSWORD)
            } else {
                putString(KEY_LOGIN, account.login.trim())
                putString(KEY_PASSWORD, account.password.trim())
            }
        }.apply()
    }
}
