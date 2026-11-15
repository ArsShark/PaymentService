# Payment Service

Payment microservice of the course e-commerce application (Innowise Java Advanced). Part of a
system with the Authentication Service, the API Gateway, the User Service and the Order Service.

> Work in progress: the database schema (JACAG-21), the DAO layer (JACAG-22) and the service
> layer (JACAG-23) are done. The REST API, Kafka, the container setup and the CI pipeline follow.

## Stack

- Java 21, Spring Boot 4.1.1 (Spring MVC, Spring Data MongoDB)
- MongoDB 8 with Liquibase migrations (extension `liquibase-mongodb`)
- Bean Validation, MapStruct, `RestClient` for the call to the random number service
- Log4j2 (SLF4J API in the code)
- JUnit 5, Mockito, WireMock, Testcontainers

## Requirements

- JDK 21
- Docker (the tests and the local MongoDB)
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
| `RANDOM_API_URI` | `https://www.random.org` | address of the random number service |
| `SERVER_PORT` | `8084` | HTTP port |
| `LOG_DIR` | `logs` | folder of the log files |

Credentials are never stored in the repository. The application is configured with host, port
and database, not with `spring.mongodb.uri`: the Liquibase migration reads the same settings.

## Run MongoDB locally

```
copy .env.example .env      # then set real values in .env
docker compose up -d
docker compose ps           # the status of mongodb becomes "healthy"
```

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
- Writing to the database and then to Kafka are two steps (JACAG-24); the insert is not
  undone if the event cannot be sent.

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

The payer is the profile id of the caller and never comes from the request body; the same goes
for the outcome and the time. The service does not check the order or the amount against the
Order Service: the Order Service does that when it receives the event (JACAG-24).

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

## Tests

```
.\mvnw test
```

The integration tests start a MongoDB container with Testcontainers (Docker must run). They
share one Spring context and one container. `QueryPlanIntegrationTest` switches the profiler of
MongoDB on for the time of a query to read its plan.

The unit tests of the service layer need no Docker (`-DexcludedGroups=integration` skips the
integration tests):

| Tests | What they prove |
|---|---|
| `PaymentServiceTest` | Mockito, a fixed clock: the parity rule, the payer, the time and the scale of the amount, the order that is already paid (the random number service is not asked), both kinds of duplicate key, a failing random number service saves nothing, ownership, the forced owner of a list, the periods |
| `RandomNumberClientTest` | WireMock: the request on the wire, every usable and unusable answer, every error status, broken connections, a refused connection, a timeout |
| `PaymentMapperTest`, `PaymentRequestValidationTest`, `RandomApiPropertiesTest`, `ClockConfigTest`, `CallerTest`, `PaymentFilterTest` | the pieces on their own |
| `PaymentSuccessCheckIntegrationTest`, `QueryPlanIntegrationTest` | the check for a successful payment against a real MongoDB, and that it uses an index |

The service against a real MongoDB, a real HTTP server for the random number service and Kafka
is covered by the integration tests of JACAG-26.

## Troubleshooting

| Symptom | Cause and fix |
|---|---|
| `Could not resolve placeholder 'MONGO_USERNAME'` | there is no `.env` in the working directory of the run (the IDE must run in the project folder) and the variable is not set |
| `Authentication failed` | the user was created with other values: `docker compose down -v` and start again |
| `Applying the MongoDB changelog failed: ... timed out` | MongoDB is not running or the port is different |
| The first changeSet fails because the collection `payments` already exists | The collection was created by hand (for example by an `insertMany` in `mongosh`) before the application first started, so it has no validator and no indexes. `db.payments.drop()` in `mongosh` and start the application again, or `docker compose down -v` |
