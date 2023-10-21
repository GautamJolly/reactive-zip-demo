# reactive-zip-demo
This WebFlux-based Spring Boot application demonstrates the building of a downloadable zip-file from multiple reactive streams.

## Prerequisites
1. Medium to advanced knowledge of Project Reactor and Spring Boot is required to understand the implementation.
2. The sample code is in Kotlin, but an experienced Java developer should also be able to follow the code.
3. Execution of the demo requires Java 17 or higher, and Maven.

## Use case
Given one or more reactive streams, a downloadable zip-file must be created on the fly, with data from those streams. For example, given `Flux<Account>` and `Flux<Transaction>`, the streams must be combined into a zip-file, with entries `account.ndjson` and `transaction.ndjson`.

Data could be serialized to ND-JSON, JSON, XML, or really anything. That aspect is not the focus of this demo, but rather the logic for combining streams of raw bytes into a zip-file without ever leaving the reactive pipeline.

## Implementation
Here is the main logic for zipping up reactive streams (see [ReactiveZipUtil](src/main/kotlin/ReactiveZipUtil.kt)):

```kotlin
Flux.using(
    { Pipe.open() }, // <1>
    { pipe ->
        val zipStream = ZipOutputStream(Channels.newOutputStream(pipe.sink()))
        val f1 = sources.entries // <2>
            .toFlux()
            .concatMap { (zipEntryName, source) ->
                source()
                    .toFlux()
                    .doOnSubscribe { zipStream.putNextEntry(ZipEntry(zipEntryName)) }
                    .doOnNext { zipStream.write(it) }
                    .thenMany(Flux.empty<DataBuffer>())
                    .subscribeOn(Schedulers.boundedElastic())
            }
            .doFinally { pipe.sink().close() }
            .doFinally { zipStream.close() }
            .subscribeOn(Schedulers.boundedElastic())

        val f2 = Flux.defer { // <3>
            DataBufferUtils.readByteChannel({ pipe.source() }, dataBufferFactory, bufferSize)
                .doOnNext(DataBufferUtils.releaseConsumer())
        }

        Flux.merge(f1, f2) // <4>
    },
    { pipe -> pipe.source().close() }
)
```

1. Data from the reactive streams will be routed through a `Pipe`.  
2. `f1` is a publisher that writes content from each reactive stream sequentially into the pipe's sink-channel via a `ZipOutputStream`.
3. `f2` is a publisher based on pipe's source-channel, which is expected to receive zipped content from the first publisher.
4. Merge the two publishers to generate a flux of zipped bytes.

## Running the demo
1. Download the code to any directory, say `APP_DIR`.
2. To start the Spring Boot application, execute the following command in `APP_DIR`: `mvn spring-boot:run`.
3. Navigate to http://localhost:8080/ in your favorite browser, which will exercise the [ReactiveZipController](src/main/kotlin/ReactiveZipDemoApplication.kt) for generating a downloadable `test.zip` file.
4. Inspect the downloaded zip-file. It should contain 3 entries, all with mock data.
5. To run the automated test, execute the following command in `APP_DIR`: `mvn clean verify`. 