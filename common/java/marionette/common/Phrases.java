package marionette.common;

import java.util.Map;

/**
 * The few sentences a bot says on its own initiative, in the language it speaks.
 *
 * <p>Everything the brain says already comes out in the bot's language, because its prompt
 * says so. These do not go through the brain: they are the urgent ones, said by the body in
 * the same tick (a creeper next to the person being escorted, being cornered), so they
 * carry their own translations. A live test found the hole the loud way: a bot speaking
 * Spanish warned "Alice, creeper 15 blocks from you".
 *
 * <p>Language and grammatical gender arrive as JVM properties from the launcher, which
 * reads them from {@code bots/<bot>/language} and {@code bots/<bot>/gender}. An unknown
 * language falls back to English, so a bot never goes mute over a missing translation.
 *
 * <p>In a language that inflects, {@code {a}} marks the letter that changes with gender:
 * feminine {@code a}, masculine {@code o} ("malherid{a}").
 */
public final class Phrases {

    private Phrases() {}

    /** English is the source: every other language is checked against these keys. */
    private static final Map<String, String> EN = Map.of(
            "shelter", "night fell and I am out in the open; moving to %s",
            "known_spot", "a known spot",
            "hurt_cornered", "badly hurt and with no way out",
            "creeper_cornered", "creeper on top of me and no way out",
            "explored", "I reached %d %d %d, %s. %s",
            "turning_back", "Turning back",
            "nothing_here", "No %s here: on to segment %d of %d",
            "next_segment", "on to segment %d of %d",
            "escort_creeper", "%s, creeper %d blocks from you",
            "recovered", "I respawned. Going for my things to %s");

    private static final Map<String, String> ES = Map.of(
            "shelter", "cayó la noche y estoy a la intemperie; me voy a %s",
            "known_spot", "un sitio conocido",
            "hurt_cornered", "malherid{a} y sin salida",
            "creeper_cornered", "creeper encima y sin salida",
            "explored", "Llegué a %d %d %d, %s. %s",
            "turning_back", "Me devuelvo",
            "nothing_here", "Aquí no hay %s: sigo al tramo %d de %d",
            "next_segment", "sigo al tramo %d de %d",
            "escort_creeper", "%s, creeper a %d bloques de ti",
            "recovered", "Reaparecí. Voy por mis cosas a %s");

    private static final Map<String, Map<String, String>> BY_LANGUAGE =
            Map.of("en", EN, "es", ES);

    private static final String LANGUAGE = setting("marionette.language",
            "MARIONETTE_LANGUAGE", "en").toLowerCase();
    private static final String GENDER = setting("marionette.gender",
            "MARIONETTE_GENDER", "f").toLowerCase();

    private static String setting(String property, String variable, String byDefault) {
        String v = System.getProperty(property,
                System.getenv().getOrDefault(variable, byDefault)).strip();
        return v.isEmpty() ? byDefault : v;
    }

    /** The sentence for that key, filled in, in the bot's language. */
    public static String of(String key, Object... arguments) {
        return in(LANGUAGE, GENDER, key, arguments);
    }

    /** The same, choosing the language and gender: this is what the tests use. */
    public static String in(String language, String gender, String key, Object... arguments) {
        String pattern = BY_LANGUAGE.getOrDefault(language, EN).get(key);
        if (pattern == null) pattern = EN.get(key);
        if (pattern == null) {
            throw new IllegalArgumentException("there is no sentence called " + key);
        }
        String said = gender.startsWith("m") ? pattern.replace("{a}", "o")
                                             : pattern.replace("{a}", "a");
        return arguments.length == 0 ? said : String.format(said, arguments);
    }

    /** The keys, so a test can check that no language is missing one. */
    public static Map<String, Map<String, String>> byLanguage() {
        return BY_LANGUAGE;
    }
}
