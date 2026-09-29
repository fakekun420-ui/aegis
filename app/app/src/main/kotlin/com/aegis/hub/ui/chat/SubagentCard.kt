package com.aegis.hub.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aegis.hub.data.ToolState

/**
 * Tarjeta de una invocacion de subagente.
 *
 * Existe porque el renderer de herramientas solo sabia pintar `bash`: una delegacion
 * a un subagente salia como una tarjeta "bash" con el comando vacio y sin resultado.
 * MEDIDO 2026-09-29 sobre una sesion real: las tool parts no traen nombre de
 * herramienta (`tool` y `callID` llegan a null en las 69 de la sesion), asi que la
 * tarjeta se construye con [ToolState.toolName] y [ToolState.isSubagent], que leen
 * la FORMA del `input` y no un catalogo.
 *
 * Deliberadamente no menciona ningun cargo, proyecto ni empresa con nombre propio:
 * si manana hay un subagente que no es de hoy, esta tarjeta lo enseña igual. Es la
 * misma leccion del guard de pantalla: nombra la accion (`task`), no la marca.
 *
 * Paleta y formas heredadas de [com.aegis.hub.ui.ToolExecutionCard] para que las dos
 * tarjetas se lean como parte de la misma familia.
 */
@Composable
fun SubagentCard(
    state: ToolState,
    modifier: Modifier = Modifier
) {
    var expanded by remember(state.subagentSessionId) { mutableStateOf(false) }

    val status = state.status ?: "running"
    val isRunning = status == "running" || status == "pending"
    val isError = status == "error"
    val agent = state.agentName ?: "subagente"
    val body = state.outputText
    // El contenido nativo envuelve la respuesta en <subagent ...>. Se quita la
    // etiqueta para no ensuciar la lectura, pero SOLO si queda texto detras: si el
    // envoltorio fuera todo el contenido, se enseña tal cual antes que inventar.
    val cleanBody = body
        ?.replace(Regex("^\\s*<subagent[^>]*>"), "")
        ?.replace(Regex("</subagent>\\s*$"), "")
        ?.trim()
        ?.takeIf { it.isNotBlank() } ?: body

    val accent = when {
        isRunning -> Color(0xFFD29922)
        isError -> Color(0xFFF85149)
        else -> Color(0xFF3FB950)
    }
    val statusText = when {
        isRunning -> "trabajando"
        isError -> "fallo"
        else -> "terminado"
    }

    Surface(
        color = Color(0xFF0D1117),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, Color(0xFF30363D)),
        modifier = modifier.fillMaxWidth()
    ) {
        Column {
            // Cabecera: nombre del subagente, etiqueta del encargo y estado.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF161B22))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "⇢",
                    color = Color(0xFFA371F7),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Spacer(Modifier.width(6.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = agent,
                        color = Color(0xFFD2A8FF),
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.5.sp,
                        maxLines = 1
                    )
                    val desc = state.agentDescription
                    if (!desc.isNullOrBlank()) {
                        Text(
                            text = desc,
                            color = Color(0xFF8B949E),
                            fontSize = 11.sp,
                            maxLines = 1
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Surface(
                    color = accent.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = statusText,
                        color = accent,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            // Cuerpo: el resultado del subagente. Colapsado por defecto porque un
            // informe de subagente son miles de caracteres y el chat es un scroll.
            if (!cleanBody.isNullOrBlank()) {
                // El estado de scroll se crea SIEMPRE, no solo cuando esta expandido:
                // llamar a un @Composable de forma condicional dentro de Modifier.then
                // es legal pero fragil. Aqui se hoistea y se decide que hacer con el.
                val bodyScroll = rememberScrollState()
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { expanded = !expanded }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = cleanBody,
                        color = Color(0xFFC9D1D9),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        // Acotado a 2000 lineas a proposito. Un subagente devuelve
                        // informes largos, y maxLines=Int.MAX_VALUE obliga a Compose
                        // a medir el texto ENTERO en cada layout: en un chat con varias
                        // delegaciones eso es jank en el scroll del LazyColumn. El
                        // texto completo esta en la sesion del subagente, enlazada
                        // abajo; aqui se enseña lo util.
                        maxLines = if (expanded) 2000 else 6,
                        modifier = Modifier
                            .heightIn(max = if (expanded) 520.dp else 200.dp)
                            .then(if (expanded) Modifier.verticalScroll(bodyScroll) else Modifier)
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { expanded = !expanded }
                        .padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = if (expanded) "ver menos" else "ver todo",
                        color = Color(0xFF58A6FF),
                        fontSize = 10.5.sp
                    )
                    val meta = buildString {
                        if (state.isTruncated) append("recortado por el Hub")
                        state.subagentSessionId?.let { append(" ${it.takeLast(6)}") }
                    }
                    if (meta.isNotBlank()) {
                        Text(
                            text = meta,
                            color = Color(0xFF6E7681),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp
                        )
                    }
                }
            } else if (isRunning) {
                Text(
                    text = "trabajando…",
                    color = Color(0xFF6E7681),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            } else {
                // Sin cuerpo: se DICE. Un hueco en blanco aqui se lee como "el cargo
                // no dijo nada", que es una conclusion distinta de "no hemos recibido
                // el resultado".
                Text(
                    text = "sin resultado en la respuesta",
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }
    }
}
