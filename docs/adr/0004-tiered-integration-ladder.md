# Integrated Tools join via a three-tier adoption ladder

Existing tools (e.g., the firm's legacy reporting tools) integrate with Dataflow through a
published, tiered contract rather than a single all-or-nothing API spec: **Tier 3 —
Wrapped** (tool's feeds appear in the Dataflow UI via its API; tool keeps scheduling,
transforms, delivery), **Tier 2 — Orchestrated** (Dataflow/Kestra owns scheduling and
triggers the tool's jobs via its API + Platform Events; tool still transforms and
delivers), **Tier 1 — Native** (the feed is migrated onto platform Engines and Delivery,
proven by an automated Parity Proof). Each tier is a stable resting point, migration is
feed-by-feed, and one tool can sit at different tiers for different feeds — because "rip
out your delivery layer" is not a viable first ask of a legacy team, but "expose a trigger
endpoint" is.

## Consequences

- The integration surface tools implement at Tier 2 is the CloudEvents Platform Event
  contract — not per-tool inventions. The event backbone is therefore a prerequisite for
  the integration milestone.
- Tiers 3 and 2 are the documented exceptions to ADR-0001 (platform-owned Delivery);
  unified delivery/audit is what Tier 1 buys.
- The contract is published to other teams once real tools adopt it — changing it later is
  expensive. Version it from the start.
