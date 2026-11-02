package com.gakovic.etikete.ui

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.tasks.await
import java.io.File

val Blue = Color(0xFF1E3A8A)
val BlueLight = Color(0xFF2563EB)
val Ok = Color(0xFF15803D)
val Err = Color(0xFFB91C1C)
val Warn = Color(0xFFB45309)
val Muted = Color(0xFF69737D)
val Bg = Color(0xFFF6F7F9)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = BlueLight, onPrimary = Color.White, secondary = Blue,
            background = Bg, surface = Color.White, error = Err,
        ),
        content = content
    )
}

/** Skeniranje kamerom (Google code scanner). Vraca null ako je otkazano. */
suspend fun scan(c: Context): String? = try {
    val opts = GmsBarcodeScannerOptions.Builder().enableAutoZoom().build()
    GmsBarcodeScanning.getClient(c, opts).startScan().await()?.rawValue?.trim()
} catch (e: Exception) {
    if (e.message?.contains("cancel", ignoreCase = true) != true) {
        Toast.makeText(c, "Skener nije dostupan: ${e.message}", Toast.LENGTH_LONG).show()
    }
    null
}

/**
 * Polje za unos sa dugmetom za kameru. Radi i sa rucnim skenerima (Zebra, iData...) koji
 * "kucaju" kod i salju Enter.
 */
@Composable
fun ScanField(
    label: String,
    value: String,
    onValue: (String) -> Unit,
    onScanClick: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    focus: FocusRequester? = null,
    enabled: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { v ->
            // rucni skeneri cesto dodaju novi red na kraj
            if (v.endsWith("\n")) {
                onValue(v.trim()); onDone()
            } else onValue(v)
        },
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().let { if (focus != null) it.focusRequester(focus) else it },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        trailingIcon = {
            Row {
                if (value.isNotEmpty()) IconButton(onClick = { onValue("") }) {
                    Icon(Icons.Filled.Clear, contentDescription = "Obriši")
                }
                IconButton(onClick = onScanClick, enabled = enabled) {
                    Icon(Icons.Filled.QrCodeScanner, contentDescription = "Skeniraj", tint = BlueLight)
                }
            }
        }
    )
}

@Composable
fun Section(title: String? = null, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (title != null) Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            content()
        }
    }
}

@Composable
fun Message(text: String, color: Color) {
    Box(
        Modifier.fillMaxWidth().background(color.copy(alpha = 0.10f), RoundedCornerShape(8.dp)).padding(10.dp)
    ) { Text(text, color = color, fontSize = 14.sp) }
}

@Composable
fun Pill(text: String, color: Color) {
    Box(Modifier.background(color.copy(alpha = 0.12f), RoundedCornerShape(10.dp)).padding(horizontal = 8.dp, vertical = 2.dp)) {
        Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun Loading(text: String = "Učitavam…") {
    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(10.dp))
        Text(text, color = Muted)
    }
}

@Composable
fun Stat(value: String, label: String, color: Color = Color.Unspecified, modifier: Modifier = Modifier) {
    Column(modifier.background(Bg, RoundedCornerShape(10.dp)).padding(10.dp)) {
        Text(value, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = color)
        Text(label, fontSize = 12.sp, color = Muted)
    }
}

fun batteryColor(b: Int?): Color = when {
    b == null -> Muted
    b <= 15 -> Err
    b <= 30 -> Warn
    else -> Ok
}

/** Deli tekstualni fajl (CSV) preko Android share menija. */
fun shareText(c: Context, fileName: String, content: String, mime: String = "text/csv") {
    val f = File(c.cacheDir, fileName)
    f.writeText(content)
    val uri = FileProvider.getUriForFile(c, c.packageName + ".files", f)
    val i = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    c.startActivity(Intent.createChooser(i, "Pošalji izveštaj").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

fun toast(c: Context, s: String) = Toast.makeText(c, s, Toast.LENGTH_SHORT).show()

/** Kratka vibracija (greska = dva puta). */
fun buzz(c: Context, error: Boolean = false) {
    runCatching {
        val v = c.getSystemService(android.os.Vibrator::class.java) ?: return
        val effect = if (error) android.os.VibrationEffect.createWaveform(longArrayOf(0, 180, 120, 180), -1)
        else android.os.VibrationEffect.createOneShot(60, android.os.VibrationEffect.DEFAULT_AMPLITUDE)
        v.vibrate(effect)
    }
}
