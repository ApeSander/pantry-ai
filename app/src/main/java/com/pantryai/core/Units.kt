package com.pantryai.core

enum class Dim { MASS, VOLUME, COUNT, NAMED }

/** A generic unit. Factor converts to the dimension's base (g, ml, pc). NAMED units need ingredient data. */
data class UnitDef(val id: String, val dim: Dim, val factor: Double, val singular: String, val plural: String)

object Units {
    val all: List<UnitDef> = listOf(
        UnitDef("g", Dim.MASS, 1.0, "g", "g"),
        UnitDef("kg", Dim.MASS, 1000.0, "kg", "kg"),
        UnitDef("mg", Dim.MASS, 0.001, "mg", "mg"),
        UnitDef("oz", Dim.MASS, 28.35, "oz", "oz"),
        UnitDef("lb", Dim.MASS, 453.6, "lb", "lb"),
        UnitDef("pond", Dim.MASS, 500.0, "pond", "pond"),
        UnitDef("ons", Dim.MASS, 100.0, "ons", "ons"),
        UnitDef("pinch", Dim.MASS, 0.4, "pinch", "pinches"),
        UnitDef("ml", Dim.VOLUME, 1.0, "ml", "ml"),
        UnitDef("cl", Dim.VOLUME, 10.0, "cl", "cl"),
        UnitDef("dl", Dim.VOLUME, 100.0, "dl", "dl"),
        UnitDef("l", Dim.VOLUME, 1000.0, "l", "l"),
        UnitDef("tsp", Dim.VOLUME, 5.0, "tsp", "tsp"),
        UnitDef("tbsp", Dim.VOLUME, 15.0, "tbsp", "tbsp"),
        UnitDef("cup", Dim.VOLUME, 240.0, "cup", "cups"),
        UnitDef("floz", Dim.VOLUME, 29.57, "fl oz", "fl oz"),
        UnitDef("dash", Dim.VOLUME, 1.0, "dash", "dashes"),
        UnitDef("splash", Dim.VOLUME, 10.0, "splash", "splashes"),
        UnitDef("pc", Dim.COUNT, 1.0, "pc", "pcs"),
        UnitDef("dozen", Dim.COUNT, 12.0, "dozen", "dozen"),
    )
    private val byId = all.associateBy { it.id }

    /** Ingredient-specific / package units. Grams come from the knowledge base or fall back to these. */
    val named: Map<String, Pair<String, String>> = linkedMapOf(
        "clove" to ("clove" to "cloves"), "bulb" to ("bulb" to "bulbs"), "head" to ("head" to "heads"),
        "can" to ("can" to "cans"), "jar" to ("jar" to "jars"), "bottle" to ("bottle" to "bottles"),
        "pack" to ("pack" to "packs"), "bag" to ("bag" to "bags"), "box" to ("box" to "boxes"),
        "bunch" to ("bunch" to "bunches"), "sprig" to ("sprig" to "sprigs"), "leaf" to ("leaf" to "leaves"),
        "slice" to ("slice" to "slices"), "stalk" to ("stalk" to "stalks"), "stick" to ("stick" to "sticks"),
        "sheet" to ("sheet" to "sheets"), "cube" to ("cube" to "cubes"), "sachet" to ("sachet" to "sachets"),
        "ball" to ("ball" to "balls"), "block" to ("block" to "blocks"), "loaf" to ("loaf" to "loaves"),
        "thumb" to ("thumb" to "thumbs"), "cm" to ("cm" to "cm"), "knob" to ("knob" to "knobs"),
        "nest" to ("nest" to "nests"), "tube" to ("tube" to "tubes"), "pot" to ("pot" to "pots"),
        "pod" to ("pod" to "pods"), "bar" to ("bar" to "bars"), "square" to ("square" to "squares"),
        "strip" to ("strip" to "strips"), "roll" to ("roll" to "rolls"), "tablet" to ("tablet" to "tablets"),
    )

    /** Generic fallback grams for package units when the KB doesn't know the ingredient. */
    val namedFallbackGrams: Map<String, Double> = mapOf(
        "can" to 400.0, "slice" to 25.0, "clove" to 5.0, "sprig" to 1.0, "leaf" to 0.5, "bunch" to 25.0,
        "cube" to 10.0, "sachet" to 8.0, "stick" to 113.0, "knob" to 15.0, "thumb" to 15.0, "cm" to 5.0,
    )

