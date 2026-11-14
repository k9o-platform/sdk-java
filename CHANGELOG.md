# Changelog — dev.k9o:core (Java)

## 1.5.2

### Changed

- `K9O_TIER_FEATURE_GATED` is no longer documented as an error to expect: the platform no longer gates features by plan.
- The LLM model type no longer has `minTier`, which the gateway does not send.
- The `tierGating` option is removed from sign-in provider creation; the platform no longer gates features by plan.
- README: Architecture at a glance, illustrations, and the package table lists only the packages for that SDK.

## 1.5.0

### Added

- `approvals().comments()`: `add(pendingId, body, visibility)` (`POST /@<project>/pending-approvals/<id>/comments`) and `list(pendingId, limit, cursor)` (`GET` on the same path, oldest first). `add` answers `Approvals.Comment` (`id`, `caseId`, `authorId`, `visibility`, `body`, `createdAt`); `list` answers `Approvals.CommentPage`, an `Envelope` with `nextCursor()`. `body` is 1 to 4000 characters and `visibility` is `everyone` or `approvers_only` (`Approvals.VISIBILITY_EVERYONE`, `Approvals.VISIBILITY_APPROVERS_ONLY`); both and `pendingId` are checked before sending (`K9O_INVALID_FIELD_VALUE`). Posting needs a session. Gateway refusals pass through as `KError`: `K9O_APPROVAL_COMMENT_NOT_ELIGIBLE`, `K9O_APPROVAL_COMMENT_CLOSED`, `K9O_NOT_FOUND`, `K9O_RBAC_DENY`.
- `realtime().replay(topic, options)`: read the events recorded on a realtime topic in order (`POST /@<project>/realtime/replay`; `Realtime.ReplayOptions` `fromSequence`, `untilSequence`, `limit` 1 to 10,000). Returns `Realtime.ReplayPage` (`events` as `Realtime.RealtimeEvent`, `nextCursor`, `hasMore`, `resync`). `Realtime.RealtimeEvent` gains `sequence()` (a string; set only on replayed events; the five-argument constructor still works). `audit().replay` is unchanged.
- `gate().signIn` first asks the project which region holds the email (`POST /@<project>/gate/locate`, only the email, no credential), then sends the password to the origin it names. The SDK uses that origin only if it is `https` with no user info, port, path, query or fragment, and is a region host of the same organization, gateway family and domain as the client's base URL whose region code matches the answer; otherwise it uses the base URL. Nothing is cached except a bounded in-memory hint (64) per refresh token, read by `refreshToken` and `signOut` and removed on sign-out. If the locate step answers 404, the base URL is used. Any other non-2xx answer from that step (429 `K9O_RATE_LIMITED`, 503 `K9O_GATE_REGION_UNAVAILABLE`) is raised as the usual `KError` (`Gate.RATE_LIMITED`, `Gate.REGION_UNAVAILABLE`), the password is not sent and nothing is retried.
- `crypto().mode()` and `crypto().mode(crypt)` return the gateway's `route` (`"client"` or `"server"`) as `Crypto.Mode.route()`, `null` on older gateways or for an unknown value; follow it, not `custodyMode`. The two-argument `Mode` constructor still works.
- The custody check before each `encrypt` names the crypt (`GET /crypto/mode?crypt=<crypt>`), so a personal access token or workload policy scoped to named crypts can encrypt. `mode()` sends no query.
- `crypto().mode(crypt)`: an optional crypt for the public mode read (`GET /crypto/mode?crypt=<crypt>`); the crypt is checked before sending (`K9O_INVALID_FIELD_VALUE`), and `mode()` is unchanged.
- `crypto().encrypt` follows the `route` field of the project's mode answer. On BYOK or HYOK only an explicit `"server"` sends plaintext to the platform (an epoch is required, else `K9O_CRYPTO_CUSTODY_UNKNOWN`); a missing route means the client core. A route that is neither `"server"` nor `"client"` is refused with `K9O_CRYPTO_CUSTODY_UNKNOWN` (context `custodyMode`, and `route` when it is a string) in every custody mode, and nothing is sent. The mode is read before every encrypt. `decrypt` and `rewrap` are unchanged.
- `expectedKeyRefs(List<String>)` client option for BYOK and HYOK: the router passes it to the registered `Crypto.ClientCore` on every `encrypt`, `decrypt` and `rewrap` as a new `Crypto.CoreOptions` argument (an empty list when not set), and the core must refuse a key that is not in it before it uses any token or makes any network call. Checked at `build()`: not empty, no blank entry, no whitespace, no `*` (`K9O_INVALID_FIELD_VALUE`, field `expectedKeyRefs`). A core declares that it enforces the pin with `ClientCore.enforcesExpectedKeyRefs()` (default `false`); with the option set and no declaration the call is refused with `K9O_CRYPTO_KEY_PIN_UNENFORCED` (`Crypto.KEY_PIN_UNENFORCED`, no HTTP status) before the core is called. `Crypto.keyRefMatches(keyRef, expected, provider)` compares by the provider's rule (`Crypto.KeyRefProvider`: AWS whole reference; GCP without the version; Azure the first five parts in lower case; Vault `<path>/<key>` without `:<version>`). The provider is required and never guessed from the reference, because a Vault reference can look like a URL. The three `ClientCore` methods take the `CoreOptions` argument.
- `rampart()`: `classify(input, label)` classifies a JSON object (`POST rampart/classify`) and returns the verdict with the signed receipt as received and the `projectId`; `jwks()` reads the public key feed (`GET /rampart/jwks`: `keys` and `issuer`); `verifyReceipt(result, input, options)` takes the classify result or a bare receipt string and verifies the receipt (key by `kid`, `EdDSA`, `typ`, signature, issuer from the key feed or the `issuer` option, project from the `projectId` option or the classify result, clock skew, and the SHA-256 of the input canonicalised with the platform's JCS code-point profile: keys sorted by UTF-8 byte order, integers only up to 2^53-1) and returns the signed claims, or throws `Rampart.ReceiptRefusal` with a closed `reason` (`malformed`, `key_unknown`, `alg`, `typ`, `signature`, `issuer`, `project`, `project_unknown`, `expired`, `not_yet_valid`, `input_hash`). Keys are cached for the client's life and refetched once on an unknown `kid`. Rampart is advisory: it never blocks, and an unavailable classifier is an error (503 `K9O_RAMPART_CLASSIFIER_UNAVAILABLE`), never a pass. A long input is scored in overlapping windows and the highest injection score decides; past 16 windows (about 6,000 tokens) the answer is 413 `K9O_RAMPART_INPUT_TOO_LONG` with no verdict; 503 `K9O_RAMPART_CLASSIFIER_UNAVAILABLE` still means the classifier could not answer. The thresholds (calibration `rampart-launch-v1`) are a starting calibration and may move; a change is a new `calibrationVersion`.
- `Rampart.VerifiedReceipt.windows()`: the signed `windows` claim, the number of windows the classifier scored (1 when the input fits one window; a receipt without the claim reads as 1). Unknown claims in a receipt are ignored. A 413 `K9O_RAMPART_INPUT_TOO_LONG` is a `KError` (`Rampart.INPUT_TOO_LONG`), never a verdict.
- `analytics()`: datasets. `list`, `create`, `get`, `delete`, `write` (`onError` skip or reject), `upload` (CSV or JSON up to 25 MiB; raw body, or multipart with a `manifest` that creates the dataset), `downloadOriginal` (bytes and content type), `materialize` (from a collection or a saved query), `query` (read-only SQL over datasets) and `read` (rows by `cursor`, `limit`, `partitionFilter`), plus `rollups()` and `schedules()` (`create`, `list`, `delete`, `reattest`). A rollup or schedule keeps running only while it is re-attested within 7, 30, 90 or 365 days; `reattest` is people only (a service, workload or extension token gets 403 `K9O_RBAC_DENY`, reason `human_only`).
- `rollups()`: `list`, `read` (cells of a time range; cells below the cohort threshold read as suppressed) and `subscribe`, a server-sent events feed that yields `Rollups.Cell` (`cellKey`, `bucket`, `dims`, `isFinal()`, `measures`, `eventId`, `occurredAt`; a cell below the cohort threshold has `suppressed()`, `status` and `reason`). It resumes with the frame `id` as `Last-Event-ID` (`fromSequence` on the first connection), drops resent cells by `eventId`, reopens after the 300-second connection limit and passes a `reread-source` resync to `onResync`.
- `keys()`: the project's key inventory, metadata only. `list()` (the active key) and `list(true)` (every version), `get(keyId)` and `rotate(keyId)` (the list is not paged: `Keys.Page` has `items` only); `Keys.Key` has `id`, `version`, `status`, `custodyMode`, `createdAt`, `rotatedAt`, `byokRef` and `hyokRef`. Rotation needs the project admin role and a fresh step-up proof, is limited to 3 per rolling 24 hours (429 `K9O_RATE_LIMITED`, `retryAfterSec()`), and refuses a second one at a time (409 `K9O_STATE_CONFLICT`).
- `audit().tail(options)`: follow the project's audit log live (`auditEventId`, `type`, `actorType`, `occurredAt`, `outcome` flattened from the frame's payload, plus the frame's `eventId`), with `types`, the change-feed behaviour of `realtime().subscribe` (resume with the SSE `id`, dedupe, idle timeout) and, on a `replay-ledger` resync, a paged catch-up through the new `audit().replay(opts)` (`afterAuditEventId` or `fromSequence`; returns `Audit.ReplayPage` with `events`, `nextCursor`, `hasMore`; `sequence` is a string) and a reconnect with `Last-Event-ID`. `warden.checkpoint` is not in the live tail.
- `audit().tail` resync is gapless: after a `replay-ledger` resync the tail reconnects fresh (no `Last-Event-ID`) first, then replays from the last delivered `auditEventId`, then merges the connection's frames, each event once; a second resync in a row backs off. `audit().replay` without `limit` sends `limit: 1000` (an explicit limit up to 10,000 is passed as given).
- `keys().rotate(keyId, Keys.RotateOptions.withStepUp(hook))`: on 401 `K9O_STEP_UP_REQUIRED` the hook receives the challenge (`scopeSetRoot`, `nonce`, `methods`, `maxAgeSec`, `rpId`) with the HTTP method and URL and returns `Keys.StepUpProof(method, proof)`; the SDK sends the `x-k9o-step-up-proof` header and retries exactly once. Without a hook the challenge is on `KError.getStepUp()`.
- `crypto()`: server-side encryption, `encrypt`, `decrypt` and `rewrap` (`POST crypto/encrypt`, `crypto/decrypt`, `crypto/rewrap`) in batches of 1 to 100 items under a crypt, with bytes and UTF-8 helpers, base64 handled for you, client-side checks of the crypt (a registered name, 1 to 64 characters of a-z, 0-9 and -, not `actions`), item count, context and sizes (`K9O_INVALID_FIELD_VALUE`, `K9O_PAYLOAD_TOO_LARGE`), a `Crypto.Result` per item and no automatic retry. Machine calls for service tokens and personal access tokens only; IAM on the crypt decides; it routes by custody: `encrypt` checks `crypto().mode()` (`custodyMode`, `epoch`) on every call, sends `custodyEpoch`, never sends plaintext to the platform for a BYOK or HYOK project and re-checks once on a 409 `K9O_ZK_STORE_FLOW_REQUIRED`; an unknown, missing or malformed mode answer raises `K9O_CRYPTO_CUSTODY_UNKNOWN` (`Crypto.CUSTODY_UNKNOWN`, no HTTP status) and a failed mode call rethrows its own error, neither sending anything; `decrypt` and `rewrap` reroute to the client core when the platform answers that 409 with route `client`; `crypto().useClientCore(Crypto.ClientCore)` registers the Kibo client core, and without one those calls throw `K9O_ZK_CLIENT_CORE_UNAVAILABLE` (501); the ciphertext follows the project key's lifecycle and is not covered by per-subject erasure; it is a different construction from client-side Kibo and from `k9o.kibo.encrypt`. Not live on the gateway yet (404 expected).
- `crypto().federatedToken(crypt, op)`: a token for `encrypt`, `decrypt` or `rewrap` on a crypt (`POST crypto/federated-token`), valid at most 15 minutes, returned as `Crypto.FederatedToken` (`token`, `expiresAt`, `issuer`, `subject`, `audience`, `kid`, and `provider` and `projectId` when the gateway sends them, `null` otherwise). Crypt and op are checked before sending (`K9O_INVALID_FIELD_VALUE`); an answer missing the token or any claim raises `K9O_INTERNAL_ERROR`. The token is a credential: `toString()` redacts it. Its `subject` reads `<project>:crypt:<crypt>:op:<op>`. Never retried.
- `crypto()`: `listCrypts()` (`GET crypto/crypts`), `createCrypt(name)` (`POST crypto/crypts`, body is only `name`) and `retireCrypt(name)` (`POST crypto/crypts/<name>/retire`, no body), returning `Crypto.Crypt` (`name`, `status` `active` or `retired`, `createdAt`, `retiredAt` or `null`). A bad name (the crypt rule) is refused before sending with `K9O_INVALID_FIELD_VALUE`, field `name`. An answer with no crypts list, an unknown status, no name or createdAt, or a retiredAt that is neither a date nor null raises `K9O_INTERNAL_ERROR`. Gateway refusals pass through as `KError`: `K9O_CRYPT_ALREADY_EXISTS` and `K9O_CRYPT_LIMIT_EXCEEDED` (`Crypto.CRYPT_ALREADY_EXISTS`, `Crypto.CRYPT_LIMIT_EXCEEDED`, 409), `K9O_CRYPT_RETIRED`, `K9O_CRYPT_NOT_FOUND`, `K9O_ACCESS_DENIED`, `K9O_IAM_POLICY_DENIED`, `K9O_RBAC_DENY`. A retired or unregistered name only makes `encrypt` refuse it; `decrypt` and `rewrap` never read the registry. Create and retire need `crypto:manage_crypts` (project admin by default); any personal access token is refused on retire.
- `rampart().classify(Object input, label)`: refuses an input that is not a JSON object with `K9O_INVALID_FIELD_VALUE` before sending.
- `KError.retryAfterSec()` is also filled from a `Retry-After` header given in seconds when the error body carries none. Retry timing is not affected.
- `Jcs.canonicalize(value)`: the platform's JCS code-point profile (keys sorted by UTF-8 byte order, integers only up to 2^53-1), in the SDK.
- `Moat`: pins the system prompt (`messages(input)` builds the list; untrusted input is always a `user` message), refuses role and system markers, enforces an input and an output schema (a JSON Schema subset checked in the SDK), runs an optional PII filter on output, and gives `promptSha256()`. It runs locally with no network access. The pin is structural: it stops injected text from becoming the system prompt and does not stop it reaching the model.
- `Moat` role-marker scan: before matching, the text is normalised with NFKC, combining marks are ignored, default-ignorable and format code points are removed, a small set of Cyrillic and Greek look-alikes of the marker letters are read as the Latin letter (not general homoglyph detection), it is case-folded and horizontal whitespace is collapsed (line breaks kept); the text sent to the model is unchanged. Checked against the shared marker vectors.
- `KError.retryAfterSec()`: the `retryAfterSec` number of the error's `recommendation` object, `null` when absent. A new seven-argument constructor takes it; the six-argument one is unchanged. Retry timing is not affected.
- `approvals().list(status, limit, cursor)`: pages through the queue; the cursor of the next page is `meta().cursor().next()`, `null` on the last page. A bad cursor fails with `K9O_INVALID_CURSOR`.
- `Approvals.BulkResult.failed()`: the rows a `bulk` call could not decide, as `Approvals.BulkFailure` (`id`, `code`, `reason`). The call answers 200 even then; an absent `failed` reads as an empty list.
- `requireApproval()` on single-record `insert`, `update` and `delete` (`from("orders").insert(record).requireApproval().execute(Row.class)`): the write is queued for approval (HTTP 202) instead of applied.
  The result is a `GatedWrite`: `Queued` with the queued change (`Approvals.QueuedChange`) or, when an `auto_approve` policy applies the write at once, `Applied` with the normal result; `isQueued()` tells them apart.
  Upserts and bulk writes do not take it. `from(c).insert(record)` without a row class now returns the insert builder; `insert(record, Row.class)` is unchanged.
- `Envelope.Meta.approval()`: the `meta.approval` object of a queued write.
- `functions().triggers()`: `list`, `create`, `update` and `delete` for a function's event triggers (`collection`, `events`, `filter`, `enabled`, `logOkInvocations`). `delete` answers 204, so `data()` is `null`.
  The SDK checks `events` (1 to 4 of `insert`, `update`, `delete`, `upsert`) and the collection name before sending.
- `schedules()`: `list`, `get`, `create`, `update`, `delete`, `runs` and `runNow` for scheduled triggers. `list` takes `status`, `functionName`, `limit` and `cursor`.
  A schedule interval under 5 minutes is rejected with 400 `K9O_INVALID_FIELD_VALUE` (field `schedule_expression`), on every plan.
- `endUsers()`: `list` and `get` for the project's end users, and `roleGrants()` with `list`, `grant` and `revoke` for the roles they hold. `revoke` answers `revoked` false, not an error, for an unknown or already revoked id.
- `vectors()`: `nearest(collection, queryVector, options)` and `upsert(collection, rows)` for collections of your own embeddings. `options` is a `Vectors.NearestOptions` (`topK`, `minScore`, `metric`, `filters`).
  The vector is sent in the request body (up to 16000 numbers, 1 to 1000 rows per upsert), as the builder's `vectorSearch` now does. A collection name must start with `_`.
  The SDK checks the name, the vector length and numbers, `topK`, `metric` and the row count before sending.
- `vectorSearch` on the select builder sends the embedding in the request body (`POST data/<collection>/search`), so real embeddings work. It cannot be combined with `textSearch`. Rows returned are the smaller of `limit` (default 50) and the vector limit (default 10).
- `custody().status()`: read-only custody status of the project (`mode`, `attached`, `provider`, `lastJob`, `hsm`) from `GET custody/status`. A missing `hsm` reads as `null`.
- `metadata()`: the Metadata API. Declare columns on a collection, then read and change them through one API with the same access rules as the data.
  `filter(input)` (`POST metadata/query`) reads one or more surfaces (`collection:<name>`, `telemetry:<event>`, `storage:<bucket>`) with `where` conditions, `limit` (1 to 1000) and a `cursor` (one surface only; the next cursor is `meta().cursor().next()`).
  `set(collection, id, metadata)` writes declared columns and returns the updated record. `search(input)` is full-text search over a declared text column (first page only, no rank).
  `vectors().nearest(input)` finds the rows nearest to an embedding (no score; at most 50 rows come back whatever `k` is).
  `subscribe(surface, options)` follows a collection live over the reconnecting change feed (`Last-Event-ID`, `eventId` de-duplication, `onResync`); events carry `collection` and `id` only, and `fromSequence` is used on the first connection only.
  `index().get(collection)` and `index().declare(collection, columns)` read and replace the declaration (at most 64 columns).
  Column names are sent as `metadata.<name>`; the prefix is added when missing (not for `createdAt` and `actorId` on a `telemetry:` surface), and `set` drops a leading `metadata.` from keys.
  The SDK checks surfaces, operators, `limit` and `k` (1 to 1000), `metric`, the embedding, the column count and cursor use before sending.
  Gateway errors arrive as `KError`: `K9O_METADATA_FIELD_NOT_INDEXED`, `K9O_UNKNOWN_SURFACE`, `K9O_QUERY_RESULT_TOO_LARGE` (413), `K9O_NOT_IMPLEMENTED` (501, `storage:` surfaces), `K9O_ENCRYPTED_COLUMN_OPERATION_UNSUPPORTED` and `K9O_EXTENSION_PERMISSION_DENIED`.

- Realtime change feed resume: `realtime().subscribe` reconnects when a connection ends (the gateway closes each after at most 300 seconds) or fails, sending `Last-Event-ID` with the last `eventId`, and drops events it already delivered (latest 1024 remembered). Delays follow the gateway's `retry:` hint (1 second by default) and back off to 30 seconds on repeated failures; a 4xx on connect is thrown once, a 5xx or network error is retried. Iteration ends when you close the stream.
- `RealtimeEvent.eventId()`.
- `realtime().subscribe(topic, filter, options)` with `Realtime.SubscribeOptions`: `onResync` (called with a `Realtime.Resync` of `reason` and `action` when the gateway reports a lost position; the stream then reconnects without `Last-Event-ID`), `idleTimeout` (default 45 seconds without bytes) and `reconnectDelay`.
- README: what an event-trigger function receives (the JSON payload, redaction, `triggeredAt`, and deduping on `eventId`).

### Changed

- A realtime stream no longer ends when the server closes the connection; it reconnects. Stop iterating by closing the stream or breaking out of the loop.

### Fixed

- `realtime().subscribe`, `metadata().subscribe` and `rollups().subscribe` no longer reconnect in a loop without delivering anything. On a connection opened without `Last-Event-ID` the gateway sends a `resync` frame (`cursor-evicted`) before the events; the stream now treats that first frame as informational (no reconnect, `onResync` not called) and keeps reading the same connection. A `resync` after an event, or on a connection that sent `Last-Event-ID` or `fromSequence`, is still a lost position and behaves as before. `audit().tail` is unchanged.
- A gateway error whose `recommendation` is an object (for example `{"action": "..."}` or `{"retryAfterSec": 30, "note": "..."}`) now keeps its text in `getRecommendation()` (the `action`, else the `note`) instead of dropping it. Any other JSON type in that field reads as `null`; the error always decodes. This covers both ordinary responses and the SSE stream open.
- `KError.getRequestId()` is filled from the `x-k9o-request-id` response header (any case) on every error built from a response, including a non-KError body, a stream open, a download and the key feed, when the error body carries no `requestId`; a body value wins. Success responses are unchanged.

## 1.4.0 — 2026-10-26

### Added

- `ai().generate`: run a prompt through an LLM, with an optional `model`. Same result as `compute().llms().invoke`.
- `streams()`: `create`, `list`, `get`, `delete`, `publish`, `replay` and `subscribe` for user data streams. `subscribe` reconnects from the last sequence.
- `query(sql, params)`: run one read-only SQL statement. `params` is a `List` (positional) or a `Map` (named `:name` placeholders, rewritten to `$n`).
- `search(collection, input)`: full-text search over a text column, best matches first, with `limit` and `cursor`.
- `from(c).select().textSearch` and `vectorSearch`: full-text and nearest-neighbour search on the select builder.
- `approvals()`: `list`, `get`, `approve`, `reject` and `bulk` for the pending-change queue. `approve` can answer with `status` still `pending_approval`; `list` is not paginated; `bulk` skips failed rows silently, so compare `affectedIds` with what you intended.
- `approvalPolicies()`: `list`, `create` and `delete` for the rules that decide which writes are queued.
- `schema().collections().list(limit, cursor)`: list the project's collections by name, with `meta().cursor().next()` for the next page.

### Changed

- README description.
- Function deploy examples use the sandbox tier.

## 1.3.0 — 2026-10-26

Every method now matches the gateway and is checked by a contract test.

### Changed

- `projects().create` takes `Projects.CreateInput` with `displayName` (required) and optional `slug`, `region`, `custodyMode`. `get` and `update` use the projects routes.
- `functions().deploy` takes `name`, `runtime`, `tier` (`gateway` or `sandbox`), `artifactKey`, `version` and `description`. Inline code is gone.
- `functions().invoke` sends `{payload}` and accepts `Functions.InvokeOptions`. `functions().list` accepts `limit` and `cursor`.
- Policies `create`, `update` and `validate` take a `Policies.PolicyInput` document. `policies().list` takes only `status`.
- `audit().query` filters are `eventType`, `actorId`, `after`, `before`, `limit`, `cursor`.
- Role grants use `/role-grants` and identity providers use `/identity-providers`, with a typed `CreateIdpInput`.
- `org().defaultLLM()` returns an object. `org().smtp().set` takes `passwordRef` and `fromAddress`; `test` takes `to` and `subject`.
- `me().apiTokens().create` takes `label`, `scopeManifest` and `ttlSeconds`. `me().sessions().revoke` and `revokeAll` use the session routes.
- `compute().llms().invoke` parameters are flat and the result has `content` and `usage`. Stream chunks are OpenAI-style.
- `RealtimeEvent` is `{topic, eventType, payload, occurredAt}`. `studio().workflows().trigger` sends `{triggerContext}`.
- Optional fields left `null` are no longer sent.
- The API host is read from the base URL: any `<org>.api.<apex>` host works and no domain is built in.

### Removed

- `gate().verifySession`, `gate().hosted`, `realtime().replay` and `realtime().subscribePoll`.
- `me().notifications()`, `me().auditLog()`, `me().dataExport()`.
- `org().groups().create`, `org().groups().delete`, `org().members().get`, `org().smtp().get`.
- The `modality` filter of `compute().llms().list` and the `triggerType` filter of `studio().workflows().list`.

### Added

- `functions().uploadArtifact(bytes, "wasm" | "tar")` returns the `artifactKey` for `deploy`.

## 1.2.0 — 2026-10-25

- The README now introduces the Kilimanjaro building blocks (Keep, Pulse, Gate, Kibo). No change to the public API.

## 1.1.1 — 2026-10-25

- Documentation links corrected: the documentation hub is https://docs.kilimanjaro.dev, with the website, platform API and console listed in a Links section. Package metadata points at the same places.

## 1.1.0 — 2026-10-25

- Documentation rewritten with runnable examples and expected output; internal cross-references removed from the published sources. No change to the public API.
- Error text for `update()` / `delete()` without an `id` filter no longer mentions a version or a roadmap.

## 1.0.0 — 2026-10-23

First public release. See the README for the full API overview.
