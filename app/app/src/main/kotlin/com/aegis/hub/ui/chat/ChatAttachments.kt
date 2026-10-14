package com.aegis.hub.ui

import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.aegis.hub.data.AttachedFile
import com.aegis.hub.data.MessagePart
import com.aegis.hub.data.PartFull

/**
 * F3-P7.1 (2026-10-14): bloque de adjuntos extraido VERBATIM de ChatScreen para
 * adelgazar el god-object. Misma package (`com.aegis.hub.ui`) para no tocar ni
 * firmas ni tests (AdjuntosLimiteTest usa estos simbolos sin import). Lo unico
 * que cambio respecto al original es `private` -> `internal` en los simbolos
 * que ChatScreen sigue usando (PartRow, humanSize, uriToAttachedFile);
 * NI UNA linea de logica.
 */

/**
 * F3-P7.1: contenido de la hoja "Agregar al chat", verbatim desde ChatScreen
 * (solo los `onClick` salieron como parametros; la logica de cada boton esta
 * intacta en el llamador). Vive aqui para adelgazar el god-object.
 */
@Composable
internal fun AttachSheetContent(
    onCamera: () -> Unit,
    onPhotos: () -> Unit,
    onFiles: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Agregar al chat", style = MaterialTheme.typography.titleMedium)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FilledTonalIconButton(
                    onClick = onCamera,
                    modifier = Modifier.size(56.dp).semantics { contentDescription = "Cámara" }
                ) { Icon(Icons.Filled.CameraAlt, contentDescription = "Cámara", modifier = Modifier.size(28.dp)) }
                Text("Cámara", style = MaterialTheme.typography.labelSmall)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FilledTonalIconButton(
                    onClick = onPhotos,
                    modifier = Modifier.size(56.dp).semantics { contentDescription = "Fotos" }
                ) { Icon(Icons.Filled.Photo, contentDescription = "Fotos", modifier = Modifier.size(28.dp)) }
                Text("Fotos", style = MaterialTheme.typography.labelSmall)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FilledTonalIconButton(
                    onClick = onFiles,
                    modifier = Modifier.size(56.dp).semantics { contentDescription = "Archivos" }
                ) { Icon(Icons.Filled.FolderOpen, contentDescription = "Archivos", modifier = Modifier.size(28.dp)) }
                Text("Archivos", style = MaterialTheme.typography.labelSmall)
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

// F3-P4 (2026-10-14): downsample con tope de lado mayor 2048. Una captura
// 1080x2400x4B ~= 10 MB a resolucion completa; con inSampleSize el bitmap en
// RAM baja ~4x y la vista lo muestra a ancho de pantalla de todos modos.
private const val MAX_BITMAP_SIDE = 2048
private fun decodeBase64Bitmap(b64: String): android.graphics.Bitmap? {
    return try {
        val clean = b64.substringAfter(",", b64)
        val bytes = android.util.Base64.decode(clean, android.util.Base64.DEFAULT)
        val bounds = android.graphics.BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        val mayor = maxOf(bounds.outWidth, bounds.outHeight)
        while (mayor / (sample * 2) >= MAX_BITMAP_SIDE) sample *= 2
        val opts = android.graphics.BitmapFactory.Options().apply {
            inSampleSize = sample
        }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    } catch (_: Exception) { null }
}

@Composable
private fun FileRow(
    name: String,
    mime: String,
    tint: androidx.compose.ui.graphics.Color,
    onClick: (() -> Unit)? = null,
    cargando: Boolean = false
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = if (onClick != null) {
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable(enabled = !cargando) { onClick?.invoke() }
        } else Modifier
    ) {
        if (cargando) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = tint
            )
        } else {
            Icon(
                if (mime.startsWith("image/")) Icons.Filled.Photo else Icons.Filled.FolderOpen,
                contentDescription = null, tint = tint, modifier = Modifier.size(20.dp)
            )
        }
        Column {
            Text(name, style = MaterialTheme.typography.bodySmall, color = tint, maxLines = 1)
            if (mime.isNotBlank()) Text(mime, style = MaterialTheme.typography.labelSmall, color = tint.copy(alpha = 0.7f), maxLines = 1)
        }
    }
}


/**
 * Una fila de parte adjunta, con el toque que recupera el binario cuando la venia
 * recortada.
 *
 * Esta en un solo lugar a proposito. La version anterior tenia la logica metida dentro
 * del bucle de `images`, y MEDIDO sobre una sesion real resulto ser codigo muerto: las
 * partes recortadas son type="file" sin `url` (el recorte se lo borra), asi que caen en
 * `nonImageFiles` y no en `images`. Con el toque en la rama, no lo tocaba nadie.
 * Ahora las tres ramas pasan por aqui, asi que el toque va con la parte y no con la
 * rama: si el reparto cambia manana, sigue funcionando.
 *
 * @param alto 0 = sin limite de alto; >0 = tope en dp, como necesitan los adjuntos
 *   pequenos. Las imagenes grandes se muestran a ancho completo sin tope.
 */
