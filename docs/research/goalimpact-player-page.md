# What a goalimpact.com player page actually shows

Research for [#37](https://github.com/JonasDockx/FootballImpact/issues/37), a
child of the wayfinder map [#31](https://github.com/JonasDockx/FootballImpact/issues/31).
Read 2026-07-31.

**Read this first.** There is no such thing as *the* goalimpact.com player page.
The site has been three different things, and the artefact this project is
copying has changed shape twice:

| era | where a player's chart lived | horizontal axis | series |
|---|---|---|---|
| 2013–2015, Blogspot blog | blog posts, and partner sites LigaInsider / PremierInsider | **calendar year only** | Goalimpact, Peak Goalimpact |
| ~2017, sales deck / squad reports | slides shown to clients, reproduced in the Übersteiger interview | **age on top, calendar year on the bottom** | Aktueller GI, Prognose Peak-GI, Über-/Unterperformance |
| 2022–today, the web product | a logged-in product page at `goalimpact.com/players` | **age and year on one combined "Age/Year" axis** | Goalimpact, Goalimpact Forecast, (in social graphics) Last 24 Months |

**Reachability, stated plainly.** goalimpact.com is up and is a commercial B2B
product site. The old Blogspot blog is **gone** — `goalimpact.com/blog/...` and
`goalimpact.com/2015/04/...` return 404/308 today; everything from that era in
this note comes from the Internet Archive, with the capture timestamp given.
The live product's player pages are **behind a login**: `/players` renders a
heading, a filter control and an empty list, and no player data is served to an
unauthenticated fetch. So the modern player page is described here from a
first-party marketing screenshot and from first-party prose, **not** from the
page itself. Every such inference is flagged.

---

## 1. The two lines, and what they are called

The names have changed over time, and all three namings are first-party.

**2015, on the blog** ([goalimpact.com, "How to read a Goalimpact Chart?", April
2015](http://www.goalimpact.com/2015/04/how-to-read-goalimpact-chart.html),
Wayback capture `20150416011303`) — stated in prose, not inferred:

> The thick line shows the Goalimpact at that time. This is the original
> estimate not using any future games. […] The expectations on how good a Zlatan
> will be in future, are derived from the Peak Goalimpact (thin dashed line) and
> the aging curve of field players.

So: **Goalimpact** (thick solid) and **Peak Goalimpact** / **PeakGI** (thin
dashed). The same post states the update rule for the second line:

> If the team results of all of Zlatan's games are better than expected by his
> Goalimpact, the PeakGI line will raise. It will do that whenever the player
> overachieves original expectations, independent if he passed his peak already
> or not.

**2017, German** ([Übersteiger, "Timbos kleine Taktikschule – Heute:
Goalimpact"](https://blog.uebersteiger.de/2018/03/19/timbos-kleine-taktikschule-heute-goalimpact/),
interview with Goalimpact's Thorsten Wittmütz, print December 2017): the chart
reproduced there is labelled **Aktueller GI** (thick red) and **Prognose
Peak-GI** (thin red), plus a third series **Über-/Unterperformance** (light
blue, very noisy). The intro states the metric is published as three numbers:

> Der Goalimpact wird in verschiedenen Werten dargestellt: dem aktuellen
> Goalimpact, dem maximalen Goalimpact und dem Goalimpact berechnet über die
> letzten zwei Jahre.

**2022, on the company's own Twitter/X account** ([@Goalimpact, 30 Sep 2022](https://x.com/Goalimpact/status/1575824438033281024),
"What is Goalimpact and How to read it? A thread" — the attached Cristiano
Ronaldo graphic): legend reads exactly

- **Goalimpact** — solid red
- **Peak** — dotted red
- **Last 24 Months** — dashed blue

**2023, product-era prose** ([Moneyball Letters, "Goalimpact 1x1", Dr. Patrick
May, Goalimpact's own Substack, 4 Sep 2023](https://goalimpact.substack.com/p/goalimpact-1x1)):

> The blue line details his career average Goalimpact rating over time, while
> the red-dotted line is the career Goalimpact predicted rating based on average
> player development.

**In the live product** the card is titled **"Goalimpact Forecast"** and its
legend reads **"Goalimpact"** (solid) and **"Goalimpact Forecast"** (dotted).

**Judgement, not a stated fact:** the third series is almost certainly one
object renamed — the German *"Goalimpact berechnet über die letzten zwei Jahre"*
is the *"Last 24 Months"* of the 2022 legend, and the 2017 chart's
*"Über-/Unterperformance"* is the same rolling series under an older label. The
two are never equated in any source I found; the identification is mine, from
the matching colour, the matching noisiness, and the interview naming exactly
three published values.

---

## 2. The axes — and yes, the old charts were on calendar date

This was the specific question, and the answer changed.

**2015 blog charts** (Zlatan Ibrahimović, Theo Walcott, Cristiano Ronaldo, Karim
Benzema — the four images in the "How to read a Goalimpact Chart?" post):

- Horizontal axis is **calendar year**, and nothing else. Zlatan's runs
  `2001 … 2015`; Walcott's `2005 … 2015`; Ronaldo's `2002 … 2015`; Benzema's
  `2005 … 2015`. Age appears only inside callout text ("Goalimpact with 26 years
  expected that time", "2008, the 18yo Walcott…").
- Vertical axis is bare numbers with **no axis title at all** — ticks every 10
  points (Zlatan 90–180, Walcott 90–190, Ronaldo 90–190, Benzema 90–210).
- Chart title is simply the **player's name**.
- The current value sits in a **circled badge at the right-hand end of the solid
  line** (Zlatan `154`, Walcott `193`, Ronaldo `181`, Benzema `199`).
- A large grey **"Goalimpact™" watermark** across the plot area.
- *Inferred from the images:* the Peak line is drawn **dashed at the very start
  of a career and solid thereafter** — the dashed head presumably marks the
  stretch where the player has too little playing time for a firm estimate.
  Never stated.
- No coloured quality bands anywhere on the 2015 charts.

**2017 chart** (Timo Schultz, in the Übersteiger interview) — a genuinely
double-scaled chart:

- **Age along the top** (20 … 36) and **calendar year along the bottom**
  (1997 … 2013), on the same plot.
- Left vertical axis: Goalimpact points, 80–190.
- **Right vertical axis: world rank** — labelled `Top20`, `Top100`, `Top200`,
  `Top500`, `Top1000`, `Top2000`, `Top5000`, `Top10000`, at unevenly spaced
  heights. *Read off pixel positions, so approximate and inferred:* Top20 ≈ 181,
  Top100 ≈ 172, Top200 ≈ 165, Top500 ≈ 151, Top1000 ≈ 141, Top2000 ≈ 130,
  Top5000 ≈ 121, Top10000 ≈ 111.
- Circled current value `124` at the right end, same device as 2015.

**2022 social graphic** (Ronaldo): horizontal axis is **age only** (18 … 38),
vertical axis 0–250 in steps of 50, no axis titles, player name as the title,
two badges bottom-right — a **dashed-outline circle `186`** (Peak) and a
**filled circle `158`** (current Goalimpact).

**The live product** (Alphonso Davies screenshot, see §3): one axis titled
**"Age/Year"** carrying **both** — age `16…29` on the upper tick row and
calendar year `2017…2030` on the lower row. Vertical axis is titled
**"Goalimpact"**, 25 → 225 in steps of 25.

So, for #36: the original started on **calendar date**, and by the product era
had moved to **age with the year kept underneath as a second tick row**. Both,
in the end — but age is the one the forecast is drawn against, because the
forecast runs into the future to age 29/2030.

---

## 3. What else is on the product player page

Source: the Alphonso Davies marketing image published in Goalimpact's own
Substack post ("Goalimpact 1x1", 2023). This is a **screenshot of the product**,
so everything here is read off an image, not off a live page. It is the single
best look at the modern page I could obtain.

**Left column — the player card:**

- Player photograph and name
- `Position` — *Defender*
- `PERSONAL INFO`: `Date of Birth` *02/11/2000 (22)*, `Nationality` *Canada*,
  `Height` *183 cm*, `Foot` *Left*
- `STATISTICS`: `Minutes played` *19155 Minutes*, `Matches` *270*

**Right column — the chart card:**

- Title **"Goalimpact Forecast"** with an ⓘ info affordance (its tooltip text is
  not obtainable without a login)
- Two headline pills: **`Peak` 175** (green) and **`Current Goalimpact` 158**
  (purple)
- The same two values repeated as **circular markers on the lines** — 158 at the
  end of the solid line, 175 at the top of the dotted forecast
- Legend with five entries: `Goalimpact` (solid), `Goalimpact Forecast`
  (dotted), and three **shaded quality bands** — `World Class` (green),
  `Champions League` (blue), `Top 5 Leagues` (amber). The band swatches carry
  tick marks, which *suggests* they are toggleable; not stated.

**The bands are the finding this project should care about most.** They are not
horizontal. Each band rises steeply from age 16 and flattens by roughly age
24 — they are **age-dependent envelopes**, i.e. "the trajectory a player of this
age has to be on to end up at that level", not fixed thresholds. Reading their
right-hand (mature) edges off the image, approximately: *Top 5 Leagues*
≈ 128–150, *Champions League* ≈ 150–175, *World Class* ≈ 175–220+. Those
boundary numbers are inferred from pixels; the band *names* are printed in the
legend and are certain.

**Also inferred, and worth stating because it is a real change:** on the Davies
page the dotted *Goalimpact Forecast* line appears **only to the right of
today** — it is a forward projection from the present to age 29. On the 2015 and
2017 charts the Peak line ran the **whole length of the history** alongside the
current line. If that reading is right, the modern product dropped the
historical Peak track. I could only see one product chart, so treat this as a
single-observation inference.

---

## 4. Reference points quoted for the scale

Everything below is a quoted number from a first-party source. Note that the
figures already recorded on #8 come from the Übersteiger interview and **not**
from the site — that ticket's framing was correct.

From the [Übersteiger interview](https://blog.uebersteiger.de/2018/03/19/timbos-kleine-taktikschule-heute-goalimpact/)
(Thorsten Wittmütz, print December 2017):

| statement | value |
|---|---|
| World class | Goalimpact **> 160**; about **200 players worldwide** |
| 1. Bundesliga average | **135** |
| 2. Bundesliga average | **116–118** |
| Highest current Goalimpact | Paul Pogba **200** |
| Highest in the Bundesliga | Thomas Müller **196** |
| Fabian Klos, best in 2. Bundesliga | **175**, described as top-100 in the world |
| A "relatively reliable" rating | after **~100 recorded matches** |
| Peak age | **26** on average, and the decline is slower than the rise |

From the [Substack "Goalimpact 1x1"](https://goalimpact.substack.com/p/goalimpact-1x1) (2023):

- **140 or higher** "typically implies top-five European League potential"
- **above 170** for "the top athletes globally"
- rating starts at **1,000 minutes** of playing time
- database is **over 500,000 players**; Peak is "around the age of 26"

From the live site (2026-07-31): "over 500000 players", "over 1000 leagues",
"over 70 countries".

From [@Goalimpact on X, 5 Apr 2015](https://twitter.com/Goalimpact/status/585927012306530304),
all-time highest Peak: Thomas Müller 218, Puskás 211, Luc Nilis 210, Cruyff 209,
Santamaría 208, Özil 202, Messi 202, Gorostiza 202. *Not verified against the
site; taken from search-result text of the company's own tweet.*

Note the drift: **>160 world class in 2017, >170 in 2023**, and the product's
green band starts around 175. The threshold is not a constant of the model. Our
ADR 0011 bands (100/140/150/170) sit inside this range but are flat, where
theirs bend with age.

---

## 5. How players are found

**2013–2015.** goalimpact.com had **no player search**. Per-player charts were
published on **partner sites** — the blog's ["Reader's Notice: Publication of
Goalimpact"](http://www.goalimpact.com/2015/05/readers-notice-publication-of-goalimpact.html)
(Wayback `20150507235657`) announces:

> We are happy to announce that the results of the new Goalimpact algorithm are
> published for the Premier League and for the Bundesliga on our partner sides
> PremierInsider (Premier League) and LigaInsider (Bundesliga)

On goalimpact.com itself you found a player by reading a **Top-50 list post**,
or by asking: the April 2015 post exists precisely because "we received a lot of
questions to individual players".

**2016–2017, as a service.** Finding was a filter query run *by Goalimpact for
the client*. The archived ["Identifying Talents First"](http://www.goalimpact.com/recruitment)
page (Wayback `20161107190441`) names the filter axes exactly:

> By setting individual filter criteria for age, league, current Goalimpact,
> predicted peak Goalimpact, position and market value, all appropriate players
> will be found.

**Today.** `goalimpact.com/players` (fetched 2026-07-31, logged out) is titled
**"What is the Rating of...?"** and contains:

- a control with `aria-label="Search Players"` — a free-text player search
- a `Filters` button (`aria-label="Open filters"`)
- a section **"Public players"** with **"Select list to add players"** and a
  **"Select list"** control

So the modern answer is **search box + filters + saved/curated lists**, and the
default empty state shows *no* players until you pick a list. The filter fields
themselves are not visible logged out.

---

## 6. Things they show that this project has no equivalent of

Ordered by how much they'd change the viewer.

1. **Age-dependent quality bands** (*World Class* / *Champions League* /
   *Top 5 Leagues*). Ours are flat lines at 100/140/150/170. Theirs curve with
   age, so a 17-year-old is judged against a 17-year-old's required trajectory.
   This is the biggest structural difference, and it interacts with #21.
2. **A world-rank second vertical axis** (`Top20 … Top10000`, 2017 chart). A
   direct answer to "is 154 good?" that costs nothing but a sorted array. We
   have no rank anywhere.
3. **A third series: "Last 24 Months" / "Über-/Unterperformance"**, deliberately
   noisy, carrying the volatility that is kept *out* of the headline line. ADR
   0011 considered a rolling recent-form window and rejected it as the main
   line; it is exactly this. It would be a *second* line, not a replacement.
4. **A player identity card** — photo, position, nationality, height, foot,
   date of birth with current age, career minutes, career matches. We have the
   minutes; the rest is either in the spine unused or absent.
5. **The two headline numbers as pills** (`Peak`, `Current Goalimpact`) with the
   same values echoed as labelled dots on the lines. Cheap, and it is what makes
   the chart readable without an axis lookup.
6. **A forecast that runs into the future** — to age 29/2030 on the Davies
   chart, i.e. seven years past the last match. Our chart stops at the last
   match played (#21).
7. **A career-transition annotation layer** — the 2015 charts hand-annotate
   "Move to ManU", "Move to Real Madrid", "Transfermarkt Market value crosses
   1M€". Those are hand-drawn illustrations, not a product feature, but #23's
   club-team bands are chasing the same readability.
8. **Squad tables** as the other main artefact: columns
   `Player | Goalimpact | Peak GI | Age | # of Games`, with loaned players greyed
   out (Übersteiger, image 2). Age is given to one decimal (`26,7`), which is
   the same *age-as-a-continuous-number* choice as the chart axis.
9. **A watermark and a per-player shareable graphic.** Not a feature so much as
   a decision that the chart is the product's face.

---

## 7. What I could not establish

- **The tooltip behind the ⓘ on "Goalimpact Forecast"**, and anything else that
  needs a login: tabs, a match log, similar-player lists, whether the chart is
  interactive, what the filter fields are. `/players` serves no player data
  logged out.
- **Whether the modern page still draws the historical Peak track.** One
  screenshot suggests it does not; one screenshot is not enough.
- **The exact band boundaries and the rank-axis mapping.** Both are read off
  pixels.
- **Any statement by Jörg Seidel himself about the chart.** Everything
  chart-specific here is written by the company — the unsigned 2015 blog, the
  Wittmütz interview, the May Substack. Seidel's own first-party writing that I
  reached ([goalimpact.com/story](https://www.goalimpact.com/story)) is about the
  model's origin, not the page.
- **Whether LigaInsider / PremierInsider still host per-player charts.** Not
  chased.
- **Full text of the 2022 "How to read it" thread** — only the head tweet and
  its image were retrievable; X blocks unauthenticated reads of the replies.

## Sources

- goalimpact.com, ["How to read a Goalimpact Chart?"](http://www.goalimpact.com/2015/04/how-to-read-goalimpact-chart.html), April 2015 — via Wayback `20150416011303`, with the four chart images from Blogspot
- goalimpact.com, ["Reader's Notice: Publication of Goalimpact"](http://www.goalimpact.com/2015/05/readers-notice-publication-of-goalimpact.html), May 2015 — via Wayback `20150507235657`
- goalimpact.com, ["Identifying Talents First"](http://www.goalimpact.com/recruitment) — via Wayback `20161107190441`
- [goalimpact.com](https://www.goalimpact.com/), [/players](https://www.goalimpact.com/players), [/story](https://www.goalimpact.com/story) — fetched live 2026-07-31
- Übersteiger, ["Timbos kleine Taktikschule – Heute: Goalimpact"](https://blog.uebersteiger.de/2018/03/19/timbos-kleine-taktikschule-heute-goalimpact/) — interview with Thorsten Wittmütz, print December 2017, plus its three images
- Moneyball Letters (Goalimpact's own Substack), ["Goalimpact 1x1"](https://goalimpact.substack.com/p/goalimpact-1x1), Dr. Patrick May, 4 September 2023, plus the Alphonso Davies product screenshot
- [@Goalimpact on X, 30 September 2022](https://x.com/Goalimpact/status/1575824438033281024) — "What is Goalimpact and How to read it?" and its Cristiano Ronaldo chart
