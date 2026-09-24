package com.aegis.hub.data

import retrofit2.Response

// ==== F4 — puente inyectable de BootstrapViewModel contra el hub ====
//
// F1/F3 dejaron al ViewModel atado a ApiClient.service (singleton Retrofit), lo
// que hacía imposible testearlo en JVM sin red ni Android. Esta interfaz expone
// EXACTAMENTE las 7 llamadas que ya hacía el ViewModel, con las MISMAS firmas
// (suspend + retrofit2.Response<T>), de modo que:
//   - producción: BootstrapViewModel(repo = RetrofitBootstrapRepository) — igual
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
 * Implementación REAL (F1/F3): delega una a una en [ApiClient.service].
 * El acceso es un getter perezoso → la object ApiClient (y RootShell/Log de
 * Android) sólo se carga en el primer uso REAL, nunca al construir el
 * ViewModel en un test JVM.
 */
object RetrofitBootstrapRepository : BootstrapRepository {

    private val service: ApiService get() = ApiClient.service

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
