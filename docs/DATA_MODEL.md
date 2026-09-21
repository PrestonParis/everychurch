# Data Model

This is the contract every implementation prompt is checked against. Entities are grouped by status. When an entity moves from Planned → In Progress → Implemented, update its section here in the same change.

## Implemented

### `Church`
`server/src/main/java/com/everychurch/server/model/Church.java`

| Field | Type | Notes |
|---|---|---|
| id | Long | PK |
| name | String | required |
| denomination | String | free text today; will need a controlled taxonomy once Faith Formation content (Prompt 22) lands |
| address, city, state, zipCode | String | |
| phone, email, website | String | |
| description | String (TEXT) | |
| imageUrl | String | single image; superseded by a `ChurchImage` gallery in Prompt 10 |
| pastor | String | free text; no structured leadership list yet |
| facebook, instagram, youtube | String | raw URLs |
| claimed | boolean | superseded by `Claim` (below) — kept for backward compatibility until Prompt 7 migrates it |
| latitude, longitude | Double | *Implemented (Prompt 14)* — nullable. Client-supplied value wins if the request includes both (e.g. the scraper's Places API result); otherwise geocoded server-side from address/city/state/zipCode. A failed or unconfigured geocode (no `GOOGLE_GEOCODING_API_KEY`) leaves existing coordinates untouched rather than clearing them. |
| serviceTimes | List\<ServiceTime\> | one-to-many, cascade + orphan removal |

Owner: `server` (writes), `apps/scraper` (proposes via the `ChurchSubmission` queue — see below — never writes directly).

### `ServiceTime`
`server/src/main/java/com/everychurch/server/model/ServiceTime.java`

| Field | Type | Notes |
|---|---|---|
| id | Long | PK |
| church | Church | many-to-one, `@JsonIgnore` |
| dayOfWeek | String | e.g. "Sunday" |
| time | String | e.g. "10:30" |
| type | String | e.g. "Mass", "Sunday Service", "Bible Study" |
| language | String | nullable |

Owner: `server`, always written as part of a `Church` update.

## Implemented (continued)

### `User` — *Implemented (Prompt 6)*
`server/src/main/java/com/everychurch/server/model/User.java`

| Field | Type | Notes |
|---|---|---|
| id | Long | PK |
| email | String | unique, required |
| passwordHash | String | BCrypt, `@JsonIgnore` |
| role | `Role` enum | `MEMBER`, `VOLUNTEER`, `CHURCH_ADMIN`, `PLATFORM_ADMIN` — Visitor is unauthenticated and not persisted |
| createdAt | Instant | |

Owner: `server` (`AuthService`, `UserRepository`). Read by: `apps/client` (`AuthService`, auth state signal).

Auth flow: `POST /api/auth/register` and `POST /api/auth/login` (`AuthController`) issue a stateless JWT (`JwtService`, HS256, subject = email, claim = role). `JwtAuthenticationFilter` reads the `Authorization: Bearer` header on every request and populates the Spring Security context if the token is valid and the user still exists; no server-side session state. `SecurityConfig` permits `GET /api/churches/**` and `/api/auth/**`, and requires authentication for everything else — currently a blanket "any authenticated user," not yet role- or ownership-scoped (that's `Claim`/`ChurchStaff` below).

### `VolunteerInvite` — *Implemented (Prompt 7)*
`server/src/main/java/com/everychurch/server/model/VolunteerInvite.java`

| Field | Type | Notes |
|---|---|---|
| id | Long | PK |
| code | String | unique, random, given to the recruit out-of-band |
| status | `VolunteerInviteStatus` enum | `PENDING`, `REDEEMED`, `REVOKED` |
| createdBy | User | the Platform Admin who generated it |
| redeemedBy | User | nullable — the resulting Volunteer `User` |
| createdAt, expiresAt, redeemedAt | Instant | `expiresAt` nullable = no expiry |

CRUD: create/revoke/list by `PLATFORM_ADMIN` only (`POST`/`PATCH`/`GET /api/admin/volunteer-invites`). Redeemed by anyone with a valid code via `POST /api/auth/register/volunteer`, which creates the `User` (`Role.VOLUNTEER`) and marks the invite `REDEEMED` in the same operation — a code can't be reused, and an expired or revoked code is rejected.

## Implemented (continued)

### `Claim` — *Implemented*
`server/src/main/java/com/everychurch/server/model/Claim.java`. Replaces the bare `Church.claimed` boolean, which is now write-protected — `ChurchService.applyDto` intentionally ignores `claimed` on the request body, so a plain `PUT /api/churches/{id}` can no longer set it directly.

| Field | Type | Notes |
|---|---|---|
| id | Long | PK |
| church | Church | many-to-one |
| user | User | many-to-one — the requester |
| status | `ClaimStatus` enum | `PENDING`, `APPROVED`, `REJECTED` |
| notes | String | evidence/justification submitted by requester |
| decidedBy | User | nullable, the Platform Admin who approved/rejected |
| createdAt / decidedAt | Instant | |

CRUD: `POST /api/churches/{churchId}/claims` — create by any authenticated `User` (rejected if the church is already claimed, or the same user already has a `PENDING` claim on it). `GET/POST /api/admin/claims[/{id}/approve|reject]` — list/approve/reject, `PLATFORM_ADMIN` only. On approval (`ClaimService.approve`), `Church.claimed` is set `true`, a `ChurchStaff` owner row is created, and the requester's global `Role` is bumped from `MEMBER` to `CHURCH_ADMIN` if it wasn't already something higher.

### `ChurchStaff` — *Implemented*
`server/src/main/java/com/everychurch/server/model/ChurchStaff.java`. Per-church staff roster with fine-grained permissions — this is what "Church Admin" actually resolves against, not the global `Role`.

| Field | Type | Notes |
|---|---|---|
| id | Long | PK |
| church | Church | many-to-one |
| user | User | many-to-one |
| isOwner | boolean | set true for the user whose `Claim` was approved; can't be changed or removed via the staff endpoints |
| permissions | Set\<`ChurchPermission`\> | `EDIT_PROFILE`, `MANAGE_SERMONS`, `MANAGE_EVENTS`, `MANAGE_STAFF` — irrelevant for an owner, who has every permission implicitly (`ChurchStaff.hasPermission`) |

CRUD: `GET/POST /api/churches/{churchId}/staff`, `PATCH/DELETE /api/churches/{churchId}/staff/{staffId}` — only the church's owner, a staff member with `MANAGE_STAFF`, or a `PLATFORM_ADMIN` may list/add/edit/remove; the owner row itself can't be edited or removed this way. `ChurchService.update`/`delete` also check this table directly: once a church is claimed, editing needs `EDIT_PROFILE` and deleting needs to be the owner (or `PLATFORM_ADMIN`) — an *unclaimed* church still has no owner, so it stays editable/deletable by any authenticated user until someone claims it.

### `ChurchSubmission` — *Implemented (Prompt 13 + 17)*
`server/src/main/java/com/everychurch/server/model/ChurchSubmission.java`. The verification queue backing both the Volunteer workflow and the scraper's ingestion pipeline (`apps/scraper`) — both submit through this same table, distinguished only by `submittedBy`.

| Field | Type | Notes |
|---|---|---|
| id | Long | PK |
| targetChurch | Church | nullable — null means "propose a new church" (always null on the scraper path — see SCRAPER.md) |
| payload | String (TEXT) | the proposed `ChurchDto`, serialized to JSON via Jackson |
| submittedBy | String | the volunteer's `User.id` as a string, or the literal `"scraper"` |
| status | `ChurchSubmissionStatus` enum | `PENDING`, `APPROVED`, `REJECTED` |
| reviewedBy | User | nullable, the Platform Admin who approved/rejected |
| createdAt / reviewedAt | Instant | |

CRUD: `POST /api/submissions` (new church) and `POST /api/churches/{churchId}/submissions` (edit) — create by `VOLUNTEER` users only (`ChurchSubmissionService.requireVolunteer`, 403 for anyone else), via a user JWT. `POST /api/scraper/church-submissions` — bulk create, `ChurchSubmissionService.submitFromScraper`, authenticated by the `X-Scraper-Api-Key` header instead of a JWT (see ARCHITECTURE.md/SCRAPER.md), always a new-church proposal. `GET/POST /api/admin/submissions[/{id}/approve|reject]` — list/approve/reject, `PLATFORM_ADMIN` only, same pattern as `Claim`. On approval (`ChurchSubmissionService.approve`), the payload is applied via the existing `ChurchService.create`/`update` — a new `Church` if `targetChurch` was null, otherwise an update to it (which also re-runs geocoding, since it's the same code path a direct edit would use).

### `ChurchImage` — *Implemented (Prompt 10)*
`server/src/main/java/com/everychurch/server/model/ChurchImage.java`

| Field | Type | Notes |
|---|---|---|
| id | Long | PK |
| church | Church | many-to-one |
| url | String | |
| sortOrder | int | |

Supersedes `Church.imageUrl` as the primary image representation; `imageUrl` stays as a denormalized "primary image" cache, unchanged by this prompt.

CRUD: `GET /api/churches/{churchId}/images` — public. `POST`/`PUT /{imageId}`/`DELETE /{imageId}` — requires `EDIT_PROFILE` on that church (via `ChurchStaff`), or `PLATFORM_ADMIN`.

### `Event` — *Implemented (Prompt 11)*
`server/src/main/java/com/everychurch/server/model/Event.java`

| Field | Type | Notes |
|---|---|---|
| id | Long | PK |
| title, description | String | |
| startsAt, endsAt | Instant | |
| churches | Set\<Church\> | many-to-many — co-hosting |

CRUD: `GET /api/events[?churchId=]`, `GET /api/events/{id}` — public. `POST`/`PUT /{id}`/`DELETE /{id}` — requires `MANAGE_EVENTS` on at least one participating church (checked against both the existing and the incoming church set on update), or `PLATFORM_ADMIN`.

### `Sermon` — *Implemented (Prompt 12)*
`server/src/main/java/com/everychurch/server/model/Sermon.java`

| Field | Type | Notes |
|---|---|---|
| id | Long | PK |
| church | Church | many-to-one |
| title | String | |
| date | LocalDate | |
| mediaUrl | String | plain string URL supplied by the caller today; object storage upload is still TBD — see ARCHITECTURE.md "Object Storage" |

CRUD: `GET /api/churches/{churchId}/sermons` — public. `POST`/`PUT /{sermonId}`/`DELETE /{sermonId}` — requires `MANAGE_SERMONS` on that church, or `PLATFORM_ADMIN`.

## Still Planned

None of the entities below have any code yet.

### `Member`
Separate from `ChurchStaff` — plain congregational membership for networking, not administrative access.

| Field | Type | Notes |
|---|---|---|
| id | Long | PK |
| church | Church | many-to-one |
| user | User | many-to-one |
| joinedAt | Instant | |

CRUD: a `User` can join/leave their own membership. Member-list read is scoped — only members of the same church can list each other.

### `Article`
| Field | Type | Notes |
|---|---|---|
| id | Long | PK |
| title, body | String | |
| denominationTags | Set\<String\> | controlled taxonomy — see Prompt 22 |
| topicTags | Set\<String\> | |
| author | User | |
| published | boolean | |

CRUD: `PLATFORM_ADMIN` (or a future `CONTENT_EDITOR` role — decide in Prompt 19).

### `Survey`, `Question`, `Response`
| Entity | Field | Type | Notes |
|---|---|---|---|
| Survey | id, title, active | Long, String, boolean | only one `active` survey served at a time |
| Question | id, survey, text, order | Long, Survey, String, int | many-to-one to `Survey` |
| Response | id, question, user (nullable), answer | Long, Question, User, String | `user` nullable to allow anonymous survey-taking |

CRUD: `PLATFORM_ADMIN` authors surveys/questions; any visitor can submit `Response`s.

### `Report`
| Field | Type | Notes |
|---|---|---|
| id | Long | PK |
| reporter | User | |
| targetType | enum | `CHURCH`, `USER` |
| targetId | Long | |
| reason | String | |
| status | enum | `OPEN`, `ACTIONED`, `DISMISSED` |

CRUD: create by any authenticated `User`; review by `PLATFORM_ADMIN`.

## Dependency order for implementation

1. ~~`User` + `Role`, `VolunteerInvite`~~ — **done.**
2. ~~`Claim`, `ChurchStaff`~~ — **done.** `ChurchService.update`/`delete` now check ownership through these.
3. ~~`ChurchImage`, `Event`, `Sermon`~~ — **done.** Built against `ChurchStaff`/`ChurchPermission` for write permissions.
4. ~~`ChurchSubmission`~~ — **done**, including the scraper's ingestion path (`apps/scraper`'s discovery/enrichment/pipeline submits through this same table, tagged `"scraper"`).
5. `Member` — needs `User`; independent of everything else here, still not built.
6. `Article`, `Survey`/`Question`/`Response` (Faith Formation, Prompts 19–20) — independent of church data, only need `User`.
7. `Report` (Vetting, Prompt 23) — needs `User`, `Church`.
