# Measuring Cloudflare's free limits on staging

Status: prepared and rehearsed against a local server. Nothing has been deployed, and no Cloudflare
account exists yet. The apps and real users are not involved at any point.

## The question

How many WebSocket messages does the free plan allow per day? Cloudflare's pricing page documents a
20:1 ratio, but its examples are for the paid plan, and its analytics show message counts without
saying how they are billed. Only reaching the real limit answers this.

| Messages sent before the limit | Meaning | Average devices that fit |
| --- | --- | --- |
| About 2,000,000 | 20:1 applies on free | About 925 |
| About 100,000 | 1:1 on free | About 80 |
| Anything else | A different rule | Work it out from the result |

## What this costs and risks

- **Cost:** ₹0. It's a dedicated free account with no card, so going over a limit can only produce errors.
- **Your project:** untouched. It doesn't use your Firebase project, real users, or the apps. Only
  public Google signing keys are ever fetched, and only if a real Firebase token is presented.
- **Hitting the limit on purpose:** only this throwaway account stops working, until 05:30 IST.
- **Secrets:** the staging secret is generated locally, kept in an ignored file, and never appears in
  chat or the repository. The staging login exists only where that secret is set, and its accounts
  can't be real ones. It must never be set on production.

## What you do

1. Create a Cloudflare account with an email you'd be happy to own Auralis's infrastructure with
   long term. Don't add a card, and don't choose Workers Paid.
2. In a terminal in `connect-server`, sign in. This opens your browser:

   ```bash
   npx wrangler login
   ```

3. Tell me to deploy staging. That approval covers only the staging Worker below.

## What I then do

1. Deploy, then generate and set the secret. Neither prints it:

   ```bash
   npx wrangler deploy --env staging
   ```

   ```bash
   node tools/measure.mjs secret wss://auralis-connect-staging.<your-subdomain>.workers.dev | npx wrangler secret put STAGING_SECRET --env staging
   ```

2. Run the measurements. Each is logged to `measure-results.jsonl`.

## Measurements

**Day 1, from about 05:45 IST, on fresh daily counters.**

| # | Command | Answers | About |
| --- | --- | --- | --- |
| 1 | `check` | It works, and real round-trip time from India | 1 min |
| 2 | `connects 100` | What one connection costs | 1 min |
| 3 | `messages 2000` | Whether analytics show 2,000 or 100 requests | 5 min |
| 4 | `idle 60` | Pings stay uncharged, and how long idle sockets last | 1 h |
| 5 | Read the dashboard and `usage` | Requests, rows, CPU and duration against the 5 runs above | 5 min |

**Day 2, from about 05:45 IST, so the limit test starts clean.**

| # | Command | Answers | About |
| --- | --- | --- | --- |
| 6 | `to-limit` | Messages before the limit, and what clients see when it trips | Up to 4 h |
| 7 | `check`, repeated after 05:30 IST on Day 3 | Recovery at the daily reset | 1 min |

The tool sends about 150 messages a second, just under the server's per-socket limit, and stops at
the first refusal, closed socket or long silence. It then tries one fresh connection to record the
failure.

**Later, during app wiring.** Connecting with a real Firebase token, to confirm verification fits the
free plan's 10 ms CPU limit. That needs the real sign-in path, so it isn't part of this run.

## What it won't tell us

- Real listener behaviour, because the capacity table assumes messages per device.
- Performance from other regions, or under many accounts at once.
- Android or Windows behaviour, which needs the app wiring first.

## Decisions after the results

| Result | Next step |
| --- | --- |
| Near 2,000,000 | Continue with Cloudflare and move on to wiring the apps |
| Near 100,000 | Stop and choose: batching and reduced reporting, Workers Paid, or Oracle. Cloudflare's free plan alone wouldn't scale |
| Failure trips open sockets or locks accounts in a way not documented | Stop and review before anything else |
| Unexpected charge or request for payment | Stop and delete everything. This shouldn't be possible on free |

## Stopping and cleaning up

Any failure above stops the run. To remove everything, including stored test data:

```bash
npx wrangler delete --env staging
```

Staging stays up only until you've seen the results. Deleting it doesn't affect anything else, and
keeping the account costs nothing.

## Separate approvals needed later

This plan covers staging only. Production deployment, wiring into the apps, turning the flag on for
anyone, deploying Firestore rules, and pushing or releasing all still need your go-ahead.
