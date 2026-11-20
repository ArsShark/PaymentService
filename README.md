# Payment Service

Payment microservice of the course e-commerce application (Innowise Java Advanced). Part of a
system with the Authentication Service, the API Gateway, the User Service and the Order Service.

> Work in progress: the database schema (JACAG-21), the DAO layer (JACAG-22), the service layer
> (JACAG-23), the Kafka producer (JACAG-24, the consumer is in the Order Service) and the REST API
> with its security are done. The container setup and the CI pipeline follow.

## Stack

- Java 21, Spring Boot 4.1.1 (Spring MVC, Spring Data MongoDB)
- MongoDB 8 with Liquibase migrations (extension `liquibase-mongodb`)
- Apache Kafka 4 (KRaft, Spring for Apache Kafka): the event of every payment, JSON
- Bean Validation, MapStruct, `RestClient` for the call to the random number service
- Log4j2 (SLF4J API in the code)
- JUnit 5, Mockito, WireMock, Testcontainers

## Requirements

- JDK 21
- Docker (the tests, the local MongoDB and the local Kafka)
- Maven Wrapper is included in the project

## Configuration

The profile `local` is the default (`spring.profiles.default`). Environment variables override
every default.

| Variable | Default | Meaning |
|---|---|---|
| `MONGO_USERNAME` | none, required | MongoDB user |
| `MONGO_PASSWORD` | none, required | MongoDB password |
| `MONGO_HOST` | `localhost` | MongoDB host |
| `MONGO_PORT` | `27017` | MongoDB port |
| `MONGO_DB` | `payment_service_db` | database name |
| `MONGO_AUTH_DB` | `admin` | database in which the user was created |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | address of the Kafka broker (profile `local`) |
| `KAFKA_PORT` | `9092` | only for Docker Compose: the port that Kafka publishes on `127.0.0.1` |
| `RANDOM_API_URI` | `https://www.random.org` | address of the random number service |
| `AUTH_JWKS_URI` | `http://localhost:8081/.well-known/jwks.json` | key set of the Authentication Service (profile `local`); the internal address, never the API Gateway |
| `JWT_ISSUER` | `authentication-service` | the issuer that an access token must have; the same value as in the Authentication Service |
| `SERVER_PORT` | `8084` | HTTP port |
| `LOG_DIR` | `logs` | folder of the log files |

Credentials are never stored in the repository. The application is configured with host, port
and database, not with `spring.mongodb.uri`: the Liquibase migration reads the same settings.

There is no `INTERNAL_API_KEY`: the service has no internal endpoints and calls no internal
endpoint of another service. Fixed in `application.yml`: at most 100 items per page and
`server.forward-headers-strategy: framework`, so that the `Location` of a created payment follows
the address of the client behind the API Gateway.

## Run MongoDB and Kafka locally

```
copy .env.example .env      # then set real values in .env
docker compose up -d
docker compose ps           # the status of mongodb and kafka becomes "healthy"
```

The first start downloads the Kafka image (about 700 MB). Kafka has no volume: `docker compose
down` removes its topics, and the application declares them again when it starts. It is
published only on `127.0.0.1:9092`. If you change `KAFKA_PORT`, set `KAFKA_BOOTSTRAP_SERVERS`
to the same port.

The user is created on the FIRST start, when the volume is empty. Changing `MONGO_PASSWORD`
afterwards changes nothing in MongoDB; reset it with `docker compose down -v`, which deletes the
data. MongoDB is published only on `127.0.0.1`.

Run the application from the IDE or with `.\mvnw spring-boot:run` from the project folder: the
profile `local` also reads the `.env` file of the working directory (as Java properties, so no
quotes around a value), and the same values serve Docker Compose and the application. Real
environment variables win over `.env`. In the `docker` profile `.env` is not read.

## Database

The collection `payments` is created by Liquibase when the application starts
(`src/main/resources/db/changelog`). Spring Boot has no Liquibase support for MongoDB, so
`MongoMigration` runs the changelog by hand.

