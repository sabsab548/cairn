package org.cairn.android

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import org.cairn.android.ble.MeshtasticBleDriver
import org.cairn.android.transport.RadioTransport

class MainActivity : ComponentActivity() {

    private lateinit var transport: RadioTransport

    private val blePermissions: Array<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE,
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION,
            )
        }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val allGranted = result.values.all { it }
            if (allGranted) {
                transport.connect(byteArrayOf())
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        transport = MeshtasticBleDriver(this)
        setContent {
            CairnTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    CairnScreen(
                        onConnect = { onConnectClicked() },
                        onDisconnect = { transport.disconnect() },
                    )
                }
            }
        }
    }

    private fun onConnectClicked() {
        val missing = blePermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            transport.connect(byteArrayOf())
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }
}

@Composable
fun CairnTheme(content: @Composable () -> Unit) {
    MaterialTheme(content = content)
}

@Composable
fun CairnScreen(
    onConnect: () -> Unit = {},
    onDisconnect: () -> Unit = {},
) {
    var status by remember { mutableStateOf("Not connected") }
    var permissionNote by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                "BLUETOOTH_SCAN / BLUETOOTH_CONNECT required"
            else
                "BLUETOOTH + location permission required"
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(48.dp))

        Text(
            text = "Cairn",
            style = MaterialTheme.typography.displayMedium,
            textAlign = TextAlign.Center
        )

        Text(
            text = "Serverless group map over LoRa",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp)
        )

        Spacer(modifier = Modifier.height(64.dp))

        Button(onClick = {
            status = "Scanning…"
            onConnect()
        }) {
            Text("Connect to radio")
        }

        Spacer(modifier = Modifier.height(24.dp))

        Button(onClick = {
            status = "Not connected"
            onDisconnect()
        }) {
            Text("Disconnect")
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = status,
            style = MaterialTheme.typography.bodyMedium,
            fontSize = 16.sp
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = permissionNote,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(48.dp))

        Text(
            text = "v0.1.0 — pre-alpha",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
