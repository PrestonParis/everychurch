# Product Scope — Talent Acquisition & Education

The EveryChurch brief names two pillars: Faith Formation and Education


## Education — options (still open, recommendation stands)

1. **Structured courses**, distinct from the Faith Formation articles/survey (docs/DATA_MODEL.md `Article`/`Survey`) — sequenced lessons with progress tracking, possibly leading to a completion certificate. Needs a `Course`/`Lesson`/`Enrollment` model, meaningfully heavier than an article feed.
2. **Just a label for Faith Formation content.** "Education" isn't a separate feature — it's the framing for the articles/survey/Bible-access work already scoped in the brief. No new entities; the doc/marketing copy should just stop implying it's a fourth pillar.
3. **Out of scope for now.** Explicitly defer.

## Recommendation

**Education = option 2** (fold into Faith Formation, no new entities) remains the recommendation — still unconfirmed, revisit if the project owner wants Education as a genuinely separate feature.

**Talent Acquisition = confirmed out of scope**, per the project owner directly (not just a recommendation anymore).

## Downstream impact

- Every prompt from Phase B onward (accounts, church profiles, volunteer workflow, scraper, Faith Formation, vetting, promotion) is written against the elaborated pillars only and does **not** build anything labeled "Talent Acquisition."
- The Volunteer role/program is unaffected by dropping Talent Acquisition — volunteers are a recruitment/data-collection mechanism for the platform itself (see the volunteer-invite auth flow in ARCHITECTURE.md), not a hiring feature.
- If Education becomes a real separate pillar (option 1) rather than folding into Faith Formation, `Article`/`Survey` in docs/DATA_MODEL.md stay as-is and a new `Course`/`Lesson`/`Enrollment` set gets added alongside them, not instead of them.
