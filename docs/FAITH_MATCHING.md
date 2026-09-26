# Learn, denomination quiz, and church matching

Implemented as an additive feature in the existing Angular/Spring Boot/Postgres application. This is an educational alignment tool, not a religious identity test. No church's beliefs are inferred from the directory's denomination string.

## User journeys

- `/learn`: learning landing page and sourced introductions to ten reference traditions.
- `/learn/traditions/:slug`: reference positions with original sources. These represent the named body, not every similarly named church.
- `/learn/denomination-quiz`: 24 questions covering doctrine, worship and devotional interest. Each has an explanation, sources, an unsure/skip response and separate importance (0–3). Essential means discrepancies must be surfaced.
- `/learn/denomination-quiz/results`: traditions to explore and church matches by exact city/state or explicit geolocation within 1–200 km. Mobile and desktop navigation both include Learn.
- `/churches/:id/beliefs`: public church-confirmed positions, notes, source links, participation guidance, confirmer display name and publication date.
- `/churches/:id/beliefs/edit`: authorized draft editing, reference-template suggestions, preview, explicit review confirmation, publishing and the latest 30 published revisions.
- `/admin/faith`: platform editors publish new editions of question wording and sourced tradition references. Existing catalog editions are immutable.

A church owner can enter through the public beliefs page's Manage button. Delegated staff need `MANAGE_BELIEFS`; `EDIT_PROFILE` alone does not grant it. Existing owners retain full access through `ChurchStaff.hasPermission`, including owners created before this permission existed. Platform admins bypass the church-specific check.

Reference templates fill only unanswered fields. Publishing is explicit, requires at least five supplied positions, and never invents local devotional offerings. A differing church statement can be published with an explanation. The church's local statement is used for local matching. A denomination label or reference-template choice is not verification of institutional affiliation.

## Implementation

Server module: `server/src/main/java/com/everychurch/server/faith`.
Client components: `apps/client/src/app/pages/learn`.
Content: `server/src/main/resources/faith/catalog-v1.json`.
Migrations: `V15__faith_matching.sql`, `V16__manage_beliefs_permission.sql`, `V17__faith_confirmer_name_length.sql`.

The initial catalog is inserted idempotently on application startup. It has stable dimension and option identifiers, source links, tradition reference positions and a matching rule version. Publishing later editions never changes existing catalog documents; changing an existing option's meaning/group/category or deleting it is rejected. Model a new distinction as a new stable dimension rather than silently rewriting historical answers.

The catalog groups dimensions, options, questions, tradition templates and rule metadata into an immutable document instead of introducing separate mutable tables for every editorial field. Postgres stores those documents as TEXT and validates them at the service boundary. Church profiles have separate private drafts and immutable published revisions, with a current-publication pointer. This avoids mutable reference defaults changing a church's confirmed profile. Publications lock the church row and require the expected base revision; stale publications return 409. Drafts retain their base revision, so reopening a stale draft does not bypass that check.

Saved attempts store the submitted answers plus a complete result snapshot, including quiz/rule versions and the exact church-profile revision ids. Opening a saved result shows the snapshot; Recalculate explicitly evaluates current church profiles using the original quiz edition. A new quiz can use the latest edition. Up to 50 private attempts can be retained per account.

## Matching rules: alignment-v1

1. Ignore user answers marked unsure or importance zero; these are distinct states.
2. Compare doctrine and practice categories separately. Importance weights are 1, 2 and 4 (essential).
3. Related baptism, gifts and authority questions share a topic budget so extra questions do not automatically dominate the result.
4. Exact doctrinal options score 1. Reviewed related groups score 0.5; explicit differences score 0. Groups are defined in the catalog. These are editorial heuristics, not empirically validated probabilities.
5. A church permitting multiple positions gets partial overlap, never universal full agreement. It needs confirmation if the user marked that position essential.
6. Devotional preferences compare with actual offerings: regular, occasional, encouraged privately or not offered locally. Not offering an organized practice is not a claim that the church rejects its theology.
7. Doctrine/practices receive a 75/25 mix when both have weighted answers. If only one category has answers, that category receives the full weight.
8. Known-answer scores divide only by comparable information; weighted coverage is shown independently. Missing data receives no agreement credit in the conservative lower bound and full possible credit only in the upper bound. Ranking uses the lower bound within status groups, preventing sparse profiles from claiming a perfect match.
9. An essential difference is separated from compatible results. An unanswered essential topic is marked Needs confirmation. Strong alignment requires at least five compared topics, 65% coverage and a lower bound of at least 75/100. These thresholds require pilot calibration.
10. All-unsure answers yield Explore without a ranking, not an identity claim.