| Field | BSON type | Meaning |
|---|---|---|
| `_id` | ObjectId | id of the payment |
| `order_id` | long | id of the order in the Order Service |
| `user_id` | long | id of the payer in the User Service |
| `status` | string | `SUCCESS` or `FAILED` |
| `timestamp` | date | when the payment was made (UTC, milliseconds) |
| `payment_amount` | decimal (Decimal128) | the amount, greater than zero |

- A JSON Schema validator rejects a document of another shape (error code 121), also when it is
  written by hand. Insert by hand with `NumberLong(1)` and `Decimal128("24.00")`: the shell
  writes plain numbers as double.
- Indexes: `idx_payments_user_id_timestamp`, `idx_payments_order_id_timestamp`,
  `idx_payments_status_timestamp` and the partial unique index `uq_payments_order_id_success`:
  an order has at most one `SUCCESS` payment, any number of `FAILED` ones.
- Amounts are Decimal128, never strings or doubles: MongoDB adds them up exactly (a string is
  silently ignored by `$sum`).

## Data access

`PaymentRepository` is the only way to the collection. It extends the marker `Repository`, not
`MongoRepository`, and has no `save` and no `delete`: a payment is a record of what happened and
never changes, so nothing can replace or remove one. The queries that need `MongoTemplate` are
in `PaymentQueries` (implemented by `PaymentQueriesImpl`).

| Method | What it does |
|---|---|
| `insert(Payment)` | Saves a new payment and returns it with its id. Never replaces. |
| `findById(String)` | One payment by its id, or empty. |
| `findAll(PaymentFilter, Pageable)` | A page of payments; the filter has optional `userId`, `orderId`, `status`, combined with AND. |
| `sumOfUser(long, Instant, Instant)` | Total and count of the successful payments of one user in `[from, before)`. |
| `sumOfAll(Instant, Instant)` | The same for all users (for the administrator). |

- **One scale.** An amount has exactly two decimal places (`24` becomes `24.00`); an amount with
  more significant places is refused by the constructor of `Payment`, not rounded. So every sum
  has two decimal places too, and an empty period is `0.00`.
- **One successful payment per order.** The partial unique index
  `uq_payments_order_id_success` decides, also when two requests arrive together. The second
  `insert` throws `DuplicateKeyException`; `ViolatedIndex.nameOf(...)` returns the name of the
  index (MongoDB puts it only into the text of the error, and a test on a real MongoDB pins the
  format). Any other index name is a bug and must not be treated as "order already paid".
- **Totals** add up only `SUCCESS` payments, in the database (`$match` and one `$group`), and
  Decimal128 is added up exactly. The period is half-open: a payment exactly at `from` is in,
  exactly at `before` is out. MongoDB keeps milliseconds, so the bounds lose anything finer.
  The user of `sumOfUser` is a primitive `long`: a `null` meaning "everybody" would show a user
  the total of all users.
- **Stable pages.** The id is always the last sort key, so equal sort values cannot make pages
  overlap or skip a payment. Which properties a client may sort by is decided by the controller.

Known limits of the data access:

- A list without any filter reads the whole collection and sorts it in memory (no index starts
  with the time). Lists with a filter use the indexes: the test `QueryPlanIntegrationTest`
  reads the plan that the server recorded for the real command. The id as the last sort key
  means MongoDB also sorts the matching payments in memory; that is cheap for the payments of
  one user or one order.
- Writing to the database and then to Kafka are two steps; the insert is not undone if the
  event cannot be sent (see "Kafka" below).

## Service layer

`PaymentService` is a plain class without `@Transactional`: a payment is one document, which
MongoDB stores atomically, and nothing in the service needs a multi-document transaction.

**Paying an order** (`create`), in this order:

1. The order is checked: if it already has a `SUCCESS` payment the request is refused
   (`OrderAlreadyPaidException`, 409 in the API) and the random number service is not asked.
   Without this check a second payment that comes out `FAILED` would be saved, because the
   unique index covers only `SUCCESS`: the same request would sometimes be a 409 and sometimes a
   saved `FAILED` payment.
