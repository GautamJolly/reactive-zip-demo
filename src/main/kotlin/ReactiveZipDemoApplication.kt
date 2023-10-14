package demo.reactivezip

import com.fasterxml.jackson.databind.ObjectMapper
import org.reactivestreams.Publisher
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DataBufferFactory
import org.springframework.core.io.buffer.DefaultDataBufferFactory
import org.springframework.http.ContentDisposition
import org.springframework.http.MediaType.APPLICATION_OCTET_STREAM
import org.springframework.stereotype.Component
import org.springframework.util.unit.DataSize.ofKilobytes
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.cast
import reactor.kotlin.core.publisher.toFlux
import java.lang.System.lineSeparator

@SpringBootApplication
class ReactiveZipDemoApplication

fun main(args: Array<String>) {
    runApplication<ReactiveZipDemoApplication>(*args)
}

/** Demo for the reactive [zip] function. */
@RestController
class ReactiveZipController(
    private val dataProvider: DataProvider,
    private val dataBufferFactory: DataBufferFactory = DefaultDataBufferFactory(),
    private val bufferSize: Int = ofKilobytes(128).toBytes().toInt()
) {
    /**
     * Combines reactive streams from a mock [DataProvider] into a `ZipOutputStream`, bytes from which are then
     * included in the response as an attachment.
     */
    @GetMapping("/")
    fun downloadZipFile(exchange: ServerWebExchange): Flux<DataBuffer> {
        // set headers
        exchange.response.headers.apply {
            contentType = APPLICATION_OCTET_STREAM
            contentDisposition = ContentDisposition.attachment().filename("test.zip").build()
        }

        // return zipped data
        return zip(dataProvider.get(), dataBufferFactory, bufferSize)
    }
}

interface DataProvider {
    fun get(): Map<String, () -> Publisher<ByteArray>>
}

/** Provides mock data for the demo. */
@Component
class MockDataProvider(private val objectMapper: ObjectMapper) : DataProvider {
    override fun get(): Map<String, () -> Publisher<ByteArray>> =
        mapOf(
            "type1.json" to { generateMockData<Type1>(1_000).toJsonBytes() },
            "type2.ndjson" to { generateMockData<Type2>(2_000_000).toNdjsonBytes() },
            "type3.ndjson" to { generateMockData<Type3>(3_000_000).toNdjsonBytes() }
        )

    private data class Type1(val f1: String)
    private data class Type2(val f1: String, val f2: Int)
    private data class Type3(val f1: String, val f2: Int, val f3: Int)

    private inline fun <reified T : Any> generateMockData(objectCount: Int): Flux<T> =
        (1..objectCount).asSequence()
            .map { objectNo ->
                when (T::class) {
                    Type1::class -> Type1("v:$objectNo")
                    Type2::class -> Type2("v:$objectNo", objectNo)
                    Type3::class -> Type3("v:$objectNo", objectNo, objectNo % 3)
                    else -> throw IllegalArgumentException("Unsupported type ${T::class}")
                }
            }
            .toFlux()
            .cast()

    private fun Flux<*>.toJsonBytes(): Mono<ByteArray> =
        collectList().map { objectMapper.writeValueAsBytes(it) }

    private fun Flux<*>.toNdjsonBytes(): Flux<ByteArray> =
        map { "${objectMapper.writeValueAsString(it)}${lineSeparator()}".toByteArray() }
}