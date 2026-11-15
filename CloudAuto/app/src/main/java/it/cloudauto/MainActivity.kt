package it.cloudauto

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** Schermata sul telefono: login a Yandex Disk (WebDAV). In auto non si fa il login. */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var loginInput: EditText
    private lateinit var passwordInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(pad + bars.left, pad + bars.top, pad + bars.right, pad + bars.bottom)
            insets
        }

        status = TextView(this).apply { textSize = 16f }
        val help = TextView(this).apply {
            text = getString(R.string.help)
            setPadding(0, pad, 0, pad)
        }
        loginInput = EditText(this).apply {
            hint = getString(R.string.login_hint)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }
        passwordInput = EditText(this).apply {
            hint = getString(R.string.password_hint)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val save = Button(this).apply {
            text = getString(R.string.save)
            setOnClickListener { saveAndVerify() }
        }
        val logout = Button(this).apply {
            text = getString(R.string.logout)
            setOnClickListener {
                CredentialStore.set(this@MainActivity, null)
                refresh()
            }
        }

        listOf(status, help, loginInput, passwordInput, save, logout).forEach { root.addView(it) }
        setContentView(root)

        refresh()
        requestNotificationPermission()
    }

    private fun saveAndVerify() {
        val login = loginInput.text.toString().trim()
        val password = passwordInput.text.toString().trim()
        if (login.isEmpty() || password.isEmpty()) return

        val account = Account(login, password)
        CredentialStore.set(this, account)
        passwordInput.text.clear()
        status.setText(R.string.checking)

        Thread {
            val message = try {
                YandexDiskApi { account }.list("/")
                getString(R.string.check_ok)
            } catch (e: YandexDiskApi.AuthException) {
                CredentialStore.set(this, null)
                getString(R.string.check_auth_error)
            } catch (e: Exception) {
                getString(R.string.check_error, e.message ?: e.javaClass.simpleName)
            }
            runOnUiThread {
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                refresh()
            }
        }.start()
    }

    private fun refresh() {
        val account = CredentialStore.get(this)
        status.text = if (account != null) {
            getString(R.string.status_logged_in, account.login)
        } else {
            getString(R.string.status_logged_out)
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }
}
