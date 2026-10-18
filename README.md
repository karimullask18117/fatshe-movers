# Fatshe Packers and Movers

A small Java web server for the Bangalore packers and movers page. It serves the page, keeps the rates and business details in one file, and saves quote requests to a CSV you can open in Excel.

It uses only the JDK. You need Java 17 or newer (built and tested on Java 21). No Maven, no libraries.

## Run it

### macOS / Linux

```
./run.sh
```

### Windows PowerShell

```
./run.ps1
```

Open http://localhost:8080. Use `PORT=9000 ./run.sh` or `$env:PORT = 9000; ./run.ps1` for another port.

## Change the content

Everything you edit is in `config.properties`. Restart the server after a change.

- Phone numbers, WhatsApp number and office hours.
- `address`, `email`, `gst`, `insurance`. They are empty now, and the page hides them until you fill them in. Add `insurance` only when you have a real policy, because it adds a "What happens if something is damaged?" answer to the page.
- Routes, goods and rates. `22000+` means "from Rs 22,000", `28000-32000` is a range, and no entry shows "Quote on survey".

A typo in a rate stops the server at startup with a message that names the line.

The page text itself (headings, services, steps, questions) is in `public/index.html`. Words in double braces, such as `{{phoneJio}}`, are filled in from the config.

## Read the quote requests

Each request is saved to `data/quotes.csv` with a reference number such as `FQ-20261019-0001`. To download it from the server, set a token of at least 16 characters:

```
ADMIN_TOKEN=choose-a-long-secret ./run.sh
curl -H "Authorization: Bearer choose-a-long-secret" https://your-site/api/quotes -o quotes.csv
```

Without `ADMIN_TOKEN` the download is switched off. Back up the `data` folder. It holds customers' names and mobile numbers, so keep it private.

## What the server protects against

- Form input is checked on the server: mobile number, date, route and goods must be valid. Anything else is refused.
- Each visitor can send 8 requests per 10 minutes, and the whole site 300 per hour.
- A hidden form field catches simple bots. They get a fake success and nothing is saved.
- Cells that start with `=`, `+`, `-` or `@` are neutralised in the CSV so a hostile request cannot run a formula in Excel.
- Only files in `public/` are served. The config, source code and data are never reachable.
- Responses carry a strict Content-Security-Policy, and the page makes no calls to other sites except Google Fonts.

## Put it on the internet

1. Run it on any server with Java 17 or newer, for example a small VPS.
2. Put it behind HTTPS with Caddy or nginx and forward to port 8080. Set `TRUST_PROXY=true` so the per-visitor limit uses the real visitor address.
3. Set `BIND=127.0.0.1` so only the proxy can reach it.
4. Keep it running with a service manager such as systemd.

Settings you can pass as environment variables: `PORT`, `BIND`, `CONFIG`, `PUBLIC_DIR`, `DATA_DIR`, `ADMIN_TOKEN`, `TRUST_PROXY`.

## Layout

```
config.properties        details and rates
run.sh                   compile and start
public/                  index.html, style.css, app.js
src/com/fatshe/movers/   Main (routes, API), Site (config), Page (template),
                         QuoteStore (CSV), RateLimiter, Json
data/quotes.csv          created on the first request
```
