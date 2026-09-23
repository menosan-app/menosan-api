package app.menosan.photo

import app.menosan.common.ApiException
import app.menosan.common.ErrorCode
import app.menosan.plugins.principal
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.cio.CIOMultipartDataBase
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.request.ApplicationRequest
import io.ktor.server.request.contentLength
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.post
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.InternalAPI
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.io.readByteArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Largest accepted image: 2 MB (§8.2). The app compresses to ≤ 1280 px, JPEG q≈80, well below this. */
const val MAX_IMAGE_BYTES = 2 * 1024 * 1024

/** Largest accepted request body: the image plus room for multipart boundaries, headers, and small fields. */
private const val MAX_BODY_BYTES = MAX_IMAGE_BYTES + 64 * 1024
private const val IMAGE_FIELD = "image"
private const val JPEG_MIME = "image/jpeg"

/** `POST /v1/photo-analysis`: multipart field `image` (JPEG, ≤ 2 MB) → `{suggestion, warning}`. Nothing is stored. */
fun Route.photoRoutes(analyzer: PhotoAnalyzer) {
    post("/photo-analysis") {
        val userId = call.principal.userId
        val image = call.receiveImage()
        val suggestion = analyzer.analyze(userId, image, JPEG_MIME)
        call.respond(PhotoAnalysisResponse(suggestion, PHOTO_WARNING))
    }
}

/**
 * Reads the `image` part into memory, never to disk (SFR8.5). The raw body is read with a hard cap first,
 * so an oversized upload (with or without Content-Length) is a clean 413 and memory stays bounded.
 * The multipart is then parsed from memory in a child scope, so parser errors are a 400, not a 500.
 * (`receiveMultipart()` runs its parser in the call's own scope, where a failure becomes a 500, and its
 * `formFieldLimit` also caps file parts. Hence Ktor's own parser, used directly: see docs/DECISIONS.md.)
 * Other parts are skipped. Error messages never echo request content.
 */
@OptIn(InternalAPI::class)
private suspend fun RoutingCall.receiveImage(): ByteArray {
    if (!request.isMultipartFormData()) {
        throw ApiException(
            ErrorCode.VALIDATION_FAILED,
            "Send the photo as multipart/form-data.",
            imageField(),
            status = HttpStatusCode.UnsupportedMediaType,
        )
    }
    request.contentLength()?.let { if (it > MAX_BODY_BYTES) throw imageTooLarge() }
    val body = receiveChannel().readRemaining(MAX_BODY_BYTES + 1L).readByteArray()
    if (body.size > MAX_BODY_BYTES) throw imageTooLarge()
    val contentTypeHeader = request.headers[HttpHeaders.ContentType].orEmpty()

    val image = try {
        coroutineScope {
            val multipart = CIOMultipartDataBase(
                coroutineContext, ByteReadChannel(body), contentTypeHeader, body.size.toLong(), MAX_BODY_BYTES.toLong(),
            )
            var found: ByteArray? = null
            multipart.forEachPart { part ->
                try {
                    if (found == null && part is PartData.FileItem && part.name == IMAGE_FIELD) {
                        found = part.provider().readRemaining().readByteArray()
                    }
                } finally {
                    part.dispose()
                }
            }
            found
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw ApiException(ErrorCode.VALIDATION_FAILED, "The photo upload is malformed. Please try again.", imageField())
    } ?: throw ApiException(ErrorCode.VALIDATION_FAILED, "Attach a photo in the \"image\" field.", imageField())

    if (image.size > MAX_IMAGE_BYTES) throw imageTooLarge()
    if (image.isEmpty()) throw ApiException(ErrorCode.VALIDATION_FAILED, "The photo is empty.", imageField())
    if (!image.isJpeg()) throw ApiException(ErrorCode.VALIDATION_FAILED, "The photo must be a JPEG image.", imageField())
    return image
}

private fun ApplicationRequest.isMultipartFormData(): Boolean =
    runCatching { contentType().match(ContentType.MultiPart.FormData) }.getOrDefault(false)

/** JPEG files start with the SOI marker FF D8 FF, whatever the client says the part's type is. */
private fun ByteArray.isJpeg(): Boolean =
    size >= 3 && this[0] == 0xFF.toByte() && this[1] == 0xD8.toByte() && this[2] == 0xFF.toByte()

private fun imageTooLarge() = ApiException(
    ErrorCode.IMAGE_TOO_LARGE,
    "The photo is too large. The limit is 2 MB.",
    buildJsonObject { put("maxBytes", MAX_IMAGE_BYTES) },
)

private fun imageField() = buildJsonObject { put("field", IMAGE_FIELD) }
