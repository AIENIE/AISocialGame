package com.aisocialgame.engine.v2.turtlesoup;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Authored truth is available only to the host; fixtures never enter a game observation. */
@Component
public final class TurtleSoupCaseCatalog {
    private static final Set<String> VERDICTS = Set.of("YES", "NO", "IRRELEVANT", "NEEDS_CLARIFICATION", "UNKNOWN", "INVALID_PREMISE");
    private final Map<String, SoupCase> cases;

    public TurtleSoupCaseCatalog(ObjectMapper mapper) {
        try (var input = new ClassPathResource("game-knowledge/turtle-soup-cases.json").getInputStream()) {
            Catalog resource = mapper.readValue(input, Catalog.class);
            if (resource.schemaVersion() != 1 || resource.cases() == null || resource.cases().isEmpty()) {
                throw new IllegalStateException("Unsupported or empty turtle soup catalog");
            }
            Map<String, SoupCase> loaded = new LinkedHashMap<>();
            for (SoupCase soupCase : resource.cases()) {
                validate(soupCase);
                if (loaded.putIfAbsent(soupCase.id(), soupCase) != null) throw new IllegalStateException("Duplicate case: " + soupCase.id());
            }
            cases = java.util.Collections.unmodifiableMap(loaded);
        } catch (IOException error) {
            throw new IllegalStateException("Cannot load turtle soup catalog", error);
        }
    }

    public List<SoupCase> cases() { return List.copyOf(cases.values()); }

    public SoupCase require(String id) {
        String selected = id == null || id.isBlank() ? "midnight_train" : id;
        SoupCase result = cases.get(selected);
        if (result == null) throw new IllegalArgumentException("Unknown turtle soup case: " + selected);
        return result;
    }

    public List<Map<String, Object>> publicCatalog() {
        return cases.values().stream().map(c -> Map.<String, Object>of(
                "id", c.id(), "title", c.title(), "difficulty", c.difficulty(), "version", c.version())).toList();
    }

    private static void validate(SoupCase c) {
        if (c == null || blank(c.id()) || blank(c.title()) || blank(c.surface()) || blank(c.solution()) || c.version() < 1
                || !Set.of("EASY", "MEDIUM", "HARD").contains(c.difficulty())) fail("Invalid case metadata");
        if (c.facts().isEmpty() || c.causalChain().size() < 3 || c.acceptedSolutions().size() < 2 || c.hints().size() != 2
                || c.questionFixtures().size() < 20 || c.solutionFixtures().size() < 5) fail("Incomplete case: " + c.id());
        Set<String> ids = new LinkedHashSet<>();
        for (Fact fact : c.facts()) {
            if (blank(fact.id()) || blank(fact.text()) || !ids.add(fact.id())) fail("Invalid fact in " + c.id());
        }
        if (c.facts().stream().noneMatch(Fact::requiredForSolution)) fail("Missing solution criteria: " + c.id());
        for (ExcludedFact fact : c.excludedFacts()) {
            if (blank(fact.id()) || blank(fact.text()) || !ids.add(fact.id())) fail("Invalid excluded fact: " + c.id());
        }
        Set<String> questions = new LinkedHashSet<>();
        for (QuestionFixture fixture : c.questionFixtures()) {
            if (blank(fixture.question()) || !questions.add(fixture.question()) || !VERDICTS.contains(fixture.verdict())
                    || !ids.containsAll(fixture.factIds())) fail("Invalid question fixture: " + c.id());
            if (Set.of("YES", "NO").contains(fixture.verdict()) && fixture.factIds().isEmpty()) fail("Missing evidence: " + c.id());
        }
        if (c.solutionFixtures().stream().filter(SolutionFixture::solved).count() < 2
                || c.solutionFixtures().stream().filter(f -> !f.solved()).count() < 3) fail("Missing solution counterexamples: " + c.id());
        if (c.hints().stream().anyMatch(TurtleSoupCaseCatalog::blank)) fail("Empty hint: " + c.id());
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static void fail(String message) { throw new IllegalStateException(message); }

    private record Catalog(int schemaVersion, List<SoupCase> cases) {}
    public record Fact(String id, String text, boolean requiredForSolution) {}
    public record ExcludedFact(String id, String text) {}
    public record QuestionFixture(String question, String verdict, List<String> factIds) {
        public QuestionFixture { factIds = List.copyOf(factIds); }
    }
    public record SolutionFixture(String solution, boolean solved, String reason) {}
    public record SoupCase(String id, int version, String title, String difficulty, String surface, String solution,
                           List<Fact> facts, List<String> causalChain, List<String> acceptedSolutions,
                           List<ExcludedFact> excludedFacts, List<String> hints,
                           List<QuestionFixture> questionFixtures, List<SolutionFixture> solutionFixtures) {
        public SoupCase {
            facts = List.copyOf(facts); causalChain = List.copyOf(causalChain); acceptedSolutions = List.copyOf(acceptedSolutions);
            excludedFacts = List.copyOf(excludedFacts); hints = List.copyOf(hints);
            questionFixtures = List.copyOf(questionFixtures); solutionFixtures = List.copyOf(solutionFixtures);
        }

        public Set<String> factIds() {
            Set<String> result = new LinkedHashSet<>();
            facts.forEach(f -> result.add(f.id())); excludedFacts.forEach(f -> result.add(f.id()));
            return Set.copyOf(result);
        }

        public Set<String> requiredFactIds() {
            Set<String> result = new LinkedHashSet<>();
            facts.stream().filter(Fact::requiredForSolution).forEach(f -> result.add(f.id()));
            return Set.copyOf(result);
        }

        public Map<String, Object> hostTruth() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("solution", solution);
            result.put("facts", facts.stream().map(f -> Map.<String, Object>of("id", f.id(), "text", f.text(), "requiredForSolution", f.requiredForSolution())).toList());
            result.put("excludedFacts", excludedFacts.stream().map(f -> Map.of("id", f.id(), "text", f.text())).toList());
            result.put("causalChain", new ArrayList<>(causalChain));
            result.put("acceptedSolutions", new ArrayList<>(acceptedSolutions));
            return result;
        }
    }
}
