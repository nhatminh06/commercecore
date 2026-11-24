# M17: isolated gRPC network faults

## Scope and topology

The opt-in [NetworkFaultIntegrationTest](../../src/m17Test/java/com/commercecore/payment/NetworkFaultIntegrationTest.java) starts two disposable `postgres:16` databases, the real `PaymentProviderGrpcService` and `ProviderLedger` on a random test gRPC port, and a uniquely named Toxiproxy container. CommerceCore's real `GrpcPaymentProviderClient` connects to the proxy's loopback-mapped listener. The provider server uses its own database; CommerceCore uses a different database. Both Spring services run in the test JVM, while their traffic crosses a real TCP proxy. This avoids production Compose and Kafka. The proxy API and gRPC listener are Docker-published only on `127.0.0.1` with dynamic host ports. The provider endpoint is never an application public API.

```text
CommerceCore PaymentService -> GrpcPaymentProviderClient -> 127.0.0.1:<dynamic proxy port>
  -> Toxiproxy -> test gRPC server (real PaymentProviderGrpcService)
  -> ProviderLedger -> disposable provider PostgreSQL
CommerceCore PaymentService -> disposable CommerceCore PostgreSQL
```

The image is the official `ghcr.io/shopify/toxiproxy:2.9.0` (pulled explicitly for local qualification). The test uses `docker run --pull=never`, so it never silently downloads the image. It also uses the already present `postgres:16`. Hosted CI runs the regular `test` task and does not invoke `m17Test`; if `m17Test` is invoked without `LAB_NETWORK_TEST=1`, JUnit skips its six scenarios. Java 21, Docker Desktop/Engine, Python 3, and the repository's Gradle wrapper are required. The M16 recorder calls `psql` through a temporary wrapper into each disposable database container; the wrapper and JSON files are removed after use. The test does not use production credentials or volumes.

```sh
LAB_NETWORK_TEST=1 TESTCONTAINERS_RYUK_DISABLED=true TESTCONTAINERS_CHECKS_DISABLE=true \
JAVA_HOME=/path/to/jdk-21 ./gradlew m17Test --rerun-tasks

LAB_POSTGRES_TEST=1 PYTHONDONTWRITEBYTECODE=1 \
python3 -m unittest discover -s tools/consistency_lab -p 'test_*.py'
```

The test cleans up its unique proxy container and both PostgreSQL containers. If interrupted before cleanup, inspect `docker ps --format '{{.Names}}'` for `commercecore-m17-*` and remove **only the identified test container** with `docker rm -f <name>`. Do not remove shared containers or volumes.

## Scenario controller and observed local qualification

`@BeforeEach` calls Toxiproxy `/reset`, checks the proxy is enabled with no toxics, then performs a bounded read-only gRPC lookup through it. Each scenario checks its API configuration and logs configured and observed timestamps; `@AfterEach` resets and verifies cleanup. The API acknowledgment proves configuration, not the exact network byte affected. A missing outcome or missed injection fails with `UNVERIFIED` rather than producing a PASS. The test uses bounded polling of the provider ledger to observe a committed authorization before installing the in-flight downstream cut.

| Scenario | Fault and expected observation | Executed result (local, 2026-11-24) |
|---|---|---|
| Provider unavailable | Disable proxy before authorization; gRPC `UNAVAILABLE`; one durable local UNKNOWN payment, PENDING order, ACTIVE reservation, no matching provider row; restore and lookup. | PASS. Repeated initiation kept the payment ID. Restored lookup returned `NOT_FOUND`; local state remained UNKNOWN. No claim that the provider received the blocked request. |
| Downstream latency | 3000 ms latency versus 2500 ms client deadline; provider may still commit; restore and reconcile by lookup. | PASS. A direct client probe observed `DEADLINE_EXCEEDED`. The payment was locally UNKNOWN and provider AUTHORIZED, then locally AUTHORIZED after lookup. Repeat initiation did not increase provider row count. |
| In-flight downstream interruption | Provider test hook commits then waits 1000 ms; after the row is observed, install a downstream `timeout=0` toxic before response; restore and lookup. | PASS within observed scope. Provider row was observed before toxic configuration, local payment became UNKNOWN, and lookup recovered AUTHORIZED. The separate no-toxic delay control completed AUTHORIZED within the same 2500 ms deadline. Exact packet timing remains unobserved. |
| Ineffective injection | No toxic; run payment and label the attempted timeout result. | PASS negative test: normal AUTHORIZED response yields `UNVERIFIED` injection. |
| Assertion failure cleanup | Raise a deliberate assertion while a toxic exists, then reset. | PASS: toxic list empty afterward. Normal scenario cleanup also verified after every test. |

The Java test suite also exercises real proxy startup/readiness and a successful gRPC request through the proxy before each fault. Its separate Gradle `m17Test` source set keeps provider Flyway resources off the regular backend test classpath. The test uses the existing `PaymentService` and `PaymentReconciliationService`; reconciliation calls `LookupPayment` through `PaymentProvider.lookup`. The local payment assertion reads `PaymentRepository`, because M16's four-table bundle deliberately omits `payments`. The provider ledger lookup is an independent observation of provider truth, not proof of one physical RPC. During the latency case, both M16 read-only recorders ran: all three CommerceCore snapshot checks and `provider_payment_observation` passed, while `cross_store_payment_state` remained `INCONCLUSIVE`. The snapshots have separate transaction IDs and no global atomic boundary.

The logs distinguish **configured** toxic time from the later **observed** payment and provider states. A configured downstream toxic does not establish which HTTP/2 frame it affected. The provider commit is proven by its row read before toxic configuration; a lost response is inferred from local UNKNOWN plus the successful no-toxic control, not from packet tracing. Reconciliation safety when stock ownership is lost remains covered by existing payment tests, not this M17 network suite.

## Relationship to the existing failure lab and later work

`scripts/failure-lab/02-provider-boundary.sh`, `PaymentProviderGrpcIntegrationTest`, `RemotePaymentUnavailableIntegrationTest`, and `RemotePaymentReconciliationEndToEndTest` already cover fake-provider outcomes, real provider persistence, and remote gRPC handling. M17 adds an actual network proxy between the existing client and provider implementation. It does not replace those tests, reconstruct Kafka delivery, or claim linearizability. M18 should introduce bounded operation plans with stable invocation IDs, observed starts/results, and a recorded schedule; M19 can then qualify history properties only when coverage and observation boundaries justify them.
