# is whatever out yet?

A multi-item release tracker hosted on GitHub Pages at [iswhateveroutyet.com](https://iswhateveroutyet.com).

Tracks AI models, games, people, and other things that may or may not be out yet.

There are redirects to this site from other sites I bought, including but not limited to... 
* [isclaudesonnet5outyet.com](https://isclaudesonnet5outyet.com/) -> https://iswhateveroutyet.com?search=Claude+Sonnet+5
* [isclaudefable5outyet.com](https://isclaudefable5outyet.com/) -> https://iswhateveroutyet.com?search=Claude+Fable+5
* [isgrandtheftauto6outyet.com](https://isgrandtheftauto6outyet.com/) -> https://iswhateveroutyet.com?search=GTA6
* [ispersona6outyet.com](https://ispersona6outyet.com/) -> https://iswhateveroutyet.com?search=Persona+6

## Why did you make this?

Because I think I am very funny and clever and you should laugh at my jokes and clap.

I don't always follow the shows and games and such that I'm vaguely interested in, so maybe it's good to have a place that reminds me about them instead of subscribing to 100 email lists and subreddits and turning on notifications on social media sites.

Plus, meh, basically I just want to play around with different AI models and stuff to see how good it is at building frontends and everything end-to-end, reading API docs for me, etc. The app is relatively simple and has a lot of boilerplate code just doing basic CRUD. It's a fun learning exercise in my freetime while I'm goofing off. I don't usually code for free, but having a few public GitHub repositories might make me look cool to someone if shit goes south, y'know?

## How does it work?

Basically, I just have the checks hit some of the various read-only / public APIs or hit a website directly and scrape for content to see if something is out yet. 

Some are jokes, some are not. Some are live-updated, some aren't. I think I am very funny and clever.

* Animes check AniList's GraphQL APIs - https://docs.anilist.co/
* Games check the Internet Game Database (IGDB) via a Twitch dev token - https://api-docs.igdb.com
* People check Wikipedia
* Big blockbuster movies usually have pretty static release dates so they're just a countdown timer
* Homestar Runner checks their sitemap.xml
* etc.

Checks run every 30 minutes via GitHub Actions and write results to per-category JSON files under `data/` (plus a tiny `data/index.json`), which the frontend reads. If I start hitting rate-limits, then maybe I'll split out the checks or something and make this better, or I might not. Maybe I'll make an actual app with like KMP or something.

If something changes, the runner will also send out notifications on the topics to people who are subscribed to them, so they should receive it if the page is open on their desktop or if they installed the PWA. Or maybe if I make an actual app. IDK. I just am goofing around.

## Wait, there IS an actual app now?

Yeah. [`app/`](app/) is a Kotlin Multiplatform + Compose Multiplatform version of the site — same
cards, same countdowns, same jokes — that builds for Android, iOS, and desktop from one shared UI.
It reads the exact same `data/` JSON off GitHub Pages (the static site remains the backend), and
the same Cloudflare Worker fans push notifications out to browsers *and* phones. See
[`app/README.md`](app/README.md) for building it and what accounts you'd need to actually ship it
to a store.

## Buy Me a Coffee

If you thought my jokes were funny, here ya go, here's the Buy Me a Coffee link.

<img width="200" height="200" alt="bmc_qr" src="https://github.com/user-attachments/assets/dc28f229-310a-47c6-b7c3-2e2ec83f5dd3" />

[Buy Me a Coffee](https://buymeacoffee.com/sfryslie) 

## MIT License

I don't *really* care about this beyond me just shitposting on a week I have off from work, so if you want to copy it and do the same thing, I mean I just was vibecoding so, strictly speaking, this was probably just stolen code from somewhere else anyway and you can vibecode a new version on a new model trained on this repo anyway. 

It would be hypocritical to assume copyright privileges on a basic idea that was built as a joke with AI assistance. ¯\\\_(ツ)_/¯

Though if you fork the repo, steal the idea, the code, and my jokes verbatim to make money off of it and close source it, you're an asshole.