Local matching scores **all published profiles in the selected geographic area**, queried in batches of 200, retaining the best 30 results. It does not preselect only the nearest churches and inadvertently discard better matches farther away. The PostGIS radius uses the existing geography index. No location permission is requested until the visitor clicks Use my location; exact city/state is an alternative. Unprofiled churches remain accessible through the ordinary directory.

## API

Public:
- `GET /api/learn/quiz`
- `GET /api/learn/quiz/versions/{version}`
- `POST /api/learn/quiz/evaluate`
- `GET /api/churches/{id}/beliefs`

Church-authorized:
- `GET /api/churches/{id}/beliefs/editor`
- `PUT /api/churches/{id}/beliefs/draft`
- `POST /api/churches/{id}/beliefs/publish`

Authenticated, caller's data only:
- `GET /api/users/me/quiz-attempts`
- `POST /api/users/me/quiz-attempts`
- `DELETE /api/users/me/quiz-attempts/{id}`

Platform admin:
- `POST /api/admin/faith/catalogs`

## Privacy

Anonymous evaluation writes no answers/results to the database. Browser state is memory-only, is cleared explicitly or on an account logout/switch, and never appears in URL query parameters. Saving requires an explicit authenticated action. The saved-result service is scoped to the authenticated user; church staff have no endpoint for visitors' answers. Evaluation and private endpoints return `Cache-Control: no-store`. This module does not log answers, send emails or publish quiz-taking activity. Source URLs are HTTPS links; the API does not fetch user-provided URLs.

## Tests and isolated browser verification

- `FaithMatcherTest`: unknowns, importance, essential conflicts, partial overlap, sparse data, devotional separation, validation and deterministic explanations.
- `FaithFlowTest`: real PostGIS migrations, anonymous access/no persistence, private drafts, owner/delegated permissions, publication conflict detection, local overrides, geographic matching, private snapshots/deletion and source URL validation.
- Angular tests: zoneless question rendering, error recovery, private memory state, distinct uncertainty/importance, review-before-publish and non-overwriting templates.
- `apps/client-e2e/src/faith.spec.ts`: mobile anonymous completion plus owner publication → quiz → real local match → private save/delete.

Run server tests with Java 21 and Docker available, and client tests with `nx test client --runInBand`. Build with `nx build client`.

The dedicated browser harness serves the built SPA on 127.0.0.1:4305 and proxies only to a loopback API. **Use a disposable database and an isolated API, never the shared development or production data.** Example:

```sh
docker run --rm -d --name everychurch-faith-e2e \
  -e POSTGRES_PASSWORD=faith-test-only -e POSTGRES_DB=faith_e2e \
  -p 127.0.0.1:15485:5432 postgis/postgis:16-3.4

# In server/, with JAVA_HOME set to your Java 21 installation:
DATABASE_URL=jdbc:postgresql://127.0.0.1:15485/faith_e2e \
DATABASE_USERNAME=postgres DATABASE_PASSWORD=faith-test-only \
ADMIN_EMAIL=faith-admin@example.test ADMIN_PASSWORD=faith-test-admin-123 \
GOOGLE_GEOCODING_API_KEY= S3_BUCKET_NAME= MAIL_HOST= \
./mvnw spring-boot:run -Dspring-boot.run.arguments=--server.port=18085

# From the repo root, after nx build client:
FAITH_E2E_API_URL=http://127.0.0.1:18085 \
./node_modules/.bin/playwright test --config apps/client-e2e/faith.playwright.config.ts

# Stop the isolated API, then discard only the named disposable database:
docker stop everychurch-faith-e2e
```

## Editorial and pilot release gates

The implementation and initial primary-source summaries do **not** constitute a completed human theological review or user pilot. Before promoting the quiz broadly:

- Ask representatives of each supported tradition to review its summaries and the wording of relevant questions. Include novice readers in comprehension checks.
- Recruit participating churches to publish their own profiles; imported church labels are insufficient for matching.
- Evaluate whether the fixed core questionnaire, partial-overlap groups, 75/25 weighting, and coverage thresholds produce explanations participants find accurate. Revise versioned content/rules from evidence.
- Consider optional deeper follow-up questions for sacramental explanations, salvation, and intra-tradition distinctions. The first edition deliberately uses a fixed 24-question flow and is not an exhaustive denomination classifier.
- Expand source coverage before adding more reference templates. No reference template is auto-created for Non-denominational, Other, Evangelical or unsupported church bodies.
- Load-test the matching workload against representative profile counts and add deployment-level abuse/rate controls for anonymous evaluation before broad promotion.

No external reviewer was contacted, no real church was assigned beliefs, and no live deployment is performed by this feature's startup.
