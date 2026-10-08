package com.aegis.hub.data

import com.aegis.hub.RootShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Unico punto de `su` con permiso (F7).
 *
 * Cada `RootShell.exec` bifurca un proceso `su` (cientos de ms con Magisk lento):
 * sin freno, 10 llamadas a la vez son 10 procesos. `Semaphore(2)` acota el dano y
 * `withContext(IO)` garantiza que nunca corre en el hilo principal (ademas del
 * chequeo de F0 dentro de `RootShell.exec`, que queda como ultima red).
 *
 * Excepciones documentadas (verificdas una a una, todas fuera de main):
 * - `OpenCodeLauncher`: lambdas `shell` inyectables (sync, para sus tests).
 * - `SetupNative.shellExecutor`: inyectable, corre en `ioDispatcher`.
 * - `Credentials.fileReader`: `getPassword` es `suspend` en IO; el `readFile` del
 *   interceptor de OkHttp corre en hilos de OkHttp.
 */
object Raiz {
    private val semaforo = Semaphore(2)

    suspend fun ejecutar(cmd: String, timeoutMs: Long = 15000L): RootShell.Result =
        withContext(Dispatchers.IO) {
            semaforo.withPermit {
                RootShell.exec(cmd, timeoutMs)
            }
        }
}