2. `RandomNumberClient` asks the random number service for one integer from 1 to 100. If the
   service does not connect or answer in time, nothing is saved (`RandomNumberTimeoutException`,
   504); if it fails in any other way or answers with something that is not one integer
   (`RandomNumberUnavailableException`, 502). There is no retry and no circuit breaker.
3. An even number is `SUCCESS`, an odd one `FAILED`. A declined payment is a normal result and
   is saved too.
4. The payment gets the time of the application clock, taken after the number arrived, and is
   inserted. The unique index has the last word when two requests for one order arrive together:
   the loser gets the same `OrderAlreadyPaidException`.
5. The event `CREATE_PAYMENT` with the outcome is sent to Kafka (see "Kafka" below), also for a
   `FAILED` payment. This step cannot undo the payment: a Kafka outage does not fail the
   request.

The payer is the profile id of the caller and never comes from the request body; the same goes
for the outcome and the time. The service does not check the order or the amount against the
Order Service: the Order Service does that when it receives the event.

| Method | What it does |
|---|---|
| `create(Caller, PaymentRequest)` | pays an order, see above |
| `getById(String, Caller)` | the payer or an admin; a payment of somebody else is the same `PaymentNotFoundException` as a missing one |
| `findAll(PaymentFilter, Pageable)` | every payment that matches the filter (for admins) |
| `findOwn(long, PaymentFilter, Pageable)` | the same, but the payer of the filter is replaced by the given user |
| `totalOfUser(long, Instant, Instant)` | the total of the successful payments of a user in `[from, before)` |
| `totalOfAll(Instant, Instant)` | the same for all users (for admins) |

- **DTOs and MapStruct.** `PaymentRequest` has only `orderId` and `amount` (at least 0.01, two
  decimal places at most, ten integer digits at most: the largest total of an order). `Payment`
  has no setters, so MapStruct builds it through its constructor; the payer, the outcome and the
  time are separate parameters of `PaymentMapper.toEntity`, so a request body cannot set them.
- **Totals.** The bounds are cut to milliseconds (all that MongoDB keeps) before they are
  compared, and the cut bounds are returned in the answer. Two bounds that differ only below a
  millisecond are an empty period (`InvalidPeriodException`, 400 in the API).
- **The random number service** is the legacy HTTP interface of random.org
  (`GET /integers/?num=1&min=1&max=100&col=1&base=10&format=plain&rnd=new`). Its address is
  `RANDOM_API_URI`; connect and read timeouts are 2 s and 5 s. It is a public service with a
  quota per address, so the tests never call it: they play it with WireMock. The answers of a
  failed call never contain its address or its body.
- **Constant error texts.** `PaymentNotFoundException` does not repeat the id from the URL.

Known limits of the service layer:

- **The order is not checked.** Payment does not ask the Order Service whether the order exists,
  belongs to the caller or costs that amount. Any user can create a `SUCCESS` payment for any
  order id. The Order Service leaves such an order unpaid (it compares the user and the amount
  of the event), but the unique index then refuses the real owner's payment of that order. The
  fix is a check of the order through an internal endpoint of the Order Service.
- A payment whose answer is lost and is sent again creates a second payment (or a 409 if the
  first one was a `SUCCESS`).
- The random number service is a public service with a quota: when it is down or refuses, every
  payment is a 502 or 504.

## REST API

Every call needs an access token of the Authentication Service: `Authorization: Bearer <token>`.
The payer of a payment is the profile in the token (`user_id`), never a field of a request.

