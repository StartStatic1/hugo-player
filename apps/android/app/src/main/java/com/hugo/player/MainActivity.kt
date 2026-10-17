package com.hugo.player

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.UUID

/**
 * Generates and persists the device identity used for activation.
 * The Device ID is NOT the hardware MAC: it is derived from a random UUID and
 * formatted MAC-style, with the locally-administered bit set and multicast bit cleared.
 */
object DeviceIdentity {
    private const val PREFS = "hugo_identity"
    private const val KEY_ID = "device_id"
    private const val KEY_KEY = "device_key"
    private const val KEY_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    private const val KEY_LENGTH = 8

    data class Identity(val deviceId: String, val deviceKey: String)

    fun get(context: Context): Identity {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val storedId = prefs.getString(KEY_ID, null)
        val storedKey = prefs.getString(KEY_KEY, null)
        if (storedId != null && storedKey != null) {
            return Identity(storedId, storedKey)
        }
        val identity = Identity(generateDeviceId(), generateDeviceKey())
        prefs.edit()
            .putString(KEY_ID, identity.deviceId)
            .putString(KEY_KEY, identity.deviceKey)
            .apply()
        return identity
    }

    fun generateDeviceId(): String {
        val uuid = UUID.randomUUID()
        val buffer = ByteBuffer.allocate(16)
        buffer.putLong(uuid.mostSignificantBits)
        buffer.putLong(uuid.leastSignificantBits)
        val bytes = buffer.array().copyOfRange(10, 16)
        // Set locally-administered bit (0x02), clear multicast bit (0x01).
        bytes[0] = ((bytes[0].toInt() or 0x02) and 0xFE).toByte()
        return bytes.joinToString(":") { "%02X".format(it.toInt() and 0xFF) }
    }

    fun generateDeviceKey(): String {
        val random = SecureRandom()
        return buildString(KEY_LENGTH) {
            repeat(KEY_LENGTH) { append(KEY_ALPHABET[random.nextInt(KEY_ALPHABET.length)]) }
        }
    }
}

/** Minimal API client using HttpURLConnection. */
object HugoApi {
    data class License(val active: Boolean, val type: String, val daysLeft: Int?, val expiresAt: String?)
    data class Result(val ok: Boolean, val message: String, val license: License? = null)

    suspend fun register(identity: DeviceIdentity.Identity, isTv: Boolean): Result =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("deviceId", identity.deviceId)
                .put("deviceKey", identity.deviceKey)
                .put("platform", if (isTv) "android-tv" else "android")
                .put("appVersion", BuildConfig.VERSION_NAME)
                .toString()
            request("POST", "/devices/register", body, identity)
        }

    suspend fun status(identity: DeviceIdentity.Identity): Result =
        withContext(Dispatchers.IO) { request("GET", "/devices/status", null, identity) }

    private fun request(
        method: String,
        path: String,
        body: String?,
        identity: DeviceIdentity.Identity
    ): Result {
        val connection = try {
            URL(BuildConfig.API_BASE_URL.trimEnd('/') + path).openConnection() as HttpURLConnection
        } catch (e: IOException) {
            return Result(false, e.message ?: e.javaClass.simpleName)
        }
        return try {
            connection.requestMethod = method
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("x-device-id", identity.deviceId)
            connection.setRequestProperty("x-device-key", identity.deviceKey)
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = try {
                if (text.isNotBlank()) JSONObject(text) else JSONObject()
            } catch (e: org.json.JSONException) {
                JSONObject()
            }
            if (code in 200..299) {
                Result(true, "Registered", parseLicense(json))
            } else {
                Result(false, "Error $code: ${json.optString("error", "unknown_error")}")
            }
        } catch (e: IOException) {
            Result(false, e.message ?: e.javaClass.simpleName)
        } finally {
            connection.disconnect()
        }
    }

    private fun parseLicense(json: JSONObject): License? {
        val license = json.optJSONObject("device")?.optJSONObject("license") ?: return null
        return License(
            active = license.optBoolean("active", false),
            type = license.optString("type", "unknown").replaceFirstChar { it.uppercase() },
            daysLeft = if (license.isNull("daysLeft")) null else license.optInt("daysLeft"),
            expiresAt = if (license.isNull("expiresAt")) null else license.optString("expiresAt").take(10)
        )
    }
}

private val HugoColors = darkColorScheme(
    primary = Color(0xFF7C5CFF),
    secondary = Color(0xFF22D3EE),
    background = Color(0xFF0B0D17),
    surface = Color(0xFF151827),
    surfaceVariant = Color(0xFF1E2236),
    onPrimary = Color.White,
    onBackground = Color(0xFFE6E8F2),
    onSurface = Color(0xFFE6E8F2),
    onSurfaceVariant = Color(0xFF9CA3C4)
)

private val Success = Color(0xFF34D399)
private val Danger = Color(0xFFF87171)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val identity = DeviceIdentity.get(applicationContext)
        val isTv = packageManager.hasSystemFeature("android.software.leanback") ||
            (Build.VERSION.SDK_INT >= 26 && packageManager.hasSystemFeature("android.software.leanback_only"))
        setContent {
            MaterialTheme(colorScheme = HugoColors) {
                ActivationScreen(identity = identity, isTv = isTv)
            }
        }
    }
}

