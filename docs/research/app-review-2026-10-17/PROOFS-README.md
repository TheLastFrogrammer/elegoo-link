# Link Workshop — feature-gap review

Open feature-gaps.html for the readable report; it requires no scripts or external assets.
FEATURE-GAP-REVIEW.md contains the full review with pinned source links.
FEATURE-GAP-HANDOFF.md is the implementation brief for Claude.
FEATURE-GAP-VALIDATION.md explains the evidence and limits.

proofs/ contains eight current-behavior reproductions, their logs, source hashes and explicit host boundaries.
Passing these proofs means a gap was reproduced, not fixed.
The runner needs the app Java source at dde76993f593f99b58264afaeb855cb718ddd2cc,
JSON-java tag20240303 source and Java17+ with the compiler module.
The Android app and third-party dependency sources are not bundled.

Hosted report: https://cc2-workshop-concepts.bagels.chatgpt.site/feature-gaps.html
No native app code, account state or printer settings was changed in this study.
