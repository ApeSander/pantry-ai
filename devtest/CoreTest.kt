import com.pantryai.core.*
import java.io.File

var fails = 0
fun check(name: String, cond: Boolean, detail: Any? = null) {
    if (cond) println("  ok   $name") else { fails++; println("  FAIL $name  -> $detail") }
}

fun main() {
    val kb = Knowledge(Knowledge.parse(File("app/src/main/assets/ingredients.json").readText()))
    println("KB: ${kb.all.size} ingredients")

    // duplicate aliases pointing to different ingredients
    val seen = HashMap<String, String>()
    for (i in kb.all) for (a in listOf(i.en, i.nl) + i.aliases) {
        val n = Text.norm(a); val prev = seen.put(n, i.id)
        if (prev != null && prev != i.id) println("  WARN alias '$n' in $prev and ${i.id}")
    }

    println("== matching")
    val m = mapOf(
        "basmati" to "rice", "Basmatirijst" to "rice", "long grain rice" to "rice", "jasmijnrijst" to "rice",
        "tomaat" to "tomato", "Tomaten" to "tomato", "tomatoes" to "tomato", "tomatos" to "tomato",
        "knoflook" to "garlic", "garlic cloves" to "garlic", "knoflok" to "garlic", "scharreleieren" to "egg",
        "eggs" to "egg", "large eggs" to "egg", "kipfiletblokjes" to "chicken_breast", "Halfvolle Melk" to "milk",
        "zak basmati rijst" to "rice", "uien" to "onion", "rode ui" to "red_onion", "paprika" to "bell_pepper",
        "extra vierge olijfolie" to "olive_oil", "all-purpose flour" to "flour", "bloem" to "flour",
        "verse koriander" to "cilantro", "geraspte kaas" to "cheese", "parmezaan" to "parmesan",
        "cherrytomaatjes" to "cherry_tomato", "spinazie" to "spinach", "champignons" to "mushroom",
        "kastanje champignons" to "mushroom", "sojasaus" to "soy_sauce", "ketjap" to "kecap_manis",
        "gehakt" to "mixed_mince", "rundergehakt" to "ground_beef", "boter" to "butter", "ongezouten roomboter" to "butter",
        "zout" to "salt", "versgemalen zwarte peper" to "black_pepper", "bouillonblokje" to "stock",
        "gepelde tomaten" to "canned_tomatoes", "chopped tomatoes" to "canned_tomatoes", "tomatenpuree" to "tomato_paste",
        "kikkererwten" to "chickpeas", "spagetti" to "spaghetti", "courgette" to "zucchini", "zucchini" to "zucchini",
        "Bananen" to "banana", "spring onions" to "spring_onion", "bosui" to "spring_onion", "brown sugar" to "brown_sugar",
        "kristalsuiker" to "sugar", "slagroom" to "cream", "heavy cream" to "cream", "mozzarella" to "mozzarella",
        "Volkoren Brood" to "bread", "kip" to "chicken_breast", "chicken" to "chicken_breast", "egg yolks" to "egg_yolk",
        "kokosmelk" to "coconut_milk", "limoensap" to "lime", "lemon juice" to "lemon",
    )
    for ((q, exp) in m) { val b = kb.best(q); check("'$q' -> $exp", b?.ingredient?.id == exp && b.score >= 0.86, "${b?.ingredient?.id} ${b?.score?.let { "%.2f".format(it) }} ${b?.via}") }
    check("tsp paprika -> powder", kb.disambiguate(kb.best("paprika"), "tsp")?.ingredient?.id == "paprika_powder")

    println("== parser")
    data class P(val line: String, val amount: Double?, val unit: String, val name: String, val size: String? = null)
    val ps = listOf(
        P("2 1/2 cups (300 g) all-purpose flour, sifted", 300.0, "g", "all-purpose flour"),
        P("3 teentjes knoflook, geperst", 3.0, "clove", "knoflook"),
        P("1 blik tomatenblokjes (400 g)", 400.0, "g", "tomatenblokjes"),
        P("2 large eggs", 2.0, "pc", "eggs", "L"),
        P("1½ el olijfolie", 1.5, "tbsp", "olijfolie"),
        P("200g spaghetti", 200.0, "g", "spaghetti"),
        P("½ ui", 0.5, "pc", "ui"),
        P("2-3 tomaten", 3.0, "pc", "tomaten"),
        P("1 tsp ground cumin", 1.0, "tsp", "ground cumin"),
        P("a pinch of salt", 1.0, "pinch", "salt"),
        P("Zout en peper naar smaak", null, "pc", "Zout en peper naar smaak"),
        P("400 ml kokosmelk", 400.0, "ml", "kokosmelk"),
        P("1 kg aardappelen (kruimig)", 1.0, "kg", "aardappelen"),
        P("2 cloves garlic, minced", 2.0, "clove", "garlic"),
        P("1 bosje koriander", 1.0, "bunch", "koriander"),
        P("1 brood", 1.0, "pc", "brood"),
        P("2 x 400 g kidneybonen", 800.0, "g", "kidneybonen"),
        P("• 150 gram geraspte kaas", 150.0, "g", "geraspte kaas"),
        P("1 middelgrote ui", 1.0, "pc", "ui", "M"),
        P("2 tbsp. soy sauce", 2.0, "tbsp", "soy sauce"),
        P("1 (14 oz) can coconut milk", 14.0, "oz", "coconut milk"),
        P("1 ons gehakt", 1.0, "ons", "gehakt"),
    )
    for (p in ps) {
        val r = IngredientParser.parse(p.line)
        check("parse '${p.line}'", r.amount == p.amount && r.unit == p.unit && r.name == p.name && r.size == p.size, r)
    }

    println("== conversions")
    val garlic = kb.get("garlic")!!; val egg = kb.get("egg")!!
    check("2 cloves = 0.2 bulb", Convert.convert(Qty(2.0, "clove"), "bulb", null, garlic)?.let { Math.abs(it - 0.2) < 1e-9 } == true)
    check("bare 3 garlic = 3 cloves", Convert.convert(Qty(3.0, "pc"), "clove", null, garlic) == 3.0)
    val le = Convert.convert(Qty(2.0, "pc", "L"), "pc", "M", egg)!!
    check("2 L eggs = ~2.34 M eggs", Math.abs(le - 136.0 / 58) < 1e-6, le)
    check("2 S eggs -> L", Convert.convert(Qty(2.0, "pc", "S"), "pc", "L", egg)?.let { Math.abs(it - 96.0 / 68) < 1e-6 } == true)
    check("3 tbsp olive oil -> g", Convert.convert(Qty(3.0, "tbsp"), "g", null, kb.get("olive_oil"))?.let { Math.abs(it - 40.95) < 0.01 } == true)
    check("1 cup flour = 127 g", Convert.convert(Qty(1.0, "cup"), "g", null, kb.get("flour"))?.let { Math.abs(it - 127.2) < 0.1 } == true)
    check("1 stock cube = 500 ml", Convert.convert(Qty(1.0, "cube"), "ml", null, kb.get("stock")) == 500.0)
    check("format 0.8 bulb", Convert.format(Qty(0.8, "bulb"), garlic) == "0.8 bulb (≈8 cloves)", Convert.format(Qty(0.8, "bulb"), garlic))
    println("     " + Convert.format(Qty(0.8, "bulb"), garlic) + " | " + Convert.format(Qty(1250.0, "g"), null) + " | " + Convert.format(Qty(1.5, "tbsp"), null))

    println("== receipt")
    val receipt = """
        ALBERT HEIJN
        Filiaal 1234 Rotterdam
        AANTAL OMSCHRIJVING PRIJS BEDRAG
        AH HALFVOLLE MELK 1L 1,09
        AH BASMATI RIJST 1KG 2,49
        2 AH TOMATENBLOKJES 0,89 1,78
        KOMKOMMER 0,79
        AH SCHARRELEIEREN L 10ST 3,29
        BANANEN 1,50
        0,754 kg x 1,99 EUR/kg
        AH UIEN 1,19
        AH KNOFLOOK 3ST 0,99
        AH KIPFILET 500G 6,49
        HV YOGHURT 0,99
        BONUS AH KIPFILET -1,00
        PLASTIC TAS 0,25
        SUBTOTAAL 20,00
        TOTAAL 19,75
        PINNEN 19,75
    """.trimIndent().lines()
    val lines = ReceiptParser.parse(receipt)
    lines.forEach { println("     $it") }
    val eng = SmartEngine(kb, NoLlm)
    val items = eng.receiptItems(lines)
    items.forEach { println("     ${it.name} [${it.ingredient?.id}] ${it.amount} ${it.unit} ${it.size ?: ""} unclear=${it.unclear} via=${it.via}") }
    fun item(id: String) = items.firstOrNull { it.ingredient?.id == id }
    check("milk 1000 ml", item("milk")?.let { it.amount == 1000.0 && it.unit == "ml" } == true, item("milk"))
    check("rice 1000 g", item("rice")?.amount == 1000.0, item("rice"))
    check("2 cans tomatoes", item("canned_tomatoes")?.let { it.amount == 2.0 && it.unit == "can" } == true, item("canned_tomatoes"))
    check("eggs 10 L", item("egg")?.let { it.amount == 10.0 && it.size == "L" } == true, item("egg"))
    check("bananas weighed 754 g", item("banana")?.let { Math.abs(it.amount!! - 754) < 0.01 } == true, item("banana"))
    check("onions unclear -> ask", item("onion")?.unclear == true, item("onion"))
    check("garlic 3 bulbs", item("garlic")?.let { it.amount == 3.0 && it.unit == "bulb" } == true, item("garlic"))
    check("cucumber 1 pc clear", item("cucumber")?.let { it.amount == 1.0 && !it.unclear } == true, item("cucumber"))
    check("no tas/totaal", lines.none { it.product.contains("Tas", true) || it.product.contains("Totaal", true) })
    check("hv yoghurt", item("yogurt") != null)

    println("== planner")
    val planner = Planner(kb)
    val pantry = listOf(
        PantryItem(ingredientId = "garlic", name = "Garlic", amount = 1.0, unit = "bulb", category = "produce"),
        PantryItem(ingredientId = "egg", name = "Egg", amount = 10.0, unit = "pc", size = "M", category = "dairy"),
        PantryItem(ingredientId = "rice", name = "Long grain rice", amount = 1000.0, unit = "g", category = "grains"),
        PantryItem(ingredientId = "olive_oil", name = "Olive oil", amount = 500.0, unit = "ml", category = "oils"),
    )
    val recipeLines = listOf("3 teentjes knoflook", "2 large eggs", "250 g basmati", "2 el olijfolie", "1 ui", "1 blik kokosmelk", "zout naar smaak")
    val ris = eng.recipeIngredients(recipeLines)
    ris.forEach { println("     ${it.raw} -> ${it.ingredientId} ${it.amount} ${it.unit} ${it.size ?: ""} (${it.via})") }
    val recipe = Recipe(title = "Test curry", servings = 2, ingredients = ris)
    val plan = planner.planDeduction(recipe, 2, pantry)
    plan.forEach { p -> println("     ${p.recipeIngredient.name}: " + p.deductions.joinToString { "${it.explanation} (left ${it.remaining})" } + " missing=${p.missing} ${p.problem ?: ""}") }
    val after = planner.applyDeductions(pantry, plan.flatMap { it.deductions })
    after.forEach { println("     after: ${it.name} ${Convert.format(it.qty, kb.get(it.ingredientId))}") }
    check("garlic 0.7 bulb left", after.first { it.ingredientId == "garlic" }.amount.let { Math.abs(it - 0.7) < 1e-6 })
    check("eggs 8 left", after.first { it.ingredientId == "egg" }.amount == 8.0)
    check("rice 750 left", after.first { it.ingredientId == "rice" }.amount == 750.0)
    check("oil 470 left", after.first { it.ingredientId == "olive_oil" }.amount == 470.0)

    val shop = planner.shoppingList(listOf(recipe to 4), pantry)
    shop.forEach { println("     buy: ${it.name} ${it.amount?.let { a -> Convert.format(Qty(a, it.unit, it.size), kb.get(it.ingredientId)) } ?: "(to taste)"} — ${it.note}") }
    check("shop onion 2", shop.firstOrNull { it.ingredientId == "onion" }?.let { it.amount == 2.0 && it.unit == "pc" } == true, shop.firstOrNull { it.ingredientId == "onion" })
    check("shop coconut 2 cans", shop.firstOrNull { it.ingredientId == "coconut_milk" }?.let { it.amount == 2.0 && it.unit == "can" } == true)
    check("garlic covered (6 cloves < 1 bulb)", shop.none { it.ingredientId == "garlic" })
    check("salt to taste listed", shop.any { it.ingredientId == "salt" })
    check("eggs: 4 L = 272g > 0? have 10 M", shop.none { it.ingredientId == "egg" })

    val addMerge = planner.addToPantry(pantry, PantryItem(ingredientId = "garlic", name = "Garlic", amount = 2.0, unit = "clove"))
    check("merge 2 cloves into bulbs", addMerge.size == pantry.size && Math.abs(addMerge.first { it.ingredientId == "garlic" }.amount - 1.2) < 1e-9)

    println("== json-ld")
    val html = """<html><head><script type="application/ld+json">{"@context":"https://schema.org","@graph":[{"@type":"WebPage"},{"@type":"Recipe","name":"Pasta &amp; pesto","recipeYield":["4","4 personen"],"recipeIngredient":["300 g penne","1 potje pesto (190 g)","&frac12; courgette"],"recipeInstructions":[{"@type":"HowToStep","text":"Kook de pasta."},{"@type":"HowToStep","text":"Meng."}]}]}</script></head></html>"""
    val sr = RecipeJsonLd.extract(html)
    println("     $sr")
    check("json-ld recipe", sr?.title == "Pasta & pesto" && sr.servings == 4 && sr.ingredients.size == 3 && sr.ingredients[2] == "½ courgette")

    println("== fake LLM")
    val fake = object : LlmBackend {
        override val ready = true
        override fun complete(system: String, user: String): String = when {
            user.contains("Which ingredient") -> "1"
            user.contains("english:") -> "english: oat milk\ncategory: dairy\nfood: yes"
            user.contains("receipt") -> " Semi-skimmed milk\n2. SKIP\n3. Peanut butter"
            else -> ""
        }
    }
    val smart = SmartEngine(kb, fake)
    val r1 = smart.resolve("Haverdrankje barista")
    println("     $r1")
    check("ai resolves something", r1.ingredient != null && r1.via.startsWith("ai"))
    val dec = smart.decodeReceiptLines(listOf("HV MLK", "TOILETP 8R", "PNDKS"))
    println("     $dec")
    check("decode numbered", dec[0] == "Semi-skimmed milk" && dec[1] == null && dec[2] == "Peanut butter")
    check("parseWhatIs", smart.parseWhatIs("**english:** smoked sausage\ncategory: meat\nfood: yes")?.english == "smoked sausage")

    println(if (fails == 0) "\nALL PASSED" else "\n$fails FAILED")
}
