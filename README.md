# reactive-zip-demo
Spring Boot WebFlux application for building zip-file from multiple reactive streams for downloading

## Use case
Given one or more reactive streams, a downloadable zip-file must be created on the fly, with data from those streams. For example, given `Flux<Account>` and `Flux<Transaction>`, these streams will be combined into a zip-file with entries `account.ndjson` and `transaction.ndjson`.

## Implementation
Data could be serialized to ND-JSON, JSON, XML, or really anything. That aspect is not the focus of this demo, but rather the logic for combining streams of raw bytes into a zip-file wihtout ever leaving the reactive pipeline. That logic can be found in the `zip` function of `ReactiveZipUtil.kt`. 
