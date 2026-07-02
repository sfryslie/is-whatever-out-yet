import java.time.LocalDate

// ── Item catalogue ────────────────────────────────────────────────────────────
//
// Category names are display strings — keep them plural or non-specific nouns. They also feed
// categorySlug() for push topics and the per-category data file names (data/<slug>.json), so
// renaming a category breaks existing push subscriptions to its topics and moves its data file.

// Infobox-anchored "still locked up" markers, OR-matched against the full rendered Wikipedia HTML
// by Check.WikipediaHtml: the card stays "No." while ANY is present and flips only when all are
// gone. Capitalized + tag-bounded on purpose so they match infobox value/label cells, not the
// lowercase body prose that mentions past incarceration forever (which would freeze the card).
// Covers the incarcerated↔imprisoned wording editors swap between. Defined before ITEMS so it's
// initialized first.
private val INCARCERATION_MARKERS = listOf("Incarcerated at", ">Incarcerated<", ">Imprisoned<")

val ITEMS = listOf(
    // AI — Anthropic (live API check)
    Item("claude-fable-5",  "Claude Fable 5",  "AI", Check.Anthropic("claude-fable-5")),
    Item("claude-sonnet-5", "Claude Sonnet 5", "AI", Check.Anthropic("claude-sonnet-5")),
    Item("claude-opus-5",   "Claude Opus 5",   "AI", Check.Anthropic("claude-opus-5")),
    Item("claude-haiku-5",  "Claude Haiku 5",  "AI", Check.Anthropic("claude-haiku-5")),
    Item("mythos",          "Claude Mythos 5",   "AI", Check.Anthropic("mythos"), "No.", "Not for us plebs."),

    // AI — other vendors
    Item("gpt-5-6",        "GPT-5.6",         "AI", Check.OpenAI("gpt-5.6"), "No.", "Sol/Terra/Luna Soon™ — I would've thought these were new Pokémon games."),
    Item("gemini-3-1-pro", "Gemini 3.1 Pro",   "AI", Check.Gemini("gemini-3.1-pro")),
    Item("grok-5",         "Grok 5",           "AI", Check.Grok("grok-5"), "No.", "... but do you care?"),
    Item("agi",            "AGI",              "AI", Check.Hardcoded, "No."),

    // Video Games
    Item("half-life-3",     "Half-Life 3",     "Video Games", Check.Hardcoded, "No."),
    Item("ricochet-2",      "Ricochet 2",      "Video Games", Check.Hardcoded, "No."),
    Item("team-fortress-3", "Team Fortress 3", "Video Games", Check.Hardcoded, "No.",  "<a href=\"https://store.steampowered.com/app/3545060/Team_Fortress_2_Classified/\" target=\"_blank\" rel=\"noopener\">TF2 Classified is kinda fun, though.</a>"),
    Item("left-4-dead-3",   "Left 4 Dead 3",   "Video Games", Check.Hardcoded, "No."),
    Item("portal-3",        "Portal 3",        "Video Games", Check.Hardcoded, "No."),
    Item("palworld-1",      "Palworld 1.0",    "Video Games", Check.ScheduledDate(LocalDate.of(2026, 7, 10))),
    Item("valheim-1",       "Valheim Deep North",     "Video Games", Check.ScheduledDate(LocalDate.of(2026, 9, 9))),
    Item("deltarune-ch5",   "Deltarune Ch. 5", "Video Games", Check.Hardcoded, "Yes.",   "Released June 24, 2026.", since = LocalDate.of(2026, 6, 24)),
    Item("deltarune-ch6",   "Deltarune Ch. 6", "Video Games", Check.Hardcoded, "No.", "Chapter 5 just came out. Relax."),
    Item("persona-6",       "Persona 6",       "Video Games", Check.Hardcoded, "No."),
    Item("persona-4-revival", "Persona 4 Revival", "Video Games", Check.IGDB("persona-4-revival")),
    Item("gta-vi",          "Grand Theft Auto VI",      "Video Games", Check.IGDB("grand-theft-auto-vi"),
        defaultDetail = "Not for PC though, rip.",
        aliases = listOf("GTA 6", "GTA VI", "GTAVI", "GTA6")),
    Item("how-many-dudes",  "How Many Dudes?",          "Video Games", Check.ScheduledDate(LocalDate.of(2026, 7, 30)),
        defaultDetail = "<a href=\"https://store.steampowered.com/app/3934270/How_Many_Dudes/\" target=\"_blank\" rel=\"noopener\">Demo's out on Steam.</a>"),
    Item("fable-game",      "Fable",                    "Video Games", Check.IGDB("fable--1")),
    Item("elder-scrolls-6", "The Elder Scrolls VI",     "Video Games", Check.Hardcoded, "No.", "I'm excited for the Skyrim Remake for the PS6"),
    Item("huniepop-3",      "HuniePop 3",               "Video Games", Check.Hardcoded, "No.", "I hope it's a roguelite deckbuilder."),
    Item("bge-2",           "Beyond Good and Evil 2",   "Video Games", Check.Hardcoded, "No.", "Announced 2008. Still waiting."),
    Item("kotor-remake",    "Star Wars: KOTOR Remake",  "Video Games", Check.Hardcoded, "No.", "Aspyr's teaser baited me into buying a PS5."),
    Item("onimusha-sword",  "Onimusha: Way of the Sword", "Video Games", Check.IGDB("onimusha-way-of-the-sword")),
    Item("halo-campaign-evolved", "Halo: Campaign Evolved", "Video Games", Check.IGDB("halo-campaign-evolved"),
        defaultDetail = "Early access July 23."),
    Item("cod-mw4",         "Call of Duty: Modern Warfare 4", "Video Games", Check.IGDB("call-of-duty-modern-warfare-4"),
        aliases = listOf("COD MW4", "MW4", "Modern Warfare 4")),
    Item("ff7-revelation",  "Final Fantasy VII Revelation", "Video Games", Check.IGDB("final-fantasy-vii-revelation")),
    Item("bloodborne-2",    "Bloodborne 2",    "Video Games", Check.Hardcoded, "No."),
    Item("bloodborne-pc",   "Bloodborne: Remastered (PC)", "Video Games", Check.Hardcoded, "No."),
    Item("elden-ring-2",    "Elden Ring 2",    "Video Games", Check.Hardcoded, "No."),
    Item("star-citizen",    "Star Citizen 1.0", "Video Games", Check.Hardcoded, "No."),
    Item("marvels-wolverine", "Marvel's Wolverine", "Video Games", Check.IGDB("marvels-wolverine")),
    Item("witcher-4",       "The Witcher IV",  "Video Games", Check.IGDB("the-witcher-iv")),
    Item("truck-kun",       "Truck-kun is Supporting Me From Another World?!", "Video Games", Check.ScheduledDate(LocalDate.of(2026, 7, 29)),
        defaultDetail = "<a href=\"https://store.steampowered.com/app/3642010/Truckkun_is_Supporting_Me_from_Another_World/\" target=\"_blank\" rel=\"noopener\">It's weeaboo isekai crazy goat taxi simulator I guess?</a>"),
    Item("runescape-dragonwilds", "RuneScape: Dragonwilds", "Video Games", Check.ScheduledDate(LocalDate.of(2026, 9, 15))),
    Item("gears-of-war-eday", "Gears of War: E-Day", "Video Games", Check.IGDB("gears-of-war-e-day")),
    Item("enshrouded",      "Enshrouded",      "Video Games", Check.IGDB("enshrouded")),
    // Already-out games — exercise the "hide old stuff" slider at different age buckets.
    Item("silksong",        "Hollow Knight: Silksong", "Video Games", Check.Hardcoded, "Yes.",
        "Silkposting is a art", since = LocalDate.of(2025, 9, 4)),
    Item("deadlock",        "Deadlock",        "Video Games", Check.Hardcoded, "No.",
        "<a href=\"https://store.steampowered.com/app/1422450/Deadlock/\" target=\"_blank\" rel=\"noopener\">Still in Early Access.</a>"),
    Item("bloodlines-2",    "Vampire: The Masquerade - Bloodlines 2", "Video Games", Check.Hardcoded, "Yes.",
        "It's okay. I just like that the devs are The Chinese Room, that's a fun name.", since = LocalDate.of(2025, 10, 21)),

    // Books
    Item("winds-of-winter", "The Winds of Winter",      "Books", Check.Hardcoded, "No."),
    Item("dsm-6",           "DSM-6",                    "Books", Check.Hardcoded, "No.", "<a href=\"https://en.wikipedia.org/wiki/Chatbot_psychosis\" target=\"_blank\" rel=\"noopener\">Chatbot psychosis</a> will likely be in there."),
    Item("doors-of-stone",  "The Doors of Stone",       "Books", Check.Hardcoded, "No."),

    // Anime — AniList-backed (no API key; one batched GraphQL request per run)
    // Premiere-confirmed shows get exact releaseDate; unscheduled ones use vagueDate/Label fallback
    // until AniList locks in a date (that transition fires a push notification).
    Item("rezero-s4-cour2", "Re:Zero Season 4", "Anime", Check.AniList(189046),
        aliases = listOf("Re:Zero S4", "Re:Zero S4 Cour 2", "ReZero Season 4", "Re:Zero kara Hajimeru Isekai Seikatsu")),
    Item("youjo-senki-s2", "Saga of Tanya the Evil Season 2", "Anime", Check.AniList(135865),
        aliases = listOf("Youjo Senki", "Youjo Senki S2", "Youjo Senki Season 2", "Tanya the Evil", "Tanya the Evil Season 2")),
    Item("mushoku-tensei-s3", "Mushoku Tensei: Jobless Reincarnation Season 3", "Anime", Check.AniList(178789),
        aliases = listOf("Mushoku Tensei", "Mushoku Tensei S3", "MT S3", "Jobless Reincarnation Season 3")),
    Item("smoking-supermarket", "Smoking Behind the Supermarket with You", "Anime", Check.AniList(196187),
        aliases = listOf("Smoking Supermarket", "Super no Ura de Yani Suu Futari", "Yanisuu")),
    Item("bleach-tybw-calamity", "BLEACH: Thousand-Year Blood War — The Calamity", "Anime", Check.AniList(185874),
        aliases = listOf("BLEACH", "Bleach", "Bleach TYBW", "Bleach Thousand-Year Blood War", "Bleach The Calamity")),
    Item("slime-s4", "That Time I Got Reincarnated as a Slime Season 4", "Anime", Check.AniList(182205),
        since = LocalDate.of(2026, 4, 3),
        aliases = listOf("TenSura", "Slime S4", "Slime Season 4", "Tensura Season 4", "Rimuru")),
    Item("jjk-s4", "Jujutsu Kaisen: The Culling Game Part 2", "Anime",
        Check.AniList(209895, vagueDate = LocalDate.of(2027, 1, 31), vagueLabel = "January 2027?"),
        defaultDetail = "<a href=\"https://www.youtube.com/watch?v=HGCsAcFzaFw\" target=\"_blank\" rel=\"noopener\">Teaser out.</a>",
        aliases = listOf("JJK", "JJK S4", "JJK Season 4", "Jujutsu Kaisen", "Jujutsu Kaisen Season 4", "Culling Game")),
    Item("steel-ball-run-ep2", "Steel Ball Run", "Anime",
        Check.AniList(210482, vagueDate = LocalDate.of(2026, 12, 31), vagueLabel = "Late 2026?"),
        defaultDetail = "Fuck Netflix.",
        aliases = listOf("SBR", "JoJo Part 7", "JoJo's Part 7", "JoJo's Bizarre Adventure Part 7", "JoJo's Bizarre Adventure: Steel Ball Run")),
    Item("shangri-la-s3", "Shangri-La Frontier Season 3", "Anime",
        Check.AniList(189323, vagueDate = LocalDate.of(2027, 1, 31), vagueLabel = "January 2027"),
        aliases = listOf("SLF S3", "SLF Season 3", "Shangri-La Frontier S3")),
    Item("frieren-s3", "Frieren: Beyond Journey's End Season 3", "Anime",
        Check.AniList(209939, vagueDate = LocalDate.of(2027, 10, 31), vagueLabel = "October 2027?"),
        aliases = listOf("Frieren S3", "Frieren Season 3", "Sousou no Frieren", "Sōsō no Furīren")),
    Item("dbs-galactic-patrol", "Dragon Ball Super: The Galactic Patrol", "Anime",
        Check.AniList(206812, vagueDate = LocalDate.of(2027, 12, 31), vagueLabel = "Late 2027?"),
        aliases = listOf("DBS", "Dragon Ball Super", "DBS Galactic Patrol")),
    Item("chainsaw-man-s2", "Chainsaw Man: The Assassins Arc", "Anime",
        Check.AniList(204429, vagueDate = LocalDate.of(2027, 12, 31), vagueLabel = "Late 2027?"),
        aliases = listOf("Chainsaw Man Season 2", "CSM", "CSM Season 2", "Chainsaw Man S2")),
    Item("cyberpunk-edgerunners-2", "Cyberpunk: Edgerunners 2", "Anime",
        Check.AniList(195539, vagueDate = LocalDate.of(2026, 10, 15), vagueLabel = "Fall 2026?"),
        defaultDetail = "<a href=\"https://www.youtube.com/watch?v=mV7451mcw-E\" target=\"_blank\" rel=\"noopener\">Teaser out.</a>",
        aliases = listOf("Edgerunners 2", "Edgerunners Season 2")),

    // Shows (non-anime)
    Item("invincible-s5", "Invincible Season 5", "Shows", Check.VagueDate(LocalDate.of(2027, 4, 15), "Spring 2027?")),

    // Movies (date-ordered)
    Item("moana-2026",         "Moana",                  "Movies", Check.ScheduledDate(LocalDate.of(2026, 7, 11)), defaultDetail = "(the live action one)"),
    Item("the-odyssey",        "The Odyssey",            "Movies", Check.ScheduledDate(LocalDate.of(2026, 7, 17))),
    Item("dune-3",              "Dune: Part Three",       "Movies", Check.ScheduledDate(LocalDate.of(2026, 12, 18))),
    Item("avengers-doomsday",   "Avengers: Doomsday",     "Movies", Check.ScheduledDate(LocalDate.of(2026, 12, 18))),
    Item("air-bud-returns",     "Air Bud Returns",        "Movies", Check.ScheduledDate(LocalDate.of(2027, 1, 22))),
    Item("sonic-4",             "Sonic the Hedgehog 4",   "Movies", Check.ScheduledDate(LocalDate.of(2027, 3, 19))),
    Item("spaceballs-new-one",  "Spaceballs: The New One", "Movies", Check.ScheduledDate(LocalDate.of(2027, 4, 23)), defaultDetail = "Sadly not Spaceballs III: The Search for Spaceballs II or Spaceballs 2: The Search for More Money"),
    Item("zelda-movie", "The Legend of Zelda", "Movies", Check.ScheduledDate(LocalDate.of(2027, 4, 30)),
        defaultDetail =
            """
            corpos can't triforce
             ▲
            ▲ ▲
            """.trimIndent() // The space before the top ▲ is a non-breaking space - I think it'll work.
    ),
    Item("starwars-starfighter", "Star Wars: Starfighter", "Movies", Check.ScheduledDate(LocalDate.of(2027, 5, 28)),
        defaultDetail = "It has Ryan Gosling, I guess? Did anyone actually go to the Mandalorian movie?"),
    Item("spiderverse-3",       "Spider-Man: Beyond the Spider-Verse", "Movies", Check.ScheduledDate(LocalDate.of(2027, 6, 18))),
    Item("shrek-5",             "Shrek 5",                "Movies", Check.ScheduledDate(LocalDate.of(2027, 6, 30))),
    Item("quiet-place-3",       "A Quiet Place Part III", "Movies", Check.ScheduledDate(LocalDate.of(2027, 7, 30))),
    Item("demon-slayer-ic-2",   "Demon Slayer: Infinity Castle Part 2", "Movies", Check.VagueDate(LocalDate.of(2027, 9, 22), "Summer 2027?")),
    Item("the-batman-2",        "The Batman Part II",     "Movies", Check.ScheduledDate(LocalDate.of(2027, 10, 1))),
    Item("helldivers",          "Helldivers",             "Movies", Check.ScheduledDate(LocalDate.of(2027, 11, 10))),
    Item("frozen-3",            "Frozen III",             "Movies", Check.ScheduledDate(LocalDate.of(2027, 11, 24))),
    Item("lotr-gollum",         "The Lord of the Rings: The Hunt for Gollum", "Movies", Check.ScheduledDate(LocalDate.of(2027, 12, 17))),
    Item("avengers-secret-wars", "Avengers: Secret Wars", "Movies", Check.ScheduledDate(LocalDate.of(2027, 12, 17))),
    Item("avatar-4",            "Avatar 4: The Tulkun Rider", "Movies", Check.ScheduledDate(LocalDate.of(2029, 12, 21))),

    // People
    Item("diddy",           "Diddy",           "People",
        Check.WikipediaHtml("Sean_Combs", INCARCERATION_MARKERS, flippedDetail = "He's out."),
        defaultDetail = "Serving ~50 months in prison."),
    Item("henry-kissinger", "Henry Kissinger", "People", Check.Hardcoded, "Maybe?", "I think he's still in one of those Myst books?",
        since = LocalDate.of(2023, 11, 29), tone = "death"),
    Item("donald-trump",    "Donald Trump",    "People",
        Check.WikipediaLead("Donald_Trump", "is the 47th president", LocalDate.of(2029, 1, 20)),
        defaultDetail = "Not out of office yet."),
    Item("vladimir-putin",  "Vladimir Putin",  "People",
        Check.WikipediaLead("Vladimir_Putin", "President of Russia since"),
        defaultDetail = "Still President of Russia. Has been since 2012."),
    Item("elizabeth-holmes", "Elizabeth Holmes", "People",
        Check.WikipediaHtml("Elizabeth_Holmes", INCARCERATION_MARKERS, flippedDetail = "She's out."),
        defaultDetail = "Serving 11+ years at FPC Bryan."),
    Item("sbf",              "Sam Bankman-Fried", "People",
        Check.WikipediaHtml("Sam_Bankman-Fried", INCARCERATION_MARKERS, flippedDetail = "He's out."),
        defaultDetail = "25 years at FCI Lompoc I."),
    // Cosby's already out of prison (conviction overturned 2021); the WikipediaLead instead tracks
    // the lead's "is/was an American" copula — when he dies the verb flips and the detail refreshes
    // to the obituary. (The summary endpoint strips the "(born July 12, 1937)" parenthetical, so the
    // birth date itself isn't a usable signal here.)
    Item("bill-cosby",      "Bill Cosby",      "People",
        Check.WikipediaLead("Bill_Cosby", "is an American former comedian", flippedTone = "death"),
        defaultAnswer = "Yes.", defaultDetail = "<a href=\"https://en.wikipedia.org/wiki/Trial_of_Bill_Cosby#Overturned_conviction\" target=\"_blank\" rel=\"noopener\">Released in 2021. It was kinda bullshit.</a>",
        since = LocalDate.of(2021, 6, 30)),
    Item("harvey-weinstein", "Harvey Weinstein", "People",
        Check.WikipediaHtml("Harvey_Weinstein", INCARCERATION_MARKERS, flippedDetail = "He's out."),
        defaultDetail = "Held at Rikers Island."),
    Item("r-kelly",         "R. Kelly",        "People",
        Check.WikipediaHtml("R._Kelly", INCARCERATION_MARKERS, flippedDetail = "He's out."),
        defaultDetail = "Serving 30 years at FCI Butner."),
    Item("jared-fogle",     "Jared Fogle",     "People",
        Check.WikipediaHtml("Jared_Fogle", INCARCERATION_MARKERS, flippedDetail = "He's out."),
        defaultDetail = "~16 years at FCI Englewood. Out around 2029."),
    Item("joe-exotic",      "Joe Exotic",      "People",
        Check.WikipediaHtml("Joe_Exotic", INCARCERATION_MARKERS, flippedDetail = "He's out."),
        defaultDetail = "21 years at FMC Fort Worth. Still no pardon."),
    Item("suge-knight",     "Suge Knight",     "People",
        Check.WikipediaHtml("Suge_Knight", INCARCERATION_MARKERS, flippedDetail = "He's out."),
        defaultDetail = "Serving 28 years at Richard J. Donovan Correctional Facility."),
    Item("danny-masterson", "Danny Masterson", "People",
        Check.WikipediaHtml("Danny_Masterson", INCARCERATION_MARKERS, flippedDetail = "He's out."),
        defaultDetail = "30 years to life at California Men's Colony."),
    Item("ted-kaczynski",   "Ted Kaczynski",   "People", Check.Hardcoded, "Yes.",
        "Yeah, he died in 2023, dude. That was like... a while ago.",
        since = LocalDate.of(2023, 6, 10), tone = "death"),
    Item("oj-simpson",      "O.J. Simpson",    "People", Check.Hardcoded, "No.",
        "The Juice is not loose, he died in 2024.", since = LocalDate.of(2024, 4, 10), tone = "death"),
    // Parole-eligibility ballparks are deliberately NOT countdowns here (no latestDate): an
    // eligibility window isn't a release date, so the card just holds at "No." until the
    // Wikipedia infobox signal actually flips.
    Item("sirhan-sirhan",    "Sirhan Sirhan",    "People",
        Check.WikipediaHtml("Sirhan_Sirhan", INCARCERATION_MARKERS, flippedDetail = "He's out."),
        defaultDetail = "Gavin Newsom overruled the parole board's recommendations. Touchy subject.",
        aliases = listOf("RFK assassin", "Robert F. Kennedy assassin", "Bobby Kennedy shooter")),
    Item("ghislaine-maxwell", "Ghislaine Maxwell", "People",
        Check.WikipediaHtml("Ghislaine_Maxwell", INCARCERATION_MARKERS, flippedDetail = "She's out."),
        defaultDetail = "Such a nasty woman, but Trump wishes her well. Pardon incoming?"),
    Item("the-epstein-list", "The Epstein Client List", "People",
        Check.Hardcoded,
        "No.",
        aliases = listOf("The Epstein files")),
    Item("menendez-brothers", "The Menendez Brothers", "People",
        Check.WikipediaHtml("Lyle_and_Erik_Menendez", INCARCERATION_MARKERS, flippedDetail = "They're out."),
        defaultDetail = "Both were youth offenders so they're still up for parole.",
        aliases = listOf("Lyle Menendez", "Erik Menendez", "Menendez")),
    Item("john-hinckley-jr", "John Hinckley Jr.", "People", Check.Hardcoded, "Yes.",
        "He has a <a href=\"https://www.youtube.com/channel/UCck3J5KR3INUP1K-hrBe8iA\" target=\"_blank\" rel=\"noopener\">YouTube channel</a> now!",
        since = LocalDate.of(2022, 6, 15),
        aliases = listOf("Reagan shooter", "Ronald Reagan shooter")),
    Item("ed-kemper",        "Ed Kemper",        "People",
        Check.WikipediaHtml("Edmund_Kemper", INCARCERATION_MARKERS, flippedDetail = "He's out."),
        defaultDetail = "The big 6'9\" mustache guy on <a href=\"https://www.youtube.com/watch?v=cKMMpCuK3bA\" target=\"_blank\" rel=\"noopener\">MINDHUNTER</a>",
        aliases = listOf("Edmund Kemper", "Co-Ed Killer", "Mindhunter")),

    // Resources
    Item("helium",          "Helium",          "Resources", Check.Hardcoded, "No.",  "~200 years of supply remaining. Don't panic."),
    Item("ram",             "RAM",             "Resources", Check.Hardcoded, "Probably.",  "Blame AI."),
    Item("sand",            "Sand",            "Resources", Check.Hardcoded, "Maybe?", "It's actually a major problem, look it up."),
    Item("sulfur",          "Sulfur",          "Resources", Check.Hardcoded, "Maybe?", "A lot of it comes from the Persian Gulf."),
    Item("bananas",         "Bananas",         "Resources", Check.Hardcoded, "Maybe?", "Panama disease for Cavendish bananas in stores."),
    Item("toilet-paper",    "Toilet Paper",    "Resources", Check.Hardcoded, "No.",  "Honestly, just get a <a href=\"https://www.costco.com/p/-/toto-drake-2-piece-elongated-toilet-with-c5-washlet-bidet-seat/4000380465\" target=\"_blank\" rel=\"noopener\">Toto bidet from Costco.</a> Y'know, with like a heated seat and warm water."),
    Item("water",           "Water",           "Resources", Check.Hardcoded, "Maybe?", "Take shorter showers, that water could go to a data center."),
    Item("gas",             "Gas",             "Resources", Check.GasPrices("https://gasprices.aaa.com/"), "No?", "I think we still have reserves."),

    // Tech
    Item("tesla-roadster-2", "Tesla Roadster 2", "Tech",
        Check.WikipediaLead("Tesla_Roadster_(second_generation)", "is an upcoming"),
        defaultDetail = "Announced November 2017. Still upcoming."),
    Item("python-4",        "Python 4",        "Tech", Check.Hardcoded, "No.", "Stop building AI backend services in Python, it sucks. Go use Java/Kotlin and <a href=\"https://spring.io/projects/spring-ai\" target=\"_blank\" rel=\"noopener\">Spring AI</a>."),
    Item("steam-machine",   "Steam Machine",   "Tech", Check.Hardcoded, "Yes.", "<a href=\"https://store.steampowered.com/hardware/steammachine\" target=\"_blank\" rel=\"noopener\">Too expensive though.</a>"),
    Item("steam-frame",     "Steam Frame",     "Tech",
        Check.WikipediaLead("Steam_Frame", "is an upcoming", LocalDate.of(2026, 9, 22)),
        defaultDetail = "Expected Summer 2026."),
    Item("java-valhalla",   "Java Value Types (Valhalla)", "Tech", Check.VagueDate(LocalDate.of(2027, 3, 31), "March 2027?"),
        defaultDetail = "JDK 28 preview."),
    Item("fsd-level-5",     "Level 5 Full Self-Driving", "Tech", Check.Hardcoded, "No."),
    Item("cold-fusion",     "Cold Fusion",     "Tech", Check.Hardcoded, "No."),
    Item("amd-zen-6",       "AMD Zen 6",       "Tech", Check.CountdownTo(LocalDate.of(2027, 1, 6)), "No.", "Might be revealed at CES 2027."),
    Item("rtx-50-super",    "RTX 50 Super Series", "Tech", Check.CountdownTo(LocalDate.of(2027, 1, 6)), "No.", "Might be revealed at CES 2027."),
    Item("rtx-60",          "RTX 60 Series",   "Tech", Check.Hardcoded, "No.", "Blame AI / Jensen."),

    // Miscellaneous
    Item("sbemail-211",     "Sbemail 211",     "Miscellaneous", Check.HomestarRunner),
    Item("scp-682",         "SCP-682",         "Miscellaneous", Check.Hardcoded, "Probably not.", "No need to panic."),
    Item("scp-096",         "SCP-096",         "Miscellaneous", Check.Hardcoded, "No.",
        "<a href=\"https://scp-wiki.wikidot.com/incident-096-1-a\" target=\"_blank\" rel=\"noopener\">Four pixels. Four fucking pixels.</a>"),
    Item("year-of-linux",   "Year of the Linux Desktop", "Miscellaneous", Check.RollingNewYear, "No."),
)