@Composable
internal fun PartRow(
    part: MessagePart,
    nombre: String,
    mime: String,
    tint: androidx.compose.ui.graphics.Color,
    onLoadPart: ((partId: String?, done: (PartFull?) -> Unit) -> Unit)?,
    alto: Int = 0
) {
    // La clave del remember es el id de la PARTE y no el del mensaje: con la del mensaje
    // dos imagenes del mismo turno comparten estado y pulsar una moveria la otra.
    var descargada by remember(part.id) { mutableStateOf<String?>(null) }
    var pidiendo by remember(part.id) { mutableStateOf(false) }

    val contenido = descargada ?: part.image ?: part.data ?: part.url
    val bitmap = remember(contenido) { contenido?.let { decodeBase64Bitmap(it) } }

    if (bitmap != null) {
        val forma = if (alto > 0) {
            Modifier.fillMaxWidth(0.7f).heightIn(max = alto.dp).clip(RoundedCornerShape(8.dp))
        } else {
            Modifier.fillMaxWidth(0.7f).clip(RoundedCornerShape(8.dp))
        }
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = nombre,
            modifier = forma,
            contentScale = ContentScale.FillWidth
        )
        return
    }

    // El toque solo si la parte venia recortada Y el binario es una imagen. En
    // nonImageFiles tambien caen adjuntos que no lo son, y bajarse 5 MB de un PDF para
    // no poder pintar nada es peor que no tener toque: se descarga solo lo que se ve.
    val esImagen = mime.isBlank() || mime.startsWith("image/")
    val pulsable = part.hasBinary == true && esImagen && !pidiendo && onLoadPart != null
    FileRow(
        name = nombre,
        mime = mime,
        tint = tint,
        cargando = pidiendo,
        onClick = if (pulsable) {
            {
                pidiendo = true
                onLoadPart?.invoke(part.id) { parte ->
                    // Siempre se invoca, tambien en error (lo garantiza loadPartFull): si
                    // no, un fallo deja el spinner girando para siempre. Por eso el false
                    // va ANTES de asignar el resultado.
                    pidiendo = false
                    descargada = parte?.base64()
                }
            }
        } else null
    )
}

/**
 * F3-P8.3: politica de adjuntos en funciones puras (sin Context) para fijarla con
 * tests JVM. `uriToAttachedFile` conserva la parte Android (ContentResolver) y delega aqui.
 */
internal const val LIMITE_ADJUNTO_BYTES = 5 * 1024 * 1024
internal const val MAX_TEXTO_ADJUNTO_CHARS = 60_000
internal fun esTextoAdjunto(mime: String, nombre: String): Boolean =
    mime.startsWith("text/") || nombre.endsWith(".txt") || nombre.endsWith(".md") || nombre.endsWith(".json")
internal fun recortarTextoAdjunto(bytes: ByteArray): String =
    String(bytes, Charsets.UTF_8).take(MAX_TEXTO_ADJUNTO_CHARS)
internal fun adjuntoExcedeLimite(bytes: ByteArray): Boolean = bytes.size > LIMITE_ADJUNTO_BYTES
internal fun describirAdjuntoGrande(sizeBytes: Long): String =
    "[archivo demasiado grande, ${humanSize(sizeBytes)}]"

internal fun humanSize(b: Long): String = when {
    b < 1024 -> "$b B"
    b < 1024 * 1024 -> "${(b / 1024.0).let { String.format("%.1f", it) }} KB"
    else -> "${(b / 1024.0 / 1024.0).let { String.format("%.1f", it) }} MB"
}

internal fun uriToAttachedFile(context: android.content.Context, uri: Uri): AttachedFile? {
    return try {
        val cr = context.contentResolver
        val mime = cr.getType(uri) ?: "application/octet-stream"
        var name = "archivo"
        var size = 0L
        cr.query(uri, null, null, null, null)?.use { c ->
            val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
            if (c.moveToFirst()) {
                if (nameIdx >= 0) name = c.getString(nameIdx) ?: name
                if (sizeIdx >= 0) size = c.getLong(sizeIdx)
            }
        }
        // Base64 for images/files under ~5MB, else just metadata
        val isText = esTextoAdjunto(mime, name)
        cr.openInputStream(uri)?.use { input ->
            val bytes = input.readBytes()
            if (adjuntoExcedeLimite(bytes)) {
                AttachedFile(name = name, mime = mime, size = bytes.size.toLong(), text = describirAdjuntoGrande(bytes.size.toLong()))
            } else if (isText) {
                val text = recortarTextoAdjunto(bytes)
                AttachedFile(name = name, mime = mime, size = bytes.size.toLong(), text = text)
            } else {
                val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                AttachedFile(name = name, mime = mime, size = bytes.size.toLong(), base64 = b64)
            }
        }
    } catch (_: Exception) { null }
}
