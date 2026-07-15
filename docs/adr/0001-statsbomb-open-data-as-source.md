# StatsBomb Open Data as the data source

We compute GoalImpact from StatsBomb Open Data (free JSON files on GitHub),
parsed locally, rather than scraping fbref or calling a live REST API.

Chosen because this is primarily a Java *learning* project: local JSON means no
API keys, no rate limits, no Cloudflare/HTML fragility, and complete,
reproducible lineup/substitution/goal/card data. The accepted cost is scope —
we can only compute the metric for the competitions StatsBomb ships, not
arbitrary matches. Swapping in a live API later is a well-scoped upgrade.