    /** Every surface form -> unit id. Dutch + English, singular + plural, abbreviations. */
    val aliases: Map<String, String> = buildMap {
        fun add(id: String, vararg forms: String) { forms.forEach { f -> this[f] = id } }
        add("g", "g", "gr", "gram", "grams", "gramme", "grammes", "grm")
        add("kg", "kg", "kilo", "kilos", "kilogram", "kilograms", "kilogramme", "kgs")
        add("mg", "mg", "milligram", "milligrams")
        add("oz", "oz", "ounce", "ounces")
        add("ons", "ons")
        add("pond", "pond")
        add("lb", "lb", "lbs", "pound", "pounds")
        add("pinch", "pinch", "pinches", "snufje", "snufjes", "mespunt", "mespuntje", "snuf", "knijpje")
        add("ml", "ml", "milliliter", "milliliters", "millilitre", "millilitres", "mls")
        add("cl", "cl", "centiliter", "centiliters")
        add("dl", "dl", "deciliter", "deciliters")
        add("l", "l", "liter", "liters", "litre", "litres", "ltr", "lt")
        add("tsp", "tsp", "tsps", "teaspoon", "teaspoons", "tl", "theelepel", "theelepels", "theel", "tlp", "t")
        add("tbsp", "tbsp", "tbsps", "tbs", "tbl", "tablespoon", "tablespoons", "el", "eetlepel", "eetlepels", "eetl", "elp", "tb")
        add("cup", "cup", "cups", "kop", "kopje", "kopjes", "koppen", "mok", "glas", "glass", "glasses", "glaasje")
        add("floz", "floz", "fl oz", "fluid ounce", "fluid ounces")
        add("dash", "dash", "dashes", "drupje", "drupjes", "drop", "drops", "druppel", "druppels")
        add("splash", "splash", "splashes", "scheut", "scheutje", "scheutjes", "flinke scheut", "glug")
        add("pc", "pc", "pcs", "piece", "pieces", "st", "stuk", "stuks", "stk", "x")
        add("dozen", "dozen", "dozijn")
        add("clove", "clove", "cloves", "teen", "teentje", "teentjes", "tenen", "teentje(s)")
        add("bulb", "bulb", "bulbs", "bol", "bollen", "bolletje", "knol", "knollen")
        add("head", "head", "heads", "krop", "kropje", "kroppen", "struik", "struikje")
        add("can", "can", "cans", "tin", "tins", "blik", "blikje", "blikjes", "blikken")
        add("jar", "jar", "jars", "pot", "potje", "potjes", "glazen pot")
        add("bottle", "bottle", "bottles", "fles", "flesje", "flessen", "flesjes")
        add("pack", "pack", "packs", "package", "packages", "packet", "packets", "pak", "pakje", "pakjes", "pakken", "verpakking", "doos", "doosje", "bakje", "bakjes", "tray", "punnet", "schaal", "schaaltje")
        add("bag", "bag", "bags", "zak", "zakken", "net", "netje")
        add("box", "box", "boxes", "carton", "cartons")
        add("bunch", "bunch", "bunches", "bos", "bosje", "bosjes", "handful", "handfuls", "handje", "handjevol", "handvol", "hand")
        add("sprig", "sprig", "sprigs", "takje", "takjes", "tak")
        add("leaf", "leaf", "leaves", "blad", "blaadje", "blaadjes", "bladeren", "blaadjes")
        add("slice", "slice", "slices", "plak", "plakje", "plakjes", "plakken", "snee", "sneetje", "sneetjes", "sneden", "schijf", "schijfje", "schijfjes", "schijven")
        add("stalk", "stalk", "stalks", "stengel", "stengels", "steel", "stelen")
        add("stick", "stick", "sticks", "staafje", "stokje", "stokjes")
        add("sheet", "sheet", "sheets", "vel", "vellen", "velletje", "velletjes", "plaat", "plaatje")
        add("cube", "cube", "cubes", "blokje", "blokjes", "klontje", "klontjes")
        add("sachet", "sachet", "sachets", "zakje", "zakjes", "envelope", "envelop")
        add("ball", "ball", "balls", "bol mozzarella")
        add("block", "block", "blocks", "blok", "blokken")
        add("loaf", "loaf", "loaves", "brood")
        add("thumb", "thumb", "thumbs", "duim", "duimpje")
        add("cm", "cm")
        add("knob", "knob", "knobs", "klont", "klontje boter")
        add("nest", "nest", "nests", "nestje", "nestjes")
        add("tube", "tube", "tubes", "tuubje")
        add("pod", "pod", "pods", "peul", "peulen")
        add("bar", "bar", "bars", "reep", "repen", "reepje")
        add("square", "square", "squares")
        add("strip", "strip", "strips", "reepje", "reepjes")
        add("roll", "roll", "rolls", "rol", "rollen", "rolletje")
        add("tablet", "tablet", "tablets", "tablet bouillon")
    }

    fun get(id: String): UnitDef? = byId[id]

    fun dim(id: String): Dim = byId[id]?.dim ?: Dim.NAMED

    fun parse(token: String): String? = aliases[token.lowercase().trim().trimEnd('.')]

    fun label(id: String, amount: Double): String {
        val one = amount in 0.0..1.0001
        byId[id]?.let { return if (one) it.singular else it.plural }
        named[id]?.let { return if (one) it.first else it.second }
        return id
    }

    val spoonish = setOf("tsp", "tbsp", "pinch", "dash")
}
