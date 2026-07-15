# Reconstruct on-pitch state by event-stream replay

We determine who was on the pitch at each goal by replaying a match's events in
their recorded order, maintaining a live on-pitch set per team (seeded from the
Starting XI, updated on substitutions and red cards), and snapshotting that set
when a goal event occurs. On-pitch minutes fall out of the same pass.

Chosen over clock-based interval math because event order resolves the key
boundary case — a substitution and a goal in the same minute — exactly, whereas
minute-granularity interval containment cannot tell which happened first. It also
computes credit/blame and on-pitch minutes in a single pass, and teaches evolving
stream state, which suits the learning goal.
