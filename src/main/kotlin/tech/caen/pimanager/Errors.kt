package tech.caen.pimanager

import io.ktor.http.HttpStatusCode

/** Exception métier portant un code HTTP, mappée par StatusPages. */
class ApiException(val status: HttpStatusCode, override val message: String) : RuntimeException(message)

fun notFound(message: String): ApiException = ApiException(HttpStatusCode.NotFound, message)
fun badRequest(message: String): ApiException = ApiException(HttpStatusCode.BadRequest, message)
