# Payment Service

[![CI](https://github.com/ArsShark/PaymentService/actions/workflows/ci.yml/badge.svg)](https://github.com/ArsShark/PaymentService/actions/workflows/ci.yml)

Payment microservice of the course e-commerce application (Innowise Java Advanced). Part of a
system with the Authentication Service, the API Gateway, the User Service and the Order Service.

> Work in progress: the database schema (JACAG-21), the DAO layer (JACAG-22), the service layer
> (JACAG-23), the Kafka producer (JACAG-24, the consumer is in the Order Service) and the REST API
> with its security, the container setup (JACAG-27) and the CI pipeline (JACAG-28) are done.

## Stack

- Java 21, Spring Boot 4.1.1 (Spring MVC, Spring Data MongoDB)
- MongoDB 8 with Liquibase migrations (extension `liquibase-mongodb`)
- Apache Kafka 4 (KRaft, Spring for Apache Kafka): the event of every payment, JSON
- Bean Validation, MapStruct, `RestClient` for the call to the random number service
- Log4j2 (SLF4J API in the code)
- JUnit 5, Mockito, WireMock, Testcontainers

## Requirements

- JDK 21
- Docker (the tests; MongoDB and Kafka for a run from the IDE, or the whole service in
  containers) with Docker Compose 2.23.1 or newer (the init script of MongoDB is an inline config)
- Maven Wrapper is included in the project

## Configuration

The profile `local` is the default (`spring.profiles.default`). Environment variables override
every default.

| Variable | Default | Meaning |
|---|---|---|
| `MONGO_USERNAME` | none, required | MongoDB user of the application (in a container the script of `docker/mongodb` creates it on the first start) |
| `MONGO_PASSWORD` | none, required | its password |
| `MONGO_ROOT_USERNAME`, `MONGO_ROOT_PASSWORD` | none | only for Docker Compose: the administrator of the MongoDB container; the application never uses it |
| `MONGO_HOST` | `localhost` (`local`), `mongodb` (`docker`) | MongoDB host |
| `MONGO_PORT` | `27017` | MongoDB port (in `.env` it is also the port that `docker-compose.dev.yml` publishes on `127.0.0.1`) |
| `MONGO_DB` | `payment_service_db` | database name |
| `MONGO_AUTH_DB` | `admin` | database in which the user was created |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` (`local`), `kafka:19092` (`docker`) | address of the Kafka broker |
| `KAFKA_PORT` | `9092` | only for `docker-compose.dev.yml`: the port that Kafka publishes on `127.0.0.1` |
| `RANDOM_API_URI` | `https://www.random.org` | address of the random number service |
| `AUTH_JWKS_URI` | `http://localhost:8081/.well-known/jwks.json` (`local`), `http://authentication-service:8081/.well-known/jwks.json` (`docker`; the Compose file of this repository sets `http://host.docker.internal:8081/...`) | key set of the Authentication Service; the internal address, never the API Gateway |
| `JWT_ISSUER` | `authentication-service` | the issuer that an access token must have; the same value as in the Authentication Service |
| `SERVER_PORT` | `8084` | HTTP port |
| `LOG_DIR` | `logs` | folder of the log files |

Credentials are never stored in the repository. The application is configured with host, port
and database, not with `spring.mongodb.uri`: the Liquibase migration reads the same settings.

There is no `INTERNAL_API_KEY`: the service has no internal endpoints and calls no internal
endpoint of another service. Fixed in `application.yml`: at most 100 items per page and
`server.forward-headers-strategy: framework`, so that the `Location` of a created payment follows
the address of the client behind the API Gateway.

### Spring profiles

| Profile | When | MongoDB | Kafka | Key set |
|---|---|---|---|---|
| `local` (default) | the IDE, `.\mvnw spring-boot:run` | `localhost:27017` | `localhost:9092` | `http://localhost:8081/...` |
| `docker` | the container (Compose sets `SPRING_PROFILES_ACTIVE=docker`) | `mongodb:27017` | `kafka:19092` | `http://authentication-service:8081/...` |

The profile files hold only addresses; credentials are never stored in them and every default is
replaced by an environment variable. The profile `local` also reads the `.env` file of the
working directory; the profile `docker` does not (a container gets its settings from the
`environment` of the Compose file). The one URL in `application.yml` is the random number
service, which is the same in every environment. `SpringProfilesTest` checks all of this without
starting Spring.

## Run with Docker

```
copy .env.example .env      # then set the names and passwords in .env
docker compose up --build
```

Compose starts MongoDB and Kafka, waits until both are healthy, builds the image
`payment-service:dev` and starts the application on `http://localhost:8084` with the profile
`docker`. Only this port is published: MongoDB and Kafka can be reached only from inside the
Compose network. Port 8084 must be free, so stop the application in the IDE first.

| Service | Image | What it is |
|---|---|---|
| `app` | `payment-service:dev` (built here) | the application, port 8084, logs in the volume `app-logs` (`/app/logs/payment-service.log`) |
| `mongodb` | `mongo:8` | the data is in the volume `mongo-data` |
| `kafka` | `apache/kafka:4.2.0` | one KRaft broker with one listener (`kafka:19092`), no volume: `docker compose down` removes the topics and the application declares them again when it starts |

- **The Authentication Service is not part of this file.** The application needs it only to check
  the access tokens (the key set is fetched on the first request), so it can start without it,
  but every token is a 401 until the key set is reachable. By default it is expected on the host,
  where its own Compose file or the IDE runs it: `http://host.docker.internal:8081/...`. The
  Compose file in which all services run together is in the Infrastructure repository
  (`https://github.com/ArsShark/Infrastructure`).
- **The random number service** (`RANDOM_API_URI`, random.org by default) is on the internet: the
  container needs access to it. Without it a payment is a 502 and nothing is saved.
- `docker compose down` keeps the data; `docker compose down -v` deletes it (the MongoDB volume
  and the logs).

### The two MongoDB users

The image creates the **administrator** (`MONGO_ROOT_USERNAME`) in the database `admin`. A script
of `docker/mongodb` then creates the **user of the application** (`MONGO_USERNAME`), also in
`admin`, with a role `<MONGO_DB>_app` on the one database of the application (`MONGO_DB`). The
role is the built-in `readWrite` plus the one action `collMod`: the user can create the
collection and its indexes (the Liquibase changelog does), read and write documents, and change
the validator of the history collection of Liquibase (`DATABASECHANGELOG`), which `readWrite`
alone does not allow. It cannot create users, drop the database or touch another database
(least privilege). The application runs as this user, with `MONGO_AUTH_DB=admin` (the default).

The script reaches the container as an inline `configs` entry of `docker-compose.yml`, not as a
mounted file. A bind mount needs the drive of the project to be visible to the Docker daemon;
with Docker Desktop on Windows a USB drive is not, and the container then gets an empty
directory in place of the file (the image stops with `EISDIR`). The text in the Compose file is
a copy of `docker/mongodb/01-create-app-user.js`, which is the source: edit the file and copy it
into the Compose file (indented by 6 spaces). `DockerComposeFilesTest` fails if the two differ.
Compose interpolates variables in the text of an inline config, so the script has no dollar sign
(the same test checks it).

The image runs the script ONCE, on the first start of an empty volume. Changing the names or
passwords afterwards changes nothing in MongoDB; reset it with `docker compose down -v`, which
deletes the data. The health check of `mongodb` logs in as the user of the application, so the
application starts only after the script has run. `MongoInitScriptIntegrationTest` proves the
script on a real, empty container.

## Run locally (the application in the IDE)

Start only MongoDB and Kafka, published on the loopback interface of the host, with the second
Compose file (it is not read automatically):

```
copy .env.example .env      # if you have not done it; then set the values in .env
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d mongodb kafka
docker compose -f docker-compose.yml -f docker-compose.dev.yml ps    # both become "healthy"
```

The first start downloads the Kafka image (about 700 MB). Kafka has no volume: `docker compose
down` removes its topics, and the application declares them again when it starts. It is
published only on `127.0.0.1:9092` (an extra listener of `docker-compose.dev.yml`). If you change
`KAFKA_PORT`, set `KAFKA_BOOTSTRAP_SERVERS` to the same port. MongoDB is published only on
`127.0.0.1`.

The users are created on the FIRST start, when the volume is empty (see above). Stop with the
same two `-f` options and `down` (add `-v` to delete the data).

Run the application from the IDE or with `.\mvnw spring-boot:run` from the project folder: the
profile `local` also reads the `.env` file of the working directory (as Java properties, so no
quotes around a value), and the same values serve Docker Compose and the application. Real
environment variables win over `.env`. In the `docker` profile `.env` is not read.

## Database

The collection `payments` is created by Liquibase when the application starts
(`src/main/resources/db/changelog`). Spring Boot has no Liquibase support for MongoDB, so
`MongoMigration` runs the changelog by hand. It reads the files through the resource loader of
Spring (`SpringResourceAccessor`) and puts that accessor into the current Liquibase scope, as
`SpringLiquibase` does: precautions for the executable jar of Spring Boot.

The Liquibase MongoDB extension had a real problem there. When the connection has no `appName`,
`MongoConnection.getVersion()` builds the URL of its own manifest from the text before the FIRST
`!` of the URL of its class. In the executable jar the URL is
`jar:nested:/app/app.jar/!BOOT-INF/lib/liquibase-mongodb.jar!/...`, so the manifest URL has an
empty nested entry and the loader of Spring Boot fails with `nestedEntryName must not be empty`
(liquibase-mongodb issue 612). On a plain class path there is one `!` and it works, so the IDE
and the tests never showed it; only the container did. `MongoDriverProperties` gives the
connection an `appName`, so the extension never asks for its version. `MongoDriverPropertiesTest`
pins this contract; the run of the container is the proof, so run it after any change in how
Liquibase starts.

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

How to look at it by hand (PowerShell, MongoDB and Kafka of this README are running, in either
mode; the commands run inside the container and use its listener `kafka:19092`):

```
docker compose exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:19092 --describe --topic payment-events
docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:19092 --topic payment-events --from-beginning --property print.key=true --property print.partition=true --property print.offset=true
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
| `DockerComposeFilesTest` | the Compose files without Docker: the inline init script equals `docker/mongodb/01-create-app-user.js`, the script has no dollar sign (Compose would interpolate it), MongoDB gets it in the directory of the image, and no service mounts a path of the host |
| `SpringProfilesTest` | the configuration files without Spring: the defaults of `local` and `docker`, the override by environment variables, the same keys in both profiles (the import of `.env` only in `local`), no credentials, and the random number service as the only URL of `application.yml` |
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

Through the whole application (real tokens, real MongoDB, real Kafka broker, and a WireMock
server instead of the random number service, see `RandomApiStub`):

| Tests | What they prove |
|---|---|
| `RandomApiStubIntegrationTest` | the shared context talks to the stub and never to the real service; the stub itself (numbers in order, delays, errors, broken connections, a request nobody stubbed is a failure) |
| `PaymentApiIntegrationTest` | a payment from the request to the document: the parity rule (also below zero and at the limits of a `long`), the stored types and the time of the clock, a failed payment that leaves the order open, a paid order refused before the random number service is asked, a 502 for every kind of failure of the service, a 504 with the real read timeout, invalid bodies and a wrong content type, fields that a client adds are ignored, reading back and totalling what was paid |
| `PaymentEventFlowIntegrationTest` | the topic is read to its end: the key, no Java type header, the eight fields of the contract, the stable event id, the order of the events of one order; a paid order, a failing random number service, an invalid body and a caller without a profile send no event |
| `ForwardedHeadersIntegrationTest` | the `Location` of a created payment follows the `X-Forwarded-*` headers of the Gateway |
| `PaymentConcurrencyIntegrationTest` | eight parallel payments of one order, repeated five times: with the answer delayed so that the unique index decides, exactly one 201, seven 409, one document and one event; with mixed numbers, only what holds whichever way the requests interleave |
| `ApiSecretsNotLoggedIntegrationTest` | one run with every kind of success and refusal, then no token, email, address of the key set or of the random number service, database password, duplicate key text or value of a refused request is in the log |
| `MongoInitScriptIntegrationTest` | an empty `mongo:8` container with the init script, like Compose starts it (own container, no Spring context): the user of the application applies the Liquibase changelog, cannot touch another database and cannot create users, even with a password full of special characters |
| `KafkaOutageIntegrationTest` | the broker is paused: the payment is answered and saved, the HTTP thread is not held, one line at ERROR level names the payment, the order and the class of the cause; the producer works again after the resume |

`PaymentApiIntegrationTest` (a 504 takes as long as the read timeout, 5 s),
`ApiSecretsNotLoggedIntegrationTest` (it contains a 504 too) and `KafkaOutageIntegrationTest`
(the delivery timeout of the producer is 30 s) wait for real timeouts, so the integration tests
take a few minutes.

### Which test covers which method of the service layer

| Method | Unit tests (`PaymentServiceTest`) | Through the application |
|---|---|---|
| `create` | outcome by parity, payer, time, scale, a paid order, both kinds of duplicate key, a failing random number service | `PaymentApiIntegrationTest`, `PaymentEventFlowIntegrationTest`, `PaymentConcurrencyIntegrationTest`, `KafkaOutageIntegrationTest` |
| `getById` | own, foreign, missing, admin | `ApiSecurityIntegrationTest`, `PaymentApiIntegrationTest` |
| `findAll`, `findOwn` | the filter, the forced owner | `ApiSecurityIntegrationTest`, `PaymentApiIntegrationTest` |
| `totalOfUser`, `totalOfAll` | the periods, a missing bound | `ApiSecurityIntegrationTest`, `PaymentApiIntegrationTest` |

### Coverage

```
.\mvnw clean test "-DexcludedGroups=integration"
start target\site\jacoco\index.html
```

The unit tests alone cover the package `service` completely (instructions and branches). Run
without `-DexcludedGroups=integration` the report also counts what the tests through the
application cover.

## CI pipeline

The workflow `.github/workflows/ci.yml` (GitHub Actions) runs on every push, on pull requests into
`main` and manually (Actions > CI > Run workflow).

| Job      | Step                                  | What it does |
|----------|---------------------------------------|--------------|
| `build`  | Build                                 | `./mvnw -DskipTests package` |
| `build`  | Test                                  | all tests (unit and integration: Testcontainers starts MongoDB and Kafka) and the JaCoCo report |
| `build`  | Code analysis                         | SonarQube Cloud; only on pull requests into `main` and on pushes to `main`; the build waits for the quality gate and fails if it is not passed |
| `build`  | Upload reports                        | JUnit and JaCoCo reports as the artifact `reports` (kept 14 days) |
| `docker` | Build the image                       | builds the image from the `Dockerfile` into the Docker daemon of the runner, on every run (after `build` succeeded) |
| `docker` | Start the service with MongoDB and Kafka | starts that image with the `docker-compose.yml` of the repository and a `.env` made from `.env.example`, and waits until the services run and MongoDB and Kafka are healthy |
| `docker` | Wait for the application, Check the container and the topic | a request without a token is answered with the 401 of the security chain (so the context started and the Liquibase changelog ran), the process runs as the user `app`, the topic `payment-events` exists |
| `docker` | Show the logs, Stop the service       | the logs of the containers on a failure; the containers and their volumes are always removed |
| `docker` | Push the image                        | the same layers from the cache of the build step; only on pushes to `main` or on a manual run with `publish` ticked |

The tests need no secrets and no environment variables: MongoDB and Kafka run in Testcontainers,
the random number service and the key set of the Authentication Service are played inside the
tests. The free plan of SonarQube Cloud analyses only `main` and pull requests into `main`, so
pushes to other branches skip the analysis. The service has no health endpoint before the
Kubernetes task, so the check of the image waits for the 401 of an unauthenticated request. The
pipeline runs the same commands as a local build:

```powershell
.\mvnw -B -ntp -DskipTests package
.\mvnw -B -ntp test
```

The check of the image can be repeated by hand (keep your own `.env` if you have one; the
placeholders of `.env.example` are enough):

```powershell
docker build -t payment-service:dev .
Copy-Item .env.example .env
docker compose up -d --no-build --wait --wait-timeout 180
curl.exe --noproxy "*" -i http://localhost:8084/api/payments/my      # 401
docker compose exec -T app id -un                                    # app
docker compose down -v
```

Published image (the package must be public to pull it without a login):

```powershell
docker pull ghcr.io/arsshark/paymentservice:latest      # last build of main
docker pull ghcr.io/arsshark/paymentservice:sha-<short commit id>
```

It runs like the locally built image, see "Run with Docker". The Compose file of the whole system
(Infrastructure repository) takes it as `PAYMENT_SERVICE_IMAGE`.

Repository settings used by the pipeline (Settings > Secrets and variables > Actions):

| Name                   | Kind     | Purpose |
|------------------------|----------|---------|
| `SONAR_TOKEN`          | secret   | token of the SonarQube Cloud project |
| `SONAR_ORGANIZATION`   | variable | SonarQube Cloud organization key |
| `SONAR_PROJECT_KEY`    | variable | SonarQube Cloud project key |

Pushing to GHCR uses the built-in `GITHUB_TOKEN`, no extra secret is needed. The `docker/*`
actions are pinned to commit SHAs (a tag can be moved, and the job may write packages); the
GitHub-owned `actions/*` stay on a major tag.

### CI troubleshooting

| Symptom | Cause and fix |
|---|---|
| The step "Code analysis" fails with an authorization error | `SONAR_TOKEN`, `SONAR_ORGANIZATION` or `SONAR_PROJECT_KEY` is missing or wrong, or Automatic Analysis is still switched on in the SonarQube Cloud project. |
| The step "Code analysis" fails after the analysis | The quality gate failed. Open the link in the log, fix the issue or, for a rule that does not apply (for example "CSRF protection is disabled": the API is stateless, the token is in a header and there are no cookies), set the status in SonarQube Cloud to Accepted with a reason. |
| A step fails with `toomanyrequests` | Docker Hub limits the anonymous pulls of `mongo:8` and `apache/kafka:4.2.0` (the tests and the check of the image both pull them). Re-run the job. |
| "Start the service with MongoDB and Kafka" fails | Read the step "Show the logs of the containers". The usual causes are the ones of "Troubleshooting" below: the init script of MongoDB, the changelog, the address of Kafka. |
| "Start the service" fails with an unknown key `configs` or `content` | `docker compose` of the runner is older than 2.23.1 (the first line of the step prints its version). |
| "Wait for the application" ends with the status `000` | Nothing listens on 8084: the application stopped or is still starting after two minutes; the logs are printed after the failure. |
| `docker pull` of the image is denied | The package is private. Make it public in the package settings of the repository owner. |
| The manual run has no `publish` box | The workflow file must be on `main` first. |

## Troubleshooting

| Symptom | Cause and fix |
|---|---|
| `Could not resolve placeholder 'MONGO_USERNAME'` | there is no `.env` in the working directory of the run (the IDE must run in the project folder) and the variable is not set |
| `Authentication failed` | the user was created with other values: `docker compose down -v` and start again |
| `mongodb` exits with `Error: EISDIR: illegal operation on a directory, read` | a file of the project is mounted into a container and the Docker daemon cannot see the drive (a USB drive with Docker Desktop on Windows): it creates an empty directory in its place. The init script is an inline config for this reason; any other `./file:/path` mount from that drive would fail the same way. After the first failed start run `docker compose down -v` (the volume is half initialized) |
| `mongodb` is `unhealthy` and `app` does not start | the health check logs in as the user of the application, which the script creates on the first start only. The volume was created before the script existed or with other `MONGO_USERNAME` and `MONGO_PASSWORD`: `docker compose down -v` and start again |
| `required variable MONGO_ROOT_PASSWORD is missing a value` (or another `MONGO_*`) | `.env` has no such line (copy `.env.example`) or Compose runs in another folder; a variable set in the shell wins over `.env` |
| `port is already allocated` or `address already in use` for 8084 | the application still runs in the IDE |
| In the container every call is a 401 and the log has `A token could not be verified` | the Authentication Service does not run on the host (8081), or the Windows firewall blocks the connection from Docker to its Java process (allow Java), or `.env` sets a `localhost` address in `AUTH_JWKS_URI` |
| In the container a payment is a 502 `The random number service is not available` | the container has no access to the internet, or the quota of random.org is used up; a stand-in service can be set with `RANDOM_API_URI` |
| `Applying the MongoDB changelog failed: ... IllegalArgumentException: nestedEntryName must not be empty` (only in the container) | `MongoConnection.getVersion()` of the Liquibase MongoDB extension fails on the nested jar of Spring Boot unless the connection has an `appName`: `MongoDriverProperties` gives it one. If the message comes back, the frames after `where:` name the class: see the section Database |
| `Applying the MongoDB changelog failed: ... | where: <frames>` | the text after `where:` is the first frames of the innermost cause (names of code only, never messages): they say which class of which library failed. Send them when a startup failure is unclear |
| `Applying the MongoDB changelog failed: ... timed out` | MongoDB is not running or the port is different |
| Every call is a 401 and the log has `A token could not be verified` | the Authentication Service is not running or `AUTH_JWKS_URI` points to another address; in a container `localhost` is the container itself |
| Every call is a 401 and the log has no such line | the token is not valid: expired (15 minutes), another issuer (`JWT_ISSUER`), or a refresh token instead of an access token |
| `POST /api/payments` is a 403 for an admin | the token has no `user_id`: the first admin of the Authentication Service has no profile, so there is no payer. Pay as a user |
| `from` or `before` is a 400 | the value is not an ISO-8601 instant, or the `+` of an offset was sent as a space: use `Z` or `%2B` |
| `Connection to node -1 (localhost/127.0.0.1:9092) could not be established` in the log | Kafka is not running (`docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d mongodb kafka`) or `KAFKA_BOOTSTRAP_SERVERS` points to another port. The application still starts; the topic is created at the next start |
| ERROR `Event of payment ... NOT published (TimeoutException)` | Kafka was not reachable, or the topic does not exist (Kafka was started after the application, or `docker compose down` removed it: restart the application). The payment is saved. Start Kafka, and tell the Order Service about the payment by hand if it matters |
| The first changeSet fails because the collection `payments` already exists | The collection was created by hand (for example by an `insertMany` in `mongosh`) before the application first started, so it has no validator and no indexes. `db.payments.drop()` in `mongosh` and start the application again, or `docker compose down -v` |