/** Adds a visible focus ring + slight scale so D-pad navigation is obvious on Android TV. */
@Composable
private fun Modifier.tvFocusable(shape: RoundedCornerShape, interaction: MutableInteractionSource): Modifier {
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) 1.04f else 1f, label = "focusScale")
    return this
        .scale(scale)
        .border(
            width = if (focused) 3.dp else 1.dp,
            color = if (focused) MaterialTheme.colorScheme.secondary else Color.White.copy(alpha = 0.08f),
            shape = shape
        )
}

@Composable
fun ActivationScreen(identity: DeviceIdentity.Identity, isTv: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val connecting = stringResource(R.string.status_connecting)
    var status by remember { mutableStateOf(connecting) }
    var ok by remember { mutableStateOf<Boolean?>(null) }
    var license by remember { mutableStateOf<HugoApi.License?>(null) }
    val firstFocus = remember { FocusRequester() }

    fun apply(result: HugoApi.Result) {
        ok = result.ok
        status = result.message
        if (result.license != null) license = result.license
    }

    LaunchedEffect(identity) {
        apply(HugoApi.register(identity, isTv))
    }
    LaunchedEffect(isTv) {
        if (isTv) runCatching { firstFocus.requestFocus() }
    }

    val copy: (String, String) -> Unit = { label, value ->
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, value))
        Toast.makeText(context, context.getString(R.string.copied, label), Toast.LENGTH_SHORT).show()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF1A1440), MaterialTheme.colorScheme.background)
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = if (isTv) 720.dp else 560.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = if (isTv) 32.dp else 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            BrandHeader()
            Text(
                text = stringResource(R.string.activation_title),
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(R.string.activation_subtitle),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )
            val idLabel = stringResource(R.string.label_device_id)
            val keyLabel = stringResource(R.string.label_device_key)
            CredentialCard(
                label = idLabel,
                value = identity.deviceId,
                onCopy = { copy(idLabel, identity.deviceId) },
                focusRequester = firstFocus
            )
            CredentialCard(
                label = keyLabel,
                value = identity.deviceKey,
                onCopy = { copy(keyLabel, identity.deviceKey) }
            )
            StatusCard(status = status, ok = ok, license = license)
            Spacer(Modifier.height(2.dp))
            val btnInteraction = remember { MutableInteractionSource() }
            val btnShape = RoundedCornerShape(28.dp)
            Button(
                onClick = {
                    status = connecting
                    ok = null
                    scope.launch { apply(HugoApi.status(identity)) }
                },
                enabled = ok != null,
                shape = btnShape,
                interactionSource = btnInteraction,
                modifier = Modifier
                    .tvFocusable(btnShape, btnInteraction)
                    .height(52.dp)
                    .widthIn(min = 220.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text(stringResource(R.string.action_refresh), fontWeight = FontWeight.SemiBold)
            }
            if (isTv) {
                Text(
                    text = stringResource(R.string.tv_hint),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun BrandHeader() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(
                    Brush.linearGradient(
                        listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary)
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Text("▶", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(
                text = stringResource(R.string.app_name),
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = stringResource(R.string.tagline),
                color = MaterialTheme.colorScheme.secondary,
                fontSize = 13.sp
            )
        }
    }
}

@Composable
private fun CredentialCard(
    label: String,
    value: String,
    onCopy: () -> Unit,
    focusRequester: FocusRequester? = null
) {
    val shape = RoundedCornerShape(18.dp)
    val interaction = remember { MutableInteractionSource() }
    Card(
        onClick = onCopy,
        shape = shape,
        interactionSource = interaction,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .tvFocusable(shape, interaction),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = label.uppercase(),
                    color = MaterialTheme.colorScheme.secondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.sp
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = value,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 24.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(
                    text = stringResource(R.string.action_copy),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun StatusCard(status: String, ok: Boolean?, license: HugoApi.License?) {
    val dotColor = when (ok) {
        true -> Success
        false -> Danger
        null -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            Text(
                text = stringResource(R.string.label_status).uppercase(),
                color = MaterialTheme.colorScheme.secondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(dotColor))
                Spacer(Modifier.width(10.dp))
                Text(text = status, color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp)
            }
            if (license != null) {
                Spacer(Modifier.height(14.dp))
                Text(
                    text = stringResource(R.string.label_license).uppercase(),
                    color = MaterialTheme.colorScheme.secondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.sp
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background((if (license.active) Success else Danger).copy(alpha = 0.18f))
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = if (license.active) "ACTIVE" else "INACTIVE",
                            color = if (license.active) Success else Danger,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = buildString {
                            append(license.type)
                            license.daysLeft?.let { append(" · $it day(s) left") }
                            license.expiresAt?.let { append(" · until $it") }
                        },
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 14.sp
                    )
                }
            }
        }
    }
}
