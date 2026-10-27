package com.hugo.player.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.hugo.player.data.SupportLink
import com.hugo.player.ui.components.FocusSurface
import com.hugo.player.ui.components.HugoColors
import kotlinx.coroutines.delay

val WhatsappGreen = Color(0xFF25D366)

/** TV/Fire TV/TV Box (sem tela de toque): abrir o grupo aqui não adianta, melhor o QR para o celular. */
fun isTvLike(c: Context): Boolean = !c.packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN) ||
    c.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

/** Tenta abrir o link (WhatsApp/navegador). Nunca derruba o app: devolve false se não houver quem abra. */
fun openLink(c: Context, url: String): Boolean = runCatching {
    c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}.isSuccess

/** O que mostrar na janela de QR (título, explicação e cor do link). */
data class QrLink(val url: String, val title: String, val hint: String, val color: Color)

/**
 * Abre links sem travar na TV. [open] tenta abrir; se [preferQrOnTv] e o aparelho for TV
 * (ou se nada abrir o link), mostra o QR grande para abrir no celular.
 */
@Composable
fun rememberQrLinkOpener(): Pair<(QrLink, Boolean) -> Unit, @Composable () -> Unit> {
    val context = androidx.compose.ui.platform.LocalContext.current
    var showing by remember { mutableStateOf<QrLink?>(null) }
    val open: (QrLink, Boolean) -> Unit = { link, preferQrOnTv ->
        if ((preferQrOnTv && isTvLike(context)) || !openLink(context, link.url)) showing = link
    }
    val dialog: @Composable () -> Unit = { showing?.let { SupportQrDialog(it, onDismiss = { showing = null }) } }
    return open to dialog
}

fun supportQrLink(c: Context) = QrLink(
    SupportLink.whatsapp(c), "Suporte no WhatsApp",
    "Aponte a câmera do celular para o código e fale com o suporte do Hugo Player", WhatsappGreen,
)

/** Clique em "Suporte": no celular abre o WhatsApp direto; na TV (ou se nada abrir) mostra o QR. */
@Composable
fun rememberSupportWhatsapp(): Pair<() -> Unit, @Composable () -> Unit> {
    val context = androidx.compose.ui.platform.LocalContext.current
    val (open, dialog) = rememberQrLinkOpener()
    return { open(supportQrLink(context), true) } to dialog
}

fun qrBitmap(text: String, size: Int = 600): ImageBitmap? = runCatching {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size,
        mapOf(EncodeHintType.MARGIN to 1, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M))
    val px = IntArray(m.width * m.height) { i -> if (m[i % m.width, i / m.width]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    Bitmap.createBitmap(px, m.width, m.height, Bitmap.Config.ARGB_8888).asImageBitmap()
}.getOrNull()

@Composable
fun SupportQrDialog(link: QrLink, onDismiss: () -> Unit) {
    val url = link.url
    val context = androidx.compose.ui.platform.LocalContext.current
    val qr = remember(url) { qrBitmap(url) }
    val close = remember { FocusRequester() }
    var note by remember { mutableStateOf<String?>(null) }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.widthIn(max = 420.dp).background(HugoColors.Panel, RoundedCornerShape(18.dp)).padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(link.title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(link.hint,
                color = HugoColors.TextMuted, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(14.dp))
            Box(Modifier.size(260.dp).background(Color.White, RoundedCornerShape(14.dp)).padding(10.dp), contentAlignment = Alignment.Center) {
                if (qr != null) Image(qr, "QR code: ${link.title}", Modifier.fillMaxSize(), filterQuality = FilterQuality.None)
                else Text("QR indisponível", color = Color.Black)
            }
            Spacer(Modifier.height(12.dp))
            Text(SupportLink.display(url), color = link.color, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            note?.let { Text(it, color = Color(0xFFFBBF24), fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp)) }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                DialogButton("Abrir aqui", Modifier.weight(1f), link.color) {
                    if (openLink(context, url)) onDismiss() else note = "Este aparelho não abre links. Use o QR no celular."
                }
                DialogButton("Fechar", Modifier.weight(1f).focusRequester(close), HugoColors.Accent, onDismiss)
            }
        }
    }
    LaunchedEffect(Unit) { delay(50); runCatching { close.requestFocus() } }
}

@Composable
private fun DialogButton(label: String, modifier: Modifier, focusColor: Color, onClick: () -> Unit) {
    FocusSurface(
        onClick = onClick,
        modifier = modifier.height(46.dp),
        color = HugoColors.CardAlt,
        focusedColor = focusColor,
        focusBorderWidth = 3.dp,
        focusScale = 1.03f,
        shape = RoundedCornerShape(10.dp),
    ) { _ -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) } }
}
