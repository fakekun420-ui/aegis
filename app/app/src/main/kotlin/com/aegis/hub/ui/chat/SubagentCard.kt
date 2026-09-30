package com.aegis.hub.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.aegis.hub.ui.MarkdownText
import com.aegis.hub.ui.markdownBlockCount

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
/**
 * Cuantos BLOQUES de markdown se ven contraidos. Antes eran 6 LINEAS sobre un `Text` que
 * no entendia markdown, asi que los `**`, las cabeceras y las listas se veian tal cual y
 * un informe corto con titulos ocupaba mas lineas de las que decias.
 *
 * Son bloques porque el corte tiene que caer en una frontera de bloque: partir una lista a
 * la mitad deja un elemento sin su punto. Y expandido ya no hay limite: antes eran 200
 * lineas, un numero inventado que ademas obligaba a Compose a medir un texto enorme en
 * cada layout. Sin limite, lo unico que acota es el propio scroll del chat.
 */
private const val BLOQUES_COLAPSADOS = 6

@Composable
fun SubagentCard(
    state: ToolState,
    modifier: Modifier = Modifier
) {
    // SIN clave en el remember, a proposito. La clave natural
    // (`state.subagentSessionId`) es una propiedad que LLEGA TARDE: mientras el
    // subagente trabaja no hay `metadata`, asi que vale null, y cuando el subagente
    // termina aparece el sessionID. `remember(clave)` reinicia el estado cuando la
    // clave cambia, o sea justo en el instante en que el usuario ya tiene el informe
    // desplegado y el subagente acaba: se le cierra solo en las manos. Sin clave, el
    // estado vive mientras viva la tarjeta, que es lo unico razonable.
    var expanded by remember { mutableStateOf(false) }

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

    // Con el parser que pinta, no con la longitud de la cadena. Se recuerda porque el
    // recomponer no debe reparsear un informe entero en cada fotograma.
    val nBloques = remember(cleanBody) { markdownBlockCount(cleanBody) }

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
                // SIN scroll propio. Antes el texto expandido iba con
                // `verticalScroll` + `heightIn(max = 520.dp)`, o sea un panel de 520 dp
                // con scroll PROPIIO dentro de un chat que ya es un scroll vertical.
                // Al desplegar, arrastrar sobre la tarjeta movia el panel y no el chat:
                // el chat parecia clavado, que es justo lo que se reportaba como "el
                // despliegue no funciona". Peor: el texto que sobrepasaba esos 520 dp
                // se quedaba OCULTO, sin ninguna pista de que hubiera mas debajo.
                // Ahora el limite son BLOQUES y el texto expandido fluye con el chat,
                // que es el unico scroll que el usuario ya sabe usar.
                MarkdownText(
                    text = cleanBody,
                    maxBlocks = if (expanded) Int.MAX_VALUE else BLOQUES_COLAPSADOS,
                    // Con MarkdownText, no con Text plano: un informe de subagente viene
                    // con markdown y antes se veian los `**`, las `#` y los `-`crudos. Los
                    // colores los fija el propio renderer (MEDIDO: los 7 tipos de bloque
                    // llevan color propio de paleta oscura), asi que dentro de esta tarjeta
                    // oscura se leen bien sin forzar nada.
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { expanded = !expanded }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // El enlace solo si queda algo por ver, o si ya esta desplegado (para
                    // poder volver a plegar). Antes habia DOS superficies pulsables
                    // —la caja del texto y esta fila— y el texto de esta fila caia
                    // dentro de la otra: un area de pulsacion ambigua.
                    //
                    // "Queda algo" lo dice el MISMO parser que pinta, no la longitud de la
                    // cadena ni un onTextLayout: un informe con un solo bloque pero largo
                    // antes ensenaba "ver todo" y no habia nada mas que ver.
                    val cuerpoCortado = nBloques > BLOQUES_COLAPSADOS
                    if (expanded || cuerpoCortado) {
                        Text(
                            text = if (expanded) "ver menos" else "ver todo",
                            color = Color(0xFF58A6FF),
                            fontSize = 10.5.sp,
                            modifier = Modifier.clickable { expanded = !expanded }
                        )
                    }
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