| Call | Who | Parameters | Answer |
|---|---|---|---|
| `POST /api/payments` | `USER`, `ADMIN` with a profile | body `{"orderId": 5, "amount": 24.00}` | 201 with the payment and `Location: /api/payments/<id>`, also when the status is `FAILED` |
| `GET /api/payments/{id}` | the payer, `ADMIN` | | 200, or 404 |
| `GET /api/payments` | `ADMIN` | `userId`, `orderId`, `status`, `page`, `size`, `sort` | 200 with a page of the payments of everybody |
| `GET /api/payments/my` | `USER`, `ADMIN` with a profile | `orderId`, `status`, `page`, `size`, `sort` | 200 with a page of the own payments |
| `GET /api/payments/my/total` | `USER`, `ADMIN` with a profile | `from`, `before` (required) | 200 `{"total", "count", "from", "before"}` |
| `GET /api/payments/total` | `ADMIN` | `from`, `before` (required) | 200, the same shape, all payers |

A payment: `{"id", "orderId", "userId", "status": "SUCCESS" or "FAILED", "timestamp", "amount"}`.
The status comes from the random number service (even is `SUCCESS`), the time from the clock of
the service. A payment never changes: no call updates or deletes one.

- **Amount.** At least `0.01`, at most two decimal places and ten integer digits.
- **Lists.** 20 per page, at most 100, newest first. `sort` takes `timestamp`, `amount`,
  `orderId`, `userId`, `status` or `id` (`id` is always the last key, so pages never overlap);
  another name, also `paymentAmount`, is a 400 that does not repeat it. The page is
  `{"content", "page", "size", "totalElements", "totalPages"}`.
- **Totals.** Only `SUCCESS` payments, in the half-open period `[from, before)`. The bounds are
  ISO-8601 instants and are cut to milliseconds; an empty or reversed period is a 400. Write the
  offset as `Z`: a `+` in a query string is a space (use `%2B`). No payments is `0.00` and `0`.
- **Somebody else's payment.** It is answered exactly like a missing one (404 with the same
  text), and so is an id that is not an id at all.
- **An admin without a profile** (the first admin of the Authentication Service has none) can
  read everything, but cannot pay and has no own list or total: 403.

Errors are RFC 9457 `application/problem+json`. A response never contains a rejected value or an
internal message.

| Status | When |
|---|---|
| 400 | an invalid body (`errors`: a list of `{field, message}`), a parameter that cannot be read, a bad sort, a bad period |
| 401 | no token, or a token that is not valid (always the same `Authentication is required`) |
| 403 | the role or the profile does not allow it (`Access is denied`) |
| 404 | the payment does not exist, or belongs to somebody else |
| 409 | the order already has a successful payment |
| 502, 504 | the random number service gave no answer or none in time; nothing is saved, the client may try again |
| 500 | anything else, also every failure of the database (constant text; only classes and the name of an index are logged, because a MongoDB message holds the document) |

### Security

The service is a resource server. It verifies the RS256 signature of the token with the key set
of the Authentication Service (`AUTH_JWKS_URI`: cached for five minutes, timeouts of two seconds,
at most one new fetch per 30 seconds for an unknown key, nothing at startup) and checks the time
(no tolerance), the issuer and `token_type = access`. If the key set cannot be reached the answer
is a 401, never a 500. The claim `role` must be exactly `ADMIN` or `USER`; `user_id` is the
profile of the caller.

Two layers decide who may call: `SecurityConfig` lists every path and method with its roles and
denies the rest (an unknown path is a 401 without a token and a 403 with one, never a 404), and
every handler of the controller repeats its role with `@PreAuthorize`. Whose payment it is, is
decided by the service layer from the caller built out of the token.

Known limits of the API:

- The request is not checked against the order: the amount and the owner of the order are checked
  by the Order Service when it receives the event, and a payment that does not fit stays
  recorded here while the order stays `PENDING`.
- A token stays valid until it expires (15 minutes): the service does not ask the Authentication
  Service whether the account still exists.
- A payment whose answer was lost and that is sent again is a new payment, unless the first one
  was successful (409). There is no idempotency key.
- A list without a filter is a collection scan with an in-memory sort (see the data access).

## Kafka

After a payment is saved, the service sends the event `CREATE_PAYMENT` to the topic
`payment-events`; the Order Service reads it and moves the order to `PAID` or `PAYMENT_FAILED`.

