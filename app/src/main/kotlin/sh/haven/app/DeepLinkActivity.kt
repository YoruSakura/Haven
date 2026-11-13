package sh.haven.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle

class DeepLinkActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val data = intent?.data
        if (data != null) {
            val activeActivity = MainActivity.getActiveInstance()
            if (activeActivity != null) {
                activeActivity.handleDeepLinkUri(data)
            }
            val forwardIntent = Intent(this, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                setData(data)
                // Add flags to ensure the intent is delivered to MainActivity
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(forwardIntent)
        }
        finish()
    }
}
