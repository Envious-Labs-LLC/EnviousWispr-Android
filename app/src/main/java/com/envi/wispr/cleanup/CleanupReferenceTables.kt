package com.envi.wispr.cleanup

/** Reference tables from macOS 214e5500 (2026-10-02); one table per decision. */
internal object CleanupReferenceTables {
    val countryCodeTLDs = setOf("de", "nl", "fr", "es", "pt", "pl", "se", "dk", "fi", "hu", "cz", "sk", "ru", "ua", "ch", "gr", "ie", "uk", "eu", "si", "hr", "lt", "lv", "ee", "bg", "ro")
    val englishProseDomainWords = setOf("the", "a", "an", "this", "that", "these", "those", "my", "your", "our", "his", "her", "its", "their", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten")
    val dottedNameRefusedSuffixes = setOf("js", "ts", "py", "rb", "go", "rs", "md", "txt", "html", "css", "json", "swift", "app", "pdf", "doc", "docx", "xls", "xlsx", "csv", "png", "jpg", "zip", "com", "net", "org", "io", "ai", "dev", "co")
    val neutralNameRefusedWords = setOf("a", "al", "de", "del", "en", "para", "por", "con", "y", "o", "e", "u", "es", "la", "el", "los", "las", "un", "una", "à", "au", "aux", "du", "des", "pour", "par", "avec", "et", "ou", "le", "les", "une", "chez", "do", "na", "w", "z", "i", "dla", "od", "to", "jest", "ze", "we", "naar", "aan", "voor", "van", "met", "of", "het", "een", "op", "in", "bij", "is", "an", "zu", "für", "und", "oder", "der", "die", "das", "ein", "eine", "mit", "bei")
    val russianNameRefusedWords = setOf("на", "и", "в", "это", "к", "для")
    val portugueseNameRefusedWords = setOf("ao", "aos", "à", "que", "em", "o", "os")
    val italianNameRefusedWords = setOf("ai", "alla", "allo", "alle", "il", "lo", "di", "della")
    val neutralOnlyDashWords = setOf("łącznik", "myślnik", "kreska", "streepje", "koppelteken", "koppel teken", "trait d'union", "дефис", "тире")
    val dutchDashWords = setOf("streepje", "koppelteken", "koppel teken")
    val streetTypes = setOf("Road", "Street", "Avenue", "Lane", "Drive", "Boulevard", "Court", "Way", "Place", "Circle", "Terrace", "Parkway", "Trail", "Highway")
    val streetDirections = setOf("Northeast", "Northwest", "Southeast", "Southwest", "North", "South", "East", "West")
    val streetUnitWords = setOf("Apartment", "apartment", "Apt", "apt", "Suite", "suite", "Unit", "unit", "Room", "room", "Floor", "floor")
    val usStates = setOf("Alabama", "Alaska", "Arizona", "Arkansas", "California", "Colorado", "Connecticut", "Delaware", "Florida", "Georgia", "Hawaii", "Idaho", "Illinois", "Indiana", "Iowa", "Kansas", "Kentucky", "Louisiana", "Maine", "Maryland", "Massachusetts", "Michigan", "Minnesota", "Mississippi", "Missouri", "Montana", "Nebraska", "Nevada", "New Hampshire", "New Jersey", "New Mexico", "New York", "North Carolina", "North Dakota", "Ohio", "Oklahoma", "Oregon", "Pennsylvania", "Rhode Island", "South Carolina", "South Dakota", "Tennessee", "Texas", "Utah", "Vermont", "Virginia", "Washington", "West Virginia", "Wisconsin", "Wyoming", "District of Columbia")
    val usStateCodes = setOf("AL", "AK", "AZ", "AR", "CA", "CO", "CT", "DE", "FL", "GA", "HI", "ID", "IL", "IN", "IA", "KS", "KY", "LA", "ME", "MD", "MA", "MI", "MN", "MS", "MO", "MT", "NE", "NV", "NH", "NJ", "NM", "NY", "NC", "ND", "OH", "OK", "OR", "PA", "RI", "SC", "SD", "TN", "TX", "UT", "VT", "VA", "WA", "WV", "WI", "WY", "DC")
    val addressIntroducers = setOf("to", "at", "for", "is", "was", "are", "were", "be", "from", "on", "into", "onto", "via", "and", "or", "near", "of", "as", "address")
    val slashSubjectPronouns = setOf("i", "you", "he", "she", "it", "we", "they")
    val slashObjectPronouns = setOf("me", "you", "him", "her", "it", "us", "them")
    val slashPossessives = setOf("my", "your", "his", "her", "its", "our", "their", "mine", "yours", "hers", "ours", "theirs")
    val slashFunctionPairs = setOf("and/or", "or/and", "on/off", "off/on", "in/out", "out/in", "up/down", "down/up", "yes/no", "no/yes", "before/after", "after/before", "auto/off", "top/down", "bottom/up")
    val slashDeterminers = setOf("a", "an", "the", "this", "that", "these", "those", "another", "one", "any", "each", "every", "some", "all", "both", "either", "neither", "my", "your", "his", "her", "its", "our", "their")
    val slashModalsAndNegators = setOf("will", "would", "can", "could", "should", "must", "may", "might", "shall", "gonna", "wanna", "let's", "not", "never", "don't", "doesn't", "didn't", "won't", "wouldn't", "can't", "couldn't", "shouldn't", "cannot")
    val slashAuxiliaries = setOf("is", "are", "am", "was", "were", "be", "been", "being", "do", "does", "did", "have", "has", "had")
    val slashConjunctions = setOf("and", "or", "but", "so", "if")
    val slashPrepositions = setOf("with", "in", "on", "at", "for", "of", "by", "from", "into", "about", "via", "like", "as", "than", "between", "after", "before", "until", "under", "over", "through", "to")
    val slashDiscourse = setOf("okay", "ok", "alright", "yes", "yeah", "yep", "sure", "hey", "oh", "anyway", "cool", "great", "thanks")
    val slashParticles = setOf("off", "down")
    val slashParticleVerbs = setOf("kick", "kicked", "kicking", "fire", "fired", "firing")
    val slashDestinationWords = setOf("switch", "switched", "switching", "back", "over", "straight", "directly")
    val slashCommandNouns = setOf("command", "commands", "skill", "skills", "shortcut", "shortcuts")
    val slashIndefinites = setOf("everything", "something", "anything", "nothing", "everyone", "someone", "anyone", "everybody", "somebody", "anybody")
    val slashAdverbs = setOf("then", "just", "now", "also", "always", "simply", "first", "next", "please", "maybe", "probably", "perhaps", "usually", "often", "sometimes")
    val slashSubordinators = setOf("after", "before", "until", "when", "once", "while", "whenever")
    val slashCommandVerbs = setOf("use", "using", "used", "type", "typed", "typing", "run", "ran", "running", "try", "tried", "say", "said", "says", "hit", "send", "sent", "press", "pressed", "enter", "entered", "call", "called", "invoke", "invoked", "add", "added", "remember", "forget", "forgot", "forgetting", "document", "documented", "mention", "mentioned", "prefix", "replace", "replaced", "show", "showed", "shows", "see", "saw", "recommend", "suggest", "prefer", "need", "needs", "want", "wants", "know", "learn", "learned", "teach", "taught", "explain", "explained", "define", "defined", "insert", "put", "choose", "chose", "pick", "picked", "select", "selected", "execute", "executed", "write", "wrote", "means", "meaning", "launch", "launched", "trigger", "triggered", "fire", "fired", "kick", "kicked", "issue", "issued", "apply", "applied", "perform", "performed")
    val slashSchemes = setOf("http", "https", "ftp")
    val slashAbbreviations = setOf("mr", "mrs", "ms", "dr", "prof", "sr", "jr", "st", "mt", "vs", "etc")
    val markerCountNouns = setOf("ad", "ads", "advert", "advertisement", "break", "burst", "centimeter", "centimeters", "clip", "cm", "commercial", "countdown", "cup", "cups", "day", "days", "degree", "degrees", "delay", "feet", "foot", "gallon", "gallons", "gap", "gram", "grams", "head", "inch", "inches", "interval", "intro", "introduction", "kg", "kilogram", "kilograms", "kilometer", "kilometers", "km", "lb", "lbs", "lead", "liter", "liters", "litre", "litres", "mark", "meter", "meters", "metre", "metres", "mg", "mile", "miles", "milligram", "milligrams", "milliliter", "milliliters", "millimeter", "millimeters", "ml", "mm", "month", "months", "mph", "ounce", "ounces", "oz", "pause", "percent", "pound", "pounds", "rest", "rule", "second", "seconds", "segment", "spot", "sprint", "tablespoon", "tablespoons", "tbsp", "teaser", "teaspoon", "teaspoons", "time", "timer", "times", "trailer", "tsp", "video", "week", "weeks", "window", "window.", "yard", "yards", "year", "years")
    val addressWordPairs = mapOf(
        "at" to setOf("dot", "punkt"),
        "klammeraffe" to setOf("punkt"),
        "affenschwanz" to setOf("punkt"),
        "arroba" to setOf("punto", "ponto"),
        "chiocciola" to setOf("punto"),
        "apenstaartje" to setOf("punt"),
        "apestaartje" to setOf("punt"),
        "malpa" to setOf("kropka"),
        "małpa" to setOf("kropka"),
        "sobaka" to setOf("точка"),
        "собака" to setOf("точка"),
        "сабака" to setOf("кропка"),
        "малпа" to setOf("кропка"),
        "собачка" to setOf("крапка"),
        "равлик" to setOf("крапка"),
        "snabel-a" to setOf("punkt", "punktum"),
        "snabela" to setOf("punkt", "punktum"),
        "krøllalfa" to setOf("punktum", "prikk"),
        "kukac" to setOf("pont"),
        "arobase" to setOf("point"),
        "ät" to setOf("piste"),
        "miuku" to setOf("piste"),
        "miukumauku" to setOf("piste"),
        "kissanhäntä" to setOf("piste"),
    )
}