| | |
|---|---|
| Topic | `payment-events`: 3 partitions, 1 replica (one broker). Declared by this service (`KafkaTopicConfig`) and created by Spring when the application starts. The broker has automatic topic creation switched off. |
| Key | the order id as text. Kafka orders records only inside a partition, and the key decides the partition: all events of one order stay together and are read in the order they were sent. |
| Value | JSON, no Java type header (each service has its own class for the event) |

```json
{
  "eventId": "4d1f8f3e-0c0b-3b52-9c7e-1f6a2d9b7a10",
  "eventType": "CREATE_PAYMENT",
  "paymentId": "65f0c0ffee0123456789abcd",
  "orderId": 5,
  "userId": 7,
  "status": "SUCCESS",
  "amount": "24.00",
  "occurredAt": "2026-11-16T10:00:00.123Z"
}
```

- `eventId` is a name-based UUID of the event type and the payment id, so it is the same every
  time for one payment. `occurredAt` is the time of the payment. `amount` is text with two
  decimal places: a JSON number could pass through a `double` in a reader and lose its scale.
- The contract is pinned by `PaymentEventPublisherIntegrationTest`, which reads the record back
  from a real broker. The Order Service has the same JSON in its tests.
- **Producer settings** (`application.yml`): `acks=all` and an idempotent producer (a retry of
  the producer never writes a record twice); `max.block.ms` 2 s, `request.timeout.ms` 5 s,
  `delivery.timeout.ms` 30 s.
- **The send is asynchronous.** The HTTP thread waits only for the metadata of the topic (at most
  `max.block.ms`), never for the delivery. The outcome is logged by the callback: INFO with the
  partition and the offset, or ERROR `Event of payment ... NOT published`.

How to look at it by hand (PowerShell, the stack of this README is running):

```
docker compose exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --describe --topic payment-events
docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic payment-events --from-beginning --property print.key=true --property print.partition=true --property print.offset=true
```

Known limits of the events:

- **Saving the payment and sending its event are two steps in two systems.** MongoDB and Kafka
  cannot take part in one transaction, and there is no outbox. If Kafka cannot be reached, the
  payment stays saved, the client gets 201, and the event is lost (ERROR in the log with the
  payment id and the order id). A lost `SUCCESS` event leaves the order `PENDING`, and a second
  payment of that order is refused (409): repair the order by hand. A lost `FAILED` event is
  harmless. The event is lost when Kafka is down at the first send after the start of the
  application (the send gives up after 2 s), when it stays down for longer than 30 s, or when the
  application stops before the producer has sent the record. A light outbox (a flag on the
  payment and a relay) would close the gap.
- The producer retries a record by itself and is idempotent, so a retry never writes it twice.
  Even so the Order Service reads an event at least once: a crash before it commits its offset
  makes it read the same event again, so it must handle a repeated event idempotently.

## Tests

```
.\mvnw test
```

The integration tests start a MongoDB container and a Kafka container with Testcontainers
(Docker must run). They share one Spring context and the two containers. `QueryPlanIntegrationTest` switches the profiler of
MongoDB on for the time of a query to read its plan.

The unit tests of the service layer need no Docker (`-DexcludedGroups=integration` skips the
integration tests):

