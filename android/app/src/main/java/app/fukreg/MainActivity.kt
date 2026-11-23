package app.fukreg

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.fukreg.link.HostService
import app.fukreg.link.PairStore
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

class MainActivity : ComponentActivity() {
    private val perms = arrayOf(
        Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE,
        Manifest.permission.READ_CONTACTS, Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS,
        Manifest.permission.SEND_SMS, Manifest.permission.READ_PHONE_STATE, Manifest.permission.POST_NOTIFICATIONS)

    private val ask = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (it.values.all { g -> g }) startForegroundService(Intent(this, HostService::class.java))
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        val qr = qrBitmap(PairStore(this).qrUri())
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkThemeCompat()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    val connected by HostService.connected.collectAsState()
                    Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(stringResource(R.string.pair_title), style = MaterialTheme.typography.titleLarge)
                        Image(qr.asImageBitmap(), null, Modifier.size(280.dp))
                        Text(stringResource(if (connected) R.string.status_connected else R.string.status_waiting))
                        Button(onClick = { ask.launch(perms) }) { Text(stringResource(R.string.start)) }
                    }
                }
            }
        }
    }

    @Composable private fun isSystemInDarkThemeCompat() = androidx.compose.foundation.isSystemInDarkTheme()

    private fun qrBitmap(text: String): Bitmap {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 600, 600)
        return Bitmap.createBitmap(600, 600, Bitmap.Config.RGB_565).apply {
            for (x in 0 until 600) for (y in 0 until 600) setPixel(x, y, if (m[x, y]) Color.BLACK else Color.WHITE)
        }
    }
}
