Fixture for `resolve()` typing (issue #1). Load `transaction.json` first, then `late/`.

- Every reference in the bundle is a `urn:uuid:` link, so its type is not in the string.
- `Observation.subject` points at a Patient twice and a Group once: a multi-target path.
- `Observation.performer` is identifier-only; only `Reference.type` names the type.
- `Encounter.participant.individual` is a bare id whose target lives in `late/`, so it
  can only be typed once that later load has happened.