| Tests | What they prove |
|---|---|
| `PaymentServiceTest` | Mockito, a fixed clock: the parity rule, the payer, the time and the scale of the amount, the order that is already paid (the random number service is not asked), both kinds of duplicate key, a failing random number service saves nothing, ownership, the forced owner of a list, the periods |
| `RandomNumberClientTest` | WireMock: the request on the wire, every usable and unusable answer, every error status, broken connections, a refused connection, a timeout |
| `PaymentEventMapperTest` | the event of a payment: the facts, the amount as text with two decimal places, the stable event id |
| `PaymentEventPublisherTest` | Mockito: topic, key and value of the send; no failure of Kafka (at once or later) or of the mapping reaches the caller |
| `PaymentMapperTest`, `PaymentRequestValidationTest`, `RandomApiPropertiesTest`, `ClockConfigTest`, `CallerTest`, `PaymentFilterTest` | the pieces on their own |
| `PaymentControllerTest` | the HTTP contract of every endpoint with the real security chain and a mocked service: statuses, `Location`, JSON, the payer from the token, validation, errors, the page limit |
| `PaymentAccessMatrixTest` | every path and method (and some that must not exist) against an anonymous caller, a user, an admin, an admin without a profile and an unknown role |
| `ControllerMethodSecurityTest`, `CallersTest` | every handler declares its `@PreAuthorize`; the caller comes from the verified token only |
| `JwtConfigTest`, `FailClosedJwtDecoderTest`, `TokenTypeValidatorTest`, `RoleAuthoritiesConverterTest`, `SecurityPropertiesTest`, `ProblemDetailSecurityHandlerTest` | the check of the token, against a real HTTP key set |
| `KeySetNotAvailableTest`, `KeySetOutageTest` | the Authentication Service is down: a 401, not a 500, no address in the log; a cached key keeps working |
| `GlobalExceptionHandlerTest`, `TypeMismatchHandlingTest`, `SortWhitelistTest`, `PageResponseTest` | the answers to errors, the sort names, the page |
| `SecurityIntegrationTest` | real tokens through the whole application: every bad token looks like no token, the clock decides the expiry, no default user, an unknown path is never a 404 |
| `ApiSecurityIntegrationTest` | the endpoints with real tokens and a real MongoDB: whose payments a caller sees, a foreign payment looks like a missing one, the totals, a refused payment stores nothing |
| `PaymentSuccessCheckIntegrationTest`, `QueryPlanIntegrationTest` | the check for a successful payment against a real MongoDB, and that it uses an index |
| `KafkaTopicIntegrationTest` | the application creates `payment-events` with 3 partitions and 1 replica |
| `PaymentEventPublisherIntegrationTest` | the record on a real broker: key, no Java type header, the JSON of the contract; the events of one order stay in one partition in order |

The service against a real MongoDB, a real HTTP server for the random number service, the log
line of a lost event and a Kafka that is down are covered by the integration tests of JACAG-26.

## Troubleshooting

| Symptom | Cause and fix |
|---|---|
| `Could not resolve placeholder 'MONGO_USERNAME'` | there is no `.env` in the working directory of the run (the IDE must run in the project folder) and the variable is not set |
| `Authentication failed` | the user was created with other values: `docker compose down -v` and start again |
| `Applying the MongoDB changelog failed: ... timed out` | MongoDB is not running or the port is different |
| Every call is a 401 and the log has `A token could not be verified` | the Authentication Service is not running or `AUTH_JWKS_URI` points to another address; in a container `localhost` is the container itself |
| Every call is a 401 and the log has no such line | the token is not valid: expired (15 minutes), another issuer (`JWT_ISSUER`), or a refresh token instead of an access token |
| `POST /api/payments` is a 403 for an admin | the token has no `user_id`: the first admin of the Authentication Service has no profile, so there is no payer. Pay as a user |
| `from` or `before` is a 400 | the value is not an ISO-8601 instant, or the `+` of an offset was sent as a space: use `Z` or `%2B` |
| `Connection to node -1 (localhost/127.0.0.1:9092) could not be established` in the log | Kafka is not running (`docker compose up -d`) or `KAFKA_BOOTSTRAP_SERVERS` points to another port. The application still starts; the topic is created at the next start |
| ERROR `Event of payment ... NOT published (TimeoutException)` | Kafka was not reachable, or the topic does not exist (Kafka was started after the application, or `docker compose down` removed it: restart the application). The payment is saved. Start Kafka, and tell the Order Service about the payment by hand if it matters |
| The first changeSet fails because the collection `payments` already exists | The collection was created by hand (for example by an `insertMany` in `mongosh`) before the application first started, so it has no validator and no indexes. `db.payments.drop()` in `mongosh` and start the application again, or `docker compose down -v` |
