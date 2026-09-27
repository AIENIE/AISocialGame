package com.aisocialgame.engine.v2.undercover;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.text.Normalizer;
import java.util.*;
import java.util.random.RandomGenerator;

/** Server-owned pairs. Only WordKnowledge may be projected into a player's observation. */
@Component
public final class UndercoverWordCatalog {
    public static final Set<String> CATEGORIES = Set.of("daily", "idiom", "acg", "tech");
    private final List<WordPair> pairs;
    private final Map<String, WordKnowledge> byWord;

    public UndercoverWordCatalog() {
        try (InputStream stream = UndercoverWordCatalog.class.getResourceAsStream("/game-knowledge/undercover-words.json")) {
            if (stream == null) throw new IllegalStateException("Undercover word catalog is missing");
            CatalogFile file = new ObjectMapper().readValue(stream, CatalogFile.class);
            if (file.version() != 1 || file.pairs() == null || file.pairs().isEmpty()) {
                throw new IllegalStateException("Undercover word catalog has an unsupported version or no pairs");
            }
            pairs = List.copyOf(file.pairs());
            Map<String, WordKnowledge> words = new LinkedHashMap<>();
            Set<String> ids = new HashSet<>();
            for (WordPair pair : pairs) {
                if (!ids.add(pair.id()) || !CATEGORIES.contains(pair.category())
                        || !Set.of("EASY", "MEDIUM", "HARD").contains(pair.difficulty())
                        || !"CURATED_REVIEWED".equals(pair.reviewStatus())) {
                    throw new IllegalStateException("Invalid or unreviewed undercover pair: " + pair.id());
                }
                for (WordKnowledge side : List.of(pair.sideA(), pair.sideB())) {
                    validateKnowledge(side);
                    if (words.putIfAbsent(normalize(side.word()), side) != null) {
                        throw new IllegalStateException("Ambiguous undercover word: " + side.word());
                    }
                }
            }
            byWord = Map.copyOf(words);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read undercover word catalog", e);
        }
    }

    public List<WordPair> all() { return pairs; }

    public List<WordPair> category(String category) {
        if (!CATEGORIES.contains(category)) throw new IllegalArgumentException("Unknown undercover word pack: " + category);
        return pairs.stream().filter(pair -> pair.category().equals(category)).toList();
    }

    public WordPair draw(String category, Collection<String> alreadyUsed, RandomGenerator random) {
        List<WordPair> pool = category(category);
        List<WordPair> remaining = pool.stream().filter(pair -> !alreadyUsed.contains(pair.id())).toList();
        if (remaining.isEmpty()) remaining = pool;
        return remaining.get(random.nextInt(remaining.size()));
    }

    /** Lookup has no side, category, pair id, or opponent-word metadata. Blank players get nothing. */
    public Optional<WordKnowledge> ownKnowledge(String word) {
        if (word == null || word.isBlank()) return Optional.empty();
        return Optional.ofNullable(byWord.get(normalize(word)));
    }

    public boolean containsForbidden(String content, String ownWord) {
        if (ownWord == null || ownWord.isBlank() || content == null || content.isBlank()) return false;
        String normalized = normalize(content);
        List<String> terms = ownKnowledge(ownWord).map(WordKnowledge::forbiddenTerms).orElse(List.of(ownWord));
        return terms.stream().map(UndercoverWordCatalog::normalize).filter(term -> !term.isBlank()).anyMatch(normalized::contains);
    }

    public static String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT).replaceAll("[\\p{P}\\p{Z}\\s\\p{Cf}]+", "");
    }

    private static void validateKnowledge(WordKnowledge side) {
        if (side == null || side.word().isBlank() || side.definition().isBlank()
                || side.attributes().size() < 3 || side.scenes().isEmpty()
                || side.attributes().stream().anyMatch(String::isBlank) || side.scenes().stream().anyMatch(String::isBlank)
                || !side.forbiddenTerms().contains(side.word())) {
            throw new IllegalStateException("Incomplete undercover word knowledge");
        }
    }

    public record CatalogFile(int version, String reviewPolicy, List<WordPair> pairs) {}
    public record WordPair(String id, String category, String difficulty, String reviewStatus,
                           WordKnowledge sideA, WordKnowledge sideB) {}
    public record WordKnowledge(String word, String definition, List<String> attributes,
                                List<String> scenes, List<String> forbiddenTerms) {
        public WordKnowledge {
            attributes = attributes == null ? List.of() : List.copyOf(attributes);
            scenes = scenes == null ? List.of() : List.copyOf(scenes);
            forbiddenTerms = forbiddenTerms == null ? List.of() : List.copyOf(forbiddenTerms);
        }
        public Map<String, Object> visibleData() {
            return Map.of("word", word, "definition", definition, "attributes", attributes,
                    "scenes", scenes, "forbiddenTerms", forbiddenTerms);
        }
    }
}
