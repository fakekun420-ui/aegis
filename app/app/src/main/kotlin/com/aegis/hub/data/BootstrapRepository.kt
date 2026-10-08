package com.aegis.hub.data

import retrofit2.Response

// ==== F4 — puente inyectable de BootstrapViewModel contra el hub ====
//
// F1/F3 dejaron al ViewModel atado a ApiClient.service (singleton Retrofit), lo
// que hacía imposible testearlo en JVM sin red ni Android. Esta interfaz expone
// EXACTAMENTE las 7 llamadas que ya hacía el ViewModel, con las MISMAS firmas
// (suspend + retrofit2.Response<T>), de modo que:
//   - producción: BootstrapViewModel(repo = NativoBootstrapRepository) — igual
//     que antes (ApiClient con interceptor de token + parseo de errorBody);
//   - tests JVM/instrumentados: un fake que devuelve Response.success/error
//     construidos a mano (sin OkHttp ni servidor).
// La app se sigue creando con viewModel() sin factory: el parámetro del
// constructor tiene por defecto y Kotlin genera el constructor sin argumentos.
interface BootstrapRepository {
    suspend fun getBootstrapState(): Response<BootstrapResponse>
    suspend fun runBootstrap(body: BootstrapRunRequest): Response<BootstrapActionResponse>
    suspend fun retryBootstrapStep(id: String): Response<BootstrapActionResponse>
    suspend fun cancelBootstrap(): Response<BootstrapActionResponse>
    suspend fun getFinalCheck(): Response<FinalCheckResponse>
    suspend fun runSmokeTest(): Response<SmokeTestResponse>
    suspend fun runAuthGuide(): Response<AuthGuideResponse>
}

/**
 * Implementación REAL: delega una a una en [Conexion.api].
 *
 * MEDIDO 2026-10-03: esto era [ApiClient.service], el Retrofit del Hub en :8765, que ya no
 * escucha. Con el Hub muerto, el bootstrap desde la app fallaba siempre. [Conexion.api] es la
 * misma interfaz `ApiService` servida por `RutaNativa` (OpenCode directo), asi que el cambio
 * es de una linea y los tests con fakes no se tocan.
 */
object NativoBootstrapRepository : BootstrapRepository {

    private val service: ApiService get() = Conexion.api

    override suspend fun getBootstrapState(): Response<BootstrapResponse> =
        service.getBootstrapState()

    override suspend fun runBootstrap(body: BootstrapRunRequest): Response<BootstrapActionResponse> =
        service.runBootstrap(body)

    override suspend fun retryBootstrapStep(id: String): Response<BootstrapActionResponse> =
        service.retryBootstrapStep(id)

    override suspend fun cancelBootstrap(): Response<BootstrapActionResponse> =
        service.cancelBootstrap()

    override suspend fun getFinalCheck(): Response<FinalCheckResponse> =
        service.getFinalCheck()

    override suspend fun runSmokeTest(): Response<SmokeTestResponse> =
        service.runSmokeTest()

    override suspend fun runAuthGuide(): Response<AuthGuideResponse> =
        service.runAuthGuide()
}
