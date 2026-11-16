package lu.volley.etl;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves the many spellings of a team to one canonical (club, squad) key.
 *
 * <p>The federation changed its naming convention over the years, so the same
 * team appears under different labels in the source:
 * <pre>
 *   2021/22   "VCS Hommes"          abbreviation, no squad number
 *   2022-25   "VCS Hommes 1"        abbreviation + squad number
 *   2023/24   "VCS 3 Hommes 3"      squad number repeated
 *   2025-     "VC Stroossen 1"      full club name + squad number
 * </pre>
 * All of them resolve to club {@code VCS}; a missing squad number means squad 1.
 * Without this step a team's history would be split across several identities.
 */
public final class TeamNameNormalizer {

    /** Canonical identity of a team. */
    public record TeamRef(String clubCode, String clubName, int squadNo) {
        public String teamName() {
            return clubName + " " + squadNo;
        }
    }

    // "VCS Hommes", "VCS Hommes 2", "VCS 3 Hommes 3"
    private static final Pattern LEGACY = Pattern.compile("^([A-Z0-9]+)(?: (\\d))? Hommes(?: (\\d))?$");
    // "VC Stroossen 1", "VC Stroossen"
    private static final Pattern MODERN = Pattern.compile("^(.+?)(?: (\\d))?$");

    private final Map<String, String> nameByCode = new HashMap<>();
    private final Map<String, String> codeByName = new HashMap<>();

    /** @param clubs map of club code to canonical club name (from data/reference/clubs.csv) */
    public TeamNameNormalizer(Map<String, String> clubs) {
        clubs.forEach((code, name) -> {
            nameByCode.put(code, name);
            codeByName.put(key(name), code);
        });
    }

    /** @return the canonical team, or empty when the name is not a known club (e.g. a placeholder). */
    public Optional<TeamRef> normalize(String rawName) {
        String name = clean(rawName);

        Matcher legacy = LEGACY.matcher(name);
        if (legacy.matches() && nameByCode.containsKey(legacy.group(1))) {
            String code = legacy.group(1);
            String squad = legacy.group(3) != null ? legacy.group(3) : legacy.group(2);
            return Optional.of(new TeamRef(code, nameByCode.get(code), squad == null ? 1 : Integer.parseInt(squad)));
        }

        Matcher modern = MODERN.matcher(name);
        if (modern.matches()) {
            String code = codeByName.get(key(modern.group(1)));
            if (code != null) {
                String squad = modern.group(2);
                return Optional.of(new TeamRef(code, nameByCode.get(code), squad == null ? 1 : Integer.parseInt(squad)));
            }
        }
        return Optional.empty();
    }

    /** Collapses repeated whitespace and removes spaces around hyphens ("Amber -Lënster"). */
    static String clean(String raw) {
        return raw == null ? "" : raw.trim().replaceAll("\\s+", " ").replaceAll(" ?- ?", "-");
    }

    private static String key(String clubName) {
        return clean(clubName).toLowerCase(Locale.ROOT);
    }
}
